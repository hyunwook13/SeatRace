#!/usr/bin/env bash
set -euo pipefail

# Random "podo-al" seat hold load test.
#
# Default scenario:
# - 500 open seats: seatId 1..500
# - 200 concurrent virtual users
# - each iteration chooses one random seat and calls POST /api/events/{eventId}/holds
#
# Example:
#   EVENT_ID=1 VUS=200 SEAT_ID_FROM=1 SEAT_ID_TO=500 DURATION=30s ./tools/run-random-seat-hold.sh

EVENT_ID="${EVENT_ID:-1}"
VUS="${VUS:-200}"
DURATION="${DURATION:-30s}"
SEAT_ID_FROM="${SEAT_ID_FROM:-1}"
SEAT_ID_TO="${SEAT_ID_TO:-500}"
THINK_TIME_MS="${THINK_TIME_MS:-0}"
PRE_SCALE_APP="${PRE_SCALE_APP:-2}"

wait_until_up() {
  local health_url="http://localhost:8080/actuator/health"
  local login_url="http://localhost:8080/login"
  local deadline=$((SECONDS + 90))
  local consecutive_ok=0

  while [ "$SECONDS" -lt "$deadline" ]; do
    local health_ok="false"
    local login_ok="false"

    if curl -fsS --max-time 2 "$health_url" 2>/dev/null | grep -q '"status":"UP"'; then
      health_ok="true"
    fi

    if curl -fsS --max-time 2 -X POST "$login_url" \
      -H "Content-Type: application/x-www-form-urlencoded" \
      --data "username=user&password=1234" 2>/dev/null \
      | jq -e '.accessToken? | length > 0' >/dev/null 2>&1; then
      login_ok="true"
    fi

    if [ "$health_ok" = "true" ] && [ "$login_ok" = "true" ]; then
      consecutive_ok=$((consecutive_ok + 1))
      if [ "$consecutive_ok" -ge 5 ]; then
        return 0
      fi
    else
      consecutive_ok=0
    fi

    sleep 1
  done

  echo "ERROR: app not ready within 90s: $health_url" >&2
  docker compose logs --tail=120 app >&2 || true
  return 1
}

cat <<MSG
Random seat hold test
  eventId       : ${EVENT_ID}
  seat range    : ${SEAT_ID_FROM}..${SEAT_ID_TO}
  VUs           : ${VUS}
  duration      : ${DURATION}
  thinkTimeMs   : ${THINK_TIME_MS}
  app scale     : ${PRE_SCALE_APP}
  queue         : disabled for throughput isolation
MSG

VIRTUAL_QUEUE_ENABLED=false LOG_LEVEL_HIKARI=INFO \
  docker compose up -d --build --force-recreate --no-deps --scale app="${PRE_SCALE_APP}" app >/dev/null
docker compose up -d --force-recreate --no-deps nginx prometheus >/dev/null || true

wait_until_up
sleep 6

docker compose run --rm --no-deps \
  -e BASE_URL=http://nginx \
  -e EVENT_ID="${EVENT_ID}" \
  -e VUS="${VUS}" \
  -e DURATION="${DURATION}" \
  -e SEAT_ID_FROM="${SEAT_ID_FROM}" \
  -e SEAT_ID_TO="${SEAT_ID_TO}" \
  -e THINK_TIME_MS="${THINK_TIME_MS}" \
  k6 run /scripts/random_seat_hold.js

cat <<MSG

Useful follow-up metrics:
  curl -s http://localhost:8080/actuator/metrics/http.server.requests
  curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active
  curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.pending
  curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.timeout
  curl -s http://localhost:8080/actuator/metrics/seatrace.hold.success.total
  curl -s http://localhost:8080/actuator/metrics/seatrace.hold.fail.total
MSG
