-- Create unified file history table
CREATE TABLE IF NOT EXISTS file_history_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id INTEGER NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    event_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(255),
    user_agent TEXT,
    FOREIGN KEY (file_id) REFERENCES file_entity (id)
);

CREATE INDEX IF NOT EXISTS idx_file_history_file ON file_history_log(file_id);
CREATE INDEX IF NOT EXISTS idx_file_history_type ON file_history_log(event_type);

-- Migrate existing download and renewal logs into unified table
INSERT INTO file_history_log (file_id, event_type, event_date, ip_address, user_agent)
SELECT dl.file_id, 'DOWNLOAD', COALESCE(dl.download_date, CURRENT_TIMESTAMP), dl.downloader_ip, dl.user_agent
FROM download_log dl;

INSERT INTO file_history_log (file_id, event_type, event_date, ip_address, user_agent)
SELECT frl.file_id, 'RENEWAL', COALESCE(frl.action_date, CURRENT_TIMESTAMP), frl.ip_address, frl.user_agent
FROM file_renewal_log frl;

-- Drop old tables once migrated
DROP TABLE IF EXISTS download_log;
DROP TABLE IF EXISTS file_renewal_log;
