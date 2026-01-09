ALTER TABLE file_entity ADD COLUMN encryption_version INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE file_entity ADD COLUMN original_size BIGINT;
UPDATE file_entity SET encryption_version = CASE WHEN encrypted = 1 THEN 1 ELSE 0 END;
UPDATE file_entity SET original_size = size WHERE original_size IS NULL;
