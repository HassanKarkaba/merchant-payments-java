CREATE FUNCTION correction_recognition_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.event_type IN('CORRECTION_CREDIT','CORRECTION_DEBIT') AND NEW.recognized AND NOT EXISTS(SELECT 1 FROM correction_proposal p WHERE p.source_id=NEW.id AND p.merchant_id=NEW.merchant_id AND p.amount_minor=NEW.amount_minor AND p.posting_month=NEW.posting_month AND p.state='APPROVED' AND p.checker IS NOT NULL AND p.checker<>p.maker) THEN RAISE EXCEPTION 'Correction recognition requires retained independent approval' USING ERRCODE='23514'; END IF; RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER correction_recognition_guard AFTER INSERT OR UPDATE ON economic_source DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION correction_recognition_guard();
INSERT INTO guard_registry SELECT tgname,md5(pg_get_triggerdef(oid)||pg_get_functiondef(tgfoid)) FROM pg_trigger WHERE tgname='correction_recognition_guard';
