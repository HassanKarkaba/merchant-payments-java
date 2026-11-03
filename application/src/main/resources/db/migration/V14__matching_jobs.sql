ALTER TABLE statement_line ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp();
CREATE TABLE matching_run(id uuid PRIMARY KEY,merchant_id uuid NOT NULL REFERENCES merchant(id),actor_id uuid NOT NULL REFERENCES app_user(id),status varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(status IN('QUEUED','RUNNING','SUCCEEDED','FAILED')),cutoff timestamptz NOT NULL DEFAULT clock_timestamp(),cursor_time timestamptz,cursor_id uuid,matched_groups bigint NOT NULL DEFAULT 0,examined_lines bigint NOT NULL DEFAULT 0,safe_message varchar(300) NOT NULL DEFAULT 'Durably queued',created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE UNIQUE INDEX matching_run_one_active ON matching_run(merchant_id) WHERE status IN('QUEUED','RUNNING');
CREATE INDEX matching_scan ON statement_line(merchant_id,occurred_at,id,created_at);
GRANT SELECT,INSERT,UPDATE ON matching_run TO mprp_runtime;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='mprp_purge') THEN GRANT SELECT,DELETE ON matching_run TO mprp_purge; END IF; END $$;
