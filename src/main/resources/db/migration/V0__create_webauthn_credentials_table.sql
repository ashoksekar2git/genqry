-- Create webauthn_credentials table for storing passkey credentials
-- This table stores all WebAuthn/Passkey registration and authentication data

CREATE TABLE IF NOT EXISTS webauthn_credentials (
  id                      SERIAL PRIMARY KEY,
  user_id                 INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  credential_id           BYTEA NOT NULL,
  public_key              BYTEA NOT NULL,
  sign_count              INTEGER DEFAULT 0,
  credential_id_b64       VARCHAR(255) UNIQUE NOT NULL,
  aaguid                  VARCHAR(36),
  device_type             VARCHAR(50),
  transports              TEXT,
  backed_up               BOOLEAN DEFAULT FALSE,
  attestation_object      TEXT,
  client_data_json        TEXT,
  friendly_name           VARCHAR(255),
  registered_at           TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  last_used_at            TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  last_authenticator_data TEXT,
  created_at              TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at              TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for fast lookups
CREATE INDEX IF NOT EXISTS idx_webauthn_user_id ON webauthn_credentials(user_id);
CREATE INDEX IF NOT EXISTS idx_webauthn_credential_id_b64 ON webauthn_credentials(credential_id_b64);
CREATE INDEX IF NOT EXISTS idx_webauthn_created_at ON webauthn_credentials(created_at);

-- Constraint: ensure we have the necessary fields
ALTER TABLE webauthn_credentials ADD CONSTRAINT chk_webauthn_required
  CHECK (credential_id IS NOT NULL AND public_key IS NOT NULL AND credential_id_b64 IS NOT NULL);

