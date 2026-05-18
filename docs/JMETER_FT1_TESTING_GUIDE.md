# JMeter FT1 Testing Guide

## 1) API Contract Verified From Code

Source files checked:
- `src/main/java/com/parallelcart/api/controller/CartController.java`
- `src/main/java/com/parallelcart/api/dto/CartItemRequest.java`
- `src/main/java/com/parallelcart/api/dto/CheckoutRequest.java`
- `src/main/java/com/parallelcart/config/SecurityConfig.java`
- `src/main/java/com/parallelcart/config/DataSeeder.java`
- `src/test/java/com/parallelcart/CheckoutConcurrencyRaceConditionTest.java`
- `src/test/java/com/parallelcart/CheckoutCapacityHttp429Test.java`
- `scripts/ft1/run-load-distribution-check.sh`

Confirmed endpoints and payloads:
- Add item:
  - `POST /api/carts/{userId}/items`
  - Header: `Content-Type: application/json`
  - Body:
    ```json
    {"productId": 1, "quantity": 1}
    ```
- Checkout:
  - `POST /api/carts/{userId}/checkout`
  - Header: `Content-Type: application/json`
  - Body:
    ```json
    {"idempotencyKey": "some-unique-key"}
    ```
- Load distribution endpoint used by FT1 script:
  - `GET /actuator/health`
  - Response includes `X-App-Instance` header (via `InstanceIdResponseHeaderFilter`).

Authentication requirement:
- None for `/api/**` and `/actuator/**` (`SecurityConfig` has `permitAll`).

Seed assumptions (local profile):
- Products: IDs `1..40`
- Users: only IDs `1` and `2` are guaranteed by seeder.

## 2) CSV Files

Created files:
- `jmeter/csv/checkout_capacity_requests.csv` (24 data rows)
- `jmeter/csv/concurrency_inventory_requests.csv` (12 data rows)
- `jmeter/csv/load_distribution_requests.csv` (40 data rows)

Headers:
- `checkout_capacity_requests.csv`
  - `userId,productId,quantity,idempotencyKey,expectedScenario`
- `concurrency_inventory_requests.csv`
  - `userId,productId,quantity,idempotencyKey,expectedScenario`
- `load_distribution_requests.csv`
  - `requestId,method,path,expectedStatus,expectedHeader`

## 3) Pre-Test Data Prep

Because the CSVs use user IDs beyond seed user `2`, create users `2..25` once:

```bash
docker compose exec -T postgres psql -U parallel_cart -d parallel_cart -c "
INSERT INTO users (id, email, password_hash, role, created_at)
SELECT gs, 'jmeter-user-' || gs || '@parallelcart.local', 'jmeter-hash', 'CUSTOMER', now()
FROM generate_series(2,25) gs
ON CONFLICT (id) DO NOTHING;
"
```

Optional cleanup before a fresh run:

```bash
docker compose exec -T postgres psql -U parallel_cart -d parallel_cart -c "
DELETE FROM payments;
DELETE FROM order_items;
DELETE FROM orders;
DELETE FROM cart_items;
DELETE FROM carts;
"
```

## 4) JMeter Setup (CSV Data Set Config)

For each plan, add `CSV Data Set Config`:
- `Filename`: one of the files under `jmeter/csv/`
- `Variable Names`: match the CSV header exactly
- `Delimiter`: `,`
- `Ignore first line`: `true`
- `Recycle on EOF`: `false`
- `Stop thread on EOF`: `true`
- `Sharing mode`: `All threads`

## 5) Req1 / Req2 Sampler Setup (Direct App)

Server and port:
- Host: `localhost`
- Port: `8080`

Create one Thread Group per scenario and add these two HTTP samplers in order.

### Sampler A: Add Cart Item
- Method: `POST`
- Path: `/api/carts/${userId}/items`
- Header Manager:
  - `Content-Type: application/json`
- Body Data:
```json
{
  "productId": ${productId},
  "quantity": ${quantity}
}
```

### Sampler B: Checkout
- Method: `POST`
- Path: `/api/carts/${userId}/checkout`
- Header Manager:
  - `Content-Type: application/json`
- Body Data:
```json
{
  "idempotencyKey": "${idempotencyKey}"
}
```

### Req1 (Concurrent Access & Data Integrity)
- CSV: `jmeter/csv/concurrency_inventory_requests.csv`
- Thread Group suggestion:
  - Threads: `12`
  - Ramp-up: `1`
  - Loop count: `1`
- Expectation:
  - Product contention on same product ID (`1`).
  - Not all checkouts should succeed when inventory is constrained.
  - Possible failures include business conflict responses.

### Req2 (Capacity Control)
- CSV: `jmeter/csv/checkout_capacity_requests.csv`
- Thread Group suggestion:
  - Threads: `24`
  - Ramp-up: `1`
  - Loop count: `1`
- Important runtime setting:
  - Capacity rejections are strongest when `app.checkout.max-concurrent=1`.
  - If running app directly:
    ```bash
    ./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local --app.checkout.max-concurrent=1"
    ```
- Expectation:
  - Mix of `200` and `429` responses.

## 6) Req5 Sampler Setup (Load Distribution via Nginx)

Server and port:
- Host: `localhost`
- Port: `8081` (Nginx)

CSV:
- `jmeter/csv/load_distribution_requests.csv`

Thread Group suggestion:
- Threads: `40`
- Ramp-up: `1`
- Loop count: `1`

HTTP sampler:
- Method: `${method}`
- Path: `${path}`
- No JSON body required.

Expectation:
- Response code `200`.
- `X-App-Instance` header should alternate across `app-1` and `app-2` over many requests.

## 7) JMeter Variables You Will Use

From capacity/concurrency CSV:
- `${userId}`
- `${productId}`
- `${quantity}`
- `${idempotencyKey}`
- `${expectedScenario}` (optional label only)

From load CSV:
- `${requestId}`
- `${method}`
- `${path}`
- `${expectedStatus}`
- `${expectedHeader}`

## 8) Validation Performed

Validated with real `curl` against current app:

1) Add item:
- `POST /api/carts/2/items` with `{"productId":1,"quantity":1}`
- Result: HTTP `200`

2) Checkout:
- `POST /api/carts/2/checkout` with `{"idempotencyKey":"capacity-jmeter-001"}`
- Result: HTTP `200`

3) Load endpoint:
- `GET http://localhost:8081/actuator/health`
- Result: HTTP `200`, header includes `X-App-Instance`.

## 9) Screenshots To Capture

For each requirement plan, capture:
- `View Results in Table` (response code distribution)
- `View Results Tree` (request + response, including response codes)
- `Summary Report`
- `Aggregate Report`

For Req5 specifically:
- In `View Results Tree`, open response headers and capture `X-App-Instance` values from multiple samples.

## 10) Limitations

- CSVs for Req1/Req2 assume user IDs `2..25` exist. Only user IDs `1` and `2` are seed-guaranteed by default.
- Capacity behavior (`429`) depends on runtime property `app.checkout.max-concurrent`; default local value is higher than `1`.
- For strict inventory contention experiments, you may need to reduce inventory for product `1` before running Req1.
