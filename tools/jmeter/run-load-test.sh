#!/usr/bin/env bash
set -eu

HOST=${HOST:-http://localhost:8080}
EVENT_ID=${EVENT_ID:-1}
THREADS=${THREADS:-250}
REQUESTS=${REQUESTS:-10000}
USERNAME=${USERNAME:-user}
PASSWORD=${PASSWORD:-1234}
PAYLOAD=${PAYLOAD:-'{"seatIds":[1]}'}

if ! command -v hey >/dev/null 2>&1; then
  echo "Error: please install https://github.com/rakyll/hey before running this script."
  exit 1
fi

login_payload="username=${USERNAME}&password=${PASSWORD}"
TOKEN=$(curl -s -X POST "${HOST}/login" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "${login_payload}" | python3 - <<'PY'
import json, sys
try:
    data = json.load(sys.stdin)
except json.JSONDecodeError:
    sys.exit('login json parse failed: ' + sys.stdin.read())
token = data.get('accessToken') or data.get('token')
if not token:
    sys.exit('no accessToken/token in login response')
print(token)
PY
)

cat <<-MSG
Using host          : ${HOST}
Event ID           : ${EVENT_ID}
Thread count       : ${THREADS}
Request count      : ${REQUESTS}
Authenticated user : ${USERNAME}
MSG

auth_header="Authorization: Bearer ${TOKEN}"

# Optional pre-entry to warm the queue and avoid repeated token refresh in the load burst.
curl -s -o /dev/null -w "%{http_code}\n" -X POST "${HOST}/api/events/${EVENT_ID}/queue/enter" \
  -H "Content-Type: application/json" \
  -H "${auth_header}"

echo "Running ${REQUESTS} concurrent requests against /api/events/${EVENT_ID}/holds"

hey -n "${REQUESTS}" -c "${THREADS}" \
  -H "Content-Type: application/json" \
  -H "${auth_header}" \
  -m POST \
  -d "${PAYLOAD}" \
  "${HOST}/api/events/${EVENT_ID}/holds"

cat <<'STATS'
Redis queue sizes and admission counters (adjust for your Redis host/port):
redis-cli -h ${REDIS_HOST:-localhost} -p ${REDIS_PORT:-6379} zcard seat-race:queue:wait:${EVENT_ID}
redis-cli -h ${REDIS_HOST:-localhost} -p ${REDIS_PORT:-6379} zcard seat-race:queue:active:${EVENT_ID}
redis-cli -h ${REDIS_HOST:-localhost} -p ${REDIS_PORT:-6379} get seat-race:queue:gate:${EVENT_ID}:$(date +%s)
curl -s ${HOST}/actuator/metrics/seatrace.queue.enter.total
curl -s ${HOST}/actuator/metrics/seatrace.queue.admit.total
curl -s ${HOST}/actuator/metrics/seatrace.queue.advance.total
STATS
