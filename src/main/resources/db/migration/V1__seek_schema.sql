-- genQry database schema migration
-- Run once against the genQry PostgreSQL database

-- ── users table additions ────────────────────────────────────────────────────
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS display_name      VARCHAR(200),
  ADD COLUMN IF NOT EXISTS last_login_at     TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS last_login_ip     INET,
  ADD COLUMN IF NOT EXISTS last_login_device VARCHAR(200);

-- Fix TIME WITH TIME ZONE → TIMESTAMPTZ for proper timestamp storage
ALTER TABLE users ALTER COLUMN token_expires_at TYPE TIMESTAMPTZ
  USING CASE WHEN token_expires_at IS NULL THEN NULL
             ELSE (CURRENT_DATE + token_expires_at)::timestamptz END;
ALTER TABLE users ALTER COLUMN registered_at TYPE TIMESTAMPTZ
  USING CASE WHEN registered_at IS NULL THEN NULL
             ELSE (CURRENT_DATE + registered_at)::timestamptz END;
ALTER TABLE users ALTER COLUMN verified_at TYPE TIMESTAMPTZ
  USING CASE WHEN verified_at IS NULL THEN NULL
             ELSE (CURRENT_DATE + verified_at)::timestamptz END;
ALTER TABLE users ALTER COLUMN updated_at TYPE TIMESTAMPTZ
  USING CASE WHEN updated_at IS NULL THEN NULL
             ELSE (CURRENT_DATE + updated_at)::timestamptz END;
ALTER TABLE users ALTER COLUMN created_at TYPE TIMESTAMPTZ
  USING CASE WHEN created_at IS NULL THEN NULL
             ELSE (CURRENT_DATE + created_at)::timestamptz END;

-- ── webauthn_credentials additions ──────────────────────────────────────────
ALTER TABLE webauthn_credentials
  ADD COLUMN IF NOT EXISTS friendly_name VARCHAR(200),
  ADD COLUMN IF NOT EXISTS device_type   VARCHAR(50),
  ADD COLUMN IF NOT EXISTS transports    TEXT,
  ADD COLUMN IF NOT EXISTS last_used_at  TIMESTAMPTZ;

-- ── schema_details table ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS schema_details (
  id          SERIAL       PRIMARY KEY,
  user_id     INT          REFERENCES users(id) ON DELETE CASCADE,
  db_name     VARCHAR(200) NOT NULL,
  schema_json JSONB        NOT NULL,
  file_path   TEXT,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_schema_details_user_id ON schema_details(user_id);
CREATE INDEX IF NOT EXISTS idx_schema_details_db_name ON schema_details(db_name);

-- ── transcript_details table ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS transcript_details (
  id              SERIAL      PRIMARY KEY,
  user_id         INT         REFERENCES users(id) ON DELETE SET NULL,
  session_id      VARCHAR(255),
  user_prompt     TEXT        NOT NULL,
  generated_sql   TEXT,
  explanation     TEXT,
  is_cached       BOOLEAN     DEFAULT FALSE,
  execution_ms    INT,
  transcript_json JSONB,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_transcript_user_id ON transcript_details(user_id);
CREATE INDEX IF NOT EXISTS idx_transcript_session  ON transcript_details(session_id);
CREATE INDEX IF NOT EXISTS idx_transcript_created  ON transcript_details(created_at);

