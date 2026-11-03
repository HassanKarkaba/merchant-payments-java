CREATE TABLE sim_control(id int PRIMARY KEY CHECK(id=1), instance_id uuid NOT NULL DEFAULT gen_random_uuid(), environment_id uuid, main_instance_id uuid, run_token text, dispatch_epoch bigint NOT NULL DEFAULT 0, source_sequence bigint NOT NULL DEFAULT 0, event_sequence bigint NOT NULL DEFAULT 0, restored boolean NOT NULL DEFAULT false, acknowledged_cursor bigint NOT NULL DEFAULT 0, worker_heartbeat timestamptz,purge_secret_digest varchar(64));
INSERT INTO sim_control(id) VALUES(1);
CREATE TABLE sim_retired_runs(run_token text PRIMARY KEY, retired_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE sim_nonces(nonce varchar(128) PRIMARY KEY,expires_at timestamptz NOT NULL);
CREATE INDEX ON sim_nonces(expires_at);
CREATE TABLE sim_accounts(id uuid PRIMARY KEY,merchant_id uuid NOT NULL UNIQUE, environment_id uuid NOT NULL,bank_balance_minor bigint NOT NULL DEFAULT 0,retired boolean NOT NULL DEFAULT false,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE sim_operations(sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,operation_key varchar(128) PRIMARY KEY,payload_hash varchar(64) NOT NULL,kind varchar(40) NOT NULL,account_id uuid,resource_id uuid,state varchar(24) NOT NULL CHECK(state IN('SUCCEEDED','FAILED','NO_EFFECT')),payload jsonb NOT NULL,result jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE sim_payments(id uuid PRIMARY KEY,account_id uuid NOT NULL REFERENCES sim_accounts(id),authorized_minor bigint NOT NULL CHECK(authorized_minor BETWEEN 100 AND 100000000),captured_minor bigint NOT NULL DEFAULT 0,refunded_minor bigint NOT NULL DEFAULT 0,fee_minor bigint NOT NULL DEFAULT 0,state varchar(24) NOT NULL,version bigint NOT NULL DEFAULT 1,authorization_expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),CHECK(captured_minor BETWEEN 0 AND authorized_minor),CHECK(refunded_minor BETWEEN 0 AND captured_minor));
CREATE TABLE sim_batches(id uuid PRIMARY KEY,account_id uuid NOT NULL REFERENCES sim_accounts(id),cutoff_identity varchar(128),net_minor bigint NOT NULL DEFAULT 0,state varchar(16) NOT NULL CHECK(state IN('OPEN','COMPLETED')),created_at timestamptz NOT NULL DEFAULT now(),closed_at timestamptz,UNIQUE(account_id,cutoff_identity));
CREATE UNIQUE INDEX sim_one_open_batch ON sim_batches(account_id) WHERE state='OPEN';
CREATE TABLE sim_sources(source_id uuid PRIMARY KEY,sequence bigint NOT NULL UNIQUE,account_id uuid NOT NULL REFERENCES sim_accounts(id),resource_id uuid NOT NULL,payment_id uuid,event_type varchar(32) NOT NULL,amount_minor bigint NOT NULL CHECK((event_type='BATCH_ZERO' AND amount_minor=0) OR (event_type<>'BATCH_ZERO' AND amount_minor<>0)),occurred_at timestamptz NOT NULL,reference varchar(64) NOT NULL UNIQUE,batch_id uuid REFERENCES sim_batches(id),clearing_line boolean NOT NULL DEFAULT true,reason text NOT NULL DEFAULT '');
CREATE INDEX ON sim_sources(account_id,occurred_at);CREATE INDEX ON sim_sources(resource_id,sequence);CREATE INDEX ON sim_sources(batch_id);
CREATE TABLE sim_transfers(id uuid PRIMARY KEY,account_id uuid NOT NULL REFERENCES sim_accounts(id),source_id uuid NOT NULL UNIQUE REFERENCES sim_sources(source_id),batch_id uuid UNIQUE REFERENCES sim_batches(id),kind varchar(16) NOT NULL,amount_minor bigint NOT NULL CHECK(amount_minor<>0),reference varchar(64) NOT NULL UNIQUE,occurred_at timestamptz NOT NULL,beneficiary_token varchar(64));
CREATE TABLE sim_disputes(id uuid PRIMARY KEY,payment_id uuid NOT NULL UNIQUE REFERENCES sim_payments(id),account_id uuid NOT NULL REFERENCES sim_accounts(id),principal_minor bigint NOT NULL CHECK(principal_minor>0),state varchar(16) NOT NULL,version bigint NOT NULL DEFAULT 1,opened_at timestamptz NOT NULL,deadline timestamptz NOT NULL,response text,selected_outcome varchar(8) NOT NULL CHECK(selected_outcome IN('WON','LOST')));
CREATE TABLE sim_events(sequence bigint PRIMARY KEY,event_id uuid NOT NULL UNIQUE,account_id uuid NOT NULL REFERENCES sim_accounts(id),body jsonb NOT NULL,delivered boolean NOT NULL DEFAULT false,attempts int NOT NULL DEFAULT 0,due_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE sim_event_failure(event_id uuid PRIMARY KEY,sequence bigint NOT NULL UNIQUE REFERENCES sim_events(sequence),account_id uuid NOT NULL REFERENCES sim_accounts(id),code varchar(64) NOT NULL,body_bytes bigint NOT NULL,digest varchar(64) NOT NULL,state varchar(24) NOT NULL DEFAULT 'OPEN',created_at timestamptz NOT NULL DEFAULT now(),resolved_at timestamptz);
CREATE TABLE sim_manifests(id uuid PRIMARY KEY,account_id uuid NOT NULL REFERENCES sim_accounts(id),statement_id varchar(64) NOT NULL UNIQUE,kind varchar(32) NOT NULL,period_start timestamptz NOT NULL,period_end timestamptz NOT NULL,content_hash varchar(64) NOT NULL,manifest jsonb NOT NULL,csv_content bytea NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(account_id,kind,period_start,period_end,content_hash));
CREATE TABLE sim_retired_namespaces(account_id uuid PRIMARY KEY,merchant_id uuid NOT NULL UNIQUE,retirement_id uuid NOT NULL UNIQUE,inventory_hash varchar(64) NOT NULL,approval_id uuid NOT NULL,merchant_closed_at timestamptz NOT NULL,retired_at timestamptz NOT NULL DEFAULT now(),phase int NOT NULL DEFAULT 0,completed_at timestamptz);
CREATE FUNCTION sim_immutable_operation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
IF current_user='simulator_purge' AND TG_OP='UPDATE' AND EXISTS(SELECT 1 FROM sim_retired_namespaces WHERE account_id=OLD.account_id AND phase>=1) AND (NEW.operation_key,NEW.payload_hash,NEW.kind,NEW.account_id,NEW.resource_id,NEW.state,NEW.created_at,NEW.sequence) IS NOT DISTINCT FROM (OLD.operation_key,OLD.payload_hash,OLD.kind,OLD.account_id,OLD.resource_id,OLD.state,OLD.created_at,OLD.sequence) AND NEW.payload='{}'::jsonb AND NEW.result->>'business_state'='PURGED' THEN RETURN NEW; END IF;
RAISE EXCEPTION 'provider operation identity and tombstone are immutable'; END $$;
CREATE TRIGGER sim_operation_immutable BEFORE UPDATE OR DELETE ON sim_operations FOR EACH ROW EXECUTE FUNCTION sim_immutable_operation();
CREATE FUNCTION sim_source_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
IF current_user='simulator_purge' AND TG_OP='DELETE' AND EXISTS(SELECT 1 FROM sim_retired_namespaces WHERE account_id=OLD.account_id AND phase>=1 AND merchant_closed_at<=now()-interval '365 days') THEN RETURN OLD; END IF;
RAISE EXCEPTION 'economic source is immutable'; END $$;
CREATE TRIGGER sim_source_immutable BEFORE UPDATE OR DELETE ON sim_sources FOR EACH ROW EXECUTE FUNCTION sim_source_immutable();
GRANT USAGE ON SCHEMA public TO simulator_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO simulator_runtime;
REVOKE UPDATE,DELETE ON sim_operations,sim_sources,sim_retired_namespaces FROM simulator_runtime;

GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO simulator_runtime;
REVOKE UPDATE ON sim_control FROM simulator_runtime;
GRANT UPDATE(environment_id,main_instance_id,run_token,dispatch_epoch,source_sequence,event_sequence,restored,acknowledged_cursor,worker_heartbeat) ON sim_control TO simulator_runtime;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='simulator_purge') THEN
GRANT USAGE ON SCHEMA public TO simulator_purge;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO simulator_purge;
GRANT DELETE ON sim_event_failure,sim_events,sim_manifests,sim_transfers,sim_disputes,sim_payments,sim_sources,sim_batches TO simulator_purge;
GRANT UPDATE(payload,result) ON sim_operations TO simulator_purge;
GRANT UPDATE(phase,completed_at) ON sim_retired_namespaces TO simulator_purge;
END IF; END $$;

REVOKE DELETE ON sim_control,sim_retired_runs,sim_accounts,sim_payments,sim_batches,sim_transfers,sim_disputes,sim_events,sim_event_failure,sim_manifests FROM simulator_runtime;
CREATE FUNCTION sim_purge_delete_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
IF current_user<>'simulator_purge' OR NOT EXISTS(SELECT 1 FROM sim_retired_namespaces WHERE account_id=OLD.account_id AND phase>=1 AND merchant_closed_at<=now()-interval '365 days') THEN RAISE EXCEPTION 'only an eligible retired namespace can be purged'; END IF; RETURN OLD; END $$;
CREATE TRIGGER sim_payment_delete_guard BEFORE DELETE ON sim_payments FOR EACH ROW EXECUTE FUNCTION sim_purge_delete_guard();
CREATE TRIGGER sim_batch_delete_guard BEFORE DELETE ON sim_batches FOR EACH ROW EXECUTE FUNCTION sim_purge_delete_guard();
CREATE TRIGGER sim_transfer_delete_guard BEFORE DELETE ON sim_transfers FOR EACH ROW EXECUTE FUNCTION sim_purge_delete_guard();
CREATE TRIGGER sim_dispute_delete_guard BEFORE DELETE ON sim_disputes FOR EACH ROW EXECUTE FUNCTION sim_purge_delete_guard();
CREATE TRIGGER sim_event_delete_guard BEFORE DELETE ON sim_events FOR EACH ROW EXECUTE FUNCTION sim_purge_delete_guard();
CREATE TRIGGER sim_manifest_delete_guard BEFORE DELETE ON sim_manifests FOR EACH ROW EXECUTE FUNCTION sim_purge_delete_guard();

CREATE FUNCTION sim_event_payload_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
IF (NEW.sequence,NEW.event_id,NEW.account_id,NEW.body) IS DISTINCT FROM (OLD.sequence,OLD.event_id,OLD.account_id,OLD.body) THEN RAISE EXCEPTION 'provider event identity and payload are immutable'; END IF; RETURN NEW; END $$;
CREATE TRIGGER sim_event_payload_immutable BEFORE UPDATE ON sim_events FOR EACH ROW EXECUTE FUNCTION sim_event_payload_guard();
CREATE TRIGGER sim_event_failure_delete_guard BEFORE DELETE ON sim_event_failure FOR EACH ROW EXECUTE FUNCTION sim_purge_delete_guard();
DO $$ BEGIN EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC, simulator_runtime',current_database()); END $$;
