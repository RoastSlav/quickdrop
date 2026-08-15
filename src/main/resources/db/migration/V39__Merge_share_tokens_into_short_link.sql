-- Merges share_token into a general-purpose short_link table with a target_type
-- discriminator, so file/paste share links and (in a later change) plain URL-shortener
-- redirect links share one code space, one resolver, and one admin page.
--
-- Existing share_token rows become target_type='UPLOAD' rows. The columns needed by a
-- future 'REDIRECT' target_type (target_url, title) are created now, nullable, so this
-- is the only table-shape migration this feature needs.

CREATE TABLE short_link
(
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    code             TEXT    NOT NULL,
    target_type      TEXT    NOT NULL,

    -- UPLOAD subtype (carried over from share_token)
    upload_id        INTEGER,
    share_key_hash   TEXT,
    sidecar_ready    INTEGER NOT NULL DEFAULT 1,

    -- REDIRECT subtype (unused until a later change)
    target_url       TEXT,
    title            TEXT,

    -- shared
    expiration_date  DATE,
    remaining_uses   INTEGER,
    use_count        INTEGER NOT NULL DEFAULT 0,
    password_hash    TEXT,
    interstitial     TEXT,
    created_at       TIMESTAMP,
    created_by_admin INTEGER NOT NULL DEFAULT 0,
    creator_ip       TEXT,
    CONSTRAINT fk_short_link_upload FOREIGN KEY (upload_id) REFERENCES upload (id)
);

CREATE UNIQUE INDEX idx_short_link_code ON short_link (code);
CREATE INDEX idx_short_link_upload ON short_link (upload_id);
CREATE INDEX idx_short_link_type ON short_link (target_type);

INSERT INTO short_link (code, target_type, upload_id, share_key_hash, sidecar_ready,
                        expiration_date, remaining_uses, created_at)
SELECT share_token, 'UPLOAD', file_id, share_key_hash, sidecar_ready,
       token_expiration_date, number_of_allowed_downloads, created_at
FROM share_token;

DROP TABLE share_token;

ALTER TABLE activity_log ADD COLUMN short_link_id INTEGER;
