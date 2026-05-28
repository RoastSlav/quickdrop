-- Add soft-delete support: files marked deleted=1 are removed from disk
-- but kept in the database so history logs retain their FK and admins can
-- still view the record.  All existing rows get deleted=0 (live) by default.

ALTER TABLE file_entity ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_file_entity_deleted ON file_entity (deleted);
