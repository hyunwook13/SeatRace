#!/usr/bin/env bash
set -euo pipefail

# Creates a fresh venue, seats, event, and event_seats for random hold load tests.
# It does not delete existing data.
#
# Example:
#   SEAT_COUNT=500 ./tools/prepare-random-seat-hold-data.sh
#
# Then run the printed command:
#   EVENT_ID=... SEAT_ID_FROM=... SEAT_ID_TO=... ./tools/run-random-seat-hold.sh

SEAT_COUNT="${SEAT_COUNT:-500}"
SECTION="${SECTION:-LOAD}"
GRADE="${GRADE:-STANDARD}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d%H%M%S)}"
DB_USER="${DB_USER:-seatrace}"
DB_NAME="${DB_NAME:-seatrace}"

if ! [[ "$SEAT_COUNT" =~ ^[0-9]+$ ]] || [ "$SEAT_COUNT" -lt 1 ]; then
  echo "SEAT_COUNT must be a positive integer" >&2
  exit 1
fi

docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" \
  -v ON_ERROR_STOP=1 \
  -v run_id="$RUN_ID" \
  -v seat_count="$SEAT_COUNT" \
  -v section="$SECTION" \
  -v grade="$GRADE" <<'SQL'
with created_venue as (
  insert into venues (name, location, created_at, updated_at)
  values (
    'Load Test Venue ' || :'run_id',
    'load-test-' || :'run_id',
    now(),
    now()
  )
  returning id
),
created_section as (
  insert into seat_sections (venue_id, name, created_at, updated_at)
  select id, :'section', now(), now()
  from created_venue
  returning id
),
created_seats as (
  insert into seats (seat_section_id, row_no, seat_number, created_at, updated_at)
  select
    created_section.id,
    lpad((((seq - 1) / 50) + 1)::text, 2, '0'),
    ((seq - 1) % 50) + 1,
    now(),
    now()
  from created_section
  cross join generate_series(1, :'seat_count'::int) as seq
  returning id
),
created_event as (
  insert into events (venue_id, name, start_at, end_at, status, created_at, updated_at)
  select
    id,
    'Load Test Event ' || :'run_id',
    now() + interval '1 day',
    now() + interval '1 day 2 hours',
    'OPEN',
    now(),
    now()
  from created_venue
  returning id, venue_id
),
created_event_seats as (
  insert into event_seats (event_id, seat_id, status, held_until, version, created_at, updated_at)
  select
    created_event.id,
    created_seats.id,
    'AVAILABLE',
    null,
    0,
    now(),
    now()
  from created_event
  cross join created_seats
  returning event_id, seat_id
)
select
  event_id,
  min(seat_id) as seat_id_from,
  max(seat_id) as seat_id_to,
  count(*) as event_seat_count,
  'EVENT_ID=' || event_id
    || ' SEAT_ID_FROM=' || min(seat_id)
    || ' SEAT_ID_TO=' || max(seat_id)
    || ' ./tools/run-random-seat-hold.sh' as run_command
from created_event_seats
group by event_id;
SQL
