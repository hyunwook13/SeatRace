#!/usr/bin/env bash
set -euo pipefail

# A/B test: Redis ON (baseline) -> Redis OFF (experiment) -> optional RECOVER
#
# Usage example:
#   EVENT_ID=1 RPS=100 ON_S=60 OFF_S=60 RECOVER_S=0 ./tools/run-ab-redis-on-off.sh
#
# It prints:
# - exact timestamps when Redis was stopped/started
# - DB load counter snapshots (best-effort) via /actuator/prometheus (nginx -> app)

BASE_URL="${BASE_URL:-http://nginx}"
EVENT_ID="${EVENT_ID:-1}"
RPS="${RPS:-100}"
ON_S="${ON_S:-60}"
OFF_S="${OFF_S:-60}"
RECOVER_S="${RECOVER_S:-0}"
PREALLOCATED_VUS="${PREALLOCATED_VUS:-200}"
MAX_VUS="${MAX_VUS:-600}"
MAX_P95_MS="${MAX_P95_MS:-10000}"

# This must be reachable from the host terminal running this script.
METRICS_URL="${METRICS_URL:-http://localhost:8080/actuator/prometheus}"

metric_db_load() {
  # NOTE: Prometheus exposition format can include labels:
  #   metric_name{...} 123
  # so we print the last field ($NF), not $2.
  curl -fsS --max-time 2 "$METRICS_URL" 2>/dev/null \
    | awk '/^seatrace_event_seats_db_load_total/{print $NF; exit}' \
    || echo "na"
}

TEST_START_EPOCH_S="$(date +%s)"
echo "[ab] start=$(date) TEST_START_EPOCH_S=$TEST_START_EPOCH_S db_load=$(metric_db_load)"

(
  sleep "$ON_S"
  echo "[ab] redis stop at $(date) db_load=$(metric_db_load)"
  docker compose stop redis >/dev/null

  sleep "$OFF_S"
  echo "[ab] redis start at $(date) db_load=$(metric_db_load)"
  docker compose start redis >/dev/null

  if [ "$RECOVER_S" -gt 0 ]; then
    sleep "$RECOVER_S"
    echo "[ab] recover end at $(date) db_load=$(metric_db_load)"
  fi
) &
CHAOS_PID=$!

docker compose run --rm \
  -e BASE_URL="$BASE_URL" -e EVENT_ID="$EVENT_ID" \
  -e RPS="$RPS" -e ON_S="$ON_S" -e OFF_S="$OFF_S" -e RECOVER_S="$RECOVER_S" \
  -e PREALLOCATED_VUS="$PREALLOCATED_VUS" -e MAX_VUS="$MAX_VUS" -e MAX_P95_MS="$MAX_P95_MS" \
  -e TEST_START_EPOCH_S="$TEST_START_EPOCH_S" \
  k6 run /scripts/ab_seats.js

wait "$CHAOS_PID"
echo "[ab] done=$(date) db_load=$(metric_db_load)"
