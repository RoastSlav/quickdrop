ALTER TABLE app_settings
    ADD COLUMN notify_on_storage_down INTEGER DEFAULT 1;
ALTER TABLE app_settings
    ADD COLUMN notify_on_storage_up INTEGER DEFAULT 1;
