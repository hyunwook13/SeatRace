#!/usr/bin/env bash
set -euo pipefail

# Redis fallback experiment. It keeps the workload constant while changing only
# Redis availability and the local-cache warm state.
#
# Phases:
#   1) redis_healthy: normal cache-aside baseline
#   2) redis_down_local_warm: Redis stopped after both app instances are warmed
#   3) redis_down_local_cold: app instances recreated while Redis is healthy,
#      then Redis stopped before the first seat-read request
#
# Example:
# EVENT_ID=1 READ_RPS=500 DURATION=30s PROM_WINDOW=30s \
#   ./tools/jmeter/run-chaos-redis-fallback.sh 2>&1 | tee tools/log/redis-fallback-$(date +%Y%m%d-%H%M%S).log

EVENT_ID="${EVENT_ID:-1}"
READ_RPS="${READ_RPS:-500}"
DURATION="${DURATION:-30s}"
PROM_WINDOW="${PROM_WINDOW:-30s}"
APP_REPLICAS="${APP_REPLICAS:-2}"
WARMUP_REQUESTS="${WARMUP_REQUESTS:-40}"
PROM_URL="${PROM_URL:-http://localhost:9090}"

prom_query() {
  local query="$1"
  curl -fsS --max-time 3 --get "$PROM_URL/api/v1/query" \
    --data-urlencode "query=$query" \
    | jq -r '.data.result[0].value[1] // "na"' 2>/dev/null || echo "na"
}

snapshot_kpis() {
  local filter='{job="seatrace-app"}'
  printf 'hikari_active_max_%s=%s\n' "$PROM_WINDOW" \
    "$(prom_query "max(max_over_time(hikaricp_connections_active${filter}[$PROM_WINDOW]))")"
  printf 'hikari_pending_max_%s=%s\n' "$PROM_WINDOW" \
    "$(prom_query "max(max_over_time(hikaricp_connections_pending${filter}[$PROM_WINDOW]))")"
  printf 'hikari_timeout_inc_%s=%s\n' "$PROM_WINDOW" \
    "$(prom_query "sum(increase(hikaricp_connections_timeout_total${filter}[$PROM_WINDOW]))")"
  printf 'seat_db_load_inc_%s=%s\n' "$PROM_WINDOW" \
    "$(prom_query "sum(increase(seatrace_event_seats_db_load_total${filter}[$PROM_WINDOW]))")"
  printf 'redis_fallback_inc_%s=%s\n' "$PROM_WINDOW" \
    "$(prom_query "sum(increase(seatrace_event_seat_cache_redis_fallback_total${filter}[$PROM_WINDOW]))")"
  printf 'redis_bypass_inc_%s=%s\n' "$PROM_WINDOW" \
    "$(prom_query "sum(increase(seatrace_event_seat_cache_redis_bypass_total${filter}[$PROM_WINDOW]))")"
}

wait_until_ready() {
  local deadline=$((SECONDS + 120))
  echo "Waiting for app readiness (up to 120s)..."
  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -fsS --max-time 2 -X POST http://localhost:8080/login \
      -H 'Content-Type: application/x-www-form-urlencoded' \
      --data 'username=user&password=1234' \
      | jq -e '.accessToken? | length > 0' >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  docker compose logs --tail=120 app >&2 || true
  return 1
}

login_token() {
  curl -fsS --max-time 3 -X POST http://localhost:8080/login \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data 'username=user&password=1234' | jq -r '.accessToken'
}

warm_all_app_caches() {
  local token
  token="$(login_token)"
  if [ -z "$token" ] || [ "$token" = "null" ]; then
    echo "ERROR: unable to obtain test access token" >&2
    return 1
  fi

  for _ in $(seq 1 "$WARMUP_REQUESTS"); do
    curl -fsS --max-time 3 "http://localhost:8080/api/events/${EVENT_ID}/seats" \
      -H "Authorization: Bearer $token" >/dev/null
  done
}

run_load() {
  docker compose run --rm --no-deps \
    -e BASE_URL=http://nginx \
    -e EVENT_ID="$EVENT_ID" \
    -e READ_RPS="$READ_RPS" \
    -e DURATION="$DURATION" \
    k6 run --summary-trend-stats 'avg,p(90),p(95),p(99),min,max' /scripts/redis_fallback.js
}

run_phase() {
  local name="$1"
  echo "== phase=${name} start=$(date) =="
  echo "load_env: EVENT_ID=${EVENT_ID} READ_RPS=${READ_RPS} DURATION=${DURATION} APP_REPLICAS=${APP_REPLICAS}"
  echo '[before]'
  snapshot_kpis
  run_load
  echo '[after]'
  snapshot_kpis
  echo "== phase=${name} end=$(date) =="
  echo
}

restore_redis() {
  echo "Restoring Redis before exit..."
  docker compose up -d --no-deps redis >/dev/null 2>&1 || true
}
trap restore_redis EXIT

# The queue and distributed lock are disabled so the experiment measures the
# seat-read cache and its Redis fallback path only.
echo "== setup: starting ${APP_REPLICAS} app replicas with Redis available =="
RESERVATION_LOCK_ENABLED=false RESERVATION_LOCK_REQUIRED=false VIRTUAL_QUEUE_ENABLED=false \
  docker compose up -d --build --force-recreate --scale app="$APP_REPLICAS" postgres redis app nginx prometheus >/dev/null
wait_until_ready
sleep 6

echo "Warming Redis and local caches with ${WARMUP_REQUESTS} seat reads..."
warm_all_app_caches
run_phase redis_healthy

echo "Injecting fault: stopping Redis while app instances stay warm..."
docker compose stop redis >/dev/null
run_phase redis_down_local_warm

# Recreate while Redis is healthy so this phase isolates runtime Redis failure
# from startup dependencies. Do not warm the new app instances.
echo "Restoring Redis before recreating app instances with empty local caches..."
docker compose up -d --no-deps redis >/dev/null
sleep 3
echo "Recreating app instances while Redis is available to clear local caches..."
RESERVATION_LOCK_ENABLED=false RESERVATION_LOCK_REQUIRED=false VIRTUAL_QUEUE_ENABLED=false \
  docker compose up -d --build --force-recreate --no-deps --scale app="$APP_REPLICAS" app >/dev/null
wait_until_ready
sleep 3
echo "Injecting fault: stopping Redis before the first cold-cache seat read..."
docker compose stop redis >/dev/null
run_phase redis_down_local_cold
