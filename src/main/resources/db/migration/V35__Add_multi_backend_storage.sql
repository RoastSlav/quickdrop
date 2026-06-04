-- Azure Blob Storage
ALTER TABLE app_settings
    ADD COLUMN azure_connection_string TEXT;
ALTER TABLE app_settings
    ADD COLUMN azure_container_name TEXT;
ALTER TABLE app_settings
    ADD COLUMN azure_key_prefix TEXT DEFAULT '';

-- SFTP
ALTER TABLE app_settings
    ADD COLUMN sftp_host TEXT;
ALTER TABLE app_settings
    ADD COLUMN sftp_port INTEGER DEFAULT 22;
ALTER TABLE app_settings
    ADD COLUMN sftp_username TEXT;
ALTER TABLE app_settings
    ADD COLUMN sftp_password TEXT;
ALTER TABLE app_settings
    ADD COLUMN sftp_private_key TEXT;
ALTER TABLE app_settings
    ADD COLUMN sftp_base_path TEXT DEFAULT '/';
ALTER TABLE app_settings
    ADD COLUMN sftp_known_hosts TEXT;

-- WebDAV
ALTER TABLE app_settings
    ADD COLUMN webdav_url TEXT;
ALTER TABLE app_settings
    ADD COLUMN webdav_username TEXT;
ALTER TABLE app_settings
    ADD COLUMN webdav_password TEXT;
ALTER TABLE app_settings
    ADD COLUMN webdav_key_prefix TEXT DEFAULT '';
