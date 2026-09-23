#!/usr/bin/env bash
set -euo pipefail

# Compare DB connection pool pressure with/without distributed lock (Redisson).
#
# Requires:
# - prometheus service running (default: http://localhost:9090)
# - app scaled to 2 behind nginx (compose service name: app)
#
# Example:
#   EVENT_ID=1 SEAT_ID=1 RPS=100 DURATION=10s ./tools/run-lock-hikari-compare.sh

EVENT_ID="${EVENT_ID:-1}"
SEAT_ID="${SEAT_ID:-1}"
RPS="${RPS:-100}"
DURATION="${DURATION:-10s}"
PREALLOCATED_VUS="${PREALLOCATED_VUS:-100}"
MAX_VUS="${MAX_VUS:-200}"

PROM_URL="${PROM_URL:-http://localhost:9090}"
PROM_WINDOW="${PROM_WINDOW:-30s}"

prom_query() {
  local q="$1"
  curl -fsS --max-time 2 \
    --get "$PROM_URL/api/v1/query" \
    --data-urlencode "query=$q" \
    | jq -r '.data.result[0].value[1] // "na"' 2>/dev/null || echo "na"
}

snapshot_hikari() {
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
}

wait_until_up() {
  local url="http://localhost:8080/actuator/health"
  local login_url="http://localhost:8080/login"
  local deadline=$((SECONDS + 90))
  local consecutive_ok=0
  while [ "$SECONDS" -lt "$deadline" ]; do
    local health_ok="false"
    local login_ok="false"

    if curl -fsS --max-time 2 "$url" 2>/dev/null | grep -q '"status":"UP"'; then
      health_ok="true"
    fi

    # Nginx may still load-balance to a not-yet-ready app container.
    # Require a few consecutive successful login calls to reduce flakiness.
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

  echo "ERROR: app not ready within 90s (need stable health+login): $url" >&2
  echo "--- nginx logs (tail) ---" >&2
  docker compose logs --tail=80 nginx >&2 || true
  echo "--- app logs (tail) ---" >&2
  docker compose logs --tail=120 app >&2 || true
  return 1
}

run_phase() {
  local name="$1"
  shift

  echo "== phase=$name start=$(date) =="
  echo "compose_env: $*"

  # Recreate only the app containers to apply env var toggles; keep nginx stable to avoid transient 502s.
  eval "$*" docker compose up -d --build --force-recreate --no-deps --scale app=2 app >/dev/null
  docker compose up -d --no-deps nginx prometheus >/dev/null || true

  wait_until_up

  # Let prometheus scrape once
  sleep 6

  echo "[before]"
  snapshot_hikari

  docker compose run --rm --no-deps \
    -e BASE_URL=http://nginx -e EVENT_ID="$EVENT_ID" -e SEAT_ID="$SEAT_ID" \
    -e RPS="$RPS" -e DURATION="$DURATION" -e PREALLOCATED_VUS="$PREALLOCATED_VUS" -e MAX_VUS="$MAX_VUS" \
    k6 run --summary-trend-stats "avg,p(90),p(95),p(99),min,max" /scripts/lock_hold.js

  echo "[after]"
  snapshot_hikari
  echo "== phase=$name end=$(date) =="
  echo
}

# Isolate hold logic by disabling virtual queue.
run_phase "baseline_lock_off" \
  "RESERVATION_LOCK_ENABLED=false RESERVATION_LOCK_REQUIRED=false VIRTUAL_QUEUE_ENABLED=false LOG_LEVEL_HIKARI=INFO"

run_phase "distributed_lock_on_required" \
  "RESERVATION_LOCK_ENABLED=true RESERVATION_LOCK_REQUIRED=true VIRTUAL_QUEUE_ENABLED=false LOG_LEVEL_HIKARI=INFO"
