-- SQLite does not support ALTER COLUMN, so we recreate the table with file_id nullable
-- to allow admin-level audit events (login, logout, settings changes) that have no file.
-- PRAGMA foreign_keys is intentionally omitted: nothing has an FK pointing TO this table.

CREATE TABLE file_history_log_new (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id    INTEGER,
    event_type VARCHAR(50)  NOT NULL,
    event_date TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(255),
    user_agent TEXT,
    FOREIGN KEY (file_id) REFERENCES file_entity (id)
);

INSERT INTO file_history_log_new (id, file_id, event_type, event_date, ip_address, user_agent)
SELECT id, file_id, event_type, event_date, ip_address, user_agent
FROM file_history_log;

DROP TABLE file_history_log;
ALTER TABLE file_history_log_new RENAME TO file_history_log;

CREATE INDEX IF NOT EXISTS idx_file_history_file ON file_history_log (file_id);
CREATE INDEX IF NOT EXISTS idx_file_history_type ON file_history_log (event_type);
