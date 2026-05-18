# FT1 Git History Verification (Requirements 1-5)

Date: 2026-05-18  
Current branch: `tariq`  
After commit (current): `59b24a8`

## Goal
Produce before/after evidence for FT1 requirements using real older commits from Git history where possible, without modifying or resetting the working `tariq` branch.

## Commit Discovery
Commands used:

```bash
git log --oneline --decorate --graph --all
git log --oneline -- src/main src/test docker-compose* nginx* scripts README.md
```

Relevant feature-introducing commits identified:

- Req1 concurrency/conflict handling: `b6cf312` (plus follow-up `83d1f21`)
- Req2/Req4/Req5 FT1 feature bundle: `8218d75`
- Req3 async event flow starts: `58af45b` (followed by `223873a`, `e38b48f`, `38d4b58`)

## Before Commit Selection

| Requirement | BEFORE commit | Why this commit was selected |
|---|---|---|
| Req1 Concurrent Access & Data Integrity | `08b7be5` | Parent/closest working commit before P2 concurrency retry/fallback behavior introduced in `b6cf312`/`83d1f21` |
| Req2 Resource Management & Capacity Control | `6cfa21d` | Direct predecessor of FT1 bundle commit `8218d75` that introduces checkout capacity guard + 429 handling |
| Req3 Asynchronous Queues | `2f9f5cb` | Direct predecessor of `58af45b` where order-created async event publishing starts |
| Req4 Batch Processing | `6cfa21d` | Direct predecessor of `8218d75` where batch chunk/checkpoint components are added |
| Req5 Load Distribution | `6cfa21d` | Direct predecessor of `8218d75` where `docker-compose.load.yml`, nginx config, and instance header filter are added |

## Worktrees Used
No destructive operations were used on `tariq`.

```bash
mkdir -p /home/teto/dev_code/parallel-cart-worktrees

git worktree add /home/teto/dev_code/parallel-cart-worktrees/parallel-cart-ft1-after tariq
git worktree add /home/teto/dev_code/parallel-cart-worktrees/parallel-cart-ft1-before-r1 08b7be5
git worktree add /home/teto/dev_code/parallel-cart-worktrees/parallel-cart-ft1-before-r2 6cfa21d
git worktree add /home/teto/dev_code/parallel-cart-worktrees/parallel-cart-ft1-before-r3 2f9f5cb
git worktree add /home/teto/dev_code/parallel-cart-worktrees/parallel-cart-ft1-before-r4 6cfa21d
git worktree add /home/teto/dev_code/parallel-cart-worktrees/parallel-cart-ft1-before-r5 6cfa21d
```

Build sanity checks run in each worktree:

```bash
./mvnw -q -DskipTests package
```

All selected BEFORE commits compiled.

## Exact Verification Commands

### Req1
```bash
cd /home/teto/dev_code/parallel-cart-worktrees/parallel-cart-ft1-before-r1
./mvnw -q -Dtest=GitHistoryR1BeforeTest test

cd /home/teto/dev_code/parallel-cart-worktrees/parallel-cart-ft1-after
./mvnw -q -Dtest=GitHistoryR1AfterTest test
```

### Req2
```bash
cd /home/teto/dev_code/parallel-cart-worktrees/parallel-cart-ft1-before-r2
./mvnw -q -Dtest=GitHistoryR2BeforeTest test

cd /home/teto/dev_code/parallel-cart-worktrees/parallel-cart-ft1-after
./mvnw -q -Dtest=CheckoutCapacityHttp429Test test
```

### Req3
```bash
cd /home/teto/dev_code/parallel-cart-worktrees/parallel-cart-ft1-before-r3
./mvnw -q -Dtest=GitHistoryR3BeforeTest test

cd /home/teto/dev_code/parallel-cart
# PTY capture used for stable compose/kafka/sql evidence logging
script -q -c './scripts/verify_phase.sh p3' /tmp/ft1-r3-after-pty.log
```

### Req4
```bash
cd /home/teto/dev_code/parallel-cart-worktrees/parallel-cart-ft1-before-r4
./mvnw -q -Dtest=GitHistoryR4BeforeTest test

cd /home/teto/dev_code/parallel-cart
./mvnw -q -Dtest=DailySalesBatchBeforeAfterComparisonTest,DailySalesBatchServiceTest test
```

### Req5
```bash
cd /home/teto/dev_code/parallel-cart-worktrees/parallel-cart-ft1-before-r5
./mvnw -q -Dtest=GitHistoryR5BeforeTest test

cd /home/teto/dev_code/parallel-cart
REQUEST_COUNT=40 ./scripts/ft1/run-load-distribution-check.sh
```

## Before/After Summary

### Req1 Concurrent Access & Data Integrity
- BEFORE (`08b7be5`): `initialStock=100`, `buyers=20`, `successes=2`, `failures=18`, `finalInventory=98`, `oversold=false`
- AFTER (`59b24a8`): `initialStock=100`, `buyers=20`, `successes=5`, `failures=15`, `finalInventory=95`, `oversold=false`
- Result: `PASS` (higher successful checkouts under contention with no oversell)
- Evidence logs:
  - `reports/interviews/first-thursday/git-history-before-after/r1-before-git.log`
  - `reports/interviews/first-thursday/git-history-before-after/r1-after-current.log`

### Req2 Resource Management & Capacity Control
- BEFORE (`6cfa21d`): `buyers=24`, `status200=24`, `status429=0`, `otherStatus=0`
- AFTER (`59b24a8`): `buyers=24`, `status200=1`, `status429=23`, `otherStatus=0`
- Result: `PASS` (explicit overload rejection with HTTP 429)
- Evidence logs:
  - `reports/interviews/first-thursday/git-history-before-after/r2-before-git.log`
  - `reports/interviews/first-thursday/git-history-before-after/r2-after-current.log`

### Req3 Asynchronous Queues
- BEFORE (`2f9f5cb`): `checkoutLatencyMs=203`, `outboxTable=0`, `invoiceTable=0`, `notificationTable=0`
- AFTER (`59b24a8`): `checkout_time_total_seconds=0.059039`, `outbox_events delta: 0 -> 1`, `invoices delta: 0 -> 1`, `notification_logs delta: 0 -> 1`
- Consumer evidence: invoice row + notification row + published outbox row all observed
- Result: `PASS`
- Evidence logs:
  - `reports/interviews/first-thursday/git-history-before-after/r3-before-git.log`
  - `reports/interviews/first-thursday/git-history-before-after/r3-after-current.log`

### Req4 Batch Processing
- BEFORE (`6cfa21d`): `records=23`, `chunkSize=ALL`, `checkpoint=NOT_IMPLEMENTED`, `resume=NOT_IMPLEMENTED`, `durationMs=5`
- AFTER (`59b24a8`): `records=23`, `chunkSize=5`, `firstRunCompleted=false`, `firstRunProcessed=5`, `finalCheckpoint=COMPLETED`, `durationMs=28`
- Result: `PASS` (reliability/resume improvement)
- Evidence logs:
  - `reports/interviews/first-thursday/git-history-before-after/r4-before-git.log`
  - `reports/interviews/first-thursday/git-history-before-after/r4-after-current.log`

### Req5 Load Distribution
- BEFORE (`6cfa21d`): `requests=40`, `success=40`, `failed=0`, `avgLatencyMs=14`, `uniqueInstances=1`
- AFTER (`59b24a8`): `requests=40`, `success=40`, `failed=0`, `avg_latency_seconds=0.0072`, `distribution: app-1=18, app-2=22`, `unique_instances=2`
- Result: `PASS`
- Evidence logs:
  - `reports/interviews/first-thursday/git-history-before-after/r5-before-git.log`
  - `reports/interviews/first-thursday/git-history-before-after/r5-after-current.log`

## Screenshot Placeholders

- `[SCREENSHOT_PLACEHOLDER] Req1 before: r1-before-git.log block`
- `[SCREENSHOT_PLACEHOLDER] Req1 after: r1-after-current.log block`
- `[SCREENSHOT_PLACEHOLDER] Req2 before: r2-before-git.log block`
- `[SCREENSHOT_PLACEHOLDER] Req2 after: r2-after-current.log block`
- `[SCREENSHOT_PLACEHOLDER] Req3 before: r3-before-git.log block`
- `[SCREENSHOT_PLACEHOLDER] Req3 after: r3-after-current.log block`
- `[SCREENSHOT_PLACEHOLDER] Req4 before: r4-before-git.log block`
- `[SCREENSHOT_PLACEHOLDER] Req4 after: r4-after-current.log block`
- `[SCREENSHOT_PLACEHOLDER] Req5 before: r5-before-git.log block`
- `[SCREENSHOT_PLACEHOLDER] Req5 after: r5-after-current.log block`

## Limitations and Notes

- Req1 limitation: the selected real BEFORE commit (`08b7be5`) already uses optimistic versioning on inventory, so oversell was not reproduced in that old commit; improvement appears as lower contention failure rate in AFTER.
- Req3 runtime note: `verify_phase.sh p3` was stable when captured through PTY (`script -q -c ...`) due intermittent non-interactive compose/sql logging behavior.
- Temporary git-history harness tests were created and executed only inside detached worktrees; no baseline FT1 scripts/tests were removed from `tariq`.

## Generated Evidence Files

Directory:

- `reports/interviews/first-thursday/git-history-before-after/`

Files:

- `r1-before-git.log`
- `r1-after-current.log`
- `r2-before-git.log`
- `r2-after-current.log`
- `r3-before-git.log`
- `r3-after-current.log`
- `r4-before-git.log`
- `r4-after-current.log`
- `r5-before-git.log`
- `r5-after-current.log`
