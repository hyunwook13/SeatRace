#!/usr/bin/env bash
set -euo pipefail

# Compares the same cold-cache Redis outage with Circuit Breaker disabled and enabled.
EVENT_ID="${EVENT_ID:-1}"
READ_RPS="${READ_RPS:-500}"
DURATION="${DURATION:-30s}"
PROM_WINDOW="${PROM_WINDOW:-45s}"
APP_REPLICAS="${APP_REPLICAS:-2}"
SCRAPE_WAIT_SECONDS="${SCRAPE_WAIT_SECONDS:-10}"
PROM_URL="${PROM_URL:-http://localhost:9090}"

prom_query() {
  curl -fsS --max-time 3 --get "$PROM_URL/api/v1/query" \
    --data-urlencode "query=$1" | jq -r '.data.result[0].value[1] // "na"' 2>/dev/null || echo "na"
}

snapshot_kpis() {
  local test_case="$1"
  local filter='{job="seatrace-app",test_case="'"$test_case"'"}'
  local seat_cache_filter='{job="seatrace-app",test_case="'"$test_case"'",feature="seatCache",reason="'
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
  printf 'seat_cache_redis_failure_inc_%s=%s\n' "$PROM_WINDOW" \
    "$(prom_query "sum(increase(seatrace_redis_resilience_failure_total${seat_cache_filter}redis_failure\"}[$PROM_WINDOW]))")"
  printf 'seat_cache_bulkhead_rejected_inc_%s=%s\n' "$PROM_WINDOW" \
    "$(prom_query "sum(increase(seatrace_redis_resilience_failure_total${seat_cache_filter}bulkhead_rejected\"}[$PROM_WINDOW]))")"
  printf 'seat_cache_circuit_open_inc_%s=%s\n' "$PROM_WINDOW" \
    "$(prom_query "sum(increase(seatrace_redis_resilience_failure_total${seat_cache_filter}circuit_open\"}[$PROM_WINDOW]))")"
}

wait_until_ready() {
  local deadline=$((SECONDS + 120))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -fsS --max-time 2 -X POST http://localhost:8080/login \
      -H 'Content-Type: application/x-www-form-urlencoded' \
      --data 'username=user&password=1234' | jq -e '.accessToken? | length > 0' >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  docker compose logs --tail=120 app >&2 || true
  return 1
}

run_load() {
  docker compose run --rm --no-deps \
    -e BASE_URL=http://nginx \
    -e EVENT_ID="$EVENT_ID" \
    -e READ_RPS="$READ_RPS" \
    -e DURATION="$DURATION" \
    k6 run --summary-trend-stats 'avg,p(90),p(95),p(99),min,max' /scripts/redis_fallback.js
}

restore_redis() {
  docker compose up -d --no-deps redis >/dev/null 2>&1 || true
}
trap restore_redis EXIT

run_case() {
  local label="$1"
  local circuit_breaker_enabled="$2"

  echo "== case=${label} circuit_breaker_enabled=${circuit_breaker_enabled} =="
  docker compose up -d --no-deps redis >/dev/null
  sleep 3
  METRICS_TEST_CASE="$label" REDIS_RESILIENCE_CIRCUIT_BREAKER_ENABLED="$circuit_breaker_enabled" \
    RESERVATION_LOCK_ENABLED=false RESERVATION_LOCK_REQUIRED=false VIRTUAL_QUEUE_ENABLED=false \
    docker compose up -d --build --force-recreate --scale app="$APP_REPLICAS" postgres redis app nginx prometheus >/dev/null
  wait_until_ready

  echo "Waiting ${SCRAPE_WAIT_SECONDS}s for a cold-cache Prometheus baseline..."
  sleep "$SCRAPE_WAIT_SECONDS"
  echo '[before fault]'
  snapshot_kpis "$label"

  docker compose stop redis >/dev/null
  run_load
  echo '[after fault]'
  snapshot_kpis "$label"
  echo "== case=${label} complete =="
  echo
}

run_case cb-off false
run_case cb-on true
