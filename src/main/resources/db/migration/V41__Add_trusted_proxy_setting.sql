-- Governs whether X-Forwarded-For/X-Real-IP request headers are trusted for client-IP
-- resolution (activity log attribution, rate limiting). Defaults to OFF: trusting these
-- headers unconditionally lets any direct client spoof its logged IP and, worse, defeat
-- rate limiting by rotating the header per request. Admins running behind a real reverse
-- proxy (the common QuickDrop deployment) opt in explicitly.
ALTER TABLE app_settings ADD COLUMN trusted_proxy_enabled BOOLEAN NOT NULL DEFAULT 0;
