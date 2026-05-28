-- Align DB table and column names with the Java entity renames carried out in the
-- same release cycle.  No data is changed; only schema names are updated.
--
-- SQLite notes:
--   * ALTER TABLE … RENAME TO  (all versions) — also rewrites FK references in other
--     tables' stored schemas as of SQLite 3.26.0 (2018-12-01).
--   * ALTER TABLE … RENAME COLUMN … TO … requires SQLite 3.25.0+ (2018-09-15).
--   * Indexes are not automatically renamed; they are dropped and recreated.

-- ---------------------------------------------------------------------------
-- 1. file_history_log → activity_log
--    Matches the ActivityLog Java entity (previously shimmed with @Table).
-- ---------------------------------------------------------------------------
ALTER TABLE file_history_log RENAME TO activity_log;

DROP INDEX IF EXISTS idx_file_history_file;
DROP INDEX IF EXISTS idx_file_history_type;

CREATE INDEX IF NOT EXISTS idx_activity_log_file_id    ON activity_log (file_id);
CREATE INDEX IF NOT EXISTS idx_activity_log_event_type ON activity_log (event_type);

-- ---------------------------------------------------------------------------
-- 2. file_entity → upload
--    Both regular files and pastes are uploads; removes the ORM-leaking
--    _entity suffix that leaked into the DB name.
-- ---------------------------------------------------------------------------
ALTER TABLE file_entity RENAME TO upload;

DROP INDEX IF EXISTS idx_file_entity_uuid;
DROP INDEX IF EXISTS idx_file_entity_keep_upload;
DROP INDEX IF EXISTS idx_file_entity_deleted;

CREATE INDEX IF NOT EXISTS idx_upload_uuid            ON upload (uuid);
CREATE INDEX IF NOT EXISTS idx_upload_keep_upload_date ON upload (keep_indefinitely, upload_date);
CREATE INDEX IF NOT EXISTS idx_upload_deleted          ON upload (deleted);

-- ---------------------------------------------------------------------------
-- 3. upload.is_paste → upload.paste
--    Makes the boolean column name consistent with all other boolean columns
--    in the table (hidden, encrypted, deleted, keep_indefinitely, …) which
--    do not carry the is_ prefix.
-- ---------------------------------------------------------------------------
ALTER TABLE upload RENAME COLUMN is_paste TO paste;

-- ---------------------------------------------------------------------------
-- 4. share_token_entity → share_token
--    Removes the _entity suffix.
-- ---------------------------------------------------------------------------
ALTER TABLE share_token_entity RENAME TO share_token;

DROP INDEX IF EXISTS idx_share_token_entity_token;

CREATE INDEX IF NOT EXISTS idx_share_token_token ON share_token (share_token);

-- ---------------------------------------------------------------------------
-- 5. application_settings_entity → app_settings
--    Removes the _entity suffix and shortens the name.
-- ---------------------------------------------------------------------------
ALTER TABLE application_settings_entity RENAME TO app_settings;
