CREATE TABLE IF NOT EXISTS share_token_entity
(
    id                          INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id                     INTEGER NOT NULL,
    share_token                 VARCHAR(255),
    token_expiration_date       DATE,
    number_of_allowed_downloads INTEGER,
    CONSTRAINT fk_file FOREIGN KEY (file_id) REFERENCES file_entity (id)
);

-- Removing share_token and token_expiration_date columns

ALTER TABLE file_entity
    RENAME TO file_entity_old;

CREATE TABLE file_entity
(
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    description       VARCHAR(255),
    keep_indefinitely BOOLEAN,
    name              VARCHAR(255),
    size              BIGINT,
    upload_date       DATE,
    uuid              VARCHAR(255),
    password_hash     VARCHAR(255),
    hidden            BOOLEAN DEFAULT FALSE
);

INSERT INTO file_entity (id,
                         description,
                         keep_indefinitely,
                         name,
                         size,
                         upload_date,
                         uuid,
                         password_hash,
                         hidden)
SELECT id,
       description,
       keep_indefinitely,
       name,
       size,
       upload_date,
       uuid,
       password_hash,
       hidden
FROM file_entity_old;

DROP TABLE file_entity_old;