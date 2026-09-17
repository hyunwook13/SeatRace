#!/usr/bin/env bash
set -euo pipefail

# Compare the same read/write load in three modes:
#   1) no cache, no queue
#   2) seat read model cache, no queue
#   3) seat read model cache + virtual queue
#
# Recommended data setup:
#   SEAT_COUNT=500 ./tools/prepare-random-seat-hold-data.sh
#   EVENT_ID=... SEAT_ID_FROM=... SEAT_ID_TO=... ./tools/run-cache-queue-compare.sh
#
# Default scenario:
#   - GET /api/events/{eventId}/seats at READ_RPS for DURATION
#   - POST /api/events/{eventId}/holds at WRITE_RPS for WRITE_DURATION, starting at WRITE_START
#   - hold seat IDs are spread across SEAT_ID_FROM..SEAT_ID_TO

EVENT_ID="${EVENT_ID:-1}"
SEAT_ID_FROM="${SEAT_ID_FROM:-1}"
SEAT_ID_TO="${SEAT_ID_TO:-100}"

READ_RPS="${READ_RPS:-500}"
WRITE_RPS="${WRITE_RPS:-100}"
DURATION="${DURATION:-30s}"
WRITE_START="${WRITE_START:-10s}"
WRITE_DURATION="${WRITE_DURATION:-10s}"
READ_VUS="${READ_VUS:-100}"
READ_MAX_VUS="${READ_MAX_VUS:-300}"
WRITE_VUS="${WRITE_VUS:-50}"
WRITE_MAX_VUS="${WRITE_MAX_VUS:-200}"

USER_COUNT="${USER_COUNT:-500}"
USER_PREFIX="${USER_PREFIX:-load-$(date +%Y%m%d%H%M%S)}"

QUEUE_TPS="${QUEUE_TPS:-50}"
QUEUE_ACTIVE_LIMIT="${QUEUE_ACTIVE_LIMIT:-100}"
QUEUE_ACTIVE_TTL_SECONDS="${QUEUE_ACTIVE_TTL_SECONDS:-30}"
QUEUE_MAX_ADVANCE_PER_CALL="${QUEUE_MAX_ADVANCE_PER_CALL:-50}"

PROM_URL="${PROM_URL:-http://localhost:9090}"
PROM_WINDOW="${PROM_WINDOW:-30s}"
DB_USER="${DB_USER:-seatrace}"
DB_NAME="${DB_NAME:-seatrace}"

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
  printf 'seat_db_load_inc_%s=%s\n' "$PROM_WINDOW" "$(prom_query "sum(increase(seatrace_event_seats_db_load_total${filter}[$PROM_WINDOW]))")"
  printf 'queue_admit_inc_%s=%s\n' "$PROM_WINDOW" "$(prom_query "sum(increase(seatrace_queue_admit_total${filter}[$PROM_WINDOW]))")"
  printf 'queue_wait_inc_%s=%s\n' "$PROM_WINDOW" "$(prom_query "sum(increase(seatrace_queue_wait_total${filter}[$PROM_WINDOW]))")"
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

clear_redis_state() {
  docker compose exec -T redis sh -c \
    'redis-cli --scan --pattern "seat-race:*" | xargs -r redis-cli del >/dev/null' \
    >/dev/null 2>&1 || true
}

reset_event_state() {
  docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 \
    -v event_id="$EVENT_ID" <<'SQL' >/dev/null
with target_event_seats as (
  select id
  from event_seats
  where event_id = :'event_id'::bigint
),
affected_reservations as (
  select distinct rs.reservation_id
  from reservation_seats rs
  join target_event_seats tes on tes.id = rs.event_seat_id
)
update reservation_seats
set active = false
where event_seat_id in (select id from target_event_seats);

with affected_reservations as (
  select distinct rs.reservation_id
  from reservation_seats rs
  join event_seats es on es.id = rs.event_seat_id
  where es.event_id = :'event_id'::bigint
)
update reservations
set status = 'CANCELLED'
where id in (select reservation_id from affected_reservations);

update event_seats
set status = 'AVAILABLE',
    held_until = null,
    version = version + 1,
    updated_at = now()
where event_id = :'event_id'::bigint;
SQL
}

run_phase() {
  local name="$1"
  shift

  echo "== phase=$name start=$(date) =="
  echo "compose_env: $*"
  echo "load_env: EVENT_ID=$EVENT_ID SEAT_ID_FROM=$SEAT_ID_FROM SEAT_ID_TO=$SEAT_ID_TO READ_RPS=$READ_RPS WRITE_RPS=$WRITE_RPS DURATION=$DURATION WRITE_START=$WRITE_START WRITE_DURATION=$WRITE_DURATION USER_COUNT=$USER_COUNT"

  eval "$*" docker compose up -d --build --force-recreate --no-deps --scale app=2 app >/dev/null
  docker compose up -d --no-deps nginx prometheus redis >/dev/null || true

  clear_redis_state
  reset_event_state
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
    -e READ_VUS="$READ_VUS" -e READ_MAX_VUS="$READ_MAX_VUS" \
    -e WRITE_VUS="$WRITE_VUS" -e WRITE_MAX_VUS="$WRITE_MAX_VUS" \
    -e USER_COUNT="$USER_COUNT" -e USER_PREFIX="$USER_PREFIX-$name" \
    k6 run --summary-trend-stats "avg,p(90),p(95),p(99),min,max" /scripts/cqrs_isolation.js

  echo "[after]"
  snapshot_kpis
  echo "== phase=$name end=$(date) =="
  echo
}

run_phase "cache_off_queue_off" \
  "RESERVATION_LOCK_ENABLED=false RESERVATION_LOCK_REQUIRED=false VIRTUAL_QUEUE_ENABLED=false EVENT_SEAT_CACHE_LOCAL_TTL_MS=0 EVENT_SEAT_CACHE_REDIS_TTL_SECONDS=0"

run_phase "cache_on_queue_off" \
  "RESERVATION_LOCK_ENABLED=false RESERVATION_LOCK_REQUIRED=false VIRTUAL_QUEUE_ENABLED=false EVENT_SEAT_CACHE_LOCAL_TTL_MS=300000 EVENT_SEAT_CACHE_REDIS_TTL_SECONDS=300"

run_phase "cache_on_queue_on" \
  "RESERVATION_LOCK_ENABLED=false RESERVATION_LOCK_REQUIRED=false VIRTUAL_QUEUE_ENABLED=true VIRTUAL_QUEUE_TPS=$QUEUE_TPS VIRTUAL_QUEUE_ACTIVE_LIMIT=$QUEUE_ACTIVE_LIMIT VIRTUAL_QUEUE_ACTIVE_TTL_SECONDS=$QUEUE_ACTIVE_TTL_SECONDS VIRTUAL_QUEUE_MAX_ADVANCE_PER_CALL=$QUEUE_MAX_ADVANCE_PER_CALL EVENT_SEAT_CACHE_LOCAL_TTL_MS=300000 EVENT_SEAT_CACHE_REDIS_TTL_SECONDS=300"
