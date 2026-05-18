#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

REPORT_DIR="reports/interviews/first-thursday"
mkdir -p "$REPORT_DIR"
LOG_FILE="$REPORT_DIR/ft1-r4-batch.log"

echo "== FT1 Req4: Batch Processing =="
echo "[step] Running before/after batch comparison + checkpoint tests"
./mvnw -q -Dtest=DailySalesBatchBeforeAfterComparisonTest,DailySalesBatchServiceTest test | tee "$LOG_FILE"

baseline_line="$(grep -ho 'Batch baseline summary:[^<]*' target/surefire-reports/TEST-com.parallelcart.DailySalesBatchBeforeAfterComparisonTest.xml | tail -1 || true)"
chunked_line="$(grep -ho 'Batch chunked summary:[^<]*' target/surefire-reports/TEST-com.parallelcart.DailySalesBatchBeforeAfterComparisonTest.xml | tail -1 || true)"
resume_line="$(
  sed -n 's/.*name="com.parallelcart.DailySalesBatchServiceTest".*tests="\([0-9]\+\)".*errors="\([0-9]\+\)".*failures="\([0-9]\+\)".*/DailySalesBatchServiceTest: tests=\1, failures=\3, errors=\2/p' \
    target/surefire-reports/TEST-com.parallelcart.DailySalesBatchServiceTest.xml | head -1
)"

echo
echo "[Req4 before/after summary]"
echo "before (single-pass baseline): ${baseline_line:-not-found}"
echo "after  (chunked + resume): ${chunked_line:-not-found}"
echo "after  (checkpoint tests): ${resume_line:-not-found}"

echo "[ok] log written to $LOG_FILE"
