-- S3 / storage-backend settings.
-- Written to work on a completely fresh database (no pre-existing S3 columns).
ALTER TABLE app_settings
    ADD COLUMN storage_backend TEXT DEFAULT 'LOCAL';
ALTER TABLE app_settings
    ADD COLUMN s3_endpoint TEXT;
ALTER TABLE app_settings
    ADD COLUMN s3_region TEXT DEFAULT 'us-east-1';
ALTER TABLE app_settings
    ADD COLUMN s3_bucket TEXT;
ALTER TABLE app_settings
    ADD COLUMN s3_access_key TEXT;
ALTER TABLE app_settings
    ADD COLUMN s3_secret_key TEXT;
ALTER TABLE app_settings
    ADD COLUMN s3_path_style INTEGER DEFAULT 0;
ALTER TABLE app_settings
    ADD COLUMN s3_key_prefix TEXT DEFAULT '';
