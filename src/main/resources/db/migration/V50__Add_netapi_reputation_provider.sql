-- NetAPI compromised-domain list as a fourth reputation provider. Ships disabled like the
-- others: it can only be turned on by accepting its licence terms (CC BY 4.0) in the settings UI.
ALTER TABLE app_settings ADD COLUMN reputation_net_api_enabled BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE app_settings ADD COLUMN net_api_terms_accepted_at TIMESTAMP;
