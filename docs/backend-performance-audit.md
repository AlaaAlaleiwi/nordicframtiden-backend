# Backend read performance

The repository audit found repeated chat queries and indexes that did not match
some common filters. Availability already has projection queries and the V49
user/created-date and status/date indexes. Schedule and salary controllers already
batch user-profile summaries; dashboard KPI queries already reuse request-local
results.

## Changes

- Chat participants: one scalar projection joins user/admin names and photo IDs.
  It excludes disabled accounts and the viewer in SQL, preserves user-profile
  name priority and username fallback, and avoids loading password hashes,
  roles, permissions, or encrypted photo data.
- Chat room lists: load memberships once, participant details once, and unread
  counts once for the visible room IDs. These three queries replace repeated
  membership/profile/photo/unread lookups. Room discovery, ownership, channel
  administration and read cursors retain their existing behavior. These counts
  exclude authentication and the two existing room-visibility queries.
- Single-room responses reuse their membership lookup and use the already loaded
  user's photo ID rather than looking the user up again.
- V50 adds `(user_id, start_at, end_at)` indexes for both schedule tables,
  `(pharmacy_id, start_at, end_at)` for pharmacy schedules, `started_at` for global
  call-history windows, and a partial `(room_id, id)` root-message index including
  `sender_id` for timelines and unread counts. Existing indexes are retained.

These are query-count and access-path improvements, not a measured production
latency claim. The time indexes support the first range bound; the remaining
overlap bound is still checked. PostgreSQL may prefer a sequential scan for small
tables or broad date windows.

## Verification and rollout

Run `mvn verify` against PostgreSQL using the test profile's datasource overrides.
`ChatReadRepositoryIT` checks projection fallbacks, disabled/current-user filters,
photos, one SQL statement without entity loads, membership/read-cursor/sender/thread
unread behavior, and the migration's five indexes. Controller tests check batching,
sorting, channel permissions, discovery and empty lists.

Flyway applies V50 during the next backend deployment. Regular `CREATE INDEX`
builds can block writes while each index is built; check production table sizes
and schedule deployment accordingly. For large tables, use a reviewed concurrent
index rollout before deployment instead of building large indexes during startup.

## Remaining production measurements

Production database access and Cloud Run logs were unavailable in this workspace;
the Google Cloud CLI is not installed. The production index state, database load,
query plans, cold starts and endpoint p50/p95 remain unverified.

Run `scripts/backend-performance-audit.sql` with read-only database credentials to
check deployed Flyway version, index definitions/usage, approximate table sizes,
autovacuum/analyze activity and connection waits without exposing user records.
If `pg_stat_statements` is available, inspect normalized query totals and mean
execution times under an account permitted to read that view. Use representative
date windows and actual filter IDs for `EXPLAIN (ANALYZE, BUFFERS)` on read queries.
Compare warm and cold request latency after deployment, including database time
and Cloud Run startup time, before changing pool sizes, instance limits or caching.
