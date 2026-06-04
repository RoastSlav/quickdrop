-- storage_backend, s3_endpoint, s3_region already exist from a partial earlier attempt.
-- Rename the three columns that have different names, then add the two new ones.
ALTER TABLE app_settings RENAME COLUMN s3_bucket_name      TO s3_bucket;
ALTER TABLE app_settings RENAME COLUMN s3_access_key_id    TO s3_access_key;
ALTER TABLE app_settings RENAME COLUMN s3_secret_access_key TO s3_secret_key;
ALTER TABLE app_settings ADD COLUMN s3_path_style  INTEGER DEFAULT 0;
ALTER TABLE app_settings ADD COLUMN s3_key_prefix  TEXT    DEFAULT '';
