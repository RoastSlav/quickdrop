-- Settings for the general link-shortener feature (redirect links). Defaults keep the
-- feature open to all users (matches uploadEnabled's default posture) while tightening
-- the one setting that carries real impersonation risk: custom aliases default to
-- admin-only, since a self-chosen slug (e.g. /s/paypal-login) is the phishing vector a
-- random code doesn't have.
ALTER TABLE app_settings ADD COLUMN shortener_enabled BOOLEAN NOT NULL DEFAULT 1;
ALTER TABLE app_settings ADD COLUMN shortener_admin_only BOOLEAN NOT NULL DEFAULT 0;
ALTER TABLE app_settings ADD COLUMN shortener_code_length INTEGER NOT NULL DEFAULT 5;
ALTER TABLE app_settings ADD COLUMN shortener_custom_alias_enabled BOOLEAN NOT NULL DEFAULT 1;
ALTER TABLE app_settings ADD COLUMN shortener_custom_alias_admin_only BOOLEAN NOT NULL DEFAULT 1;
ALTER TABLE app_settings ADD COLUMN shortener_interstitial_mode TEXT NOT NULL DEFAULT 'NON_ADMIN';
ALTER TABLE app_settings ADD COLUMN shortener_domain_rule_mode TEXT NOT NULL DEFAULT 'OFF';
ALTER TABLE app_settings ADD COLUMN shortener_domain_rules TEXT NOT NULL DEFAULT '';
