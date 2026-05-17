#!/usr/bin/env bash
set -euo pipefail

phase="${1:-}"

if [[ -z "$phase" ]]; then
  echo "Usage: $0 <p0|p1|p2|p3>"
  exit 1
fi

pretty_json() {
  local file="$1"
  if command -v jq >/dev/null 2>&1; then
    jq . "$file" 2>/dev/null || cat "$file"
  else
    python -m json.tool "$file" 2>/dev/null || cat "$file"
  fi
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

run_sql_scalar() {
  local sql="$1"
  docker compose exec -T postgres psql -U parallel_cart -d parallel_cart -tAc "$sql" | tr -d '[:space:]'
}

print_sql_table() {
  local label="$1"
  local sql="$2"
  echo
  echo "[$label]"
  echo "$ docker compose exec -T postgres psql -U parallel_cart -d parallel_cart -c \"$sql\""
  docker compose exec -T postgres psql -U parallel_cart -d parallel_cart -c "$sql"
}

wait_for_count_ge_1() {
  local label="$1"
  local sql="$2"
  local retries=60
  local sleep_s=2
  local value=""

  echo "$ wait_for '$label' (up to $((retries * sleep_s))s)"
  for i in $(seq 1 "$retries"); do
    value=$(run_sql_scalar "$sql" || true)
    if [[ "$value" =~ ^[0-9]+$ ]] && [[ "$value" -ge 1 ]]; then
      echo "[ok] $label count=$value"
      return 0
    fi
    sleep "$sleep_s"
  done

  echo "[fail] timeout waiting for $label"
  echo "Last value: ${value:-<empty>}"
  return 1
}

wait_for_app() {
  echo '$ for i in {1..120}; do curl -sf http://localhost:8080/actuator/health >/dev/null && break; sleep 1; done'
  for i in {1..120}; do
    if curl -sf http://localhost:8080/actuator/health >/dev/null; then
      echo "[ok] app reachable"
      return 0
    fi
    sleep 1
  done
  echo "[fail] app not reachable in 120s"
  echo '$ docker compose ps -a'
  docker compose ps -a || true
  echo '$ docker compose logs --no-color --tail=80 app'
  docker compose logs --no-color --tail=80 app || true
  exit 1
}

print_http() {
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
    local code
    code=$(curl -sS -o "$tmp" -w "%{http_code}" -X "$method" "$url" -H "Content-Type: application/json" -d "$body" || true)
    echo "HTTP: $code"
  else
    echo "$ curl -sS -X $method '$url'"
    local code
    code=$(curl -sS -o "$tmp" -w "%{http_code}" -X "$method" "$url" || true)
    echo "HTTP: $code"
  fi

  echo "Response:"
  pretty_json "$tmp"
  rm -f "$tmp"

  if [[ "$code" -ge 400 || "$code" -lt 200 ]]; then
    echo "[fail] $label returned HTTP $code"
    exit 1
  fi
}

case "$phase" in
  p0)
    echo "== P0 verify =="
    echo '$ ./mvnw -q test'
    ./mvnw -q test
    echo '$ docker compose up -d --build'
    docker compose up -d --build
    wait_for_app
    echo '$ docker compose ps'
    docker compose ps
    print_http "health" GET "http://localhost:8080/actuator/health"
    print_http "readiness" GET "http://localhost:8080/actuator/health/readiness"
    print_http "liveness" GET "http://localhost:8080/actuator/health/liveness"
    ;;
  p1)
    echo "== P1 verify =="
    echo '$ docker compose down -v'
    docker compose down -v
    echo '$ docker compose up -d --build'
    docker compose up -d --build
    wait_for_app

    print_http "products" GET "http://localhost:8080/api/products"

    USER_ID=2
    PRODUCT_ID=1
    print_http "cart-add" POST "http://localhost:8080/api/carts/${USER_ID}/items" "{\"productId\":${PRODUCT_ID},\"quantity\":2}"
    print_http "checkout" POST "http://localhost:8080/api/carts/${USER_ID}/checkout" '{"idempotencyKey":"verify-phase-p1"}'
    ;;
  p2)
    echo "== P2 verify =="
    echo '$ ./mvnw -q -Dtest=CheckoutTransactionIntegrityTest test'
    ./mvnw -q -Dtest=CheckoutTransactionIntegrityTest test
    echo '$ docker compose up -d --build'
    docker compose up -d --build
    wait_for_app

    USER_ID=2
    PRODUCT_ID=2
    print_http "cart-add" POST "http://localhost:8080/api/carts/${USER_ID}/items" "{\"productId\":${PRODUCT_ID},\"quantity\":1}"

    echo
    echo "[idempotency]"
    echo "$ curl -sS -X POST 'http://localhost:8080/api/carts/${USER_ID}/checkout' -H 'Content-Type: application/json' -d '{\"idempotencyKey\":\"verify-phase-p2\"}'"
    first_file=$(mktemp)
    second_file=$(mktemp)
    curl -sS -X POST "http://localhost:8080/api/carts/${USER_ID}/checkout" -H "Content-Type: application/json" -d '{"idempotencyKey":"verify-phase-p2"}' > "$first_file"
    curl -sS -X POST "http://localhost:8080/api/carts/${USER_ID}/checkout" -H "Content-Type: application/json" -d '{"idempotencyKey":"verify-phase-p2"}' > "$second_file"
    echo "First response:"
    pretty_json "$first_file"
    echo "Second response:"
    pretty_json "$second_file"
    rm -f "$first_file" "$second_file"
    ;;
  p3)
    echo "== P3 verify =="
    echo '$ docker compose down -v'
    docker compose down -v
    echo '$ docker compose up -d --build'
    docker compose up -d --build
    wait_for_app

    USER_ID=2
    PRODUCT_ID=1
    IDEM_KEY="verify-phase-p3-$(date +%s)"

    echo
    echo "[phase3-initial-state]"
    OUTBOX_BEFORE=$(run_sql_scalar "select count(*) from outbox_events;")
    INVOICE_BEFORE=$(run_sql_scalar "select count(*) from invoices;")
    NOTIFICATION_BEFORE=$(run_sql_scalar "select count(*) from notification_logs;")
    echo "outbox_events before checkout: ${OUTBOX_BEFORE}"
    echo "invoices before checkout: ${INVOICE_BEFORE}"
    echo "notification_logs before checkout: ${NOTIFICATION_BEFORE}"

    print_http "cart-add" POST "http://localhost:8080/api/carts/${USER_ID}/items" "{\"productId\":${PRODUCT_ID},\"quantity\":1}"

    checkout_file=$(mktemp)
    echo
    echo "[checkout]"
    echo "$ curl -sS -X POST 'http://localhost:8080/api/carts/${USER_ID}/checkout' -H 'Content-Type: application/json' -d '{\"idempotencyKey\":\"${IDEM_KEY}\"}'"
    checkout_code=$(curl -sS -o "$checkout_file" -w "%{http_code}" -X POST "http://localhost:8080/api/carts/${USER_ID}/checkout" -H "Content-Type: application/json" -d "{\"idempotencyKey\":\"${IDEM_KEY}\"}" || true)
    echo "HTTP: $checkout_code"
    echo "Response:"
    pretty_json "$checkout_file"
    if [[ "$checkout_code" -ge 400 || "$checkout_code" -lt 200 ]]; then
      echo "[fail] checkout returned HTTP $checkout_code"
      rm -f "$checkout_file"
      exit 1
    fi

    ORDER_ID=$(json_field "$checkout_file" "orderId")
    rm -f "$checkout_file"
    if [[ -z "$ORDER_ID" || "$ORDER_ID" == "null" ]]; then
      echo "[fail] checkout response does not contain orderId"
      exit 1
    fi
    echo "Extracted orderId=$ORDER_ID"

    print_sql_table "order-payment-row" "select o.id as order_id, o.status as order_status, o.total_amount, p.id as payment_id, p.status as payment_status from orders o join payments p on p.order_id = o.id where o.id = ${ORDER_ID};"
    print_sql_table "outbox-row-initial" "select id, event_type, aggregate_id, status, publish_attempts, published_at, left(coalesce(last_error, ''), 120) as last_error from outbox_events where aggregate_id = ${ORDER_ID} order by id desc;"

    wait_for_count_ge_1 "invoice row" "select count(*) from invoices where order_id = ${ORDER_ID};"
    wait_for_count_ge_1 "notification row" "select count(*) from notification_logs where order_id = ${ORDER_ID};"
    wait_for_count_ge_1 "published outbox row" "select count(*) from outbox_events where aggregate_id = ${ORDER_ID} and event_type = 'ORDER_CREATED' and status = 'PUBLISHED';"

    print_sql_table "invoice-row" "select id, order_id, user_id, payment_id, total_amount, order_status, issued_at from invoices where order_id = ${ORDER_ID} order by id desc;"
    print_sql_table "notification-row" "select id, order_id, user_id, channel, left(message, 120) as message, sent_at from notification_logs where order_id = ${ORDER_ID} order by id desc;"
    print_sql_table "outbox-row-final" "select id, event_type, aggregate_id, status, publish_attempts, published_at, left(coalesce(last_error, ''), 120) as last_error from outbox_events where aggregate_id = ${ORDER_ID} order by id desc;"
    print_sql_table "outbox-latest-5" "select id, event_type, aggregate_id, status, publish_attempts, published_at from outbox_events order by id desc limit 5;"

    OUTBOX_AFTER=$(run_sql_scalar "select count(*) from outbox_events;")
    INVOICE_AFTER=$(run_sql_scalar "select count(*) from invoices;")
    NOTIFICATION_AFTER=$(run_sql_scalar "select count(*) from notification_logs;")
    echo
    echo "[phase3-summary]"
    echo "orderId=${ORDER_ID}"
    echo "outbox_events delta: ${OUTBOX_BEFORE} -> ${OUTBOX_AFTER}"
    echo "invoices delta: ${INVOICE_BEFORE} -> ${INVOICE_AFTER}"
    echo "notification_logs delta: ${NOTIFICATION_BEFORE} -> ${NOTIFICATION_AFTER}"
    ;;
  *)
    echo "Unknown phase: $phase"
    echo "Usage: $0 <p0|p1|p2|p3>"
    exit 1
    ;;
esac
