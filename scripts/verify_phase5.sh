#!/usr/bin/env bash
set -euo pipefail

task="${1:-all}" # t1|t2|t3|t4|all
base_app_url="${BASE_APP_URL:-http://localhost:8080}"
base_lb_url="${BASE_LB_URL:-http://localhost:8088}"

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "[fail] missing command: $cmd"
    exit 1
  fi
}

wait_for_url() {
  local url="$1"
  local label="$2"
  for _ in {1..180}; do
    if curl -sf "$url" >/dev/null; then
      echo "[ok] ${label} reachable: ${url}"
      return 0
    fi
    sleep 1
  done
  echo "[fail] timeout waiting for ${label}: ${url}"
  exit 1
}

print_sql() {
  local sql="$1"
  echo "$ docker compose exec -T postgres psql -U parallel_cart -d parallel_cart -c \"$sql\""
  docker compose exec -T postgres psql -U parallel_cart -d parallel_cart -c "$sql"
}

verify_t1_batch_chunk_aggregation() {
  echo "== P5-T1 verify: daily chunk aggregation =="
  echo '$ ./mvnw -Dtest=DailySalesAggregationServiceTest test'
  ./mvnw -Dtest=DailySalesAggregationServiceTest test

  echo
  echo "[code-check: chunk query + paid filter + summary write]"
  echo '$ rg -n "findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanAndIdGreaterThanOrderByIdAsc|OrderStatus.PAID|setProcessedChunks|DailySalesSummary" src/main/java/com/parallelcart/service/impl/DailySalesAggregationServiceImpl.java'
  rg -n "findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanAndIdGreaterThanOrderByIdAsc|OrderStatus.PAID|setProcessedChunks|DailySalesSummary" \
    src/main/java/com/parallelcart/service/impl/DailySalesAggregationServiceImpl.java

  echo
  echo "[ok] P5-T1 validated by integration test + implementation checks"
}

verify_t2_resumable_checkpointing() {
  echo "== P5-T2 verify: resumable checkpointing =="
  echo '$ ./mvnw -Dtest=DailySalesAggregationCheckpointResumeTest test'
  ./mvnw -Dtest=DailySalesAggregationCheckpointResumeTest test

  echo
  echo "[code-check: checkpoint fields + max-chunks-per-run]"
  echo '$ rg -n "lastProcessedOrderId|paidOrderCount|totalRevenue|processedChunks|BatchRunStatus|max-chunks-per-run" src/main/java/com/parallelcart/domain/model/DailySalesCheckpoint.java src/main/java/com/parallelcart/service/impl/DailySalesAggregationServiceImpl.java src/main/resources/application-local.yml'
  rg -n "lastProcessedOrderId|paidOrderCount|totalRevenue|processedChunks|BatchRunStatus|max-chunks-per-run" \
    src/main/java/com/parallelcart/domain/model/DailySalesCheckpoint.java \
    src/main/java/com/parallelcart/service/impl/DailySalesAggregationServiceImpl.java \
    src/main/resources/application-local.yml

  echo
  echo "[ok] P5-T2 validated by resume integration test + configuration checks"
}

verify_t3_multi_instance_lb() {
  echo "== P5-T3 verify: 2 app instances behind nginx =="
  require_cmd curl
  require_cmd docker

  echo '$ docker compose down -v'
  docker compose down -v

  echo '$ docker compose --profile lb up -d --build app-1 app-2 nginx'
  docker compose --profile lb up -d --build app-1 app-2 nginx

  wait_for_url "${base_lb_url}/actuator/health" "load balancer"

  echo
  echo "[containers]"
  echo '$ docker compose ps app-1 app-2 nginx'
  docker compose ps app-1 app-2 nginx

  echo
  echo "[health via lb]"
  echo "\$ curl -s ${base_lb_url}/actuator/health"
  curl -s "${base_lb_url}/actuator/health"
  echo

  echo
  echo "[distribution sample via X-Upstream-Addr]"
  echo "Running concurrent burst (60 requests, concurrency=12) to avoid least_conn tie-stickiness"
  tmp_headers=$(mktemp)
  : > "$tmp_headers"
  for i in $(seq 1 60); do
    (
      curl -s -D - "${base_lb_url}/api/products" -o /dev/null \
        | tr -d '\r' \
        | awk -F': ' '/^X-Upstream-Addr:/{print $2}' >> "$tmp_headers"
    ) &
    if (( i % 12 == 0 )); then
      wait
    fi
  done
  wait

  echo "Raw upstreams:"
  cat "$tmp_headers"
  echo
  echo "Counts:"
  sort "$tmp_headers" | uniq -c

  unique_count=$(sort -u "$tmp_headers" | sed '/^$/d' | wc -l | tr -d ' ')
  rm -f "$tmp_headers"

  if [[ "$unique_count" -lt 2 ]]; then
    echo "[fail] expected traffic distribution across at least 2 upstreams"
    exit 1
  fi

  echo
  echo "[ok] P5-T3 verified: traffic reached multiple app instances"
}

verify_t4_strategy_docs() {
  echo "== P5-T4 verify: strategy/rationale documentation =="

  if [[ ! -f docs/load-distribution-strategy.md ]]; then
    echo "[fail] missing docs/load-distribution-strategy.md"
    exit 1
  fi

  echo '$ rg -n "Topology|Strategy|least_conn|Tradeoffs|X-Upstream-Addr" docs/load-distribution-strategy.md'
  rg -n "Topology|Strategy|least_conn|Tradeoffs|X-Upstream-Addr" docs/load-distribution-strategy.md

  echo
  echo "[ok] P5-T4 docs present with strategy and rationale"
}

case "$task" in
  t1) verify_t1_batch_chunk_aggregation ;;
  t2) verify_t2_resumable_checkpointing ;;
  t3) verify_t3_multi_instance_lb ;;
  t4) verify_t4_strategy_docs ;;
  all)
    verify_t1_batch_chunk_aggregation
    verify_t2_resumable_checkpointing
    verify_t3_multi_instance_lb
    verify_t4_strategy_docs
    ;;
  *)
    echo "Usage: $0 <t1|t2|t3|t4|all>"
    exit 1
    ;;
esac
