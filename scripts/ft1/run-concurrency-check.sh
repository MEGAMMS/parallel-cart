#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

REPORT_DIR="reports/interviews/first-thursday"
mkdir -p "$REPORT_DIR"
LOG_FILE="$REPORT_DIR/ft1-r1-concurrency.log"

echo "== FT1 Req1: Concurrent Access & Data Integrity =="
echo "[step] Running before/after race checks"
./mvnw -q -Dtest=UnsafeInventoryBaselineRaceTest,CheckoutConcurrencyRaceConditionTest test | tee "$LOG_FILE"

before_line="$(grep -ho 'Unsafe baseline summary:[^<]*' target/surefire-reports/TEST-com.parallelcart.UnsafeInventoryBaselineRaceTest.xml | tail -1 || true)"
after_line="$(grep -ho 'Race test summary:[^<]*' target/surefire-reports/TEST-com.parallelcart.CheckoutConcurrencyRaceConditionTest.xml | tail -1 || true)"

echo
echo "[Req1 before/after summary]"
echo "before (unsafe baseline): ${before_line:-not-found}"
echo "after  (current implementation): ${after_line:-not-found}"

if [[ -n "$before_line" && -n "$after_line" ]]; then
  before_oversold="$(echo "$before_line" | sed -n 's/.*oversold=\([^,]*\).*/\1/p')"
  after_successes="$(echo "$after_line" | sed -n 's/.*successes=\([0-9]\+\).*/\1/p')"
  after_failures="$(echo "$after_line" | sed -n 's/.*failures=\([0-9]\+\).*/\1/p')"
  echo "oversell_in_before=${before_oversold:-unknown}"
  echo "after_successes=${after_successes:-unknown}"
  echo "after_failures=${after_failures:-unknown}"
fi

echo "[ok] log written to $LOG_FILE"
