INSERT INTO guard_registry SELECT tgname,md5(pg_get_triggerdef(oid)||pg_get_functiondef(tgfoid)) FROM pg_trigger WHERE NOT tgisinternal AND tgname IN('merchant_closed_time','payout_proposal_immutable','encryption_key_identity_immutable');
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='mprp_purge') THEN GRANT SELECT,INSERT ON encryption_key_identity TO mprp_purge; END IF; END $$;

DO $$ BEGIN EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC',current_database()); END $$;
ALTER TABLE system_control ADD COLUMN provider_feed_blocked boolean NOT NULL DEFAULT false;
