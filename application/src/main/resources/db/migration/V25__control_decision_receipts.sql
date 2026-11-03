ALTER TABLE recovery_inventory ADD CONSTRAINT recovery_phase CHECK(status IN('PROPOSED','RELEASING','RELEASED'));
ALTER TABLE recovery_inventory ADD CONSTRAINT recovery_independent_checker CHECK(checker_id IS NULL OR checker_id<>maker_id);
ALTER TABLE recovery_inventory ADD CONSTRAINT recovery_decided_checker CHECK(status='PROPOSED' OR checker_id IS NOT NULL);
ALTER TABLE purge_receipt ADD CONSTRAINT purge_phase CHECK(phase IN('PROPOSED','APPROVED','RETIRED','PROVIDER_PURGED','COMPLETED'));
ALTER TABLE purge_receipt ADD CONSTRAINT purge_decided_checker CHECK(phase='PROPOSED' OR (checker_id IS NOT NULL AND approved_at IS NOT NULL));
CREATE FUNCTION control_recovery_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Recovery decision receipts are retained'; END IF;
 IF (NEW.id,NEW.maker_id,NEW.fingerprint,NEW.content,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.maker_id,OLD.fingerprint,OLD.content,OLD.created_at)
 OR (OLD.checker_id IS NOT NULL AND NEW.checker_id IS DISTINCT FROM OLD.checker_id)
 OR (OLD.status='RELEASING' AND NEW.status NOT IN('RELEASING','RELEASED'))
 OR (OLD.status='RELEASED' AND NEW.status<>'RELEASED')
 OR (OLD.status='PROPOSED' AND NEW.status NOT IN('PROPOSED','RELEASING')) THEN RAISE EXCEPTION 'Immutable recovery identity or decision'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER control_recovery_receipt BEFORE UPDATE OR DELETE ON recovery_inventory FOR EACH ROW EXECUTE FUNCTION control_recovery_receipt_guard();
CREATE FUNCTION control_purge_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Purge decision receipts are retained'; END IF;
 IF (NEW.id,NEW.merchant_id,NEW.maker_id,NEW.inventory,NEW.fingerprint,NEW.backup_inventory,NEW.provider_hash,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.merchant_id,OLD.maker_id,OLD.inventory,OLD.fingerprint,OLD.backup_inventory,OLD.provider_hash,OLD.created_at)
 OR (OLD.checker_id IS NOT NULL AND NEW.checker_id IS DISTINCT FROM OLD.checker_id)
 OR (OLD.approved_at IS NOT NULL AND NEW.approved_at IS DISTINCT FROM OLD.approved_at)
 OR (OLD.completed_at IS NOT NULL AND NEW.completed_at IS DISTINCT FROM OLD.completed_at)
 OR (array_position(ARRAY['PROPOSED','APPROVED','RETIRED','PROVIDER_PURGED','COMPLETED'],NEW.phase)<array_position(ARRAY['PROPOSED','APPROVED','RETIRED','PROVIDER_PURGED','COMPLETED'],OLD.phase))
 OR (OLD.phase='PROPOSED' AND NEW.phase NOT IN('PROPOSED','APPROVED'))
 OR (OLD.phase='APPROVED' AND NEW.phase NOT IN('APPROVED','RETIRED')) THEN RAISE EXCEPTION 'Immutable purge identity or decision'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER control_purge_receipt BEFORE UPDATE OR DELETE ON purge_receipt FOR EACH ROW EXECUTE FUNCTION control_purge_receipt_guard();
INSERT INTO guard_registry SELECT tgname,md5(pg_get_triggerdef(oid)||pg_get_functiondef(tgfoid)) FROM pg_trigger WHERE tgname IN('control_recovery_receipt','control_purge_receipt');
