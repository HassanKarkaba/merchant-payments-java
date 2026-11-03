ALTER TABLE correction_proposal ADD COLUMN original_maker uuid REFERENCES app_user(id);
UPDATE correction_proposal SET original_maker=maker;
ALTER TABLE correction_proposal ALTER COLUMN original_maker SET NOT NULL;
CREATE TABLE correction_proposal_history(id uuid PRIMARY KEY,proposal_id uuid NOT NULL REFERENCES correction_proposal(id),merchant_id uuid NOT NULL REFERENCES merchant(id),proposal_version bigint NOT NULL,state varchar(16) NOT NULL,maker uuid NOT NULL REFERENCES app_user(id),checker uuid REFERENCES app_user(id),snapshot_json text NOT NULL CHECK(octet_length(snapshot_json)<=16384),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(proposal_id,proposal_version));
INSERT INTO correction_proposal_history(id,proposal_id,merchant_id,proposal_version,state,maker,checker,snapshot_json) SELECT gen_random_uuid(),id,merchant_id,version,state,maker,checker,to_jsonb(p)::text FROM correction_proposal p;
CREATE TABLE exception_history(id uuid PRIMARY KEY,exception_id uuid NOT NULL REFERENCES reconciliation_exception(id),merchant_id uuid NOT NULL REFERENCES merchant(id),actor uuid REFERENCES app_user(id),state varchar(24) NOT NULL,reason text NOT NULL CHECK(length(reason)<=2000),legacy_json text,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE INDEX exception_history_paging ON exception_history(exception_id,created_at,id);
INSERT INTO exception_history(id,exception_id,merchant_id,state,reason,legacy_json) SELECT gen_random_uuid(),id,merchant_id,state,'Retained prior history',history_json FROM reconciliation_exception WHERE history_json<>'[]';
CREATE FUNCTION correction_proposal_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='INSERT' THEN IF NEW.original_maker<>NEW.maker OR NEW.state<>'PROPOSED' OR NEW.checker IS NOT NULL THEN RAISE EXCEPTION 'New correction proposal must identify its original maker' USING ERRCODE='23514'; END IF; RETURN NEW; END IF;
 IF (NEW.id,NEW.merchant_id,NEW.source_id,NEW.amount_minor,NEW.posting_month,NEW.rationale,NEW.payload_hash,NEW.original_maker,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.merchant_id,OLD.source_id,OLD.amount_minor,OLD.posting_month,OLD.rationale,OLD.payload_hash,OLD.original_maker,OLD.created_at) THEN RAISE EXCEPTION 'Frozen correction payload is immutable' USING ERRCODE='23514'; END IF;
 IF NEW.maker<>OLD.maker THEN
  IF OLD.state<>'PROPOSED' OR NEW.state<>'PROPOSED' OR NEW.checker IS NOT NULL OR NEW.version<>OLD.version+1 OR EXISTS(SELECT 1 FROM app_user WHERE id=OLD.maker AND active AND verified AND totp_confirmed AND platform_role='RECONCILIATION') OR NOT EXISTS(SELECT 1 FROM app_user WHERE id=NEW.maker AND active AND verified AND totp_confirmed AND platform_role='RECONCILIATION') THEN RAISE EXCEPTION 'Only an identical pending correction with a revoked maker may be adopted' USING ERRCODE='23514'; END IF;
 END IF;
 IF OLD.state IN('APPROVED','REJECTED') AND (NEW.state,NEW.maker,NEW.checker,NEW.decided_at) IS DISTINCT FROM (OLD.state,OLD.maker,OLD.checker,OLD.decided_at) THEN RAISE EXCEPTION 'Correction decision is retained' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER correction_proposal_identity_guard BEFORE INSERT OR UPDATE ON correction_proposal FOR EACH ROW EXECUTE FUNCTION correction_proposal_identity_guard();
CREATE TRIGGER correction_history_immutable BEFORE UPDATE OR DELETE ON correction_proposal_history FOR EACH ROW EXECUTE FUNCTION immutable_history();
CREATE TRIGGER exception_history_immutable BEFORE UPDATE OR DELETE ON exception_history FOR EACH ROW EXECUTE FUNCTION immutable_history();
INSERT INTO guard_registry SELECT tgname,md5(pg_get_triggerdef(oid)||pg_get_functiondef(tgfoid)) FROM pg_trigger WHERE tgname IN('correction_proposal_identity_guard','correction_history_immutable','exception_history_immutable');
GRANT SELECT,INSERT ON correction_proposal_history,exception_history TO mprp_runtime;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='mprp_purge') THEN GRANT SELECT,DELETE ON correction_proposal_history,exception_history TO mprp_purge; END IF; END $$;
