-- Threat-intelligence reputation checking for the link shortener. All providers ship
-- disabled: each one is opt-in behind an admin-facing licence-acceptance gate (see the
-- *_terms_accepted_at columns) since Phishing Army, URLhaus, and Google Safe Browsing all
-- carry non-commercial-use licensing terms distinct from QuickDrop's own MIT license.
ALTER TABLE app_settings ADD COLUMN reputation_check_enabled BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE app_settings ADD COLUMN reputation_phishing_army_enabled BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE app_settings ADD COLUMN reputation_urlhaus_enabled BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE app_settings ADD COLUMN reputation_safe_browsing_enabled BOOLEAN NOT NULL DEFAULT 0;
-- Fail-open (default): a stale/unreachable feed allows link creation/resolution rather than
-- blocking the whole feature on a third-party outage. Fail-closed is opt-in for admins who
-- prefer the stricter posture.
ALTER TABLE app_settings ADD COLUMN reputation_fail_closed BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE app_settings ADD COLUMN reputation_feed_cron TEXT NOT NULL DEFAULT '0 0 4 * * *';
ALTER TABLE app_settings ADD COLUMN urlhaus_auth_key TEXT;
ALTER TABLE app_settings ADD COLUMN safe_browsing_api_key TEXT;
ALTER TABLE app_settings ADD COLUMN phishing_army_terms_accepted_at TIMESTAMP;
ALTER TABLE app_settings ADD COLUMN urlhaus_terms_accepted_at TIMESTAMP;
ALTER TABLE app_settings ADD COLUMN safe_browsing_terms_accepted_at TIMESTAMP;
