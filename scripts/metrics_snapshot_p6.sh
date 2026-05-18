#!/usr/bin/env bash
set -euo pipefail

base_url="${BASE_URL:-http://localhost:8080}"
compose_cmd="docker compose"

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "[fail] missing command: $cmd"
    exit 1
  fi
}

require_cmd curl
require_cmd python

metric_json() {
  local name="$1"
  curl -s "${base_url}/actuator/metrics/${name}"
}

metric_value() {
  local name="$1"
  local stat="$2"
  python - "$name" "$stat" "$base_url" <<'PY'
import json, sys, urllib.request
name, stat, base = sys.argv[1], sys.argv[2], sys.argv[3]
url = f"{base}/actuator/metrics/{name}"
with urllib.request.urlopen(url) as r:
    data = json.loads(r.read().decode("utf-8"))
for m in data.get("measurements", []):
    if m.get("statistic") == stat:
        print(m.get("value", 0))
        sys.exit(0)
print(0)
PY
}

echo "== P6 metrics snapshot =="
echo "Base URL: ${base_url}"

http_count=$(metric_value "http.server.requests" "COUNT")
http_total_time=$(metric_value "http.server.requests" "TOTAL_TIME")
http_max=$(metric_value "http.server.requests" "MAX")

if python - <<'PY' "$http_count"
import sys
print(1 if float(sys.argv[1]) > 0 else 0)
PY
then
  avg_ms=$(python - <<'PY' "$http_count" "$http_total_time"
import sys
count = float(sys.argv[1]); total = float(sys.argv[2])
print((total / count) * 1000 if count else 0)
PY
)
else
  avg_ms=0
fi

echo
printf "%-28s %s\n" "http.requests.count" "$http_count"
printf "%-28s %s\n" "http.requests.avg_ms" "$avg_ms"
printf "%-28s %s\n" "http.requests.max_s" "$http_max"

echo
err_5xx=$(curl -s "${base_url}/actuator/metrics/http.server.requests?tag=status:500" || true)
if [[ -n "$err_5xx" ]]; then
  err_count=$(python - <<'PY' "$err_5xx"
import json, sys
try:
    data=json.loads(sys.argv[1])
    m=data.get("measurements", [])
    v=0
    for x in m:
        if x.get("statistic")=="COUNT":
            v=x.get("value",0)
            break
    print(v)
except Exception:
    print(0)
PY
)
else
  err_count=0
fi
printf "%-28s %s\n" "http.errors.500.count" "$err_count"

echo
echo "[raw actuator metric payloads]"
echo "- http.server.requests"
metric_json "http.server.requests"

echo
if $compose_cmd ps kafka >/dev/null 2>&1; then
  echo "[kafka consumer lag snapshot]"
  echo '$ docker compose exec -T kafka kafka-consumer-groups --bootstrap-server kafka:9092 --all-groups --describe'
  $compose_cmd exec -T kafka kafka-consumer-groups --bootstrap-server kafka:9092 --all-groups --describe || true
else
  echo "[skip] kafka service not running"
fi
