#!/usr/bin/env bash
set -euo pipefail

mode="${1:-both}" # before|after|both
base_url="${BASE_URL:-http://localhost:8080}"
results_dir="${RESULTS_DIR:-/tmp/parallel-cart-k6}"
start_vus="${START_VUS:-20}"
target_vus="${TARGET_VUS:-220}"
ramp_up="${RAMP_UP:-30s}"
hold="${HOLD:-120s}"
ramp_down="${RAMP_DOWN:-30s}"
product_count="${PRODUCT_COUNT:-20}"
sleep_seconds="${SLEEP_SECONDS:-0.05}"
seed_products="${SEED_PRODUCTS:-0}"

mkdir -p "$results_dir"

wait_for_app() {
  for _ in {1..180}; do
    if curl -sf "${base_url}/actuator/health" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "[fail] app not reachable at ${base_url}"
  exit 1
}

start_stack() {
  local cache_type="$1"
  echo "\n== start stack cache_type=${cache_type} =="
  SPRING_CACHE_TYPE="$cache_type" docker compose up -d --build
  wait_for_app
  if [[ "$seed_products" -gt 0 ]]; then
    seed_catalog "$seed_products"
  fi
}

seed_catalog() {
  local count="$1"
  echo "\n== seeding extra catalog rows: ${count} =="
  docker compose exec -T postgres psql -U parallel_cart -d parallel_cart <<SQL
insert into products (sku, name, description, price, active, created_at)
select
  'K6-SKU-' || gs,
  'K6 Product ' || gs,
  'k6 benchmark seeded product ' || gs,
  99.99,
  true,
  now()
from generate_series(1, ${count}) gs
on conflict (sku) do nothing;
SQL
}

run_k6() {
  local label="$1"
  local output_file="$results_dir/${label}.json"

  echo "\n== k6 run: ${label} =="
  docker run --rm --network host \
    -v "$(pwd)/scripts:/scripts" \
    grafana/k6:0.51.0 run /scripts/k6_products_read.js \
    --summary-export "/scripts/${label}.json" \
    -e BASE_URL="$base_url" \
    -e START_VUS="$start_vus" \
    -e TARGET_VUS="$target_vus" \
    -e RAMP_UP="$ramp_up" \
    -e HOLD="$hold" \
    -e RAMP_DOWN="$ramp_down" \
    -e PRODUCT_COUNT="$product_count" \
    -e SLEEP_SECONDS="$sleep_seconds"

  cp "scripts/${label}.json" "$output_file"
  rm -f "scripts/${label}.json"
}

print_summary() {
  local json_file="$1"
  python - "$json_file" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, 'r', encoding='utf-8') as f:
    data = json.load(f)

m = data.get('metrics', {})
lat = m.get('http_req_duration', {})
rps = m.get('http_reqs', {}).get('rate', 0)
checks = m.get('checks', {}).get('value', 0)

print(f"avg_ms={lat.get('avg', 0):.2f}")
print(f"p90_ms={lat.get('p(90)', 0):.2f}")
print(f"p95_ms={lat.get('p(95)', 0):.2f}")
print(f"max_ms={lat.get('max', 0):.2f}")
print(f"rps={rps:.2f}")
print(f"checks_rate={checks:.4f}")
PY
}

compare() {
  local before_file="$results_dir/before_no_cache.json"
  local after_file="$results_dir/after_redis_cache.json"

  python - "$before_file" "$after_file" <<'PY'
import json
import sys

def read(path):
    with open(path, 'r', encoding='utf-8') as f:
        d = json.load(f)
    m = d['metrics']
    lat = m['http_req_duration']
    rps = m['http_reqs']['rate']
    return {
        'avg': lat.get('avg', 0.0),
        'p95': lat.get('p(95)', 0.0),
        'rps': rps,
    }

b = read(sys.argv[1])
a = read(sys.argv[2])

avg_improvement = ((b['avg'] - a['avg']) / b['avg'] * 100) if b['avg'] else 0
p95_improvement = ((b['p95'] - a['p95']) / b['p95'] * 100) if b['p95'] else 0
rps_gain = ((a['rps'] - b['rps']) / b['rps'] * 100) if b['rps'] else 0

print("\n== before vs after ==")
print(f"avg latency: {b['avg']:.2f}ms -> {a['avg']:.2f}ms ({avg_improvement:.2f}% faster)")
print(f"p95 latency: {b['p95']:.2f}ms -> {a['p95']:.2f}ms ({p95_improvement:.2f}% faster)")
print(f"throughput: {b['rps']:.2f} rps -> {a['rps']:.2f} rps ({rps_gain:.2f}% change)")
PY
}

case "$mode" in
  before)
    docker compose down -v
    start_stack "none"
    run_k6 "before_no_cache"
    echo "\n== summary: before_no_cache =="
    print_summary "$results_dir/before_no_cache.json"
    ;;
  after)
    docker compose down -v
    start_stack "redis"
    run_k6 "after_redis_cache"
    echo "\n== summary: after_redis_cache =="
    print_summary "$results_dir/after_redis_cache.json"
    ;;
  both)
    docker compose down -v
    start_stack "none"
    run_k6 "before_no_cache"

    docker compose down -v
    start_stack "redis"
    run_k6 "after_redis_cache"

    echo "\n== summary: before_no_cache =="
    print_summary "$results_dir/before_no_cache.json"
    echo "\n== summary: after_redis_cache =="
    print_summary "$results_dir/after_redis_cache.json"
    compare
    ;;
  *)
    echo "Usage: $0 <before|after|both>"
    exit 1
    ;;
esac
