ALTER TABLE share_token_entity ADD COLUMN public_id VARCHAR(16);
ALTER TABLE share_token_entity ADD COLUMN wrapped_dek VARCHAR(512);
ALTER TABLE share_token_entity ADD COLUMN wrap_nonce VARCHAR(128);
ALTER TABLE share_token_entity ADD COLUMN secret_hash VARCHAR(128);
ALTER TABLE share_token_entity ADD COLUMN encryption_version INTEGER;
ALTER TABLE share_token_entity ADD COLUMN token_mode VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_share_token_public_id ON share_token_entity(public_id);
