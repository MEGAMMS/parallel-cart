# P6 Bottleneck Analysis and Optimization Report

## Scope
- Task: `P6-T4` identify bottleneck.
- Task: `P6-T5` implement one optimization and compare before/after metrics.

## Baseline (Before)
- Source: `reports/stress/p6-stress_20260518_211019.json`
- Profile: `TARGET_VUS=120`, `RAMP_UP=20s`, `HOLD=45s`, `RAMP_DOWN=20s`
- Avg latency: `2.21 ms`
- P95 latency: `5.41 ms`
- Throughput: `7482.53 req/s`

## Identified Bottleneck
- Candidate: product read path did entity materialization + DTO mapping in service layer.
- Location:
  - `ProductServiceImpl.listProducts()`
  - `ProductServiceImpl.getProduct()`

## Optimization Implemented
- Switched product read path to JPA projection queries returning `ProductResponse` directly from repository.
- Files changed:
  - `src/main/java/com/parallelcart/infra/repository/ProductRepository.java`
  - `src/main/java/com/parallelcart/service/impl/ProductServiceImpl.java`

## After Optimization
- Source: `reports/stress/p6-stress-opt_20260518_211441.json`
- Same stress profile as baseline.
- Avg latency: `2.52 ms`
- P95 latency: `6.46 ms`
- Throughput: `7295.13 req/s`

## Comparison
- Avg latency: `2.21 -> 2.52 ms` (`+14.03%`, worse)
- P95 latency: `5.41 -> 6.46 ms` (`+19.41%`, worse)
- Throughput: `7482.53 -> 7295.13 req/s` (`-2.50%`, worse)

## Conclusion
- This optimization attempt did **not** improve performance under this workload.
- Next optimization candidate should target checkout/inventory contention path (where lock/flush behavior is dominant), then rerun before/after checkout-focused stress.
