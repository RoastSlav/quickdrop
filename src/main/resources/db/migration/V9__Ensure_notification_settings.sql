ALTER TABLE application_settings_entity
    ADD COLUMN discord_webhook_enabled BOOLEAN NOT NULL DEFAULT 0;

ALTER TABLE application_settings_entity
    ADD COLUMN discord_webhook_url VARCHAR(512) DEFAULT '';

ALTER TABLE application_settings_entity
    ADD COLUMN email_notifications_enabled BOOLEAN NOT NULL DEFAULT 0;

ALTER TABLE application_settings_entity
    ADD COLUMN email_from VARCHAR(255) DEFAULT '';

ALTER TABLE application_settings_entity
    ADD COLUMN email_to VARCHAR(512) DEFAULT '';

ALTER TABLE application_settings_entity
    ADD COLUMN smtp_host VARCHAR(255) DEFAULT '';

ALTER TABLE application_settings_entity
    ADD COLUMN smtp_port INTEGER DEFAULT 587;

ALTER TABLE application_settings_entity
    ADD COLUMN smtp_username VARCHAR(255) DEFAULT '';

ALTER TABLE application_settings_entity
    ADD COLUMN smtp_password VARCHAR(255) DEFAULT '';

ALTER TABLE application_settings_entity
    ADD COLUMN smtp_use_tls BOOLEAN NOT NULL DEFAULT 1;
