-- Rename negatively-named boolean columns to positive equivalents and invert values

ALTER TABLE application_settings_entity RENAME COLUMN disable_encryption       TO encryption_enabled;
UPDATE application_settings_entity SET encryption_enabled    = NOT encryption_enabled;

ALTER TABLE application_settings_entity RENAME COLUMN disable_upload_password  TO upload_password_enabled;
UPDATE application_settings_entity SET upload_password_enabled = NOT upload_password_enabled;

ALTER TABLE application_settings_entity RENAME COLUMN disable_preview           TO preview_enabled;
UPDATE application_settings_entity SET preview_enabled       = NOT preview_enabled;

ALTER TABLE application_settings_entity RENAME COLUMN share_links_disabled      TO share_links_enabled;
UPDATE application_settings_entity SET share_links_enabled   = NOT share_links_enabled;

-- New upload-control columns
ALTER TABLE application_settings_entity ADD COLUMN upload_enabled    BOOLEAN NOT NULL DEFAULT 1;
ALTER TABLE application_settings_entity ADD COLUMN upload_admin_only BOOLEAN NOT NULL DEFAULT 0;
