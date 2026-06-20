#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Run a real Git before/after JMeter comparison for Parallel Cart.

Default comparison:
  BEFORE_REF=38d4b58
  AFTER_REF=HEAD

Default JMeter settings:
  THREADS=100
  RAMP=20
  READ_LOOPS=50
  CHECKOUT_LOOPS=1

Output:
  reports/jmeter/git-history-<timestamp>/
  reports/jmeter/git-before/
  reports/jmeter/git-after/
  reports/jmeter/git-before-after-summary.md

Examples:
  scripts/run-git-history-jmeter-comparison.sh

  BEFORE_REF=38d4b58 AFTER_REF=HEAD \
    THREADS=100 RAMP=20 READ_LOOPS=50 CHECKOUT_LOOPS=1 \
    scripts/run-git-history-jmeter-comparison.sh

  ALLOW_DIRTY=1 scripts/run-git-history-jmeter-comparison.sh

Notes:
  - The script refuses to run on a dirty worktree unless ALLOW_DIRTY=1.
  - It does not delete existing Git worktrees.
  - It creates fresh timestamped worktrees.
  - It copies the current jmeter/ directory into both worktrees for testing only.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "[fail] Missing command: $1" >&2
    exit 1
  }
}

run_logged() {
  local log_file="$1"
  shift
  {
    echo "[cmd] $*"
    "$@"
  } 2>&1 | tee "$log_file"
}

cleanup_fixed_containers() {
  docker rm -f \
    parallel-cart-app \
    parallel-cart-app-1 \
    parallel-cart-app-2 \
    parallel-cart-nginx \
    parallel-cart-postgres \
    parallel-cart-redis \
    parallel-cart-kafka \
    parallel-cart-zookeeper >/dev/null 2>&1 || true
}

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

require_cmd git
require_cmd docker
require_cmd curl
require_cmd jmeter
require_cmd python3

before_ref="${BEFORE_REF:-38d4b58}"
after_ref="${AFTER_REF:-HEAD}"
threads="${THREADS:-100}"
ramp="${RAMP:-20}"
read_loops="${READ_LOOPS:-50}"
checkout_loops="${CHECKOUT_LOOPS:-1}"
run_id="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
results_root="${RESULTS_ROOT:-$repo_root/reports/jmeter/git-history-$run_id}"
before_results="$results_root/git-before"
after_results="$results_root/git-after"
latest_before="$repo_root/reports/jmeter/git-before"
latest_after="$repo_root/reports/jmeter/git-after"
summary_file="$repo_root/reports/jmeter/git-before-after-summary.md"
worktree_parent="${WORKTREE_PARENT:-$(dirname "$repo_root")/parallel-cart-git-history-worktrees}"
before_worktree="$worktree_parent/before-$run_id-${before_ref//\//_}"
after_worktree="$worktree_parent/after-$run_id-${after_ref//\//_}"

mkdir -p "$results_root" "$before_results" "$after_results" "$worktree_parent" "$repo_root/reports/jmeter"

git status > "$results_root/git-status.txt"
git log --oneline --decorate --graph --all > "$results_root/git-log.txt"

if [[ "${ALLOW_DIRTY:-0}" != "1" && -n "$(git status --porcelain)" ]]; then
  echo "[fail] Working tree is not clean. Commit/stash changes or rerun with ALLOW_DIRTY=1." >&2
  echo "[info] See: $results_root/git-status.txt" >&2
  exit 1
fi

if [[ ! -d "$repo_root/jmeter" ]]; then
  echo "[fail] Missing jmeter/ directory in current workspace." >&2
  exit 1
fi

before_hash="$(git rev-parse "$before_ref")"
after_hash="$(git rev-parse "$after_ref")"
before_short="$(git show -s --format='%h %s' "$before_hash")"
after_short="$(git show -s --format='%h %s' "$after_hash")"

cat > "$results_root/selection.env" <<EOF
RUN_ID=$run_id
BEFORE_REF=$before_ref
BEFORE_HASH=$before_hash
BEFORE_SUMMARY=$before_short
AFTER_REF=$after_ref
AFTER_HASH=$after_hash
AFTER_SUMMARY=$after_short
THREADS=$threads
RAMP=$ramp
READ_LOOPS=$read_loops
CHECKOUT_LOOPS=$checkout_loops
BEFORE_WORKTREE=$before_worktree
AFTER_WORKTREE=$after_worktree
RESULTS_ROOT=$results_root
EOF

echo "[info] Before: $before_hash ($before_short)"
echo "[info] After : $after_hash ($after_short)"
echo "[info] Results: $results_root"

git worktree add --detach "$before_worktree" "$before_hash" 2>&1 | tee "$results_root/worktree-before.log"
git worktree add --detach "$after_worktree" "$after_hash" 2>&1 | tee "$results_root/worktree-after.log"

cp -a "$repo_root/jmeter" "$before_worktree/jmeter"
cp -a "$repo_root/jmeter" "$after_worktree/jmeter"

run_release() {
  local release="$1"
  local mode="$2"
  local worktree="$3"
  local out_dir="$4"
  local command_log="$out_dir/command.log"
  local sql_log="$out_dir/sql-validation.log"
  local status_file="$out_dir/status.env"

  mkdir -p "$out_dir"
  echo "[info] Running $release in $worktree"

  {
    echo "[step] docker compose down"
    docker compose down -v --remove-orphans || true
    echo "[step] fixed container cleanup"
    cleanup_fixed_containers
    echo "[step] docker system prune"
    docker system prune -f
    echo "[step] docker builder prune"
    docker builder prune -f
  } 2>&1 | tee "$out_dir/docker-cleanup.log"

  set +e
  (
    cd "$worktree"
    RESULTS_DIR="$out_dir" \
      THREADS="$threads" \
      RAMP="$ramp" \
      READ_LOOPS="$read_loops" \
      CHECKOUT_LOOPS="$checkout_loops" \
      ./jmeter/run-jmeter-before-after.sh "$mode"
  ) 2>&1 | tee "$command_log"
  local command_exit="${PIPESTATUS[0]}"
  set -e

  set +e
  (
    cd "$worktree"
    docker compose exec -T postgres psql -U parallel_cart -d parallel_cart -c \
      "select count(*) as paid_orders from orders where status='PAID';
       select count(*) as payments from payments where status='CAPTURED';
       select min(available_quantity) as min_inventory from inventory;
       select count(*) as negative_inventory from inventory where available_quantity < 0;
       select status, count(*) from outbox_events group by status order by status;"
  ) 2>&1 | tee "$sql_log"
  local sql_exit="${PIPESTATUS[0]}"
  set -e

  {
    echo "COMMAND_EXIT=$command_exit"
    echo "SQL_EXIT=$sql_exit"
  } > "$status_file"

  (
    cd "$worktree"
    docker compose down -v --remove-orphans || true
  ) 2>&1 | tee "$out_dir/docker-down.log"
  cleanup_fixed_containers
}

run_release "before" "before" "$before_worktree" "$before_results"
run_release "after" "after" "$after_worktree" "$after_results"

rm -rf "$latest_before" "$latest_after"
cp -a "$before_results" "$latest_before"
cp -a "$after_results" "$latest_after"

python3 - "$results_root" "$summary_file" "$latest_before" "$latest_after" <<'PY'
import csv
import math
import os
import re
import sys
from pathlib import Path

results_root = Path(sys.argv[1])
summary_file = Path(sys.argv[2])
latest_before = Path(sys.argv[3])
latest_after = Path(sys.argv[4])

selection = {}
for line in (results_root / "selection.env").read_text().splitlines():
    if "=" in line:
        key, value = line.split("=", 1)
        selection[key] = value

def pct(values, p):
    if not values:
        return None
    values = sorted(values)
    k = (len(values) - 1) * p / 100
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return values[f]
    return values[f] * (c - k) + values[c] * (k - f)

def jtl_metrics(path):
    path = Path(path)
    if not path.exists():
        return {"exists": False}
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        return {"exists": True, "samples": 0}
    elapsed = [float(row["elapsed"]) for row in rows if row.get("elapsed")]
    errors = [row for row in rows if str(row.get("success", "")).lower() != "true"]
    starts = [int(row["timeStamp"]) for row in rows]
    ends = [int(row["timeStamp"]) + int(row["elapsed"]) for row in rows]
    duration = max((max(ends) - min(starts)) / 1000, 0.001)
    received_bytes = sum(int(row.get("bytes") or 0) for row in rows)
    labels = {}
    for row in rows:
        key = (row.get("label", ""), row.get("responseCode", ""), str(row.get("success", "")).lower())
        labels[key] = labels.get(key, 0) + 1
    return {
        "exists": True,
        "samples": len(rows),
        "errors": len(errors),
        "error_rate": len(errors) / len(rows) * 100,
        "avg": sum(elapsed) / len(elapsed),
        "p50": pct(elapsed, 50),
        "p90": pct(elapsed, 90),
        "p95": pct(elapsed, 95),
        "p99": pct(elapsed, 99),
        "max": max(elapsed),
        "throughput": len(rows) / duration,
        "dtr": received_bytes / duration / 1024,
        "failed_rows": errors[:10],
        "labels": labels,
    }

def status(path):
    path = Path(path)
    data = {}
    if not path.exists():
        return data
    for line in path.read_text().splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            data[key] = value
    return data

def sql_values(path):
    path = Path(path)
    if not path.exists():
        return {"raw": "missing"}
    raw = path.read_text()
    def number_after(name):
        match = re.search(rf"{name}\\s*\\n[- ]+\\n\\s*([0-9]+)", raw)
        return int(match.group(1)) if match else None
    statuses = {}
    for match in re.finditer(r"\\n\\s*([A-Z_]+)\\s*\\|\\s*([0-9]+)\\s*\\n", raw):
        statuses[match.group(1)] = int(match.group(2))
    return {
        "paid_orders": number_after("paid_orders"),
        "payments": number_after("payments"),
        "min_inventory": number_after("min_inventory"),
        "negative_inventory": number_after("negative_inventory"),
        "statuses": statuses,
        "raw": raw.strip(),
    }

def fmt(value, digits=2):
    if value is None:
        return "n/a"
    if isinstance(value, str):
        return value
    return f"{value:.{digits}f}"

def metric_row(release, scenario, metrics):
    if not metrics.get("exists"):
        return f"| {release} | {scenario} | missing | missing | missing | missing | missing | missing | missing | missing | missing | missing |\n"
    return (
        f"| {release} | {scenario} | {metrics.get('samples', 0)} | {metrics.get('errors', 0)} | "
        f"{fmt(metrics.get('error_rate'))}% | {fmt(metrics.get('avg'))} | {fmt(metrics.get('p90'))} | "
        f"{fmt(metrics.get('p95'))} | {fmt(metrics.get('p99'))} | {fmt(metrics.get('max'))} | "
        f"{fmt(metrics.get('throughput'))} | {fmt(metrics.get('dtr'))} |\n"
    )

before_read = jtl_metrics(results_root / "git-before" / "before-read.jtl")
before_checkout = jtl_metrics(results_root / "git-before" / "before-checkout.jtl")
after_read = jtl_metrics(results_root / "git-after" / "after-read.jtl")
after_checkout = jtl_metrics(results_root / "git-after" / "after-checkout.jtl")
before_sql = sql_values(results_root / "git-before" / "sql-validation.log")
after_sql = sql_values(results_root / "git-after" / "sql-validation.log")
before_status = status(results_root / "git-before" / "status.env")
after_status = status(results_root / "git-after" / "status.env")

lines = []
lines.append("# Git Before/After JMeter Performance Summary\n\n")
lines.append("Generated by `scripts/run-git-history-jmeter-comparison.sh` from real JMeter `.jtl` files and SQL output.\n\n")
lines.append("## Commit Selection\n\n")
lines.append(f"- Before: `{selection['BEFORE_HASH']}` - `{selection['BEFORE_SUMMARY']}`\n")
lines.append(f"- After: `{selection['AFTER_HASH']}` - `{selection['AFTER_SUMMARY']}`\n")
lines.append("- Earlier commits without `outbox_events` are not compatible with the required SQL validation.\n\n")
lines.append("## Worktrees\n\n")
lines.append(f"- Before worktree: `{selection['BEFORE_WORKTREE']}`\n")
lines.append(f"- After worktree: `{selection['AFTER_WORKTREE']}`\n")
lines.append("- Existing worktrees were not deleted.\n\n")
lines.append("## Settings\n\n")
lines.append("| Setting | Value |\n|---|---:|\n")
lines.append(f"| Threads | {selection['THREADS']} |\n")
lines.append(f"| Ramp | {selection['RAMP']} |\n")
lines.append(f"| Read loops | {selection['READ_LOOPS']} |\n")
lines.append(f"| Checkout loops | {selection['CHECKOUT_LOOPS']} |\n")
lines.append("| JMeter users | 3 through 202 |\n")
lines.append("| Products | 1 through 40 |\n\n")
lines.append("## Build And Run Status\n\n")
lines.append("| Release | Docker/JMeter command exit | SQL validation exit |\n|---|---:|---:|\n")
lines.append(f"| Before | {before_status.get('COMMAND_EXIT', 'missing')} | {before_status.get('SQL_EXIT', 'missing')} |\n")
lines.append(f"| After | {after_status.get('COMMAND_EXIT', 'missing')} | {after_status.get('SQL_EXIT', 'missing')} |\n\n")
lines.append("## JMeter Metrics\n\n")
lines.append("DTR is received KB/s computed from JTL `bytes` over the measured sample window.\n\n")
lines.append("| Release | Scenario | Samples | Errors | Error Rate | Avg ms | p90 ms | p95 ms | p99 ms | Max ms | Throughput req/s | DTR KB/s |\n")
lines.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n")
lines.append(metric_row("Before", "Read", before_read))
lines.append(metric_row("Before", "Checkout", before_checkout))
lines.append(metric_row("After", "Read", after_read))
lines.append(metric_row("After", "Checkout", after_checkout))
lines.append("\n## Checkout Sampler Breakdown\n\n")
lines.append("| Release | Sampler | Response | Success | Count |\n|---|---|---:|---|---:|\n")
for release, metrics in [("Before", before_checkout), ("After", after_checkout)]:
    for (label, response, success), count in sorted(metrics.get("labels", {}).items()):
        lines.append(f"| {release} | `{label}` | {response} | {success} | {count} |\n")
lines.append("\n## First Checkout Failures\n\n")
for release, metrics in [("Before", before_checkout), ("After", after_checkout)]:
    failures = metrics.get("failed_rows", [])
    lines.append(f"### {release}\n\n")
    if not failures:
        lines.append("No failed JMeter rows.\n\n")
    else:
        for row in failures:
            lines.append("```text\n")
            lines.append(f"label={row.get('label')}\n")
            lines.append(f"responseCode={row.get('responseCode')}\n")
            lines.append(f"URL={row.get('URL')}\n")
            lines.append(f"failureMessage={row.get('failureMessage')}\n")
            lines.append("```\n\n")
lines.append("## SQL Verification\n\n")
lines.append("| Release | Paid orders | Payments | Min inventory | Negative inventory | Outbox statuses |\n")
lines.append("|---|---:|---:|---:|---:|---|\n")
for release, sql in [("Before", before_sql), ("After", after_sql)]:
    statuses = ", ".join(f"{k}={v}" for k, v in sorted(sql.get("statuses", {}).items())) or "none"
    lines.append(
        f"| {release} | {sql.get('paid_orders')} | {sql.get('payments')} | "
        f"{sql.get('min_inventory')} | {sql.get('negative_inventory')} | `{statuses}` |\n"
    )
lines.append("\n### Raw SQL Output - Before\n\n```text\n")
lines.append(before_sql.get("raw", "missing") + "\n")
lines.append("```\n\n### Raw SQL Output - After\n\n```text\n")
lines.append(after_sql.get("raw", "missing") + "\n")
lines.append("```\n\n")
lines.append("## Output Files\n\n")
lines.append(f"- Run root: `{results_root}`\n")
lines.append(f"- Latest before copy: `{latest_before}`\n")
lines.append(f"- Latest after copy: `{latest_after}`\n")
lines.append(f"- Summary: `{summary_file}`\n\n")
lines.append("## Conclusion\n\n")
if before_checkout.get("exists") and after_checkout.get("exists"):
    lines.append(
        f"- Checkout errors changed from `{before_checkout['errors']}/{before_checkout['samples']}` "
        f"to `{after_checkout['errors']}/{after_checkout['samples']}`.\n"
    )
if before_sql.get("paid_orders") is not None and after_sql.get("paid_orders") is not None:
    lines.append(f"- Paid orders changed from `{before_sql['paid_orders']}` to `{after_sql['paid_orders']}`.\n")
if before_sql.get("payments") is not None and after_sql.get("payments") is not None:
    lines.append(f"- Captured payments changed from `{before_sql['payments']}` to `{after_sql['payments']}`.\n")
if before_sql.get("negative_inventory") is not None and after_sql.get("negative_inventory") is not None:
    lines.append(f"- Negative inventory stayed `{before_sql['negative_inventory']}` before and `{after_sql['negative_inventory']}` after.\n")
if before_checkout.get("exists") and after_checkout.get("exists"):
    if after_checkout["avg"] < before_checkout["avg"]:
        lines.append("- Checkout average latency improved in this run.\n")
    else:
        lines.append("- Checkout average latency did not improve in this run.\n")
lines.append("- Do not claim a performance improvement unless the measured numbers above show it.\n")

summary_file.write_text("".join(lines), encoding="utf-8")

print(f"[ok] Summary written: {summary_file}")
print(f"[ok] Run root: {results_root}")
if before_checkout.get("exists") and after_checkout.get("exists"):
    print(f"[result] checkout_errors_before={before_checkout['errors']} checkout_errors_after={after_checkout['errors']}")
    print(f"[result] checkout_avg_ms_before={before_checkout['avg']:.2f} checkout_avg_ms_after={after_checkout['avg']:.2f}")
if before_sql.get("paid_orders") is not None and after_sql.get("paid_orders") is not None:
    print(f"[result] paid_orders_before={before_sql['paid_orders']} paid_orders_after={after_sql['paid_orders']}")
PY

echo "[ok] Latest before results: $latest_before"
echo "[ok] Latest after results : $latest_after"
echo "[ok] Summary             : $summary_file"
