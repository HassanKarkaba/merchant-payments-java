CREATE FUNCTION job_lease_commit_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE c system_control%ROWTYPE; BEGIN
 SELECT * INTO c FROM system_control WHERE id=true;
 IF OLD.lease_until IS NULL OR OLD.lease_until<=clock_timestamp() OR OLD.run_id IS DISTINCT FROM c.run_id OR OLD.dispatch_epoch IS DISTINCT FROM c.dispatch_epoch THEN RAISE EXCEPTION 'Expired or fenced worker cannot commit a result' USING ERRCODE='23514'; END IF; RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER job_lease_commit_guard AFTER UPDATE ON durable_job DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(OLD.state='RUNNING' AND NEW.generation=OLD.generation AND NEW.owner=OLD.owner) EXECUTE FUNCTION job_lease_commit_guard();
CREATE FUNCTION publisher_lease_commit_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE c system_control%ROWTYPE; BEGIN
 SELECT * INTO c FROM system_control WHERE id=true;
 IF OLD.lease_until IS NULL OR OLD.lease_until<=clock_timestamp() OR OLD.run_id IS DISTINCT FROM c.run_id OR OLD.dispatch_epoch IS DISTINCT FROM c.dispatch_epoch THEN RAISE EXCEPTION 'Expired or fenced publisher cannot acknowledge a result' USING ERRCODE='23514'; END IF; RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER publisher_lease_commit_guard AFTER UPDATE ON publication_outbox DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(OLD.published_at IS NULL AND NEW.published_at IS NOT NULL AND OLD.owner=NEW.owner AND OLD.generation=NEW.generation) EXECUTE FUNCTION publisher_lease_commit_guard();
INSERT INTO guard_registry SELECT tgname,md5(pg_get_triggerdef(oid)||pg_get_functiondef(tgfoid)) FROM pg_trigger WHERE tgname IN('job_lease_commit_guard','publisher_lease_commit_guard');
CREATE INDEX ledger_entry_journal ON ledger_entry(journal_id);
