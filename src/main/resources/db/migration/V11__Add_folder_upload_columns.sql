ALTER TABLE file_entity ADD COLUMN folder_upload BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE file_entity ADD COLUMN folder_name VARCHAR(255);
ALTER TABLE file_entity ADD COLUMN folder_manifest TEXT;
