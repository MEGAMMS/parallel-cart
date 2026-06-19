#!/usr/bin/env bash
# profile-with-jfr.sh
# Records a Java Flight Recorder (JFR) snapshot for bottleneck analysis.
# Usage: ./profile-with-jfr.sh <java-process-id-or-app-name> [duration-seconds]

set -euo pipefail

APP_NAME="${1:-parallel-cart}"
DURATION="${2:-60}"
OUTPUT_FILE="parallel-cart-$(date +%Y%m%d-%H%M%S).jfr"

echo "[JFR Profiling] Target: $APP_NAME | Duration: ${DURATION}s | Output: $OUTPUT_FILE"

# Find PID by application name if not numeric
if [[ "$APP_NAME" =~ ^[0-9]+$ ]]; then
    PID="$APP_NAME"
else
    PID=$(jcmd -l | grep "$APP_NAME" | grep -v grep | awk '{print $1}' | head -n1)
    if [ -z "$PID" ]; then
        echo "ERROR: Could not find Java process matching '$APP_NAME'"
        exit 1
    fi
fi

echo "[JFR Profiling] Found PID: $PID"

# Start JFR recording
jcmd "$PID" JFR.start \
    name=parallel-cart-profile \
    settings=profile \
    duration="${DURATION}s" \
    filename="$OUTPUT_FILE"

echo "[JFR Profiling] Recording started. Waiting ${DURATION} seconds..."
sleep "$DURATION"

# Dump the recording
jcmd "$PID" JFR.dump \
    name=parallel-cart-profile \
    filename="$OUTPUT_FILE"

echo "[JFR Profiling] Recording saved to: $OUTPUT_FILE"
echo "[JFR Profiling] Open with JDK Mission Control (jmc) to analyze CPU, allocations, and locks."
