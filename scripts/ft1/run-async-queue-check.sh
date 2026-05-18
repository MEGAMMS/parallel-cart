#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

REPORT_DIR="reports/interviews/first-thursday"
mkdir -p "$REPORT_DIR"
BASELINE_LOG="$REPORT_DIR/ft1-r3-async-baseline.log"
ASYNC_LOG="$REPORT_DIR/ft1-r3-async-runtime.log"

echo "== FT1 Req3: Asynchronous Queues =="
echo "[step] Running synthetic before baseline (inline vs queued latency)"
./mvnw -q -Dtest=AsyncQueueLatencyBaselineTest test | tee "$BASELINE_LOG"

echo "[step] Running real async outbox/Kafka verification"
./scripts/verify_phase.sh p3 | tee "$ASYNC_LOG"

baseline_line="$(grep -ho 'Async baseline summary:[^<]*' target/surefire-reports/TEST-com.parallelcart.AsyncQueueLatencyBaselineTest.xml | tail -1 || true)"
checkout_time="$(awk '/^\[checkout\]/{flag=1;next} flag && /time_total_seconds:/{print $2; exit}' "$ASYNC_LOG" || true)"
outbox_delta="$(grep -E 'outbox_events delta:' "$ASYNC_LOG" | tail -1 || true)"
invoice_delta="$(grep -E 'invoices delta:' "$ASYNC_LOG" | tail -1 || true)"
notification_delta="$(grep -E 'notification_logs delta:' "$ASYNC_LOG" | tail -1 || true)"

echo
echo "[Req3 before/after summary]"
echo "before (inline-vs-queued baseline): ${baseline_line:-not-found}"
echo "after  (checkout response time seconds): ${checkout_time:-not-found}"
echo "after  (${outbox_delta:-outbox delta not-found})"
echo "after  (${invoice_delta:-invoice delta not-found})"
echo "after  (${notification_delta:-notification delta not-found})"

echo "[ok] logs written to $BASELINE_LOG and $ASYNC_LOG"
