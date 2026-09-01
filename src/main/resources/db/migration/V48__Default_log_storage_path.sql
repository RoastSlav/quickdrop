UPDATE app_settings
SET log_storage_path = 'log'
WHERE log_storage_path IS NULL
   OR TRIM(log_storage_path) = ''
   OR log_storage_path = 'logs';
