ALTER TABLE application_settings_entity
    ADD COLUMN disable_preview INTEGER NOT NULL DEFAULT 0;
ALTER TABLE application_settings_entity
    ADD COLUMN max_preview_size_bytes BIGINT NOT NULL DEFAULT 5242880;