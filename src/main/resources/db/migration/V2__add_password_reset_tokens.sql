-- Password reset token support
-- Adds columns to support password reset functionality

ALTER TABLE users
  ADD COLUMN IF NOT EXISTS password_reset_token VARCHAR(255),
  ADD COLUMN IF NOT EXISTS password_reset_expires_at TIMESTAMPTZ;

-- Create indexes for faster token lookup
CREATE INDEX IF NOT EXISTS idx_users_password_reset_token ON users(password_reset_token);

