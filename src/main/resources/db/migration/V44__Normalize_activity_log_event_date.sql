-- Normalizes activity_log.event_date to the epoch-millisecond INTEGER form Hibernate
-- writes, so every row shares one SQLite storage class.
--
-- Why: SQLite orders by storage class before value (NULL < INTEGER/REAL < TEXT < BLOB).
-- Rows inserted outside the app -- seeded demo data, fixtures, a hand-written INSERT --
-- land as TEXT ('2026-08-06 21:06:00'), while the app writes INTEGER millis. With both
-- present, "ORDER BY event_date DESC" returns *every* TEXT row before *any* INTEGER row
-- regardless of the actual timestamps. On an instance with several hundred seeded rows
-- that pushes all genuinely recent activity -- short-link creates and visits, admin
-- logins -- past the last page of the activity log, so it looks like those events were
-- never recorded at all. They were; they were just sorted to the bottom.
--
-- strftime('%s', ...) parses the ISO-ish text SQLite understands and yields seconds;
-- x1000 matches the millisecond precision Hibernate uses. Rows whose text cannot be
-- parsed yield NULL from strftime and are left untouched rather than zeroed, so a
-- malformed value is preserved for inspection instead of silently becoming 1970.
UPDATE activity_log
SET event_date = CAST(strftime('%s', event_date) AS INTEGER) * 1000
WHERE typeof(event_date) = 'text'
  AND strftime('%s', event_date) IS NOT NULL;

-- REAL (Julian day) is the other shape a non-app writer can produce.
UPDATE activity_log
SET event_date = CAST((event_date - 2440587.5) * 86400000 AS INTEGER)
WHERE typeof(event_date) = 'real';
