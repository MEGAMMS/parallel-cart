# Parallel Cart: Project Structure and Concurrency Deep Dive

This document explains how the current codebase is organized and how it handles concurrency, async processing, batching, and load distribution.

## Project Structure (Code Map)

The project follows a layered Spring Boot architecture:

- `src/main/java/com/parallelcart/api`
  - REST controllers and DTOs.
  - Entry points for cart and product APIs.
- `src/main/java/com/parallelcart/service`
  - Business interfaces and implementations.
  - Core checkout workflow and domain rules.
- `src/main/java/com/parallelcart/domain`
  - JPA entities (`Order`, `Inventory`, `Payment`, `OutboxEvent`, etc.).
  - Persistence-level constraints and state models.
- `src/main/java/com/parallelcart/infra`
  - Repositories, Kafka publishers/consumers, outbox job.
  - Integration with PostgreSQL, Redis, Kafka.
- `src/main/java/com/parallelcart/config`
  - Thread pools, scheduler, cache, Kafka error handling, seed data.
- `src/main/resources`
  - Runtime configuration (`application.yml`, `application-local.yml`, `application-test.yml`).
- `src/test/java/com/parallelcart`
  - Integration and unit tests for transactional integrity and caching.

---

## 1) Concurrent Access & Data Integrity (Race Conditions)

### How race conditions are controlled in checkout

The checkout path is implemented in `CartServiceImpl.checkout(...)` and runs inside a single `@Transactional` boundary. This ensures order creation, payment creation, inventory reservation, cart clearing, and outbox event enqueueing are all committed or rolled back together.

For inventory updates under contention:

- `Inventory` uses JPA optimistic locking with `@Version`.
- `reserveInventoryWithRetry(...)` performs bounded retries (`3` attempts).
- On each optimistic conflict, it waits with random jitter (`20-80ms`) to reduce synchronized retry collisions.
- If retries are exhausted, it escalates to pessimistic locking via `InventoryRepository.findByProductForUpdate(...)` (`PESSIMISTIC_WRITE`).

### Additional data integrity protections

- Checkout idempotency:
  - `OrderRepository.findByUserIdAndIdempotencyKey(...)` returns existing order if same key is reused.
  - `Order.idempotencyKey` is DB-unique, preventing duplicate persisted orders for the same key.
- API-level conflict reporting:
  - `GlobalExceptionHandler` maps optimistic lock conflicts and business conflicts to HTTP `409`.

### Net effect

This design prevents overselling from concurrent writes, avoids duplicate order submission under retries/replays, and preserves ACID behavior for checkout.

---

## 2) Resource Management & Capacity Control (Thread Pools, Queueing)

Resource bounds are centralized in `ResourceManagementConfig` and `application-local.yml`.

### Configured execution resources

- Async executor (`applicationTaskExecutor`):
  - `corePoolSize=4`, `maxPoolSize=8`, `queueCapacity=100`, `keepAliveSeconds=60`.
  - Rejection policy: `CallerRunsPolicy`.
- Scheduler (`taskScheduler`):
  - `poolSize=2`.
  - Rejection policy: `CallerRunsPolicy`.
- Kafka listener concurrency:
  - `spring.kafka.listener.concurrency=2`.

### Capacity-control strategy

- Bounded queue capacity avoids unbounded memory growth.
- `CallerRunsPolicy` provides built-in backpressure by making the caller thread execute tasks when pools are saturated.
- Scheduler pool prevents background jobs from spawning uncontrolled threads.

### Important current-state note

`@EnableAsync` is enabled and the async executor is configured, but the current codebase does not yet define explicit `@Async` business methods. The main active parallelism is in Kafka listeners and scheduled jobs.

---

## 3) Asynchronous Queues (Invoice + Notification Flow)

Invoice and notification are handled asynchronously using Kafka with an outbox pattern.

### End-to-end async flow

1. During checkout, `OutboxEventService.enqueueOrderCreated(...)` stores an `OutboxEvent` row (`PENDING`) in the same DB transaction as order/payment/inventory updates.
2. `OutboxPublisherJob` polls pending outbox rows and publishes `OrderCreatedEvent` to Kafka topic `order.created`.
3. Consumers process independently:
   - `InvoiceOrderCreatedConsumer` (`groupId=invoice-workers`) creates invoice records.
   - `NotificationOrderCreatedConsumer` (`groupId=notification-workers`) creates notification logs.

### Idempotency in async consumers

- Invoice consumer checks `invoiceRepository.findByOrderId(...)` first.
- Invoice entity enforces `orderId` uniqueness at DB level.
- Notification consumer checks `notificationLogRepository.findByOrderIdAndChannel(...)` before insert.

### Failure handling

- `KafkaConsumerErrorHandlingConfig` configures retry with fixed backoff.
- After max attempts, messages are published to a dead-letter topic (`order.created.dlq`).

This gives at-least-once delivery with idempotent consumers.

---

## 4) Batch Processing (Chunked Background Jobs)

The implemented batch-style processing is the outbox publisher job.

### How chunking works

`OutboxPublisherJob.publishPendingEvents()` executes on a schedule and reads work in chunks:

- Query: `findTop100ByStatusOrderByCreatedAtAsc(PENDING)`.
- Chunk size: `100` events per run.
- Schedule: fixed delay (`app.outbox.publisher.fixed-delay-ms`, default `2000ms`).

For each event in a chunk:

- Increment `publishAttempts`.
- Publish to Kafka.
- Mark as `PUBLISHED` on success.
- Mark as `FAILED` once max attempts is reached.
- Persist error summary in `lastError`.

### Why this is useful

- Limits per-run memory and processing pressure.
- Preserves oldest-first fairness for pending events.
- Makes retries observable and recoverable.

### Current scope vs planned scope

The plan (`PLAN.md`, phase P5) mentions a future dedicated daily sales chunk job with resumable checkpoints. That specific batch pipeline is not yet implemented in the current code.

---

## 5) Load Distribution Strategy (What Is Used Now)

### Strategy in current implementation

The active load-distribution model is event-driven and Kafka-based:

- Producer uses Kafka key = `orderId` (`KafkaOrderEventPublisher`), so partition routing is deterministic.
- Two separate consumer groups (`invoice-workers`, `notification-workers`) create fan-out pipelines:
  - each group gets the full event stream;
  - each group handles different business responsibility.
- Within each group, listener concurrency (`2`) enables parallel processing across partitions.

This is the classic **partitioned topic + competing consumers per group** strategy.

### Capacity and resilience characteristics

- Work is naturally distributed by Kafka partition assignment.
- Retry/backoff and DLQ isolate poison messages from normal flow.
- Outbox chunking (`top 100`) prevents publisher bursts from becoming unbounded.

### Current limitation to be aware of

HTTP-level multi-instance load balancing (for example, multiple app replicas behind Nginx) is planned in P5 but not yet present in `docker-compose.yml`, which currently runs a single `app` service instance.

---

## Quick Reference: Key Files

- Checkout and concurrency logic:
  - `src/main/java/com/parallelcart/service/impl/CartServiceImpl.java`
  - `src/main/java/com/parallelcart/domain/model/Inventory.java`
  - `src/main/java/com/parallelcart/infra/repository/InventoryRepository.java`
- Resource management:
  - `src/main/java/com/parallelcart/config/ResourceManagementConfig.java`
  - `src/main/resources/application-local.yml`
- Async queues and outbox:
  - `src/main/java/com/parallelcart/infra/messaging/OutboxEventService.java`
  - `src/main/java/com/parallelcart/infra/messaging/OutboxPublisherJob.java`
  - `src/main/java/com/parallelcart/infra/messaging/KafkaOrderEventPublisher.java`
  - `src/main/java/com/parallelcart/infra/messaging/consumers/InvoiceOrderCreatedConsumer.java`
  - `src/main/java/com/parallelcart/infra/messaging/consumers/NotificationOrderCreatedConsumer.java`
  - `src/main/java/com/parallelcart/config/KafkaConsumerErrorHandlingConfig.java`
- Integrity tests:
  - `src/test/java/com/parallelcart/CheckoutTransactionIntegrityTest.java`
