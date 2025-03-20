ALTER TABLE file_entity
    ADD COLUMN encrypted BOOLEAN NOT NULL DEFAULT 0;

ALTER TABLE application_settings_entity
    ADD COLUMN disable_encryption BOOLEAN NOT NULL DEFAULT 0;

UPDATE file_entity
SET encrypted = 1
WHERE password_hash IS NOT NULL
  AND password_hash <> '';