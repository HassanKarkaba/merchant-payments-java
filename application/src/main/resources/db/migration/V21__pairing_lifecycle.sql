ALTER TABLE system_control ADD COLUMN paired_at timestamptz;
ALTER TABLE system_control ADD COLUMN restore_requested boolean NOT NULL DEFAULT false;
