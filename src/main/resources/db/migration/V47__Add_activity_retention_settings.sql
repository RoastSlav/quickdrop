-- Ships disabled so an upgrade never starts discarding history on its own.
-- Once enabled, each category keeps a year by default; 0 means keep forever.
ALTER TABLE app_settings ADD COLUMN activity_retention_enabled BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE app_settings ADD COLUMN activity_retention_cron TEXT NOT NULL DEFAULT '0 30 3 * * *';
ALTER TABLE app_settings ADD COLUMN activity_retention_file_days INTEGER NOT NULL DEFAULT 365;
ALTER TABLE app_settings ADD COLUMN activity_retention_paste_days INTEGER NOT NULL DEFAULT 365;
ALTER TABLE app_settings ADD COLUMN activity_retention_share_days INTEGER NOT NULL DEFAULT 365;
ALTER TABLE app_settings ADD COLUMN activity_retention_shortlink_days INTEGER NOT NULL DEFAULT 365;
ALTER TABLE app_settings ADD COLUMN activity_retention_admin_days INTEGER NOT NULL DEFAULT 365;
ALTER TABLE app_settings ADD COLUMN activity_retention_system_days INTEGER NOT NULL DEFAULT 365;
