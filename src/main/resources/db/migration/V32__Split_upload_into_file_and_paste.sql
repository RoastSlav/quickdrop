-- V32: Split the monolithic 'upload' table into a base table plus two subtype
-- tables, implementing the JPA JOINED inheritance hierarchy
-- Upload (base) → StoredFile (paste=0) / Paste (paste=1).
--
-- The 'upload' table keeps all common columns.  The existing
-- folder_upload / folder_name / folder_manifest columns remain in 'upload'
-- as unused legacy data; they are no longer read or written by JPA because
-- those fields are now owned by the 'file' subtype table.
--
-- ---------------------------------------------------------------------------
-- 1. Create the 'file' extension table (StoredFile subtype)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS file (
    id              INTEGER NOT NULL,
    folder_upload   INTEGER NOT NULL DEFAULT 0,
    folder_name     TEXT,
    folder_manifest TEXT,
    CONSTRAINT pk_file    PRIMARY KEY (id),
    CONSTRAINT fk_file_id FOREIGN KEY (id) REFERENCES upload (id)
);

-- ---------------------------------------------------------------------------
-- 2. Populate 'file' from existing non-paste rows
-- ---------------------------------------------------------------------------
INSERT INTO file (id, folder_upload, folder_name, folder_manifest)
SELECT id,
       COALESCE(folder_upload, 0),
       folder_name,
       folder_manifest
FROM upload
WHERE paste = 0;

-- ---------------------------------------------------------------------------
-- 3. Create the 'paste' extension table (Paste subtype, no extra columns)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS paste (
    id INTEGER NOT NULL,
    CONSTRAINT pk_paste    PRIMARY KEY (id),
    CONSTRAINT fk_paste_id FOREIGN KEY (id) REFERENCES upload (id)
);

-- ---------------------------------------------------------------------------
-- 4. Populate 'paste' from existing paste rows
-- ---------------------------------------------------------------------------
INSERT INTO paste (id)
SELECT id FROM upload WHERE paste = 1;
