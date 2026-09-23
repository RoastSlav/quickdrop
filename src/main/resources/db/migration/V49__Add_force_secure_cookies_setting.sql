-- Live, admin-toggleable equivalent of the server.servlet.session.cookie.secure startup
-- property: forces the session and CSRF cookies' Secure flag on unconditionally, for a
-- reverse proxy that doesn't send X-Forwarded-Proto (so trustedProxyEnabled's per-request
-- detection has nothing to key off). Defaults to OFF so a plain-HTTP local install is
-- unaffected; the startup property remains available too, for deployments that prefer
-- env-var-managed config over a DB-stored toggle.
ALTER TABLE app_settings ADD COLUMN force_secure_cookies_enabled BOOLEAN NOT NULL DEFAULT 0;
