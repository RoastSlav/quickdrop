ALTER TABLE application_settings_entity ADD COLUMN simplified_share_links BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE application_settings_entity ADD COLUMN disable_share_links BOOLEAN NOT NULL DEFAULT FALSE;
