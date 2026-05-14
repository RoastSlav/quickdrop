-- Backfill any share tokens that were created after V24 but before the
-- ShareTokenEntity constructor was updated to set createdAt automatically.
UPDATE share_token_entity
SET created_at = datetime('now')
WHERE created_at IS NULL;
