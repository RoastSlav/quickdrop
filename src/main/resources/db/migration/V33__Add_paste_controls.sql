-- Adds immutable flag (paste content can no longer be edited) and
-- edit_only flag (password protects editing only, not viewing) to the paste table.
ALTER TABLE paste ADD COLUMN immutable INTEGER NOT NULL DEFAULT 0;
ALTER TABLE paste ADD COLUMN edit_only INTEGER NOT NULL DEFAULT 0;
