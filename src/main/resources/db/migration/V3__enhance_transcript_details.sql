-- ── Enhance transcript_details table with success/failure tracking and user feedback ──

-- Add new columns to transcript_details if they don't exist
ALTER TABLE IF EXISTS transcript_details
  ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'SUCCESS',
  ADD COLUMN IF NOT EXISTS failure_reason TEXT,
  ADD COLUMN IF NOT EXISTS llm_request_json JSONB,
  ADD COLUMN IF NOT EXISTS llm_response_json JSONB,
  ADD COLUMN IF NOT EXISTS user_feedback TEXT,
  ADD COLUMN IF NOT EXISTS feedback_rating INT,
  ADD COLUMN IF NOT EXISTS retrieval_duration_ms INT,
  ADD COLUMN IF NOT EXISTS llm_call_duration_ms INT,
  ADD COLUMN IF NOT EXISTS validation_duration_ms INT,
  ADD COLUMN IF NOT EXISTS confidence_score NUMERIC(5,4),
  ADD COLUMN IF NOT EXISTS error_code VARCHAR(100),
  ADD COLUMN IF NOT EXISTS database_name VARCHAR(255),
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP;

-- Create index for status to query success/failure queries efficiently
CREATE INDEX IF NOT EXISTS idx_transcript_status ON transcript_details(status);

-- Create index for created_at + status for efficient time-based filtering
CREATE INDEX IF NOT EXISTS idx_transcript_created_status ON transcript_details(created_at, status);

-- Create index for user_id + created_at for user activity timeline
CREATE INDEX IF NOT EXISTS idx_transcript_user_created ON transcript_details(user_id, created_at);

-- Create index for database_name for per-database analytics
CREATE INDEX IF NOT EXISTS idx_transcript_database ON transcript_details(database_name);

-- Create index for error_code for debugging
CREATE INDEX IF NOT EXISTS idx_transcript_error_code ON transcript_details(error_code);

