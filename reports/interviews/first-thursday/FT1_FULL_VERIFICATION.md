# First Thursday Requirements 1-5 Verification

Generated at: 2026-05-18T18:09:07+03:00

## Req 1 — Concurrent Access & Data Integrity
- Status: PASS
- Evidence:
  - [FT1-T1_RACE_PROOF.md](/home/teto/dev_code/parallel-cart/reports/interviews/first-thursday/FT1-T1_RACE_PROOF.md)
- Runtime summary:
  - Race test summary: initialStock=5, buyers=12, successes=2, failures=10, finalInventory=3

## Req 2 — Resource Management & Capacity Control
- Status: PASS
- Evidence:
  - [FT1-T2_CAPACITY_CONTROL.md](/home/teto/dev_code/parallel-cart/reports/interviews/first-thursday/FT1-T2_CAPACITY_CONTROL.md)
- Runtime summary:
  - Capacity test summary: buyers=10, successes=1, rejections=9, finalInventory=49

## Req 3 — Asynchronous Queues
- Status: PASS
- Evidence log:
  - [ft1-req3.log](/home/teto/dev_code/parallel-cart/reports/interviews/first-thursday/ft1-req3.log)

## Req 4 — Batch Processing (Chunks + Checkpoint Resume)
- Status: PASS
- Tests:
  - DailySalesBatchServiceTest
- Evidence line:
  - DailySalesBatchServiceTest: tests=2, failures=0, errors=0

## Req 5 — Load Distribution
- Status: PASS
- Strategy: Nginx upstream round-robin across app-1 and app-2
- Evidence:
  - [ft1-req5-load.log](/home/teto/dev_code/parallel-cart/reports/interviews/first-thursday/ft1-req5-load.log)
- Instance distribution snapshot:

unique_instances=2
instance_count[app-1]=14
instance_count[app-2]=16
