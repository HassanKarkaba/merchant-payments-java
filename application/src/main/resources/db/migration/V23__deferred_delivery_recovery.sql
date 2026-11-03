CREATE INDEX deferred_event_next ON deferred_event(consumer,((envelope::jsonb->>'aggregate_id')),(((envelope::jsonb->>'stream_sequence')::bigint)));
CREATE TABLE deferred_failure(consumer text NOT NULL,event_id uuid NOT NULL,quarantine_id uuid NOT NULL REFERENCES quarantine(id),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(consumer,event_id));
CREATE TRIGGER deferred_failure_immutable BEFORE UPDATE OR DELETE ON deferred_failure FOR EACH ROW EXECUTE FUNCTION immutable_history();
GRANT SELECT,INSERT ON deferred_failure TO mprp_runtime;
INSERT INTO guard_registry SELECT tgname,md5(pg_get_triggerdef(oid)||pg_get_functiondef(tgfoid)) FROM pg_trigger WHERE tgname='deferred_failure_immutable';
