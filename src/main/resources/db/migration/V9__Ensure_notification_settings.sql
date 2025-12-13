-- Re-apply notification settings columns with idempotent guards for upgraded deployments
ALTER TABLE application_settings_entity
    ADD COLUMN IF NOT EXISTS keep_indefinitely_admin_only BOOLEAN NOT NULL DEFAULT 0;

ALTER TABLE application_settings_entity
    ADD COLUMN IF NOT EXISTS discord_webhook_enabled BOOLEAN NOT NULL DEFAULT 0;

ALTER TABLE application_settings_entity
    ADD COLUMN IF NOT EXISTS discord_webhook_url VARCHAR(512) DEFAULT '';

ALTER TABLE application_settings_entity
    ADD COLUMN IF NOT EXISTS email_notifications_enabled BOOLEAN NOT NULL DEFAULT 0;

ALTER TABLE application_settings_entity
    ADD COLUMN IF NOT EXISTS email_from VARCHAR(255) DEFAULT '';

ALTER TABLE application_settings_entity
    ADD COLUMN IF NOT EXISTS email_to VARCHAR(512) DEFAULT '';

ALTER TABLE application_settings_entity
    ADD COLUMN IF NOT EXISTS smtp_host VARCHAR(255) DEFAULT '';

ALTER TABLE application_settings_entity
    ADD COLUMN IF NOT EXISTS smtp_port INTEGER DEFAULT 587;

ALTER TABLE application_settings_entity
    ADD COLUMN IF NOT EXISTS smtp_username VARCHAR(255) DEFAULT '';

ALTER TABLE application_settings_entity
    ADD COLUMN IF NOT EXISTS smtp_password VARCHAR(255) DEFAULT '';

ALTER TABLE application_settings_entity
    ADD COLUMN IF NOT EXISTS smtp_use_tls BOOLEAN NOT NULL DEFAULT 1;
