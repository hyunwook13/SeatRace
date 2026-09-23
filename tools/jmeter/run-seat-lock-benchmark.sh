#!/usr/bin/env bash
set -euo pipefail

PROM_HOST="${PROM_HOST:-http://localhost:9090}"
APP_HOST="${APP_HOST:-localhost}"
APP_PORT="${APP_PORT:-8080}"
PLAN="${PLAN:-tools/jmeter/seat-lock-benchmark.jmx}"
RESULT_DIR="${RESULT_DIR:-tools/jmeter/results}"
EVENT_ID="${EVENT_ID:-1}"
THREADS="${THREADS:-200}"
RAMP="${RAMP:-15}"
DURATION="${DURATION:-150}"
PROM_RANGE="${PROM_RANGE:-30m}"

mkdir -p "${RESULT_DIR}"

if ! command -v jmeter >/dev/null 2>&1; then
  echo "jmeter command not found"
  exit 1
fi

query_prometheus() {
  local name="$1"
  local query="$2"
  local response
  response="$(curl -sG "${PROM_HOST}/api/v1/query" --data-urlencode "query=${query}")"
  PROM_QUERY_RESPONSE="${response}" PROM_QUERY_NAME="${name}" python3 - <<'PY'
import json
import os

payload = json.loads(os.environ["PROM_QUERY_RESPONSE"])
name = os.environ["PROM_QUERY_NAME"]

result = payload.get("data", {}).get("result", [])
if not result:
    print(f"{name}=0")
    raise SystemExit(0)

value = result[0].get("value", [None, "0"])[1]
print(f"{name}={value}")
PY
}

summarize_jtl() {
  local file="$1"
  python3 - "$file" <<'PY'
import csv
import sys

path = sys.argv[1]
with open(path, newline="") as f:
    reader = csv.DictReader(f)
    rows = [r for r in reader if r.get("label") == "Hold seats"]

if not rows:
    print("hold_samples=0")
    print("hold_max_latency_ms=0")
    print("hold_tps=0")
    print("hold_success_rate=0")
    raise SystemExit(0)

elapsed = [float(r["elapsed"]) for r in rows if r.get("elapsed")]
timestamps = [int(r["timeStamp"]) for r in rows if r.get("timeStamp")]
successes = sum(1 for r in rows if r.get("success", "").lower() == "true")
total = len(rows)
duration_s = max((max(timestamps) - min(timestamps)) / 1000.0, 0.001) if timestamps else 0.001
codes = {}
for row in rows:
    code = row.get("responseCode", "unknown")
    codes[code] = codes.get(code, 0) + 1

print(f"hold_samples={total}")
print(f"hold_max_latency_ms={max(elapsed):.2f}")
print(f"hold_tps={total / duration_s:.2f}")
print(f"hold_success_rate={(successes / total) * 100:.2f}")
for code in sorted(codes):
    print(f"hold_http_{code}_count={codes[code]}")
PY
}

run_case() {
  local label="$1"
  local seat_csv="$2"
  local result_file="${RESULT_DIR}/${label}.jtl"

  echo "=== ${label} ==="
  echo "# seat csv: ${seat_csv}"
  echo "# results: ${result_file}"
  echo "# duration: ${DURATION}s"

  tools/lock-benchmark/snapshot_metrics.sh "${label}-before" >/dev/null

  jmeter \
    -n \
    -t "${PLAN}" \
    -l "${result_file}" \
    -JHOST="${APP_HOST}" \
    -JPORT="${APP_PORT}" \
    -JEVENT_ID="${EVENT_ID}" \
    -JTHREADS="${THREADS}" \
    -JRAMP="${RAMP}" \
    -JDURATION="${DURATION}" \
    -JSEAT_CSV="${seat_csv}" \
    -Jjmeter.save.saveservice.output_format=csv \
    -Jjmeter.save.saveservice.print_field_names=true \
    -Jjmeter.save.saveservice.timestamp_format=ms \
    -Jjmeter.save.saveservice.time=true \
    -Jjmeter.save.saveservice.latency=true \
    -Jjmeter.save.saveservice.label=true \
    -Jjmeter.save.saveservice.code=true \
    -Jjmeter.save.saveservice.success=true

  tools/lock-benchmark/snapshot_metrics.sh "${label}-after" >/dev/null

  echo "--- JMeter summary ---"
  summarize_jtl "${result_file}"

  echo "--- Prometheus connection pool peak ---"
  query_prometheus "hikaricp_connections_active_peak" "max_over_time(hikaricp_connections_active[${PROM_RANGE}])"
  query_prometheus "hikaricp_connections_pending_peak" "max_over_time(hikaricp_connections_pending[${PROM_RANGE}])"
  query_prometheus "hikaricp_connections_idle_peak" "max_over_time(hikaricp_connections_idle[${PROM_RANGE}])"
}

run_case "hot-contention" "tools/jmeter/seat-sets-hot.csv"
run_case "mixed-contention" "tools/jmeter/seat-sets-mixed.csv"
