-- Supports a user's availability list while preserving newest-first ordering.
CREATE INDEX IF NOT EXISTS idx_availability_user_created
  ON availability_request (user_id, created_at DESC);

-- Supports status-filtered date-overlap searches used by the scheduling UI.
CREATE INDEX IF NOT EXISTS idx_availability_status_start_end
  ON availability_request (status, start_date, end_date);
