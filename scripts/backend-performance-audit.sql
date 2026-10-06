-- Read-only production audit; execute against the deployed database with psql.
-- No user records, credentials, or message bodies are returned.
BEGIN READ ONLY;

SELECT version, description, installed_on, success
FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;

SELECT tablename, indexname, indexdef FROM pg_indexes
WHERE schemaname = 'public' AND tablename IN
  ('schedule_shift', 'staff_shift', 'availability_request', 'call_history',
   'chat_message', 'chat_room_member', 'user_profile', 'admin_profile')
ORDER BY tablename, indexname;

SELECT relname, n_live_tup, n_dead_tup, seq_scan, idx_scan,
       last_autovacuum, last_autoanalyze
FROM pg_stat_user_tables ORDER BY n_live_tup DESC;

SELECT relname, indexrelname, idx_scan, pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
FROM pg_stat_user_indexes ORDER BY relname, indexrelname;

SELECT state, wait_event_type, wait_event, count(*) AS connections
FROM pg_stat_activity WHERE datname = current_database()
GROUP BY state, wait_event_type, wait_event ORDER BY connections DESC;

SELECT extname FROM pg_extension WHERE extname = 'pg_stat_statements';
COMMIT;
