ALTER TABLE file_entity
    ADD COLUMN is_paste BOOLEAN DEFAULT FALSE;

UPDATE file_entity
SET is_paste = FALSE
WHERE is_paste IS NULL;

