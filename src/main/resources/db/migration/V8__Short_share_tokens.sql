-- Enforce uniqueness for new short share codes and clear legacy tokens
DELETE FROM share_token_entity;

CREATE UNIQUE INDEX IF NOT EXISTS idx_share_token_unique ON share_token_entity (share_token);
