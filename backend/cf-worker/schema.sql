-- D1 (SQLite) schema for the Meetings Remote token backend.
-- Mirrors the old Postgres schema 1:1 (backend/server.py init_schema).
-- Times are REAL seconds (Date.now()/1000), matching the Python float epoch.

CREATE TABLE IF NOT EXISTS sessions (
  sid           TEXT PRIMARY KEY,
  refresh_token TEXT NOT NULL,
  zoom_user_id  TEXT NOT NULL DEFAULT '',
  name          TEXT NOT NULL DEFAULT '',
  pmi           TEXT NOT NULL DEFAULT '',
  zak           TEXT NOT NULL DEFAULT '',
  zak_ts        REAL NOT NULL DEFAULT 0,
  created_at    REAL NOT NULL,
  last_used_at  REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS oauth_pending (
  sid        TEXT PRIMARY KEY,
  verifier   TEXT NOT NULL,
  created_at REAL NOT NULL
);

-- Deauthorization deletes by Zoom user id (secondary lookup).
CREATE INDEX IF NOT EXISTS idx_sessions_zoom_user ON sessions (zoom_user_id);
