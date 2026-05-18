#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

REPORT_DIR="reports/interviews/first-thursday"
mkdir -p "$REPORT_DIR"
LOG_FILE="$REPORT_DIR/ft1-r5-load-distribution.log"
REQUEST_COUNT="${REQUEST_COUNT:-40}"

exec > >(tee "$LOG_FILE") 2>&1

wait_http() {
  local url="$1"
  local max_seconds="${2:-240}"
  local waited=0
  while (( waited < max_seconds )); do
    if curl -sf "$url" >/dev/null; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

wait_for_two_instances() {
  local url="$1"
  local max_seconds="${2:-240}"
  local waited=0
  local saw_a=0
  local saw_b=0

  while (( waited < max_seconds )); do
    local headers_file
    headers_file="$(mktemp)"
    local meta
    meta="$(curl -sS -o /dev/null -D "$headers_file" -w "%{http_code}" "$url" || true)"
    local code="$meta"
    local instance
    instance="$(awk -F': ' 'tolower($1)=="x-app-instance" {gsub("\r", "", $2); print $2}' "$headers_file" | tail -1)"
    rm -f "$headers_file"

    if [[ "$code" == "200" && "$instance" == "app-1" ]]; then
      saw_a=1
    fi
    if [[ "$code" == "200" && "$instance" == "app-2" ]]; then
      saw_b=1
    fi
    if (( saw_a == 1 && saw_b == 1 )); then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

collect_requests() {
  local url="$1"
  local out_file="$2"
  local count="$3"

  : > "$out_file"
  for _ in $(seq 1 "$count"); do
    local headers_file
    headers_file="$(mktemp)"
    local meta
    meta="$(curl -sS -o /dev/null -D "$headers_file" -w "%{http_code} %{time_total}" "$url" || true)"
    local code="${meta%% *}"
    local time_total="${meta##* }"
    local instance
    instance="$(awk -F': ' 'tolower($1)=="x-app-instance" {gsub("\r", "", $2); print $2}' "$headers_file" | tail -1)"
    rm -f "$headers_file"
    if [[ -z "$instance" ]]; then
      instance="unknown"
    fi
    echo "$code,$time_total,$instance" >> "$out_file"
  done
}

print_summary() {
  local label="$1"
  local file="$2"
  local total
  local success
  local failed
  local avg_latency

  total="$(wc -l < "$file" | tr -d '[:space:]')"
  success="$(awk -F',' '$1 == 200 {count++} END {print count+0}' "$file")"
  failed=$((total - success))
  avg_latency="$(awk -F',' '{sum+=$2} END {if (NR==0) {print "0.0000"} else {printf "%.4f", sum/NR}}' "$file")"

  echo
  echo "[$label]"
  echo "requests=$total"
  echo "success=$success"
  echo "failed=$failed"
  echo "success_rate=$(awk -v s="$success" -v t="$total" 'BEGIN { if (t==0) print "0.00"; else printf "%.2f", (s*100.0)/t }')%"
  echo "avg_latency_seconds=$avg_latency"
  awk -F',' '{count[$3]++} END {for (k in count) printf "instance_count[%s]=%d\n", k, count[k]}' "$file" | sort
}

single_data="$(mktemp)"
load_data="$(mktemp)"
trap 'rm -f "$single_data" "$load_data"' EXIT

echo "== FT1 Req5: Load Distribution =="
echo "[step] Single-instance baseline with docker-compose.yml"
docker compose down -v || true
docker compose up -d --build
wait_http "http://localhost:8080/actuator/health" 240
collect_requests "http://localhost:8080/actuator/health" "$single_data" "$REQUEST_COUNT"
print_summary "single-instance" "$single_data"

echo
echo "[step] Load-balanced mode with docker-compose.load.yml (nginx round-robin)"
docker compose -f docker-compose.yml -f docker-compose.load.yml down -v || true
docker compose -f docker-compose.yml -f docker-compose.load.yml up -d --build
wait_http "http://localhost:8081/actuator/health" 300
wait_for_two_instances "http://localhost:8081/actuator/health" 300
collect_requests "http://localhost:8081/actuator/health" "$load_data" "$REQUEST_COUNT"
print_summary "load-balanced" "$load_data"

unique_instances="$(awk -F',' '{seen[$3]=1} END {print length(seen)}' "$load_data")"
echo "unique_instances_in_load_mode=$unique_instances"
if (( unique_instances < 2 )); then
  echo "[fail] expected responses from at least 2 app instances in load-balanced mode"
  exit 1
fi

echo "[ok] load distribution evidence written to $LOG_FILE"
