#!/usr/bin/env bash
set -euo pipefail

# Compares a simultaneous reservation-flow burst with the queue disabled/enabled.
# The test uses unique users and unique seats so lock contention is not mistaken
# for queue protection. Cache remains enabled in both phases.

EVENT_ID="${EVENT_ID:-1}"
SEAT_ID_FROM="${SEAT_ID_FROM:-1}"
SEAT_ID_TO="${SEAT_ID_TO:-500}"
USER_COUNT="${USER_COUNT:-200}"
READS_PER_USER="${READS_PER_USER:-3}"
HOLDS_PER_USER="${HOLDS_PER_USER:-1}"
QUEUE_TPS="${QUEUE_TPS:-40}"
QUEUE_ACTIVE_LIMIT="${QUEUE_ACTIVE_LIMIT:-80}"
QUEUE_ACTIVE_TTL_SECONDS="${QUEUE_ACTIVE_TTL_SECONDS:-15}"
QUEUE_MAX_ADVANCE_PER_CALL="${QUEUE_MAX_ADVANCE_PER_CALL:-20}"
ADMISSION_TIMEOUT_SECONDS="${ADMISSION_TIMEOUT_SECONDS:-120}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-1}"
ACTIVE_SESSION_SECONDS="${ACTIVE_SESSION_SECONDS:-0}"
HEARTBEAT_INTERVAL_SECONDS="${HEARTBEAT_INTERVAL_SECONDS:-5}"
HEARTBEAT_ENABLED="${HEARTBEAT_ENABLED:-false}"
RELEASE_AFTER_FLOW="${RELEASE_AFTER_FLOW:-false}"
PROM_URL="${PROM_URL:-http://localhost:9090}"
PROM_WINDOW="${PROM_WINDOW:-180s}"
SCRAPE_WAIT_SECONDS="${SCRAPE_WAIT_SECONDS:-10}"
DB_USER="${DB_USER:-seatrace}"
DB_NAME="${DB_NAME:-seatrace}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
USER_PREFIX="${USER_PREFIX:-queue-${RUN_ID//[-:]/}}"
PASSWORD="${PASSWORD:-1234}"

if (( SEAT_ID_TO - SEAT_ID_FROM + 1 < USER_COUNT * HOLDS_PER_USER )); then
  echo "ERROR: unique seats are insufficient for USER_COUNT * HOLDS_PER_USER" >&2
  exit 1
fi

JAR_FILE="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)"
if [[ -z "$JAR_FILE" ]] || find src/main build.gradle settings.gradle -newer "$JAR_FILE" -print -quit | grep -q .; then
  echo "ERROR: application JAR is missing or stale. Run: JAVA_HOME=\$(/usr/libexec/java_home -v 17) ./gradlew bootJar" >&2
  exit 1
fi

wait_for_postgres() {
  local deadline=$((SECONDS + 90))
  until docker compose exec -T postgres pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "ERROR: postgres was not ready within 90 seconds" >&2
      return 1
    fi
    sleep 1
  done
}

wait_for_app() {
  local deadline=$((SECONDS + 120))
  until docker compose exec -T app \
    curl -fsS --max-time 2 http://localhost:50000/actuator/health/readiness >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "ERROR: app was not ready within 120 seconds" >&2
      docker compose logs --tail=100 app >&2 || true
      return 1
    fi
    sleep 1
  done
}

wait_for_prometheus_scrape() {
  local test_case="$1"
  local deadline=$((SECONDS + 30))
  local scrape_count

  while true; do
    scrape_count="$(prom_query "count(jvm_info{job=\"seatrace-app\",test_case=\"${test_case}\"})")"
    if [[ "$scrape_count" != "na" && "$scrape_count" != "0" ]]; then
      return
    fi
    if (( SECONDS >= deadline )); then
      echo "ERROR: Prometheus did not scrape test_case=${test_case} within 30 seconds" >&2
      return 1
    fi
    sleep 1
  done
}

prepare_users() {
  docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 -v user_prefix="$USER_PREFIX" -v user_count="$USER_COUNT" <<SQL
insert into users (email, password_hash, name, role, status, created_at, updated_at)
select
  :'user_prefix' || '-' || series || '@queue.local',
  (select password_hash from users where email = 'user'),
  'queue-' || series,
  'USER',
  'ACTIVE',
  now(),
  now()
from generate_series(0, :'user_count'::int - 1) as series
where not exists (
  select 1
  from users existing
  where existing.email = :'user_prefix' || '-' || series || '@queue.local'
);
SQL
}

clear_redis_state() {
  docker compose exec -T redis sh -c \
    'redis-cli --scan --pattern "seat-race:*" | xargs -r redis-cli del >/dev/null' \
    >/dev/null
}

reset_event_state() {
  docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 -v event_id="$EVENT_ID" <<'SQL' >/dev/null
update reservation_seats
set active = false
where event_seat_id in (select id from event_seats where event_id = :'event_id'::bigint);

update reservations
set status = 'CANCELLED'
where id in (
  select distinct rs.reservation_id
  from reservation_seats rs
  join event_seats es on es.id = rs.event_seat_id
  where es.event_id = :'event_id'::bigint
);

update event_seats
set status = 'AVAILABLE', held_until = null, version = version + 1, updated_at = now()
where event_id = :'event_id'::bigint;
SQL
}

prom_query() {
  local query="$1"
  local empty_value="${2:-na}"
  local response body status value

  if ! response="$(curl -sS --max-time 5 --get "$PROM_URL/api/v1/query" \
    --data-urlencode "query=$query" \
    --write-out $'\n%{http_code}')"; then
    printf 'Prometheus query transport failure: query=%s\n' "$query" >&2
    printf 'na\n'
    return
  fi

  status="${response##*$'\n'}"
  body="${response%$'\n'*}"
  if [[ "$status" != "200" ]]; then
    printf 'Prometheus query failed: http_status=%s query=%s response=%s\n' \
      "$status" "$query" "$body" >&2
    printf 'na\n'
    return
  fi

  if ! value="$(jq -r 'if .status == "success" and (.data.result | length) > 0 then .data.result[0].value[1] else empty end' \
      <<<"$body")"; then
    printf 'Prometheus query returned invalid JSON: query=%s response=%s\n' "$query" "$body" >&2
    printf 'na\n'
    return
  fi

  if [[ -z "$value" ]]; then
    printf '%s\n' "$empty_value"
  else
    printf '%s\n' "$value"
  fi
}

snapshot_kpis() {
  local test_case="$1"
  local filter="{job=\"seatrace-app\",test_case=\"${test_case}\"}"
  local http_5xx_filter="{job=\"seatrace-app\",test_case=\"${test_case}\",status=~\"5..\"}"
  local queue_features='queueStatus|queueEntry|queueAdvance|queueLease'
  local redis_failure_filter="{job=\"seatrace-app\",test_case=\"${test_case}\",feature=~\"${queue_features}\",reason=\"redis_failure\"}"
  local circuit_open_filter="{job=\"seatrace-app\",test_case=\"${test_case}\",feature=~\"${queue_features}\",reason=\"circuit_open\"}"
  local redis_queue_filter="{job=\"seatrace-app\",test_case=\"${test_case}\",feature=~\"${queue_features}\"}"
  local redis_queue_lua_filter="{job=\"seatrace-app\",test_case=\"${test_case}\",feature=~\"${queue_features}\",operation=\"Lua script\"}"
  local redis_queue_get_filter="{job=\"seatrace-app\",test_case=\"${test_case}\",feature=~\"${queue_features}\",operation=\"GET\"}"
  local heap_filter="{job=\"seatrace-app\",test_case=\"${test_case}\",area=\"heap\"}"
  printf 'hikari_pending_max=%s\n' "$(prom_query "max(max_over_time(hikaricp_connections_pending${filter}[${PROM_WINDOW}]))")"
  printf 'hikari_timeout_total=%s\n' "$(prom_query "sum(hikaricp_connections_timeout_total${filter}) or vector(0)")"
  printf 'http_5xx_total=%s\n' "$(prom_query "sum(http_server_requests_seconds_count${http_5xx_filter}) or vector(0)")"
  printf 'hold_request_total=%s\n' "$(prom_query "sum(seatrace_hold_request_total${filter}) or vector(0)")"
  printf 'hold_fail_total=%s\n' "$(prom_query "sum(seatrace_hold_fail_total${filter}) or vector(0)")"
  printf 'queue_enter_total=%s\n' "$(prom_query "sum(seatrace_queue_enter_total${filter}) or vector(0)")"
  printf 'queue_advance_total=%s\n' "$(prom_query "sum(seatrace_queue_advance_total${filter}) or vector(0)")"
  printf 'queue_wait_total=%s\n' "$(prom_query "sum(seatrace_queue_wait_total${filter}) or vector(0)")"
  printf 'queue_redis_failures=%s\n' "$(prom_query "sum(seatrace_redis_resilience_failure_total${redis_failure_filter}) or vector(0)")"
  printf 'queue_circuit_open=%s\n' "$(prom_query "sum(seatrace_redis_resilience_failure_total${circuit_open_filter}) or vector(0)")"
  for feature in queueStatus queueEntry queueAdvance queueLease; do
    local feature_redis_failure_filter="{job=\"seatrace-app\",test_case=\"${test_case}\",feature=\"${feature}\",reason=\"redis_failure\"}"
    local feature_circuit_open_filter="{job=\"seatrace-app\",test_case=\"${test_case}\",feature=\"${feature}\",reason=\"circuit_open\"}"
    printf 'queue_%s_redis_failures=%s\n' "$feature" "$(prom_query "sum(seatrace_redis_resilience_failure_total${feature_redis_failure_filter}) or vector(0)")"
    printf 'queue_%s_circuit_open=%s\n' "$feature" "$(prom_query "sum(seatrace_redis_resilience_failure_total${feature_circuit_open_filter}) or vector(0)")"
  done
  printf 'queue_redis_operation_total=%s\n' "$(prom_query "sum(seatrace_redis_operation_seconds_count${redis_queue_filter}) or vector(0)")"
  printf 'queue_redis_operation_p95_ms=%s\n' "$(prom_query "histogram_quantile(0.95, sum by (le) (increase(seatrace_redis_operation_seconds_bucket${redis_queue_filter}[${PROM_WINDOW}]))) * 1000")"
  printf 'queue_redis_operation_p99_ms=%s\n' "$(prom_query "histogram_quantile(0.99, sum by (le) (increase(seatrace_redis_operation_seconds_bucket${redis_queue_filter}[${PROM_WINDOW}]))) * 1000")"
  printf 'queue_redis_operation_max_ms=%s\n' "$(prom_query "max(seatrace_redis_operation_seconds_max${redis_queue_filter}) * 1000")"
  printf 'queue_lua_operation_total=%s\n' "$(prom_query "sum(seatrace_redis_operation_seconds_count${redis_queue_lua_filter}) or vector(0)")"
  printf 'queue_lua_operation_p95_ms=%s\n' "$(prom_query "histogram_quantile(0.95, sum by (le) (increase(seatrace_redis_operation_seconds_bucket${redis_queue_lua_filter}[${PROM_WINDOW}]))) * 1000")"
  printf 'queue_lua_operation_p99_ms=%s\n' "$(prom_query "histogram_quantile(0.99, sum by (le) (increase(seatrace_redis_operation_seconds_bucket${redis_queue_lua_filter}[${PROM_WINDOW}]))) * 1000")"
  printf 'queue_lua_operation_max_ms=%s\n' "$(prom_query "max(seatrace_redis_operation_seconds_max${redis_queue_lua_filter}) * 1000")"
  printf 'queue_get_operation_total=%s\n' "$(prom_query "sum(seatrace_redis_operation_seconds_count${redis_queue_get_filter}) or vector(0)")"
  printf 'queue_get_operation_p99_ms=%s\n' "$(prom_query "histogram_quantile(0.99, sum by (le) (increase(seatrace_redis_operation_seconds_bucket${redis_queue_get_filter}[${PROM_WINDOW}]))) * 1000")"
  printf 'queue_heartbeat_total=%s\n' "$(prom_query "sum(seatrace_queue_heartbeat_total${filter}) or vector(0)")"
  printf 'queue_release_total=%s\n' "$(prom_query "sum(seatrace_queue_release_total${filter}) or vector(0)")"
  printf 'process_cpu_max=%s\n' "$(prom_query "max(max_over_time(process_cpu_usage${filter}[${PROM_WINDOW}]))")"
  printf 'heap_used_max_bytes=%s\n' "$(prom_query "max(max_over_time(jvm_memory_used_bytes${heap_filter}[${PROM_WINDOW}]))")"
}

run_phase() {
  local name="$1"
  local queue_enabled="$2"
  local test_case="virtual-queue-${RUN_ID}-${name}"

  echo "== phase=${name} run_id=${RUN_ID} =="
  echo "users=${USER_COUNT} reads_per_user=${READS_PER_USER} holds_per_user=${HOLDS_PER_USER} queue_enabled=${queue_enabled}"

  docker compose stop app nginx >/dev/null 2>&1 || true
  # Reset while no app scheduler can mutate the previous phase's reservations.
  clear_redis_state
  reset_event_state
  METRICS_TEST_CASE="$test_case" \
  VIRTUAL_QUEUE_ENABLED="$queue_enabled" \
  VIRTUAL_QUEUE_TPS="$QUEUE_TPS" \
  VIRTUAL_QUEUE_ACTIVE_LIMIT="$QUEUE_ACTIVE_LIMIT" \
  VIRTUAL_QUEUE_ACTIVE_TTL_SECONDS="$QUEUE_ACTIVE_TTL_SECONDS" \
  VIRTUAL_QUEUE_MAX_ADVANCE_PER_CALL="$QUEUE_MAX_ADVANCE_PER_CALL" \
  EVENT_SEAT_CACHE_LOCAL_TTL_MS=300000 \
  EVENT_SEAT_CACHE_REDIS_TTL_SECONDS=300 \
  docker compose up -d --build --force-recreate --no-deps --scale app=2 app
  # Do not let nginx dependency resolution recreate the app service at scale 1.
  docker compose up -d --no-deps nginx prometheus redis >/dev/null
  wait_for_app
  wait_for_prometheus_scrape "$test_case"
  local phase_started_at
  phase_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  docker compose run --rm --no-deps \
    -e BASE_URL=http://nginx -e EVENT_ID="$EVENT_ID" \
    -e USER_COUNT="$USER_COUNT" -e USER_PREFIX="$USER_PREFIX" -e PASSWORD="$PASSWORD" \
    -e SEAT_ID_FROM="$SEAT_ID_FROM" -e SEAT_ID_TO="$SEAT_ID_TO" \
    -e READS_PER_USER="$READS_PER_USER" -e HOLDS_PER_USER="$HOLDS_PER_USER" \
    -e ADMISSION_TIMEOUT_SECONDS="$ADMISSION_TIMEOUT_SECONDS" -e POLL_INTERVAL_SECONDS="$POLL_INTERVAL_SECONDS" \
    -e ACTIVE_SESSION_SECONDS="$ACTIVE_SESSION_SECONDS" \
    -e HEARTBEAT_INTERVAL_SECONDS="$HEARTBEAT_INTERVAL_SECONDS" \
    -e HEARTBEAT_ENABLED="$HEARTBEAT_ENABLED" -e RELEASE_AFTER_FLOW="$RELEASE_AFTER_FLOW" \
    k6 run --summary-trend-stats 'avg,p(90),p(95),p(99),min,max' /scripts/virtual_queue_admission.js

  echo "waiting ${SCRAPE_WAIT_SECONDS}s for Prometheus scrape"
  sleep "$SCRAPE_WAIT_SECONDS"
  echo "[prometheus phase=${name}]"
  snapshot_kpis "$test_case"
  echo "[app failures phase=${name}]"
  docker compose logs --since "$phase_started_at" app 2>&1 \
    | rg 'VirtualQueue|RedisCommandTimeout|RedisConnection|Bulkhead|CallNotPermitted|Redis .*실패' \
    | tail -80 || true
  echo
}

docker compose up -d postgres redis prometheus >/dev/null
wait_for_postgres
prepare_users

run_phase queue_off false
run_phase queue_on true
