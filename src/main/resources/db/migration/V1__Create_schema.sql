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
    session_lifetime BIGINT DEFAULT 30,
    is_file_list_page_enabled BOOLEAN DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS file_entity
(
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    name             VARCHAR(255),
    uuid             VARCHAR(255),
    description      VARCHAR(255),
    size             BIGINT,
    keep_indefinitely BOOLEAN,
    upload_date       DATE,
    password_hash     VARCHAR(255),
    hidden           BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS download_log
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    download_date TIMESTAMP,
    user_agent    VARCHAR(255),
    downloader_ip VARCHAR(255),
    file_id       INTEGER,
    FOREIGN KEY (file_id) REFERENCES file_entity (id)
);

CREATE TABLE IF NOT EXISTS file_renewal_log
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id    INTEGER NOT NULL,
    action_date  TIMESTAMP,
    user_agent   VARCHAR(255),
    ip_address VARCHAR(255),
    FOREIGN KEY (file_id) REFERENCES file_entity (id)
);
