CREATE INDEX IF NOT EXISTS idx_file_entity_uuid ON file_entity(uuid);
CREATE INDEX IF NOT EXISTS idx_file_entity_keep_upload ON file_entity(keep_indefinitely, upload_date);
CREATE INDEX IF NOT EXISTS idx_share_token_entity_token ON share_token_entity(share_token);
