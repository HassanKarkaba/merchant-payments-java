CREATE TABLE payout_proposal_version(payout_id uuid NOT NULL REFERENCES payout(id),merchant_id uuid NOT NULL REFERENCES merchant(id),proposal_version bigint NOT NULL,snapshot_json text NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(payout_id,proposal_version));
CREATE TRIGGER payout_proposal_immutable BEFORE UPDATE OR DELETE ON payout_proposal_version FOR EACH ROW EXECUTE FUNCTION immutable_history();
GRANT SELECT,INSERT ON payout_proposal_version TO mprp_runtime;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='mprp_purge') THEN GRANT SELECT,DELETE ON payout_proposal_version TO mprp_purge; END IF; END $$;
