-- Governs whether resolving a short link (either type) writes a row to activity_log.
-- Defaults to on, matching the other shortener defaults (open feature, visible activity).
-- use_count/remaining_uses on short_link itself are ALWAYS updated regardless of this
-- setting; this only controls the audit-log row.
ALTER TABLE app_settings ADD COLUMN shortener_click_logging_enabled BOOLEAN NOT NULL DEFAULT 1;
