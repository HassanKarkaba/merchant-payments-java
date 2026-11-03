CREATE TABLE quarantine_payload(id uuid PRIMARY KEY REFERENCES quarantine(id),topic text NOT NULL,envelope text NOT NULL);
CREATE TABLE redrive_receipt(id uuid PRIMARY KEY,quarantine_id uuid NOT NULL REFERENCES quarantine(id),actor_id uuid NOT NULL,event_id uuid NOT NULL,digest char(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TRIGGER redrive_receipt_immutable BEFORE UPDATE OR DELETE ON redrive_receipt FOR EACH ROW EXECUTE FUNCTION immutable_history();
GRANT SELECT,INSERT ON quarantine_payload,redrive_receipt TO mprp_runtime;
INSERT INTO guard_registry SELECT tgname,md5(pg_get_triggerdef(oid)||pg_get_functiondef(tgfoid)) FROM pg_trigger WHERE tgname='redrive_receipt_immutable';
