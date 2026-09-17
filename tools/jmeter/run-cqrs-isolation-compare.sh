#!/usr/bin/env bash
set -euo pipefail

# CQRS isolation test:
# - Background read load (seat map)
# - Burst write load (hold)
# Compare:
#   1) "integrated DB" mode: seat cache TTLs = 0 (reads hit DB)
#   2) "CQRS read model" mode: seat cache TTLs > 0 (reads hit Redis/local after warm-up)
#
# Example:
#   EVENT_ID=1 SEAT_ID_FROM=1 SEAT_ID_TO=100 READ_RPS=500 WRITE_RPS=100 DURATION=30s WRITE_START=10s WRITE_DURATION=10s ./tools/run-cqrs-isolation-compare.sh

EVENT_ID="${EVENT_ID:-1}"
SEAT_ID_FROM="${SEAT_ID_FROM:-1}"
SEAT_ID_TO="${SEAT_ID_TO:-100}"

READ_RPS="${READ_RPS:-500}"
WRITE_RPS="${WRITE_RPS:-100}"
DURATION="${DURATION:-30s}"
WRITE_START="${WRITE_START:-10s}"
WRITE_DURATION="${WRITE_DURATION:-10s}"

PROM_URL="${PROM_URL:-http://localhost:9090}"
PROM_WINDOW="${PROM_WINDOW:-30s}"

prom_query() {
  local q="$1"
  curl -fsS --max-time 2 \
    --get "$PROM_URL/api/v1/query" \
    --data-urlencode "query=$q" \
    | jq -r '.data.result[0].value[1] // "na"' 2>/dev/null || echo "na"
}

snapshot_kpis() {
  local filter='{job="seatrace-app"}'
  local blocked_filter='{job="seatrace-app",state="blocked"}'
  local waiting_filter='{job="seatrace-app",state="waiting"}'
  printf 'hikari_active_max_%s=%s\n' "$PROM_WINDOW" "$(prom_query "max(max_over_time(hikaricp_connections_active${filter}[$PROM_WINDOW]))")"
  printf 'hikari_max_connections=%s\n' "$(prom_query "max(hikaricp_connections_max${filter})")"
  printf 'hikari_pending_max_%s=%s\n' "$PROM_WINDOW" "$(prom_query "max(max_over_time(hikaricp_connections_pending${filter}[$PROM_WINDOW]))")"
  printf 'hikari_timeout_inc_%s=%s\n' "$PROM_WINDOW" "$(prom_query "sum(increase(hikaricp_connections_timeout_total${filter}[$PROM_WINDOW]))")"
  printf 'jvm_threads_live_max_%s=%s\n' "$PROM_WINDOW" "$(prom_query "max(max_over_time(jvm_threads_live_threads${filter}[$PROM_WINDOW]))")"
  printf 'jvm_threads_peak_max_%s=%s\n' "$PROM_WINDOW" "$(prom_query "max(max_over_time(jvm_threads_peak_threads${filter}[$PROM_WINDOW]))")"
  printf 'jvm_threads_blocked_max_%s=%s\n' "$PROM_WINDOW" "$(prom_query "max(max_over_time(jvm_threads_states_threads${blocked_filter}[$PROM_WINDOW]))")"
  printf 'jvm_threads_waiting_max_%s=%s\n' "$PROM_WINDOW" "$(prom_query "max(max_over_time(jvm_threads_states_threads${waiting_filter}[$PROM_WINDOW]))")"
  printf 'executor_active_max_%s=%s\n' "$PROM_WINDOW" "$(prom_query "max(max_over_time(executor_active_threads${filter}[$PROM_WINDOW]))")"
  printf 'executor_queued_max_%s=%s\n' "$PROM_WINDOW" "$(prom_query "max(max_over_time(executor_queued_tasks${filter}[$PROM_WINDOW]))")"

  # Seat read DB load (should drop close to 0 in CQRS read-model mode after warm-up)
  printf 'seat_db_load_inc_%s=%s\n' "$PROM_WINDOW" "$(prom_query "sum(increase(seatrace_event_seats_db_load_total${filter}[$PROM_WINDOW]))")"
}

wait_until_ready() {
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

  echo "ERROR: app not ready within 90s" >&2
  docker compose logs --tail=120 app >&2 || true
  docker compose logs --tail=80 nginx >&2 || true
  return 1
}

run_phase() {
  local name="$1"
  shift

  echo "== phase=$name start=$(date) =="
  echo "compose_env: $*"

  # Keep nginx stable; only recreate app to apply env toggles.
  eval "$*" docker compose up -d --build --force-recreate --no-deps --scale app=2 app >/dev/null
  docker compose up -d --no-deps nginx prometheus >/dev/null || true

  wait_until_ready
  sleep 6

  echo "[before]"
  snapshot_kpis

  docker compose run --rm --no-deps \
    -e BASE_URL=http://nginx \
    -e EVENT_ID="$EVENT_ID" \
    -e SEAT_ID_FROM="$SEAT_ID_FROM" -e SEAT_ID_TO="$SEAT_ID_TO" \
    -e READ_RPS="$READ_RPS" -e WRITE_RPS="$WRITE_RPS" \
    -e DURATION="$DURATION" -e WRITE_START="$WRITE_START" -e WRITE_DURATION="$WRITE_DURATION" \
    k6 run --summary-trend-stats "avg,p(90),p(95),p(99),min,max" /scripts/cqrs_isolation.js

  echo "[after]"
  snapshot_kpis
  echo "== phase=$name end=$(date) =="
  echo
}

# Make the comparison meaningful: disable distributed lock & virtual queue in both runs.
# (Only the read model/caching changes.)
run_phase "integrated_db_reads" \
  "RESERVATION_LOCK_ENABLED=false RESERVATION_LOCK_REQUIRED=false VIRTUAL_QUEUE_ENABLED=false EVENT_SEAT_CACHE_LOCAL_TTL_MS=0 EVENT_SEAT_CACHE_REDIS_TTL_SECONDS=0"

run_phase "cqrs_read_model_cache" \
  "RESERVATION_LOCK_ENABLED=false RESERVATION_LOCK_REQUIRED=false VIRTUAL_QUEUE_ENABLED=false EVENT_SEAT_CACHE_LOCAL_TTL_MS=300000 EVENT_SEAT_CACHE_REDIS_TTL_SECONDS=300"
