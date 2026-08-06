-- Opt-in: an upgrading instance shouldn't silently start writing backups on an unconfigured schedule.
ALTER TABLE app_settings ADD COLUMN backup_schedule_enabled INTEGER DEFAULT 0;

-- Avoids the existing 2/3/3:30 AM maintenance jobs in ScheduleService.
ALTER TABLE app_settings ADD COLUMN backup_cron VARCHAR(255) DEFAULT '0 0 4 * * *';

ALTER TABLE app_settings ADD COLUMN max_backups INTEGER DEFAULT 7;
