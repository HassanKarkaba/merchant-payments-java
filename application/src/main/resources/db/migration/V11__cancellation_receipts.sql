CREATE TABLE operation_cancel_receipt(id uuid PRIMARY KEY,merchant_id uuid NOT NULL,operation_id uuid NOT NULL,actor_id uuid NOT NULL,action_key varchar(128) NOT NULL,fingerprint char(64) NOT NULL,expected_version bigint NOT NULL,proposal_hash char(64) NOT NULL,decision varchar(20) NOT NULL CHECK(decision='CANCEL'),created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(merchant_id,operation_id,actor_id,action_key));
CREATE TRIGGER operation_cancel_receipt_immutable BEFORE UPDATE OR DELETE ON operation_cancel_receipt FOR EACH ROW EXECUTE FUNCTION immutable_history();
GRANT SELECT,INSERT ON operation_cancel_receipt TO mprp_runtime;
INSERT INTO guard_registry SELECT tgname,md5(pg_get_triggerdef(oid)||pg_get_functiondef(tgfoid)) FROM pg_trigger WHERE tgname='operation_cancel_receipt_immutable';
