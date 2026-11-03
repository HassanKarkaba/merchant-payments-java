CREATE TABLE provider_history_task(
 task_id uuid PRIMARY KEY,operation_id uuid UNIQUE REFERENCES operation(id),inbox_id uuid UNIQUE REFERENCES callback_inbox(id),merchant_id uuid NOT NULL REFERENCES merchant(id),
 summary_json text NOT NULL CHECK(octet_length(summary_json)<=131072),summary_digest char(64) NOT NULL,history_resource_id uuid NOT NULL,
 source_high_water bigint NOT NULL CHECK(source_high_water>=0),expected_count bigint NOT NULL CHECK(expected_count>=0),
 cursor bigint NOT NULL DEFAULT 0 CHECK(cursor>=0),source_count integer NOT NULL DEFAULT 0 CHECK(source_count BETWEEN 0 AND 10000),
 status varchar(16) NOT NULL DEFAULT 'FETCHING' CHECK(status IN('FETCHING','COMPLETE','MANUAL')),inline_sources boolean NOT NULL DEFAULT false,
 sources_digest char(64),posting_total integer CHECK(posting_total BETWEEN 0 AND 10000),posted_count integer NOT NULL DEFAULT 0 CHECK(posted_count BETWEEN 0 AND 10000),posting_complete boolean NOT NULL DEFAULT false,
 owner uuid,generation bigint,run_id uuid,dispatch_epoch bigint,continuation_deadline timestamptz,
 safe_reason varchar(300) NOT NULL DEFAULT '',created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((operation_id IS NULL)<>(inbox_id IS NULL)),CHECK(cursor<=source_high_water),CHECK(status<>'COMPLETE' OR (source_count=expected_count AND sources_digest IS NOT NULL AND posting_total IS NOT NULL)),CHECK(NOT posting_complete OR posted_count=posting_total)
);
CREATE TABLE provider_history_source(
 task_id uuid NOT NULL REFERENCES provider_history_task(task_id),merchant_id uuid NOT NULL,
 source_id varchar(128) NOT NULL,event_type varchar(40) NOT NULL,sequence bigint NOT NULL CHECK(sequence>0),
 source_json text NOT NULL CHECK(octet_length(source_json)<=16384),digest char(64) NOT NULL,
 PRIMARY KEY(task_id,source_id,event_type),UNIQUE(task_id,sequence)
);
CREATE INDEX provider_history_task_order ON provider_history_task(created_at DESC,task_id);
CREATE INDEX provider_history_task_tenant ON provider_history_task(merchant_id);
CREATE INDEX provider_history_source_tenant ON provider_history_source(merchant_id);
CREATE UNIQUE INDEX provider_history_job_identity ON durable_job(kind,resource_id) WHERE kind='PROVIDER_HISTORY';
CREATE FUNCTION provider_history_task_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF current_user='mprp_purge' AND current_setting('mprp.purge_merchant',true)=OLD.merchant_id::text AND EXISTS(SELECT 1 FROM retired_merchant WHERE merchant_id=OLD.merchant_id) THEN RETURN OLD; END IF;
  RAISE EXCEPTION 'Provider history identity retained';
 END IF;
 IF (NEW.task_id,NEW.operation_id,NEW.inbox_id,NEW.merchant_id,NEW.summary_json,NEW.summary_digest,NEW.history_resource_id,NEW.source_high_water,NEW.expected_count,NEW.inline_sources)
 IS DISTINCT FROM (OLD.task_id,OLD.operation_id,OLD.inbox_id,OLD.merchant_id,OLD.summary_json,OLD.summary_digest,OLD.history_resource_id,OLD.source_high_water,OLD.expected_count,OLD.inline_sources)
 OR NEW.cursor<OLD.cursor OR NEW.source_count<OLD.source_count OR NEW.posted_count<OLD.posted_count OR (OLD.posting_complete AND NOT NEW.posting_complete) OR (OLD.sources_digest IS NOT NULL AND NEW.sources_digest IS DISTINCT FROM OLD.sources_digest) OR (OLD.posting_total IS NOT NULL AND NEW.posting_total IS DISTINCT FROM OLD.posting_total) THEN RAISE EXCEPTION 'Immutable provider history identity'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER provider_history_task_identity BEFORE UPDATE OR DELETE ON provider_history_task FOR EACH ROW EXECUTE FUNCTION provider_history_task_guard();
CREATE FUNCTION provider_history_source_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t provider_history_task;
BEGIN
 SELECT * INTO t FROM provider_history_task WHERE task_id=NEW.task_id FOR UPDATE;
 IF t.status<>'FETCHING' OR t.merchant_id<>NEW.merchant_id OR NEW.sequence<=t.cursor OR NEW.sequence>t.source_high_water
 OR (NEW.source_json::jsonb->>'source_id') IS DISTINCT FROM NEW.source_id OR (NEW.source_json::jsonb->>'event_type') IS DISTINCT FROM NEW.event_type
 OR (NEW.source_json::jsonb->>'sequence')::bigint IS DISTINCT FROM NEW.sequence OR (NEW.source_json::jsonb->>'provider_account_id')::uuid IS DISTINCT FROM NEW.merchant_id
 THEN RAISE EXCEPTION 'Provider history source scope or phase mismatch'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER provider_history_source_insert BEFORE INSERT ON provider_history_source FOR EACH ROW EXECUTE FUNCTION provider_history_source_guard();
CREATE TRIGGER provider_history_source_immutable BEFORE UPDATE OR DELETE ON provider_history_source FOR EACH ROW EXECUTE FUNCTION immutable_history();
GRANT SELECT,INSERT,UPDATE ON provider_history_task TO mprp_runtime;
GRANT SELECT,INSERT ON provider_history_source TO mprp_runtime;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='mprp_purge') THEN GRANT SELECT,DELETE ON provider_history_source,provider_history_task TO mprp_purge; END IF; END $$;

INSERT INTO guard_registry SELECT tgname,md5(pg_get_triggerdef(oid)||pg_get_functiondef(tgfoid)) FROM pg_trigger WHERE tgname IN('provider_history_task_identity','provider_history_source_insert','provider_history_source_immutable');
