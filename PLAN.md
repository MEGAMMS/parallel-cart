# PLAN.md — Parallel Cart Project Execution Plan

## Current Next Step
- [ ] P5-T3: Run 2+ app instances behind Nginx load balancer.

## Working Rules
- Complete tasks in order unless a blocker is documented.
- A task is done only when its acceptance criteria and tests pass.
- Update `Current Next Step` immediately after finishing any task.
- Keep evidence links/paths under each phase (`reports/`, `tests/`, logs snapshots).

---

## P0 — Foundation and Architecture

### Objective
Establish runnable backend foundation and project structure for high-concurrency development.

### Tasks
- [x] P0-T1: Initialize Spring Boot project (Web, Data JPA, Validation, Actuator, AOP, Security basic).
- [x] P0-T2: Add Docker Compose services: `postgres`, `redis`, `kafka`, `zookeeper` (or KRaft Kafka setup).
- [x] P0-T3: Define package structure: `api`, `service`, `domain`, `infra`, `config`, `observability`.
- [x] P0-T4: Add baseline configs (`application.yml` profiles: `local`, `test`).
- [x] P0-T5: Add health checks and readiness checks for DB/Redis/Kafka connectivity.

### Acceptance Criteria
- `docker compose up` starts all infra services.
- Spring Boot app starts and reports healthy dependencies.
- Base package structure exists and is consistent.

### Verification
- Smoke test app boot.
- Confirm Actuator health includes DB/Redis/Kafka.
- Command reference: `docs/PHASE_VERIFICATION.md` (P0 section).

---

## P1 — Core Commerce Domain (Minimal Functional Scope)

### Objective
Implement minimum features required to exercise non-functional requirements.

### Tasks
- [x] P1-T1: Implement entities: `User`, `Product`, `Inventory`, `Cart`, `CartItem`, `Order`, `OrderItem`, `Payment`.
- [x] P1-T2: Implement APIs: products list/detail, cart add/update/remove, checkout.
- [x] P1-T3: Seed sample data for load tests.
- [x] P1-T4: Add DTO validation and uniform error handling.

### Acceptance Criteria
- Checkout path works end-to-end under single-user flow.
- API contract documented (OpenAPI/Swagger).

### Verification
- Integration test: create cart -> checkout -> order persisted.
- Command reference: `docs/PHASE_VERIFICATION.md` (P1 section).

---

## P2 — Concurrency Control and Transaction Integrity

### Objective
Guarantee correctness under concurrent access and preserve ACID behavior.

### Tasks
- [x] P2-T1: Add optimistic locking (`@Version`) on `Inventory`.
- [x] P2-T2: Add retry policy for optimistic lock conflicts (bounded retries + jitter).
- [x] P2-T3: Add fallback pessimistic lock strategy for hot products.
- [x] P2-T4: Make checkout transactional (payment + inventory update + order create all-or-nothing).
- [x] P2-T5: Add idempotency key on checkout to prevent duplicate orders.

### Acceptance Criteria
- No overselling or negative inventory under concurrent checkout.
- Partial transaction states are impossible after failures.

### Verification
- Concurrency integration test with parallel checkout on same SKU.
- Failure-path test verifies rollback.
- Command reference: `docs/PHASE_VERIFICATION.md` (P2 section).

---

## P3 — Async Processing with Kafka

### Objective
Move non-blocking work out of request path and use team’s Kafka experience effectively.

### Kafka Design
- Topics:
  - `order.created`
  - `invoice.generate`
  - `notification.send`
  - `inventory.events` (optional analytics/audit stream)
- Consumer groups:
  - `invoice-workers`
  - `notification-workers`
- Delivery semantics:
  - At-least-once processing with idempotent consumers.
- Reliability:
  - Dead-letter topic (`*.dlq`) + retry/backoff policy.

### Tasks
- [x] P3-T1: Publish `order.created` event after successful checkout.
- [x] P3-T2: Implement consumer for invoice generation.
- [x] P3-T3: Implement consumer for notifications.
- [x] P3-T4: Add DLQ and structured failure logging.
- [x] P3-T5: Add outbox pattern (or transactional event publish) to avoid dual-write inconsistency.

### Acceptance Criteria
- Checkout latency decreases vs inline invoice/notification flow.
- Failed message processing is observable and recoverable.

### Verification
- Integration test with embedded Kafka/Testcontainers.
- Consumer idempotency test (duplicate event delivery).

---

## P4 — Caching and Resource Management

### Objective
Improve latency and stability via Redis and bounded execution resources.

### Tasks
- [x] P4-T1: Cache hot product reads in Redis with TTL.
- [x] P4-T2: Invalidate/update cache on product/inventory mutation.
- [x] P4-T3: Configure bounded thread pools and queue capacities.
- [x] P4-T4: Add backpressure/fail-fast behavior when system saturated.

### Acceptance Criteria
- Read latency improves measurably for hot products.
- System remains responsive under high load without collapse.

### Verification
- Benchmark before/after cache.
- Load run with controlled resource limits.

---

## P5 — Batch Processing and Load Distribution

### Objective
Implement daily sales batch job and simulate multi-instance traffic distribution.

### Tasks
- [x] P5-T1: Build daily sales aggregation batch job (chunk-based processing).
- [x] P5-T2: Add resumable progress markers/checkpointing.
- [ ] P5-T3: Run 2+ app instances behind Nginx load balancer.
- [ ] P5-T4: Document chosen distribution strategy and rationale.

### Acceptance Criteria
- Batch completes on large dataset without memory spikes.
- Requests distribute across multiple instances as expected.

### Verification
- Batch run report with chunk metrics.
- Load balancer logs showing instance distribution.

---

## P6 — Observability, Stress Testing, Benchmarking, Bottleneck Fix

### Objective
Prove stability at required concurrency and show measurable optimization.

### Tasks
- [ ] P6-T1: Add AOP timing around critical operations (product read, checkout, inventory update).
- [ ] P6-T2: Add metrics dashboards/queries (latency p50/p95, throughput, error rate, consumer lag).
- [ ] P6-T3: Run stress test with >=100 concurrent users.
- [ ] P6-T4: Identify at least one bottleneck.
- [ ] P6-T5: Implement one optimization and compare before/after metrics.
- [ ] P6-T6: Write final technical report with architecture + AOP explanation + evidence.

### Acceptance Criteria
- System handles >=100 concurrent users without data loss/corruption.
- Numeric before/after comparison exists for at least one bottleneck fix.

### Verification
- Stress report in `reports/stress/`.
- Benchmark report in `reports/benchmark/`.
- Final documentation file ready for submission.

---

## Suggested Repo Artifacts
- `docs/architecture.md`
- `docs/kafka-topology.md`
- `reports/stress/`
- `reports/benchmark/`
- `reports/interviews/` (milestone notes for mandatory progress interviews)

## Definition of Done (Project)
- All phase tasks checked.
- All acceptance criteria met.
- Test suite green (unit + integration + concurrency scenarios).
- Stress and benchmark reports complete and reproducible.
- Final architecture/AOP documentation prepared for submission and demo.
