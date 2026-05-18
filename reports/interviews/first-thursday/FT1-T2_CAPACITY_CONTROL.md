# FT1-T2 Evidence: Resource Capacity Control (Fail-Fast)

Date: 2026-05-18 (Asia/Damascus)

## Objective
Add explicit saturation behavior so checkout requests fail fast when bounded concurrency is exceeded.

## Implementation Summary
- Added semaphore-based checkout guard:
  - `src/main/java/com/parallelcart/service/CheckoutCapacityGuard.java`
- Added dedicated exception:
  - `src/main/java/com/parallelcart/service/CheckoutCapacityExceededException.java`
- Wired guard into checkout path:
  - `src/main/java/com/parallelcart/service/impl/CartServiceImpl.java`
- Exposed overload response as HTTP `429`:
  - `src/main/java/com/parallelcart/api/controller/GlobalExceptionHandler.java`
- Added runtime setting:
  - `app.checkout.max-concurrent` in `src/main/resources/application-local.yml`

## Executed Command
```bash
./mvnw -Dtest=CheckoutCapacityControlTest,CheckoutConcurrencyRaceConditionTest test
```

## Result Summary
- Build result: `SUCCESS`
- Capacity test: `CheckoutCapacityControlTest`
  - Tests run: `1`, Failures: `0`, Errors: `0`
  - Runtime summary (from surefire XML):
    - `buyers=10`
    - `successes=1`
    - `rejections=9`
    - `finalInventory=49`

## Why This Proves Req 2 Progress
The capacity test confirms:
1. Requests are bounded under overload (non-zero rejections).
2. Rejections are explicit capacity rejections (`CheckoutCapacityExceededException`) rather than crash/hang.
3. Data integrity remains correct (`finalInventory` matches successful checkouts).
4. Order/payment row counts match successful checkouts only.

## Artifact Paths
- Test implementation:
  - `src/test/java/com/parallelcart/CheckoutCapacityControlTest.java`
- Surefire text report:
  - `target/surefire-reports/com.parallelcart.CheckoutCapacityControlTest.txt`
- Surefire XML report:
  - `target/surefire-reports/TEST-com.parallelcart.CheckoutCapacityControlTest.xml`
