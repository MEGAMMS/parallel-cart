# Parallel Cart — Final Technical Report (P6-T6)

## 1) Project Goal
Build a high-performance, concurrency-safe e-commerce backend that remains correct and observable under load.

Core goals achieved:
- transaction integrity under concurrent checkout
- async side effects with Kafka + outbox reliability
- cache-assisted read performance and invalidation
- resource bounds + backpressure behavior
- batch processing with resumable checkpoints
- multi-instance request distribution
- stress evidence and bottleneck analysis

## 2) Final Architecture

### Runtime Components
- Application: Spring Boot (`api`, `service`, `domain`, `infra`, `config`, `observability`)
- Database: PostgreSQL
- Cache: Redis
- Messaging: Kafka (+ Zookeeper)
- Load balancer (P5): Nginx (`least_conn`) with `app-1`, `app-2`

### Request/Data Flow (Checkout)
1. API receives cart/checkout request.
2. Service validates user/cart/items.
3. Inventory reservation uses optimistic lock with bounded retries; fallback to pessimistic lock for hot contention.
4. Order + payment persist in one transaction.
5. Outbox event persisted in same transaction.
6. Background outbox publisher sends Kafka `order.created`.
7. Consumers handle invoice/notification idempotently.

### Request/Data Flow (Product Reads)
1. API requests `GET /api/products` or `GET /api/products/{id}`.
2. Product service uses cache (`products::all`, `products::id:<id>`).
3. Cache invalidated on inventory mutation after checkout commit.

## 3) Concurrency and Correctness Strategy
- Inventory entity versioning (`@Version`) for optimistic concurrency.
- Retry with jitter for optimistic conflicts.
- Pessimistic lock fallback for hot inventory rows.
- Checkout idempotency key prevents duplicate order creation.
- Transactional boundary ensures payment/inventory/order consistency.

## 4) Async Reliability Strategy (Kafka)
- Outbox pattern prevents dual-write inconsistency.
- At-least-once processing with idempotent consumers.
- DLQ + structured error logging for failed processing paths.

## 5) Caching and Resource Controls
- Redis cache for hot product reads with TTL.
- Explicit cache invalidation for product/list keys after inventory-changing checkout.
- Bounded thread-pool and queue configuration.
- Backpressure/fail-fast behavior verified for saturation conditions.

## 6) Batch Processing + Distribution

### Daily Sales Batch (P5)
- Chunk-based paid-order aggregation per day.
- Resumable checkpoints store:
  - `lastProcessedOrderId`
  - partial count/revenue
  - processed chunks
  - run status (`IN_PROGRESS`, `COMPLETED`)
- Supports controlled progress windows via `max-chunks-per-run`.

### Multi-instance Distribution (P5)
- Nginx upstream to `app-1` + `app-2`.
- Strategy: `least_conn`.
- Verification via `X-Upstream-Addr` header sampling.

## 7) AOP Timing Explanation (P6-T1)
Instrumentation file:
- `src/main/java/com/parallelcart/observability/PerformanceTimingAspect.java`

Pointcuts:
- Product reads:
  - `ProductServiceImpl.listProducts(..)`
  - `ProductServiceImpl.getProduct(..)`
- Checkout:
  - `CartServiceImpl.checkout(..)`
- Inventory update persistence:
  - `InventoryRepository.saveAndFlush(..)`

Behavior:
- Around advice records start nanoseconds.
- On success: logs `perf_timing` with `operation`, method, `duration_ms`.
- On error: logs same with `status=error` + exception type.

Why AOP here:
- zero business-logic pollution
- uniform timing format
- easy extension to other critical methods

## 8) Observability Queries (P6-T2)
- Actuator and script-based queries documented in:
  - `docs/p6-metrics-queries.md`
- Snapshot script:
  - `scripts/metrics_snapshot_p6.sh`
- Captures:
  - request counts / derived latency
  - error counts
  - Kafka consumer group lag snapshot

## 9) Stress Evidence (P6-T3)
Baseline stress run (`>=100` concurrent users):
- Profile: `TARGET_VUS=120`, `RAMP_UP=20s`, `HOLD=45s`, `RAMP_DOWN=20s`
- Avg latency: `2.21 ms`
- P95 latency: `5.41 ms`
- Throughput: `7482.53 req/s`
- Failed request rate: `0.000000`

Artifacts:
- `reports/stress/p6-stress_20260518_211019.md`
- `reports/stress/p6-stress_20260518_211019.json`
- `reports/stress/p6-stress_20260518_211019.log`

## 10) Bottleneck + Optimization Result (P6-T4/T5)
Identified candidate bottleneck:
- Product read path entity materialization + mapping overhead.

Optimization attempt:
- Switched to direct repository projection (`ProductResponse`) queries.

Measured outcome (same stress profile):
- Avg latency: `2.21 -> 2.52 ms` (worse)
- P95 latency: `5.41 -> 6.46 ms` (worse)
- Throughput: `7482.53 -> 7295.13 req/s` (worse)

Conclusion:
- Optimization attempt documented and reproducible but not beneficial for this workload.
- Next optimization direction should target checkout/inventory contention path.

Evidence:
- `reports/benchmark/p6-bottleneck-analysis.md`
- `reports/stress/p6-stress-opt_20260518_211441.json`
- `reports/stress/p6-stress-opt_20260518_211441.log`

## 11) Reproducibility Commands

Phase verifiers:
- `./scripts/verify_phase.sh p3`
- `./scripts/verify_phase4.sh all`
- `./scripts/verify_phase5.sh all`

Stress + metrics:
- `TARGET_VUS=120 HOLD=45s RAMP_UP=20s RAMP_DOWN=20s ./scripts/stress_p6.sh`
- `./scripts/metrics_snapshot_p6.sh`

## 12) Limitations and Next Improvements
- Product projection optimization regressed under read-heavy stress; rollback decision should be considered if prioritizing raw read throughput.
- Add Prometheus/Grafana dashboards for persistent time-series and percentile charts.
- Add targeted checkout-contention stress profile and optimize lock/flush path.
- Add automated regression threshold checks in CI for p95 latency / throughput drift.

## 13) Submission Checklist Status
- Architecture documented: done
- AOP explanation included: done
- Stress report included: done
- Benchmark/bottleneck comparison included: done
- Reproducible commands included: done
