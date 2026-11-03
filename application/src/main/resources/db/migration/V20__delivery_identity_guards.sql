ALTER TABLE journal ADD CONSTRAINT economic_journal_purpose CHECK(purpose='ECONOMIC');
CREATE FUNCTION callback_inbox_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF (NEW.id,NEW.digest,NEW.payload,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.digest,OLD.payload,OLD.created_at) THEN RAISE EXCEPTION 'Authenticated inbox identity is immutable'; END IF; RETURN NEW; END $$;
CREATE TRIGGER callback_inbox_identity BEFORE UPDATE ON callback_inbox FOR EACH ROW EXECUTE FUNCTION callback_inbox_identity_guard();
CREATE FUNCTION publication_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF (NEW.id,NEW.merchant_id,NEW.aggregate_id,NEW.stream,NEW.sequence,NEW.envelope,NEW.digest,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.merchant_id,OLD.aggregate_id,OLD.stream,OLD.sequence,OLD.envelope,OLD.digest,OLD.created_at) THEN RAISE EXCEPTION 'Publication identity is immutable'; END IF; RETURN NEW; END $$;
CREATE TRIGGER publication_identity BEFORE UPDATE ON publication_outbox FOR EACH ROW EXECUTE FUNCTION publication_identity_guard();
CREATE FUNCTION durable_job_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='UPDATE' AND (NEW.id,NEW.operation_id,NEW.kind,NEW.resource_id,NEW.merchant_id,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.operation_id,OLD.kind,OLD.resource_id,OLD.merchant_id,OLD.created_at) THEN RAISE EXCEPTION 'Durable job identity is immutable'; END IF;
 IF NEW.operation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM operation o WHERE o.id=NEW.operation_id AND o.merchant_id=NEW.merchant_id AND o.resource_id=NEW.resource_id AND NEW.kind='OPERATION') THEN RAISE EXCEPTION 'Durable job operation scope mismatch'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER durable_job_identity BEFORE INSERT OR UPDATE ON durable_job FOR EACH ROW EXECUTE FUNCTION durable_job_identity_guard();
INSERT INTO guard_registry SELECT tgname,md5(pg_get_triggerdef(oid)||pg_get_functiondef(tgfoid)) FROM pg_trigger WHERE tgname IN('callback_inbox_identity','publication_identity','durable_job_identity');
