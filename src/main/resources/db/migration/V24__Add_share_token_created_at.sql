ALTER TABLE share_token_entity
    ADD COLUMN created_at DATETIME;
UPDATE share_token_entity
SET created_at = CURRENT_TIMESTAMP
WHERE created_at IS NULL;
