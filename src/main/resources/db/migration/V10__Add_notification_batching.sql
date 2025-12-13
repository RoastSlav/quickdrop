ALTER TABLE application_settings_entity
    ADD COLUMN notification_batch_enabled BOOLEAN NOT NULL DEFAULT 0;

ALTER TABLE application_settings_entity
    ADD COLUMN notification_batch_minutes INTEGER NOT NULL DEFAULT 5;
