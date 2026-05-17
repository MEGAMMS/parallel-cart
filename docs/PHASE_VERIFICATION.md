# Phase Verification Guide (P0, P1, P2, P3)

This file gives runnable commands to verify completed work for phases P0-P3.

## Prerequisites

- Docker running
- Java 21
- Maven wrapper executable (`./mvnw`)

## P0 Verification — Foundation and Architecture

### 1) Build and baseline tests

```bash
./mvnw -q test
```

Expected:
- Exit code `0`

### 2) Start infrastructure + app

```bash
docker compose up -d --build
```

### 3) Check containers health

```bash
docker compose ps
```

Expected:
- `postgres` healthy
- `redis` healthy
- `kafka` healthy
- `app` up

### 4) Check actuator endpoints

```bash
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/actuator/health/readiness
curl -s http://localhost:8080/actuator/health/liveness
```

Expected:
- all responses contain `"status":"UP"`

## P1 Verification — Core Commerce Domain and APIs

### 1) Reset for deterministic seeded data

```bash
docker compose down -v
docker compose up -d --build
```

Why: P1 checks assume local seed dataset (users/products/inventory). Resetting volumes guarantees fresh seeded state.

### 2) Smoke product API

```bash
curl -s http://localhost:8080/api/products
```

Expected:
- JSON array (seeded products in local profile)

### 3) Create cart item and checkout

```bash
USER_ID=2
PRODUCT_ID=1

curl -s -X POST http://localhost:8080/api/carts/${USER_ID}/items \
  -H "Content-Type: application/json" \
  -d "{\"productId\":${PRODUCT_ID},\"quantity\":2}"

curl -s -X POST http://localhost:8080/api/carts/${USER_ID}/checkout \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"demo-key-1"}'
```

Expected:
- checkout response with fields: `orderId`, `paymentId`, `totalAmount`, `status`

### 4) Validation error shape check

```bash
curl -s -X POST http://localhost:8080/api/carts/${USER_ID}/items \
  -H "Content-Type: application/json" \
  -d '{"productId":1,"quantity":0}'
```

Expected:
- `ValidationError` response with `validationErrors.quantity`

## P2 Verification — Concurrency and Transaction Integrity

### 1) Run transaction integrity integration tests

```bash
./mvnw -q -Dtest=CheckoutTransactionIntegrityTest test
```

Expected:
- Exit code `0`
- verifies commit case and rollback case

### 2) Idempotency check (duplicate checkout request)

```bash
USER_ID=2
PRODUCT_ID=2

curl -s -X POST http://localhost:8080/api/carts/${USER_ID}/items \
  -H "Content-Type: application/json" \
  -d "{\"productId\":${PRODUCT_ID},\"quantity\":1}"

curl -s -X POST http://localhost:8080/api/carts/${USER_ID}/checkout \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"idem-proof-1"}'

curl -s -X POST http://localhost:8080/api/carts/${USER_ID}/checkout \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"idem-proof-1"}'
```

Expected:
- second response returns same `orderId` and `paymentId` as first

### 3) Optional quick concurrency stress check (local)

```bash
USER_ID=2
PRODUCT_ID=3
for i in {1..20}; do
  curl -s -X POST http://localhost:8080/api/carts/${USER_ID}/items \
    -H "Content-Type: application/json" \
    -d "{\"productId\":${PRODUCT_ID},\"quantity\":1}" >/dev/null
done

for i in {1..10}; do
  curl -s -X POST http://localhost:8080/api/carts/${USER_ID}/checkout \
    -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"load-${i}\"}" &
done
wait
```

Expected:
- no app crash
- conflict responses may appear under contention, but no server failure

## P3 Verification — Kafka Async Flow, DLQ Handler, and Outbox

### 1) Reset and start fresh environment

```bash
docker compose down -v
docker compose up -d --build
```

### 2) Trigger checkout to produce `order.created`

```bash
USER_ID=2
PRODUCT_ID=1
IDEM_KEY="verify-phase-p3-$(date +%s)"

curl -s -X POST http://localhost:8080/api/carts/${USER_ID}/items \
  -H "Content-Type: application/json" \
  -d "{\"productId\":${PRODUCT_ID},\"quantity\":1}"

curl -s -X POST http://localhost:8080/api/carts/${USER_ID}/checkout \
  -H "Content-Type: application/json" \
  -d "{\"idempotencyKey\":\"${IDEM_KEY}\"}"
```

Expected:
- checkout returns `orderId` and `paymentId`

### 3) Verify async consumers persisted side effects

Replace `<ORDER_ID>` with the checkout `orderId`.

```bash
docker compose exec -T postgres psql -U parallel_cart -d parallel_cart -tAc \
  "select count(*) from invoices where order_id = <ORDER_ID>;"

docker compose exec -T postgres psql -U parallel_cart -d parallel_cart -tAc \
  "select count(*) from notification_logs where order_id = <ORDER_ID>;"
```

Expected:
- invoice count `>= 1`
- notification count `>= 1`

### 4) Verify outbox event was published

```bash
docker compose exec -T postgres psql -U parallel_cart -d parallel_cart -tAc \
  "select count(*) from outbox_events where aggregate_id = <ORDER_ID> and event_type = 'ORDER_CREATED' and status = 'PUBLISHED';"
```

Expected:
- count `>= 1`

### 5) Optional: check last outbox records snapshot

```bash
docker compose exec -T postgres psql -U parallel_cart -d parallel_cart -c \
  "select id, event_type, aggregate_id, status, publish_attempts, published_at from outbox_events order by id desc limit 5;"
```

Expected:
- latest `ORDER_CREATED` row appears as `PUBLISHED`

### 6) One-command script verification

```bash
./scripts/verify_phase.sh p3
```

Expected:
- script exits with `0`
- prints formatted checkout response
- prints pre-check table counters (before checkout)
- prints order/payment row for the created order
- prints outbox row before and after async publish
- confirms invoice, notification, and published outbox rows
- prints final delta summary for `outbox_events`, `invoices`, and `notification_logs`

## Cleanup

```bash
docker compose down
```

Full reset (also volumes):

```bash
docker compose down -v
```
