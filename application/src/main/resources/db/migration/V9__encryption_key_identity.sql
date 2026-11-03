CREATE TABLE encryption_key_identity(key_id varchar(32) PRIMARY KEY,material_digest char(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TRIGGER encryption_key_identity_immutable BEFORE UPDATE OR DELETE ON encryption_key_identity FOR EACH ROW EXECUTE FUNCTION immutable_history();
GRANT SELECT,INSERT ON encryption_key_identity TO mprp_runtime;
