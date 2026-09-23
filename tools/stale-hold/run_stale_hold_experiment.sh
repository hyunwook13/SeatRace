
#!/bin/bash
set -euo pipefail
DEFAULT_CHUNK_SIZES=(10 20 50 100)
if [ -n "${CHUNK_SIZES_OVERRIDE:-}" ]; then
  read -r -a CHUNK_SIZES <<< "${CHUNK_SIZES_OVERRIDE}"
else
  CHUNK_SIZES=("${DEFAULT_CHUNK_SIZES[@]}")
fi
EVENT_ID=8
HOST=http://localhost:8080
HEALTH_URL="$HOST/health"
PROM_URL="$HOST/actuator/prometheus"
SEATS_FILE=tools/stale-hold/seat_ids.csv
wait_time=18
RESULTS_FILE=tools/stale-hold/metric_results.tsv
BOOT_LOG=tools/stale-hold/bootrun.log
SKIP_BOOTRUN="${SKIP_BOOTRUN:-0}"
if [ ! -f "$RESULTS_FILE" ]; then
  printf "chunk\tcleaned\tdeadletter\tp95_le\tavg_seconds\n" >> "$RESULTS_FILE"
fi

is_port_in_use() {
  lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1
}

wait_for_app_ready() {
  local retries=30
  for _ in $(seq 1 "$retries"); do
    if curl -s "$HEALTH_URL" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

if [ "$SKIP_BOOTRUN" != "1" ] && is_port_in_use; then
  echo "ERROR: 8080 포트가 이미 사용 중입니다. 기존 앱을 종료한 후 실행하세요."
  echo "확인 명령: lsof -nP -iTCP:8080 -sTCP:LISTEN"
  exit 1
fi

for CHUNK in "${CHUNK_SIZES[@]}"; do
  echo "\n=== chunkSize=${CHUNK} ==="
  export SPRING_APPLICATION_JSON="{\"reservation\":{\"hold\":{\"chunkSize\":${CHUNK}}}}"
  if [ "$SKIP_BOOTRUN" = "1" ]; then
    echo "SKIP_BOOTRUN=1: 기존 서버에 요청을 보냅니다."
  else
    ./gradlew bootRun > "$BOOT_LOG" 2>&1 &
    GRADLE_PID=$!
  fi

  if ! wait_for_app_ready; then
    echo "ERROR: 앱 기동 실패 (chunkSize=${CHUNK})"
    tail -n 60 "$BOOT_LOG"
    if [ "$SKIP_BOOTRUN" != "1" ]; then
      kill "$GRADLE_PID" 2>/dev/null || true
      wait "$GRADLE_PID" 2>/dev/null || true
    fi
    exit 1
  fi

  TOKEN=$(curl -s -X POST "$HOST/login" -H "Content-Type: application/x-www-form-urlencoded" -d "username=user&password=1234" | jq -r .accessToken)
  if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
    echo "ERROR: 로그인 토큰 발급 실패 (chunkSize=${CHUNK})"
    tail -n 60 "$BOOT_LOG"
    if [ "$SKIP_BOOTRUN" != "1" ]; then
      kill "$GRADLE_PID" 2>/dev/null || true
      wait "$GRADLE_PID" 2>/dev/null || true
    fi
    exit 1
  fi
  echo "token saved"
  while IFS= read -r SEAT && [ -n "$SEAT" ]; do
    curl -s -X POST "$HOST/api/events/${EVENT_ID}/holds" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{\"seatIds\":[${SEAT}]}"
  done < "$SEATS_FILE"
  echo "waiting ${wait_time}s for TTL + cleanup"
  sleep "$wait_time"
  METRICS=$(curl -s "$PROM_URL")
  echo "$METRICS" | rg -n "seatrace_hold_stale_cleaned_total\|seatrace_hold_stale_cleanup_duration_seconds_bucket\|seatrace_hold_stale_deadletter_total" || true
  CLEANED=$(awk '/seatrace_hold_stale_cleaned_total{source="scheduler"}/ {print $2}' <<< "$METRICS")
  CLEANED=${CLEANED:-0}
  DEADLETTER=$(awk '/seatrace_hold_stale_deadletter_total/ {print $2}' <<< "$METRICS")
  DEADLETTER=${DEADLETTER:-0}
  HIST=$(awk '/seatrace_hold_stale_cleanup_duration_seconds_bucket{source="scheduler"}/ {print}' <<< "$METRICS")
  SUM=$(awk '/seatrace_hold_stale_cleanup_duration_seconds_sum{source="scheduler"}/ {print $2}' <<< "$METRICS")
  SUM=${SUM:-0}
  COUNT=$(awk '/seatrace_hold_stale_cleanup_duration_seconds_count{source="scheduler"}/ {print $2}' <<< "$METRICS")
  COUNT=${COUNT:-0}
  P95=$(python3 - <<PY
import re, sys
total = float(${CLEANED})
target = total * 0.95
if total == 0:
    print("0")
    sys.exit(0)
lines = """${HIST}""".strip().splitlines()
for line in lines:
    match = re.search(r'le="([^"]+)"', line)
    if not match:
        continue
    val = float(line.split()[-1])
    if val >= target:
        print(match.group(1))
        sys.exit(0)
print("inf")
PY)
  AVG=$(python3 - <<PY
import sys
sum_v = float(${SUM})
count_v = float(${COUNT})
if count_v == 0:
    print("0")
else:
    print(sum_v / count_v)
PY)
  printf "%s\t%s\t%s\t%s\t%s\n" "$CHUNK" "$CLEANED" "$DEADLETTER" "$P95" "$AVG" >> "$RESULTS_FILE"
  if [ "$SKIP_BOOTRUN" != "1" ]; then
    kill "$GRADLE_PID"
    wait $GRADLE_PID 2>/dev/null || true
  fi
  sleep 3
done
