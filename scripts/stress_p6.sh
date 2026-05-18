#!/usr/bin/env bash
set -euo pipefail

base_url="${BASE_URL:-http://localhost:8080}"
results_dir="${RESULTS_DIR:-reports/stress}"
target_vus="${TARGET_VUS:-120}"
ramp_up="${RAMP_UP:-30s}"
hold="${HOLD:-120s}"
ramp_down="${RAMP_DOWN:-30s}"
profile_name="${PROFILE_NAME:-p6-stress}"

mkdir -p "$results_dir"

wait_for_app() {
  for _ in {1..180}; do
    if curl -sf "${base_url}/actuator/health" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "[fail] app not reachable: ${base_url}"
  exit 1
}

echo '$ docker compose up -d --build'
docker compose up -d --build
wait_for_app

timestamp="$(date +%Y%m%d_%H%M%S)"
summary_file="${results_dir}/${profile_name}_${timestamp}.json"
log_file="${results_dir}/${profile_name}_${timestamp}.log"

echo "== run stress profile ==" | tee "$log_file"
echo "target_vus=${target_vus} ramp_up=${ramp_up} hold=${hold} ramp_down=${ramp_down}" | tee -a "$log_file"

docker run --rm --network host \
  -v "$(pwd)/scripts:/scripts" \
  grafana/k6:0.51.0 run /scripts/k6_products_read.js \
  --summary-export "/scripts/p6_stress_tmp.json" \
  -e BASE_URL="$base_url" \
  -e START_VUS=20 \
  -e TARGET_VUS="$target_vus" \
  -e RAMP_UP="$ramp_up" \
  -e HOLD="$hold" \
  -e RAMP_DOWN="$ramp_down" \
  -e PRODUCT_COUNT=20 \
  -e SLEEP_SECONDS=0.02 | tee -a "$log_file"

cp scripts/p6_stress_tmp.json "$summary_file"
rm -f scripts/p6_stress_tmp.json

python - "$summary_file" <<'PY' | tee -a "$log_file"
import json, sys
p=sys.argv[1]
with open(p,'r',encoding='utf-8') as f:
    d=json.load(f)
m=d['metrics']
lat=m['http_req_duration']
rps=m['http_reqs']['rate']
fail=m['http_req_failed']['value']
print('\n== parsed summary ==')
print(f"avg_ms={lat.get('avg',0):.2f}")
print(f"p90_ms={lat.get('p(90)',0):.2f}")
print(f"p95_ms={lat.get('p(95)',0):.2f}")
print(f"max_ms={lat.get('max',0):.2f}")
print(f"rps={rps:.2f}")
print(f"http_failed_rate={fail:.6f}")
PY

echo "\n[ok] stress report saved"
echo "summary=${summary_file}"
echo "log=${log_file}"
