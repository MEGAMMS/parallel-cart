#!/usr/bin/env bash
set -euo pipefail

# Run from the root of the parallel-cart repository.
# Put this folder anywhere, then set JMETER_PLAN_DIR to its path if needed.
JMETER_PLAN_DIR="${JMETER_PLAN_DIR:-$(pwd)/jmeter}"
RESULTS_DIR="${RESULTS_DIR:-reports/jmeter}"
HOST="${HOST:-localhost}"
PORT="${PORT:-8080}"
THREADS="${THREADS:-100}"
RAMP="${RAMP:-20}"
READ_LOOPS="${READ_LOOPS:-50}"
CHECKOUT_LOOPS="${CHECKOUT_LOOPS:-1}"
HOT_PRODUCT_PERCENT="${HOT_PRODUCT_PERCENT:-0}"
HOT_PRODUCT_ID="${HOT_PRODUCT_ID:-1}"

mkdir -p "$RESULTS_DIR"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "[fail] missing command: $1"; exit 1; }
}

wait_for_app() {
  for _ in {1..180}; do
    if curl -sf "http://${HOST}:${PORT}/actuator/health" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "[fail] app not reachable at http://${HOST}:${PORT}"
  exit 1
}

seed_for_checkout() {
  local container_seed="/tmp/seed_jmeter_users.sql"
  docker compose cp "${JMETER_PLAN_DIR}/seed_jmeter_users.sql" "postgres:${container_seed}"
  docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U parallel_cart -d parallel_cart -f "${container_seed}"
  docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U parallel_cart -d parallel_cart -c \
    "select count(*) as seeded_jmeter_users from users where id between 3 and 202;"
}

start_stack() {
  local cache_type="$1"
  docker compose down -v
  SPRING_CACHE_TYPE="$cache_type" docker compose up -d --build
  wait_for_app
  seed_for_checkout
}

run_jmeter() {
  local label="$1"
  local plan="$2"
  local loops="$3"
  local jtl="${RESULTS_DIR}/${label}.jtl"
  local dashboard="${RESULTS_DIR}/${label}-dashboard"
  rm -rf "$dashboard" "$jtl"
  jmeter -n -t "${JMETER_PLAN_DIR}/${plan}" \
    -Jhost="$HOST" -Jport="$PORT" -Jthreads="$THREADS" -Jramp="$RAMP" -Jloops="$loops" \
    -Juser_start=3 -Jproduct_count=40 -Jhot_product_percent="$HOT_PRODUCT_PERCENT" -Jhot_product_id="$HOT_PRODUCT_ID" \
    -l "$jtl" -e -o "$dashboard"
  echo "[ok] ${label}: ${jtl} + ${dashboard}/index.html"
}

summarize_jtl() {
  local file="$1"
  python3 - "$file" <<'PY'
import csv, statistics, sys
path=sys.argv[1]
rows=[]
with open(path, newline='', encoding='utf-8') as f:
    reader=csv.DictReader(f)
    for r in reader:
        rows.append(r)
elapsed=[float(r['elapsed']) for r in rows if r.get('elapsed')]
errors=[r for r in rows if str(r.get('success','')).lower() != 'true']
if not rows:
    print(f"{path}: no rows")
    raise SystemExit
elapsed_sorted=sorted(elapsed)
def pct(p):
    if not elapsed_sorted: return 0
    k=(len(elapsed_sorted)-1)*p/100
    f=int(k); c=min(f+1,len(elapsed_sorted)-1)
    return elapsed_sorted[f] if f==c else elapsed_sorted[f]*(c-k)+elapsed_sorted[c]*(k-f)
first=min(int(r['timeStamp']) for r in rows)
last=max(int(r['timeStamp'])+int(r['elapsed']) for r in rows)
duration_s=max((last-first)/1000, 0.001)
print(f"file={path}")
print(f"samples={len(rows)}")
print(f"errors={len(errors)} error_rate={len(errors)/len(rows):.6f}")
print(f"avg_ms={statistics.mean(elapsed):.2f} p90_ms={pct(90):.2f} p95_ms={pct(95):.2f} max_ms={max(elapsed):.2f}")
print(f"throughput_rps={len(rows)/duration_s:.2f}")
PY
}

require_cmd docker
require_cmd curl
require_cmd jmeter

case "${1:-both}" in
  before)
    start_stack none
    run_jmeter before-read parallel-cart-read-stress.jmx "$READ_LOOPS"
    run_jmeter before-checkout parallel-cart-checkout-stress.jmx "$CHECKOUT_LOOPS"
    summarize_jtl "${RESULTS_DIR}/before-read.jtl"
    summarize_jtl "${RESULTS_DIR}/before-checkout.jtl"
    ;;
  after)
    start_stack redis
    run_jmeter after-read parallel-cart-read-stress.jmx "$READ_LOOPS"
    run_jmeter after-checkout parallel-cart-checkout-stress.jmx "$CHECKOUT_LOOPS"
    summarize_jtl "${RESULTS_DIR}/after-read.jtl"
    summarize_jtl "${RESULTS_DIR}/after-checkout.jtl"
    ;;
  both)
    start_stack none
    run_jmeter before-read parallel-cart-read-stress.jmx "$READ_LOOPS"
    run_jmeter before-checkout parallel-cart-checkout-stress.jmx "$CHECKOUT_LOOPS"
    start_stack redis
    run_jmeter after-read parallel-cart-read-stress.jmx "$READ_LOOPS"
    run_jmeter after-checkout parallel-cart-checkout-stress.jmx "$CHECKOUT_LOOPS"
    for f in before-read before-checkout after-read after-checkout; do summarize_jtl "${RESULTS_DIR}/${f}.jtl"; done
    ;;
  *)
    echo "Usage: $0 before|after|both"
    exit 1
    ;;
esac
