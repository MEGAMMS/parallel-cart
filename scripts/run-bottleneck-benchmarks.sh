#!/usr/bin/env bash
set -euo pipefail

# Run from repository root. Each scenario rebuilds a clean Docker Compose stack,
# seeds deterministic JMeter users, runs read + checkout plans, and captures
# bottleneck attribution from actuator.

RESULTS_ROOT="${RESULTS_ROOT:-reports/bottleneck}"
JMETER_PLAN_DIR="${JMETER_PLAN_DIR:-$(pwd)/jmeter}"
HOST="${HOST:-localhost}"
PORT="${PORT:-8080}"
READ_LOOPS="${READ_LOOPS:-20}"
CHECKOUT_LOOPS="${CHECKOUT_LOOPS:-1}"

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
}

cleanup_stack() {
  docker compose down -v --remove-orphans
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

run_jmeter_plan() {
  local scenario_dir="$1"
  local label="$2"
  local plan="$3"
  local threads="$4"
  local ramp="$5"
  local loops="$6"
  local hot_percent="$7"
  local jtl="${scenario_dir}/${label}.jtl"
  local dashboard="${scenario_dir}/${label}-dashboard"
  rm -rf "$jtl" "$dashboard"
  jmeter -n -t "${JMETER_PLAN_DIR}/${plan}" \
    -Jhost="$HOST" -Jport="$PORT" -Jthreads="$threads" -Jramp="$ramp" -Jloops="$loops" \
    -Juser_start=3 -Jproduct_count=40 -Jhot_product_percent="$hot_percent" -Jhot_product_id=1 \
    -l "$jtl" -e -o "$dashboard"
}

capture_sql() {
  local scenario_dir="$1"
  docker compose exec -T postgres psql -U parallel_cart -d parallel_cart -c \
    "select count(*) as paid_orders from orders where status='PAID';
     select count(*) as payments from payments where status='CAPTURED';
     select min(available_quantity) as min_inventory from inventory;
     select count(*) as negative_inventory from inventory where available_quantity < 0;
     select status, count(*) from outbox_events group by status order by status;" \
    2>&1 | tee "${scenario_dir}/sql-validation.log"
}

capture_actuator() {
  local scenario_dir="$1"
  curl -sf -o "${scenario_dir}/bottlenecks.json" "http://${HOST}:${PORT}/actuator/bottlenecks"
  curl -sf -o "${scenario_dir}/benchmark-report.json" "http://${HOST}:${PORT}/actuator/benchmarkreport"
  curl -sf -o "${scenario_dir}/metrics-index.json" "http://${HOST}:${PORT}/actuator/metrics"
}

run_scenario() {
  local name="$1"
  local threads="$2"
  local ramp="$3"
  local cache_type="$4"
  local hot_percent="$5"
  local scenario_dir="${RESULTS_ROOT}/${name}"
  mkdir -p "$scenario_dir"

  {
    echo "scenario=${name}"
    echo "threads=${threads}"
    echo "ramp=${ramp}"
    echo "read_loops=${READ_LOOPS}"
    echo "checkout_loops=${CHECKOUT_LOOPS}"
    echo "cache_type=${cache_type}"
    echo "hot_product_percent=${hot_percent}"
  } > "${scenario_dir}/scenario.properties"

  cleanup_stack
  SPRING_CACHE_TYPE="$cache_type" docker compose up -d --build
  wait_for_app
  seed_for_checkout

  run_jmeter_plan "$scenario_dir" "read" "parallel-cart-read-stress.jmx" "$threads" "$ramp" "$READ_LOOPS" 0
  run_jmeter_plan "$scenario_dir" "checkout" "parallel-cart-checkout-stress.jmx" "$threads" "$ramp" "$CHECKOUT_LOOPS" "$hot_percent"
  sleep 5
  capture_sql "$scenario_dir"
  capture_actuator "$scenario_dir"
}

require_cmd docker
require_cmd curl
require_cmd jmeter

mkdir -p "$RESULTS_ROOT"

case "${1:-smoke}" in
  smoke)
    run_scenario "low-cache-enabled" 20 10 redis 0
    run_scenario "hot-cache-enabled" 50 10 redis 70
    ;;
  cache-disabled)
    run_scenario "medium-cache-disabled" 60 15 none 0
    ;;
  full)
    run_scenario "low-cache-enabled" 20 10 redis 0
    run_scenario "medium-cache-enabled" 60 15 redis 0
    run_scenario "high-cache-enabled" 100 20 redis 0
    run_scenario "hot-cache-enabled" 100 20 redis 70
    run_scenario "medium-cache-disabled" 60 15 none 0
    ;;
  *)
    echo "Usage: $0 smoke|cache-disabled|full"
    exit 1
    ;;
esac
