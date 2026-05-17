#!/usr/bin/env bash
set -euo pipefail

phase="${1:-}"

if [[ -z "$phase" ]]; then
  echo "Usage: $0 <p0|p1|p2>"
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
  *)
    echo "Unknown phase: $phase"
    echo "Usage: $0 <p0|p1|p2>"
    exit 1
    ;;
esac
