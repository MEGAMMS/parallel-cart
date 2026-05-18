# FT1-T1 Evidence: Concurrent Checkout Race Proof

Date: 2026-05-18 (Asia/Damascus)

## Objective
Demonstrate that concurrent checkout against the same SKU does not oversell inventory and keeps transactional consistency.

## Executed Command
```bash
./mvnw -Dtest=CheckoutConcurrencyRaceConditionTest test
```

## Result Summary
- Build result: `SUCCESS`
- Test class: `CheckoutConcurrencyRaceConditionTest`
- Tests run: `1`
- Failures: `0`
- Errors: `0`
- Skipped: `0`

Observed runtime counters from test output:
- `initialStock=5`
- `buyers=12`
- `successes=2`
- `failures=10`
- `finalInventory=3`

## Why This Proves Req 1
The test enforces these invariants under parallel execution:

1. Successful checkouts never exceed available stock.
2. `finalInventory == initialStock - successes * quantityPerBuyer`.
3. Inventory never goes negative.
4. `orders.count == successes` and `payments.count == successes`.

All invariants passed.

## Artifact Paths
- Test implementation:
  - `src/test/java/com/parallelcart/CheckoutConcurrencyRaceConditionTest.java`
- Surefire text report:
  - `target/surefire-reports/com.parallelcart.CheckoutConcurrencyRaceConditionTest.txt`
- Surefire XML report:
  - `target/surefire-reports/TEST-com.parallelcart.CheckoutConcurrencyRaceConditionTest.xml`
