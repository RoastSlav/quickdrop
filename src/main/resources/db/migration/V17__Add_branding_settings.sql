ALTER TABLE application_settings_entity
    ADD COLUMN app_name TEXT DEFAULT 'QuickDrop';
ALTER TABLE application_settings_entity
    ADD COLUMN logo_file_name TEXT;