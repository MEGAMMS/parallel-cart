# FT1 Verification Results (Requirements 1-5)

Date: 2026-05-18  
Branch verified: `tariq`  
Backup branch: `backup/tariq-pre-ft1-verify-20260518-201557`

## Scope
Verified the first five non-functional requirements from `parallel-programming-project-2026.pdf`:

1. Concurrent Access & Data Integrity
2. Resource Management & Capacity Control
3. Asynchronous Queues
4. Batch Processing
5. Load Distribution

## Overall Status

| Requirement | Status | Notes |
|---|---|---|
| Req 1: Concurrent Access & Data Integrity | PASS | Unsafe baseline oversells; current implementation prevents oversell |
| Req 2: Resource Management & Capacity Control | PASS | Guard rejects overload, including HTTP `429` evidence |
| Req 3: Asynchronous Queues | PASS | Async outbox/Kafka flow verified end-to-end; baseline latency comparison included |
| Req 4: Batch Processing | PASS | Chunking + checkpoint resume verified; single-pass baseline comparison included |
| Req 5: Load Distribution | PASS | Single-instance vs Nginx round-robin evidence captured |

---

## Req 1 — Concurrent Access & Data Integrity

### Implementation Mapping
- `src/main/java/com/parallelcart/service/impl/CartServiceImpl.java`
- `src/main/java/com/parallelcart/domain/model/Inventory.java`
- `src/main/java/com/parallelcart/infra/repository/InventoryRepository.java`
- `src/test/java/com/parallelcart/CheckoutConcurrencyRaceConditionTest.java`

### Verification Artifacts
- Script: `scripts/ft1/run-concurrency-check.sh`
- Before baseline: `src/test/java/com/parallelcart/UnsafeInventoryBaselineRaceTest.java`
- After test: `src/test/java/com/parallelcart/CheckoutConcurrencyRaceConditionTest.java`
- Log: `reports/interviews/first-thursday/ft1-r1-concurrency.log`

### Before vs After

| Metric | Before (unsafe baseline) | After (current) |
|---|---|---|
| Initial inventory | 5 | 5 |
| Concurrent buyers | 12 | 12 |
| Successful checkouts/reservations | 12 | 2 |
| Failed checkouts/reservations | 0 | 10 |
| Final inventory | 4 | 3 |
| Overselling happened | Yes (`oversold=true`) | No |

### Conclusion
Current implementation satisfies Req 1 by preventing oversell under contention using optimistic locking with retry and pessimistic fallback.

---

## Req 2 — Resource Management & Capacity Control

### Implementation Mapping
- `src/main/java/com/parallelcart/service/CheckoutCapacityGuard.java`
- `src/main/java/com/parallelcart/service/CheckoutCapacityExceededException.java`
- `src/main/java/com/parallelcart/service/impl/CartServiceImpl.java`
- `src/main/java/com/parallelcart/api/controller/GlobalExceptionHandler.java`
- `src/test/java/com/parallelcart/CheckoutCapacityControlTest.java`

### Verification Artifacts
- Script: `scripts/ft1/run-capacity-check.sh`
- Before baseline test: `src/test/java/com/parallelcart/CheckoutCapacityBypassBaselineTest.java`
- After service-level test: `src/test/java/com/parallelcart/CheckoutCapacityControlTest.java`
- After HTTP-level test: `src/test/java/com/parallelcart/CheckoutCapacityHttp429Test.java`
- Log: `reports/interviews/first-thursday/ft1-r2-capacity.log`

### Before vs After

| Metric | Before (high capacity) | After (guard enabled) |
|---|---|---|
| Concurrent requests | 10 | 10 |
| Accepted | 10 | 1 |
| Rejected by capacity | 0 | 9 |
| HTTP status evidence | N/A baseline | `status200=1`, `status429=23`, `otherStatus=0` (24 concurrent HTTP requests) |
| Stability | Stable | Stable |

### Conclusion
Req 2 is satisfied. Under overload, requests are rejected explicitly rather than exhausting resources; HTTP `429` behavior is verified.

---

## Req 3 — Asynchronous Queues

### Implementation Mapping
- `src/main/java/com/parallelcart/infra/messaging/OutboxEventService.java`
- `src/main/java/com/parallelcart/infra/messaging/OutboxPublisherJob.java`
- `src/main/java/com/parallelcart/infra/messaging/KafkaOrderEventPublisher.java`
- `src/main/java/com/parallelcart/infra/messaging/consumers/InvoiceOrderCreatedConsumer.java`
- `src/main/java/com/parallelcart/infra/messaging/consumers/NotificationOrderCreatedConsumer.java`
- `src/main/java/com/parallelcart/service/impl/CartServiceImpl.java`

### Verification Artifacts
- Script: `scripts/ft1/run-async-queue-check.sh`
- Before baseline test: `src/test/java/com/parallelcart/AsyncQueueLatencyBaselineTest.java`
- After runtime script: `scripts/verify_phase.sh p3`
- Logs:
  - `reports/interviews/first-thursday/ft1-r3-async-baseline.log`
  - `reports/interviews/first-thursday/ft1-r3-async-runtime.log`

### Before vs After

| Metric | Before (inline synthetic baseline) | After (current async outbox/Kafka flow) |
|---|---|---|
| Checkout path average latency | `inlineAvgMs=250` | Checkout HTTP `time_total_seconds=0.061861` |
| Queued path latency | `queuedAvgMs=10` | Cart add `time_total_seconds=0.145502`, checkout remains short |
| Outbox event created/published | N/A in synthetic baseline | `outbox_events delta: 0 -> 1` |
| Invoice consumer processed | N/A | `invoices delta: 0 -> 1` |
| Notification consumer processed | N/A | `notification_logs delta: 0 -> 1` |

### Conclusion
Req 3 is satisfied. Checkout returns quickly while invoice/notification side effects are handled asynchronously through outbox + Kafka consumers.

### Limitation
The “before” comparison is a controlled test-only inline-vs-queued benchmark, not a production profile toggle.

---

## Req 4 — Batch Processing

### Implementation Mapping
- `src/main/java/com/parallelcart/service/impl/DailySalesBatchService.java`
- `src/main/java/com/parallelcart/domain/model/BatchJobCheckpoint.java`
- `src/main/java/com/parallelcart/domain/model/DailySalesAggregate.java`
- `src/main/java/com/parallelcart/infra/repository/BatchJobCheckpointRepository.java`
- `src/main/java/com/parallelcart/infra/repository/DailySalesAggregateRepository.java`
- `src/main/java/com/parallelcart/infra/repository/OrderRepository.java`
- `src/main/java/com/parallelcart/infra/messaging/DailySalesBatchJob.java`

### Verification Artifacts
- Script: `scripts/ft1/run-batch-check.sh`
- Before/after comparison test: `src/test/java/com/parallelcart/DailySalesBatchBeforeAfterComparisonTest.java`
- Resume/chunk correctness test: `src/test/java/com/parallelcart/DailySalesBatchServiceTest.java`
- Log: `reports/interviews/first-thursday/ft1-r4-batch.log`

### Before vs After

| Metric | Before (single-pass baseline) | After (chunked + checkpoint resume) |
|---|---|---|
| Records processed | 23 | 23 |
| Chunk size | ALL | 5 |
| Checkpoint support | None | RUNNING -> COMPLETED |
| Resume behavior | None | `firstRunCompleted=false`, `firstRunProcessed=5`, then resumed to completion |
| Duration | `20ms` | `28ms` |

### Conclusion
Req 4 is satisfied. Chunked processing and resumable checkpoint progression are implemented and verified.

---

## Req 5 — Load Distribution

### Implementation Mapping
- `docker-compose.load.yml`
- `infra/nginx/load-balancer.conf`
- `src/main/java/com/parallelcart/config/InstanceIdResponseHeaderFilter.java`
- `docs/LOAD_DISTRIBUTION_STRATEGY.md`

### Verification Artifacts
- Script: `scripts/ft1/run-load-distribution-check.sh`
- Log: `reports/interviews/first-thursday/ft1-r5-load-distribution.log`

### Before vs After

| Metric | Before (single instance) | After (load-balanced) |
|---|---|---|
| Requests sent | 40 | 40 |
| Success | 40 | 40 |
| Failed | 0 | 0 |
| Success rate | 100% | 100% |
| Average latency | `0.0080s` | `0.0107s` |
| Instance distribution | one instance only (`cb79b161c280=40`) | `app-1=18`, `app-2=22` |
| Unique instances observed | 1 | 2 |

### Conclusion
Req 5 is satisfied. Nginx round-robin distributes requests across both app instances.

---

## Full Test Suite Result

Command: `./mvnw test`  
Result: `BUILD SUCCESS`  
Summary: `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`

---

## Commands Used for FT1 Verification

```bash
./mvnw test
./scripts/ft1/run-concurrency-check.sh
./scripts/ft1/run-capacity-check.sh
./scripts/ft1/run-async-queue-check.sh
./scripts/ft1/run-batch-check.sh
./scripts/ft1/run-load-distribution-check.sh
```

---

## Changes Made During Verification

- Added test-only Redis-independent cache config for `test` profile.
- Added explicit FT1 before/after verification tests.
- Added FT1 helper scripts under `scripts/ft1/`.
- Enhanced `scripts/verify_phase.sh` to print HTTP `time_total_seconds` for timing evidence.
