ALTER TABLE share_token_entity
    ADD COLUMN sidecar_ready BOOLEAN NOT NULL DEFAULT TRUE;
