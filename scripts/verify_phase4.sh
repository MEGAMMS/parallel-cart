#!/usr/bin/env bash
set -euo pipefail

task="${1:-all}" # t1|t2|t3|t4|benchmark|all
base_url="${BASE_URL:-http://localhost:8080}"

wait_for_app() {
  echo '$ for i in {1..120}; do curl -sf http://localhost:8080/actuator/health >/dev/null && break; sleep 1; done'
  for i in {1..120}; do
    if curl -sf "${base_url}/actuator/health" >/dev/null; then
      echo "[ok] app reachable"
      return 0
    fi
    sleep 1
  done
  echo "[fail] app not reachable"
  exit 1
}

json_field() {
  local file="$1"
  local field="$2"
  if command -v jq >/dev/null 2>&1; then
    jq -r ".$field" "$file"
  else
    python - "$file" "$field" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
print(data.get(sys.argv[2], ""))
PY
  fi
}

pretty_json() {
  local file="$1"
  if command -v jq >/dev/null 2>&1; then
    jq . "$file" 2>/dev/null || cat "$file"
  else
    python -m json.tool "$file" 2>/dev/null || cat "$file"
  fi
}

start_stack() {
  local cache_type="${1:-redis}"
  local max_concurrent="${2:-50}"
  echo '$ docker compose down -v'
  docker compose down -v
  echo "\$ SPRING_CACHE_TYPE=${cache_type} APP_BACKPRESSURE_CHECKOUT_MAX_CONCURRENT=${max_concurrent} docker compose up -d --build"
  SPRING_CACHE_TYPE="${cache_type}" APP_BACKPRESSURE_CHECKOUT_MAX_CONCURRENT="${max_concurrent}" docker compose up -d --build
  wait_for_app
}

assert_2xx() {
  local label="$1"
  local method="$2"
  local url="$3"
  local body="${4:-}"
  local tmp
  tmp=$(mktemp)

  echo
  echo "[$label]"
  if [[ -n "$body" ]]; then
    echo "$ curl -sS -X $method '$url' -H 'Content-Type: application/json' -d '$body'"
    code=$(curl -sS -o "$tmp" -w "%{http_code}" -X "$method" "$url" -H "Content-Type: application/json" -d "$body" || true)
  else
    echo "$ curl -sS -X $method '$url'"
    code=$(curl -sS -o "$tmp" -w "%{http_code}" -X "$method" "$url" || true)
  fi
  echo "HTTP: $code"
  echo "Response:"
  pretty_json "$tmp"
  if [[ "$code" -lt 200 || "$code" -ge 300 ]]; then
    rm -f "$tmp"
    echo "[fail] expected 2xx for $label"
    exit 1
  fi
  rm -f "$tmp"
}

verify_t1_cache_hot_reads() {
  echo "== P4-T1 verify: cache hot reads =="
  start_stack "redis" "50"

  assert_2xx "products-list-prime" GET "${base_url}/api/products"
  assert_2xx "product-detail-prime" GET "${base_url}/api/products/1"

  echo
  echo "[redis-keys]"
  echo '$ docker compose exec -T redis redis-cli --scan --pattern "products::*"'
  docker compose exec -T redis redis-cli --scan --pattern "products::*"
}

verify_t2_cache_invalidation() {
  echo "== P4-T2 verify: cache invalidation after checkout =="
  start_stack "redis" "50"

  assert_2xx "prime-list" GET "${base_url}/api/products"
  assert_2xx "prime-detail-product-1" GET "${base_url}/api/products/1"

  echo
  echo "[redis-before-checkout]"
  echo '$ docker compose exec -T redis redis-cli EXISTS "products::all"'
  docker compose exec -T redis redis-cli EXISTS "products::all"
  echo '$ docker compose exec -T redis redis-cli EXISTS "products::id:1"'
  docker compose exec -T redis redis-cli EXISTS "products::id:1"

  assert_2xx "cart-add" POST "${base_url}/api/carts/2/items" '{"productId":1,"quantity":1}'
  assert_2xx "checkout" POST "${base_url}/api/carts/2/checkout" '{"idempotencyKey":"verify-p4-t2-1"}'

  echo
  echo "[redis-after-checkout]"
  echo '$ docker compose exec -T redis redis-cli EXISTS "products::all"'
  docker compose exec -T redis redis-cli EXISTS "products::all"
  echo '$ docker compose exec -T redis redis-cli EXISTS "products::id:1"'
  docker compose exec -T redis redis-cli EXISTS "products::id:1"
}

verify_t3_bounded_resources() {
  echo "== P4-T3 verify: bounded pools and queue capacities =="
  echo '$ ./mvnw -DskipTests compile'
  ./mvnw -DskipTests compile

  echo
  echo '[config-check]'
  echo '$ rg -n "app.executors|spring.kafka.listener.concurrency" src/main/resources/application-local.yml src/main/java/com/parallelcart/config/ResourceManagementConfig.java'
  rg -n "app.executors|spring.kafka.listener.concurrency|queue-capacity|core-pool-size|max-pool-size|pool-size" \
    src/main/resources/application-local.yml src/main/java/com/parallelcart/config/ResourceManagementConfig.java
}

verify_t4_backpressure_failfast() {
  echo "== P4-T4 verify: backpressure/fail-fast =="
  echo '$ ./mvnw -Dtest=CartControllerSaturationTest test'
  ./mvnw -Dtest=CartControllerSaturationTest test

  start_stack "redis" "1"
  assert_2xx "seed-cart-item" POST "${base_url}/api/carts/2/items" '{"productId":1,"quantity":1}'

  echo
  echo "[runtime-503-attempt]"
  echo "Firing concurrent checkout requests with max-concurrent=1"
  tmp_codes=$(mktemp)
  : > "$tmp_codes"
  for i in $(seq 1 40); do
    (
      code=$(curl -sS -o /dev/null -w "%{http_code}" -X POST \
        "${base_url}/api/carts/2/checkout" \
        -H "Content-Type: application/json" \
        -d "{\"idempotencyKey\":\"p4-t4-${i}\"}" || true)
      echo "$code" >> "$tmp_codes"
    ) &
  done
  wait
  echo "HTTP code counts:"
  sort "$tmp_codes" | uniq -c
  rm -f "$tmp_codes"
}

run_benchmark() {
  echo "== P4 benchmark: before/after cache =="
  echo '$ TARGET_VUS=220 HOLD=120s ./scripts/benchmark_phase4.sh both'
  TARGET_VUS=220 HOLD=120s ./scripts/benchmark_phase4.sh both
}

case "$task" in
  t1) verify_t1_cache_hot_reads ;;
  t2) verify_t2_cache_invalidation ;;
  t3) verify_t3_bounded_resources ;;
  t4) verify_t4_backpressure_failfast ;;
  benchmark) run_benchmark ;;
  all)
    verify_t1_cache_hot_reads
    verify_t2_cache_invalidation
    verify_t3_bounded_resources
    verify_t4_backpressure_failfast
    run_benchmark
    ;;
  *)
    echo "Usage: $0 <t1|t2|t3|t4|benchmark|all>"
    exit 1
    ;;
esac
