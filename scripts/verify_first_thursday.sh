#!/usr/bin/env bash
set -euo pipefail

REPORT_DIR="reports/interviews/first-thursday"
mkdir -p "$REPORT_DIR"

TIMESTAMP="$(date -Iseconds)"
TEST_LOG="$REPORT_DIR/ft1-tests.log"
REQ3_LOG="$REPORT_DIR/ft1-req3.log"
REQ5_LOG="$REPORT_DIR/ft1-req5-load.log"
SUMMARY_MD="$REPORT_DIR/FT1_FULL_VERIFICATION.md"

wait_http() {
  local url="$1"
  local max_seconds="${2:-180}"
  local waited=0
  while (( waited < max_seconds )); do
    if curl -sf "$url" >/dev/null; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

wait_for_two_instances() {
  local url="$1"
  local max_seconds="${2:-180}"
  local waited=0
  local saw_app1=0
  local saw_app2=0

  while (( waited < max_seconds )); do
    headers_file="$(mktemp)"
    code="$(curl -sS -o /dev/null -D "$headers_file" -w "%{http_code}" "$url" || true)"
    instance="$(awk -F': ' 'tolower($1)=="x-app-instance" {gsub("\r", "", $2); print $2}' "$headers_file" | tail -1)"
    rm -f "$headers_file"

    if [[ "$code" == "200" && "$instance" == "app-1" ]]; then
      saw_app1=1
    fi
    if [[ "$code" == "200" && "$instance" == "app-2" ]]; then
      saw_app2=1
    fi

    if (( saw_app1 == 1 && saw_app2 == 1 )); then
      return 0
    fi

    sleep 1
    waited=$((waited + 1))
  done

  return 1
}

echo "== FT1 verification started at $TIMESTAMP =="

# Req 1, 2, 4 -> test proofs
echo "[step] running Req1/Req2/Req4 tests"
./mvnw -q -Dtest=CheckoutConcurrencyRaceConditionTest,CheckoutCapacityControlTest,DailySalesBatchServiceTest test | tee "$TEST_LOG"

# Req 3 -> async flow proof
echo "[step] running Req3 async verification"
./scripts/verify_phase.sh p3 | tee "$REQ3_LOG"

# Req 5 -> load distribution with nginx + 2 instances
echo "[step] running Req5 load distribution verification"
{
  echo "-- compose up (base + load)"
  docker compose -f docker-compose.yml -f docker-compose.load.yml down -v || true
  docker compose -f docker-compose.yml -f docker-compose.load.yml up -d --build

  echo "-- waiting for nginx endpoint"
  wait_http "http://localhost:8081/actuator/health" 240
  echo "-- waiting until both app instances answer with HTTP 200"
  wait_for_two_instances "http://localhost:8081/actuator/health" 240

  declare -A COUNTS=()
  for i in $(seq 1 30); do
    headers_file="$(mktemp)"
    code="$(curl -sS -o /dev/null -D "$headers_file" -w "%{http_code}" "http://localhost:8081/actuator/health")"
    if [[ "$code" != "200" ]]; then
      echo "request $i returned HTTP $code"
      rm -f "$headers_file"
      exit 1
    fi

    instance="$(awk -F': ' 'tolower($1)=="x-app-instance" {gsub("\r", "", $2); print $2}' "$headers_file" | tail -1)"
    rm -f "$headers_file"

    if [[ -z "$instance" ]]; then
      instance="unknown"
    fi
    COUNTS["$instance"]=$(( ${COUNTS["$instance"]:-0} + 1 ))
  done

  UNIQUE_INSTANCES="${#COUNTS[@]}"
  echo "unique_instances=$UNIQUE_INSTANCES"
  for key in "${!COUNTS[@]}"; do
    echo "instance_count[$key]=${COUNTS[$key]}"
  done

  if (( UNIQUE_INSTANCES < 2 )); then
    echo "Load distribution check failed: expected >=2 instances, got $UNIQUE_INSTANCES"
    exit 1
  fi

  echo "-- nginx logs"
  docker compose -f docker-compose.yml -f docker-compose.load.yml logs --no-color --tail=200 nginx
} | tee "$REQ5_LOG"

REQ1_SUMMARY="$(grep -E "Race test summary:" target/surefire-reports/TEST-com.parallelcart.CheckoutConcurrencyRaceConditionTest.xml | tail -1 | sed 's/.*Race test summary:/Race test summary:/')"
REQ2_SUMMARY="$(grep -E "Capacity test summary:" target/surefire-reports/TEST-com.parallelcart.CheckoutCapacityControlTest.xml | tail -1 | sed 's/.*Capacity test summary:/Capacity test summary:/')"
REQ4_RESULT_LINE="$(
  sed -n 's/.*name="com.parallelcart.DailySalesBatchServiceTest".*tests="\([0-9]\+\)".*errors="\([0-9]\+\)".*failures="\([0-9]\+\)".*/DailySalesBatchServiceTest: tests=\1, failures=\3, errors=\2/p' \
    target/surefire-reports/TEST-com.parallelcart.DailySalesBatchServiceTest.xml | head -1
)"
REQ5_COUNTS="$(grep -E "instance_count\[|unique_instances=" "$REQ5_LOG" || true)"

cat > "$SUMMARY_MD" <<MD
# First Thursday Requirements 1-5 Verification

Generated at: $TIMESTAMP

## Req 1 — Concurrent Access & Data Integrity
- Status: PASS
- Evidence: 
  - [FT1-T1_RACE_PROOF.md]($PWD/reports/interviews/first-thursday/FT1-T1_RACE_PROOF.md)
- Runtime summary:
  - ${REQ1_SUMMARY:-Race summary not found}

## Req 2 — Resource Management & Capacity Control
- Status: PASS
- Evidence:
  - [FT1-T2_CAPACITY_CONTROL.md]($PWD/reports/interviews/first-thursday/FT1-T2_CAPACITY_CONTROL.md)
- Runtime summary:
  - ${REQ2_SUMMARY:-Capacity summary not found}

## Req 3 — Asynchronous Queues
- Status: PASS
- Evidence log:
  - [ft1-req3.log]($PWD/$REQ3_LOG)

## Req 4 — Batch Processing (Chunks + Checkpoint Resume)
- Status: PASS
- Tests:
  - DailySalesBatchServiceTest
- Evidence line:
  - ${REQ4_RESULT_LINE:-DailySalesBatchServiceTest summary not found}

## Req 5 — Load Distribution
- Status: PASS
- Strategy: Nginx upstream round-robin across app-1 and app-2
- Evidence:
  - [ft1-req5-load.log]($PWD/$REQ5_LOG)
- Instance distribution snapshot:

\
$REQ5_COUNTS
\
MD

echo "[ok] FT1 verification completed"
echo "Summary: $SUMMARY_MD"
