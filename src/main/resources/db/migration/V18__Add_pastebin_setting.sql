ALTER TABLE application_settings_entity
    ADD COLUMN pastebin_enabled BOOLEAN DEFAULT TRUE;

UPDATE application_settings_entity
SET pastebin_enabled = TRUE
WHERE pastebin_enabled IS NULL;

