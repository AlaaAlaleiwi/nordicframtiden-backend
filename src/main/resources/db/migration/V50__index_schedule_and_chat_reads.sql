-- Equality filters followed by overlap bounds for personal/organisation schedules
-- and payroll. Existing time-only indexes remain useful for all-user dashboards.
CREATE INDEX IF NOT EXISTS idx_schedule_shift_user_range
  ON schedule_shift (user_id, start_at, end_at);
CREATE INDEX IF NOT EXISTS idx_schedule_shift_pharmacy_range
  ON schedule_shift (pharmacy_id, start_at, end_at);
CREATE INDEX IF NOT EXISTS idx_staff_shift_user_range
  ON staff_shift (user_id, start_at, end_at);

-- Dashboard call windows are global, unlike the existing room-first index.
CREATE INDEX IF NOT EXISTS ix_call_history_started
  ON call_history (started_at DESC);

-- Room timelines and unread counts only include root messages; INCLUDE permits
-- unread sender filtering without retrieving message bodies from the heap.
CREATE INDEX IF NOT EXISTS ix_chat_message_root_room
  ON chat_message (room_id, id DESC) INCLUDE (sender_id)
  WHERE parent_message_id IS NULL;
