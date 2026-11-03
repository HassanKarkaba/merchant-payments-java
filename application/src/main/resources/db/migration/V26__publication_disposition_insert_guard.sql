CREATE OR REPLACE FUNCTION publication_disposition_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='UPDATE' THEN
  IF OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at THEN RAISE EXCEPTION 'Durable publication disposition cannot be withdrawn'; END IF;
 END IF;
 IF NEW.consumed_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM consumer_receipt r WHERE r.consumer='worker.s1' AND r.event_id=NEW.id AND r.digest=NEW.digest) THEN RAISE EXCEPTION 'Publication disposition requires its immutable consumer receipt'; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER publication_disposition ON publication_outbox;
CREATE TRIGGER publication_disposition BEFORE INSERT OR UPDATE ON publication_outbox FOR EACH ROW EXECUTE FUNCTION publication_disposition_guard();
UPDATE guard_registry SET definition_hash=(SELECT md5(pg_get_triggerdef(oid)||pg_get_functiondef(tgfoid)) FROM pg_trigger WHERE tgname='publication_disposition') WHERE name='publication_disposition';
