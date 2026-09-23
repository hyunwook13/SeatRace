# Redis Resilience Policy

## Goal

Redis is shared by the seat cache, virtual queue, reservation hold helpers, and
distributed locks. A shared Redis deployment does not require a shared failure
policy: each business role has a separate Circuit Breaker and Bulkhead.

| Role | Resilience name | Redis failure policy |
| --- | --- | --- |
| Seat cache | `seatCache` | Skip Redis and use the existing local-cache/DB read path. |
| Queue status | `queueStatus` | Return `503` when the queue state cannot be read safely. |
| Queue entry | `queueEntry` | Reject admission with `503`; clients may retry a temporary failure. |
| Queue advancement | `queueAdvance` | Stop advancing users rather than corrupting queue state. |
| Queue lease | `queueLease` | Reject heartbeat, release, and token validation with `503`. |
| Reservation hold helpers | `reservationHold` | Preserve the existing best-effort hold-key behavior. |
| Hold expiration stream | `holdStream` | Preserve the existing best-effort stream processing behavior. |
| Reservation lock | `reservationLock` | Reject the mutation with `503`; never continue after a Redis/Breaker failure. |

The shared Circuit Breaker named `redisCore` must not be reintroduced. Queue
status, entry, advancement, and lease failures are also isolated from one
another so a burst of entry retries cannot open the advancement breaker.

## Degraded Profile

The `degraded` profile is an explicit read-only mode for a Redis outage.

- It disables the Redis-backed virtual queue and distributed lock.
- Redisson is not created, so the application can start without Redis.
- Seat reads can use the cache fallback path.
- Queue APIs and reservation mutations return `503 REDIS_UNAVAILABLE`.

It is a controlled fallback mode, not a way to continue reservation writes
without distributed coordination.

## Circuit Breaker Settings

Each Redis role opens after at least five of the latest ten calls have been
observed and 50 percent have failed. It stays open for five seconds, then
permits three half-open calls to check recovery.

## Verification

Run `tools/jmeter/run-chaos-redis-fallback.sh` after building the application
JAR. The script records three read scenarios with identical load:

1. Redis healthy and caches warmed.
2. Redis stopped while local caches remain warm.
3. App instances restarted while Redis is healthy to empty local caches, then
   Redis stopped before the first read.

Compare success rate, p95/p99 latency, HikariCP pending connections, DB seat
loads, and the cache fallback/bypass counters. Separately verify that queue and
reservation APIs return `503` in the `degraded` profile.
