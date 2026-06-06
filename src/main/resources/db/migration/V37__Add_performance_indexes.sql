-- Performance indexes for activity_log table
-- These indexes are also defined via @Table(indexes=...) in ActivityLog.java for new installs.
-- This migration adds them to existing databases.

CREATE INDEX IF NOT EXISTS idx_activity_log_event_type ON activity_log (event_type);
CREATE INDEX IF NOT EXISTS idx_activity_log_event_date ON activity_log (event_date);
CREATE INDEX IF NOT EXISTS idx_activity_log_file_date ON activity_log (file_id, event_date);

-- Index for upload UUID lookups (most common query pattern)
-- Should already exist from V27/V31 migrations, but ensuring it's present
CREATE INDEX IF NOT EXISTS idx_upload_uuid ON upload (uuid);

-- Index on share_token for token lookups
CREATE INDEX IF NOT EXISTS idx_share_token_lookup ON share_token (share_token);
