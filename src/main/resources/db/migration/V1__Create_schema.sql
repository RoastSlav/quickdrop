CREATE TABLE IF NOT EXISTS application_settings_entity
(
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    max_file_size         BIGINT,
    max_file_life_time    BIGINT,
    file_storage_path     VARCHAR(255),
    log_storage_path      VARCHAR(255),
    file_deletion_cron    VARCHAR(255),
    app_password_enabled  BOOLEAN,
    app_password_hash     VARCHAR(255),
    admin_password_hash   VARCHAR(255),
    sessionLifetime       BIGINT  DEFAULT 30,
    isFileListPageEnabled BOOLEAN DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS file_entity
(
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    name             VARCHAR(255),
    uuid             VARCHAR(255),
    description      VARCHAR(255),
    size             BIGINT,
    keepIndefinitely BOOLEAN,
    uploadDate       DATE,
    passwordHash     VARCHAR(255),
    hidden           BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS download_log
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    download_date TIMESTAMP,
    user_agent    VARCHAR(255),
    ip            VARCHAR(255),
    file_id       INTEGER,
    FOREIGN KEY (file_id) REFERENCES file_entity (id)
);

CREATE TABLE IF NOT EXISTS upload_log
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    upload_date  TIMESTAMP,
    download_ref INTEGER,
    user_agent   VARCHAR(255),
    ip           VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS file_renewal_log
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    renewal_date TIMESTAMP,
    action_date  TIMESTAMP,
    user_agent   VARCHAR(255),
    ip           VARCHAR(255)
);
