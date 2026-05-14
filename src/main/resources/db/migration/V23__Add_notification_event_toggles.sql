ALTER TABLE application_settings_entity
    ADD COLUMN notify_on_upload BOOLEAN DEFAULT 1;
ALTER TABLE application_settings_entity
    ADD COLUMN notify_on_download BOOLEAN DEFAULT 1;
ALTER TABLE application_settings_entity
    ADD COLUMN notify_on_renewal BOOLEAN DEFAULT 1;
ALTER TABLE application_settings_entity
    ADD COLUMN notify_on_deletion BOOLEAN DEFAULT 1;
ALTER TABLE application_settings_entity
    ADD COLUMN notify_on_share_create BOOLEAN DEFAULT 1;
ALTER TABLE application_settings_entity
    ADD COLUMN notify_on_share_download BOOLEAN DEFAULT 0;
ALTER TABLE application_settings_entity
    ADD COLUMN notify_on_paste_create BOOLEAN DEFAULT 1;
ALTER TABLE application_settings_entity
    ADD COLUMN notify_on_paste_view BOOLEAN DEFAULT 0;
ALTER TABLE application_settings_entity
    ADD COLUMN notify_on_paste_edit BOOLEAN DEFAULT 1;
