-- Run once before deploying the field-encryption Worker. This is deliberately
-- separate from schema.sql because SQLite has no ADD COLUMN IF NOT EXISTS.
ALTER TABLE sessions ADD COLUMN zoom_user_hash TEXT NOT NULL DEFAULT '';
DROP INDEX IF EXISTS idx_sessions_zoom_user;
CREATE INDEX IF NOT EXISTS idx_sessions_zoom_user_hash ON sessions (zoom_user_hash);
