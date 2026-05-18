#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

REPORT_DIR="reports/interviews/first-thursday"
mkdir -p "$REPORT_DIR"
LOG_FILE="$REPORT_DIR/ft1-r2-capacity.log"

echo "== FT1 Req2: Resource Management & Capacity Control =="
echo "[step] Running before/after capacity checks (service + HTTP)"
./mvnw -q -Dtest=CheckoutCapacityBypassBaselineTest,CheckoutCapacityControlTest,CheckoutCapacityHttp429Test test | tee "$LOG_FILE"

before_line="$(grep -ho 'Capacity baseline summary:[^<]*' target/surefire-reports/TEST-com.parallelcart.CheckoutCapacityBypassBaselineTest.xml | tail -1 || true)"
after_service_line="$(grep -ho 'Capacity test summary:[^<]*' target/surefire-reports/TEST-com.parallelcart.CheckoutCapacityControlTest.xml | tail -1 || true)"
after_http_line="$(grep -ho 'Capacity HTTP summary:[^<]*' target/surefire-reports/TEST-com.parallelcart.CheckoutCapacityHttp429Test.xml | tail -1 || true)"

echo
echo "[Req2 before/after summary]"
echo "before (high capacity baseline): ${before_line:-not-found}"
echo "after  (guarded capacity service): ${after_service_line:-not-found}"
echo "after  (HTTP status evidence): ${after_http_line:-not-found}"

echo "[ok] log written to $LOG_FILE"
