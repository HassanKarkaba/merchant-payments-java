ALTER TABLE publication_outbox ADD COLUMN consumed_at timestamptz;
UPDATE publication_outbox o SET consumed_at=r.created_at FROM consumer_receipt r WHERE r.consumer='worker.s1' AND r.event_id=o.id AND r.digest=o.digest;
CREATE INDEX publication_missing_disposition ON publication_outbox(created_at,id) WHERE consumed_at IS NULL;
CREATE FUNCTION publication_disposition_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at THEN RAISE EXCEPTION 'Durable publication disposition cannot be withdrawn'; END IF;
 IF OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM consumer_receipt r WHERE r.consumer='worker.s1' AND r.event_id=NEW.id AND r.digest=NEW.digest) THEN RAISE EXCEPTION 'Publication disposition requires its immutable consumer receipt'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER publication_disposition BEFORE UPDATE ON publication_outbox FOR EACH ROW EXECUTE FUNCTION publication_disposition_guard();
INSERT INTO guard_registry SELECT tgname,md5(pg_get_triggerdef(oid)||pg_get_functiondef(tgfoid)) FROM pg_trigger WHERE tgname='publication_disposition';
