# Virtual Queue Benchmark

This benchmark compares the same burst of authenticated users with virtual
queue admission disabled and enabled. It measures whether the queue converts
excess reservation traffic into waiting rather than server-side saturation.

## Problem, Fix, Verify

1. Without admission control, a simultaneous reservation burst reaches seat
   reads and holds immediately and can saturate the application and DB pool.
2. The original queue reduced protected traffic, but each queue enter/status
   request made several Redis round trips. Under a burst, Redis command failures
   could open the `queueAdmission` Circuit Breaker and return `503` instead of
   a queue wait response.
3. Queue enter, status, and advancement now use short Lua scripts to perform
   each Redis state transition atomically in one round trip. The benchmark also
   polls queue status once per second instead of five times per second.
4. Re-run the same queue-off/queue-on burst. A valid result requires no queue
   admission rejection or Circuit Breaker opening, while admitted users retain
   successful reads/holds and the server avoids HikariCP saturation.

## Scope

- Protected endpoints: `GET /api/events/{eventId}/seats` and
  `POST /api/events/{eventId}/holds`.
- Each user is assigned a unique seat. Hold failures therefore indicate an
  application or infrastructure problem, not intentional seat contention.
- Seat cache is enabled in both phases. The comparison is about admission
  control, not cache versus database performance.
- `queue_on` admits users at `QUEUE_TPS` and keeps at most
  `QUEUE_ACTIVE_LIMIT` active users. The remainder poll the queue status.

The current controller guards the seat-list and hold APIs. Reservation
confirmation and cancellation are not part of this benchmark until they also
enforce the event admission token.

## Lease Lifecycle

An active admission is a lease, not a fixed reservation of capacity. The client
renews an active lease through `POST /api/events/{eventId}/queue/heartbeat` and
returns it through `DELETE /api/events/{eventId}/queue/active` when leaving the
reservation flow. Both operations validate the admission token and update the
Redis active set and token TTL in one Lua script.

Run two separate comparisons:

1. Queue protection: keep `HEARTBEAT_ENABLED=false` and
   `RELEASE_AFTER_FLOW=false`, then compare `queue_off` and `queue_on`.
2. Lease recovery: keep the queue enabled and compare a fixed lease with
   `RELEASE_AFTER_FLOW=false` against immediate return with
   `RELEASE_AFTER_FLOW=true`. Compare admission wait p95 and
   `queue_release_total`.

To verify renewal itself, set `ACTIVE_SESSION_SECONDS` longer than
`QUEUE_ACTIVE_TTL_SECONDS`. The no-heartbeat run should lose admission before
its protected request; the heartbeat run should retain it and report a 100%
`heartbeat_ok_rate`.

## Run

Create an event with enough seats first. The default test needs 200 seats.

```bash
SEAT_COUNT=300 ./tools/jmeter/prepare-random-seat-hold-data.sh
```

Build with the Java version configured by the project, then run both phases.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew bootJar

EVENT_ID=<printed_event_id> SEAT_ID_FROM=<printed_first_seat_id> \
SEAT_ID_TO=<printed_last_seat_id> USER_COUNT=200 \
QUEUE_TPS=40 QUEUE_ACTIVE_LIMIT=80 QUEUE_ACTIVE_TTL_SECONDS=15 \
./tools/jmeter/run-virtual-queue-compare.sh 2>&1 | \
tee tools/log/virtual-queue-compare-$(date +%Y%m%d-%H%M%S).log
```

## Interpret Results

Compare the `queue_off` and `queue_on` summaries.

- k6: admitted users, admission wait p95, protected read/hold success rate,
  and protected read/hold p95.
- Prometheus: `hikari_pending_max`, `hikari_timeout_total`, `http_5xx_total`, and
  hold request/failure counters.
- Queue-on should have nonzero queue-enter, advance, and wait counters. The
  expected tradeoff is a longer admission wait for excess users, while admitted
  users keep successful protected requests and the application avoids HikariCP
  pool saturation or 5xx responses.

Do not claim an improvement merely because the queue-on phase has lower total
throughput. It is valid only when it limits overload while preserving the
success rate and latency of users admitted to the reservation flow.
