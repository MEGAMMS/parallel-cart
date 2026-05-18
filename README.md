# Parallel Cart

A high-performance Spring Boot e-commerce backend engineered for concurrency, transactional integrity, and asynchronous order processing.

## Project Overview

`parallel-cart` is a backend engine that implements a minimal commerce domain with cart management, checkout, inventory reservation, payments, and async event-driven notifications via Kafka. It is designed for:

- safe concurrent checkout using optimistic locking + pessimistic fallback
- idempotent order submission
- Redis caching for product reads
- Kafka-based asynchronous order pipelines with outbox event publishing
- health-aware infrastructure orchestration via Docker Compose

## Key Features

- Product listing and product detail API
- User cart CRUD operations (add, update, remove, get)
- Checkout with transactional order, payment, and inventory reservations
- Idempotent checkout using `idempotencyKey`
- Optimistic locking on inventory updates with retry and pessimistic lock fallback
- Redis cache for hot product reads
- Kafka event publishing for `order.created`
- Outbox persistence for reliable async message delivery
- Spring Boot actuator health checks for DB, Redis, Kafka
- Local seed data for users, products, and inventory in `local` profile

## Architecture

Package structure:

- `api` — REST controllers and DTOs
- `service` — business logic interfaces and implementations
- `domain` — JPA entities and domain models
- `infra` — repositories, messaging, outbox, and persistence
- `config` — Spring configuration, caching, seeding
- `observability` — monitoring and health-related support

Concurrency and reliability:

- Inventory reservations are attempted with an optimistic lock strategy
- On repeated version conflicts, the service upgrades to a pessimistic lock
- Checkout is transactional and clears cart items only after order and payment succeed
- Duplicate checkout requests return the same order/payment when `idempotencyKey` is reused

Async processing:

- Order creation publishes `OrderCreatedEvent` to Kafka
- Outbox pattern ensures event persistence before publish retries
- Kafka topic names are configured in `application-local.yml`

## Tech Stack

- Java 21
- Spring Boot 3.5.x
- Spring Data JPA
- Spring Web
- Spring Security
- Spring Cache / Redis
- Spring Kafka
- PostgreSQL
- Redis
- Apache Kafka + Zookeeper
- Docker Compose
- JUnit / Spring Boot Test

## Running Locally

### Prerequisites

- Docker Desktop / Docker Engine
- Java 21
- Maven wrapper is included (`mvnw`, `mvnw.cmd`)

### Start the app and infrastructure

```bash
docker compose up -d --build
```

This brings up:

- `postgres` on `localhost:5432`
- `redis` on `localhost:6379`
- `kafka` on `localhost:9092`
- `app` on `localhost:8080`

### Verify service health

```bash
curl -s http://localhost:8080/actuator/health | jq
curl -s http://localhost:8080/actuator/health/readiness | jq
```

Expected: all statuses should be `UP`.

## API Reference

### Products

- `GET /api/products` — list all products
- `GET /api/products/{id}` — get product details

### Cart

- `GET /api/carts/{userId}` — retrieve a user's cart
- `POST /api/carts/{userId}/items` — add a product to a cart
- `PUT /api/carts/{userId}/items/{itemId}` — update cart item quantity
- `DELETE /api/carts/{userId}/items/{itemId}` — remove item from cart

### Checkout

- `POST /api/carts/{userId}/checkout` — perform checkout with `idempotencyKey`

#### Checkout request example

```json
{
  "idempotencyKey": "checkout-1234"
}
```

#### Checkout response example

```json
{
  "orderId": 10,
  "paymentId": 5,
  "totalAmount": 120.00,
  "status": "PAID"
}
```

## Sample Local Seed Data

The `local` Spring profile seeds data automatically on startup via `DataSeeder`:

- two users: `admin@parallelcart.local`, `customer@parallelcart.local`
- 40 sample products, each with inventory of 500 units

## Configuration

### Profiles

- `default` → `local`
- `application-local.yml` configures local DB, Redis, Kafka, cache TTL, executor pools, topic names, and outbox retry settings

### Docker Compose

`docker-compose.yml` starts all required runtime services and sets environment variables for the Spring Boot app.

### Important config values

- Kafka topic: `order.created`
- Kafka DLQ topic: `order.created.dlq`
- Redis product cache TTL: `60` seconds
- Outbox publisher delay: `2000` ms

## Testing

### Run full test suite

```bash
./mvnw test
```

### Run FT1 (Requirements 1-5) verification scripts

```bash
./scripts/ft1/run-concurrency-check.sh
./scripts/ft1/run-capacity-check.sh
./scripts/ft1/run-async-queue-check.sh
./scripts/ft1/run-batch-check.sh
./scripts/ft1/run-load-distribution-check.sh
```

Combined report target:
- `docs/FT1_VERIFICATION_RESULTS.md`

### Run checkout integrity tests only

```bash
./mvnw -Dtest=CheckoutTransactionIntegrityTest test
```

## Verification Guide

For step-by-step verification, see `docs/PHASE_VERIFICATION.md`.

### Quick verification commands

Create a cart item:

```bash
curl -X POST http://localhost:8080/api/carts/2/items \
  -H "Content-Type: application/json" \
  -d '{"productId":1,"quantity":2}'
```

Checkout:

```bash
curl -X POST http://localhost:8080/api/carts/2/checkout \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"demo-checkout-1"}'
```

Re-send the same checkout request to verify idempotency.

## Deployment

### Build image locally

```bash
docker build -t parallel-cart:latest .
```

### Run with Docker Compose

```bash
docker compose up -d --build
```

## Future Enhancements

Potential next improvements:

- add OpenAPI/Swagger documentation
- implement user authentication and authorization flows
- add request/response logging middleware
- support product catalog search/filtering
- add batch reporting and analytics jobs
- implement explicit DLQ backoff and retry policies for Kafka consumers

## Contact

This repository is intended for parallel programming and distributed systems coursework. For code questions, inspect the source under `src/main/java/com/parallelcart`.
