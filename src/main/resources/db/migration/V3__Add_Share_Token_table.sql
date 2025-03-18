CREATE TABLE IF NOT EXISTS share_token_entity
(
    id                          INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id                     INTEGER NOT NULL,
    share_token                 VARCHAR(255),
    token_expiration_date       DATE,
    number_of_allowed_downloads INTEGER,
    CONSTRAINT fk_file FOREIGN KEY (file_id) REFERENCES file_entity (id)
);

