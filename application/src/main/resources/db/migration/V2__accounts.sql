CREATE TABLE app_user (
 id uuid PRIMARY KEY, email varchar(254) NOT NULL UNIQUE, password_hash varchar(255),
 verified boolean NOT NULL DEFAULT false, active boolean NOT NULL DEFAULT true,
 auth_epoch bigint NOT NULL DEFAULT 1 CHECK (auth_epoch>0), platform_role varchar(32),
 totp_cipher text, totp_confirmed boolean NOT NULL DEFAULT false,
 totp_last_counter bigint NOT NULL DEFAULT -1, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(platform_role IS NULL OR platform_role IN ('OPERATIONS','RECONCILIATION','FINANCE_APPROVER','AUDITOR'))
);
CREATE TABLE merchant (
 id uuid PRIMARY KEY, name varchar(100) NOT NULL, timezone varchar(64) NOT NULL DEFAULT 'UTC',
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACTIVE','SUSPENDED','REJECTED','CLOSING','CLOSED')),
 version bigint NOT NULL DEFAULT 1, provider_account_id uuid NOT NULL UNIQUE, bank_account_id uuid NOT NULL UNIQUE,
 ledger_sequence bigint NOT NULL DEFAULT 0, funds_version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE membership (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES app_user(id), merchant_id uuid NOT NULL REFERENCES merchant(id),
 role varchar(16) NOT NULL CHECK(role IN ('OWNER','FINANCE','ANALYST')), active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 1, UNIQUE(user_id,merchant_id)
);
CREATE INDEX membership_tenant ON membership(merchant_id,user_id) WHERE active;
CREATE TABLE account_token (
 id uuid PRIMARY KEY, token_digest char(64) NOT NULL UNIQUE, user_id uuid REFERENCES app_user(id),
 kind varchar(16) NOT NULL CHECK(kind IN ('VERIFY','RESET','INVITE')),
 merchant_id uuid REFERENCES merchant(id), issuer_id uuid REFERENCES app_user(id), email varchar(254), role varchar(16),
 expires_at timestamptz NOT NULL, consumed_at timestamptz, revoked_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE recovery_code (
 user_id uuid NOT NULL REFERENCES app_user(id), digest char(64) NOT NULL, used_at timestamptz,
 PRIMARY KEY(user_id,digest)
);
CREATE TABLE rate_event (bucket varchar(100) NOT NULL, occurred_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE INDEX rate_event_window ON rate_event(bucket,occurred_at);
CREATE TABLE account_mail (
 id uuid PRIMARY KEY, recipient varchar(254) NOT NULL, encrypted_body text NOT NULL,
 subject varchar(150) NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL, sent_at timestamptz, attempts integer NOT NULL DEFAULT 0,
 due_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE account_event (
 id uuid PRIMARY KEY, actor_id uuid, merchant_id uuid, action varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), detail varchar(1000) NOT NULL DEFAULT ''
);
CREATE FUNCTION protect_last_owner() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m uuid;
BEGIN
 m:=OLD.merchant_id;
 PERFORM 1 FROM merchant WHERE id=m FOR UPDATE;
 IF OLD.active AND OLD.role='OWNER' AND (TG_OP='DELETE' OR NOT NEW.active OR NEW.role<>'OWNER')
 AND EXISTS(SELECT 1 FROM merchant WHERE id=m AND status<>'CLOSED')
 AND NOT EXISTS(SELECT 1 FROM membership x JOIN app_user u ON u.id=x.user_id WHERE x.merchant_id=m AND x.id<>OLD.id AND x.active AND x.role='OWNER' AND u.active)
 THEN RAISE EXCEPTION 'last active owner cannot be removed' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER membership_last_owner BEFORE UPDATE OR DELETE ON membership FOR EACH ROW EXECUTE FUNCTION protect_last_owner();
CREATE FUNCTION protect_user_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.active AND NOT NEW.active THEN
   PERFORM m.id FROM merchant m JOIN membership x ON x.merchant_id=m.id WHERE x.user_id=OLD.id AND x.active ORDER BY m.id FOR UPDATE OF m;
   IF EXISTS(SELECT 1 FROM membership me JOIN merchant m ON m.id=me.merchant_id WHERE me.user_id=OLD.id AND me.active AND me.role='OWNER' AND m.status<>'CLOSED' AND NOT EXISTS(SELECT 1 FROM membership x JOIN app_user u ON u.id=x.user_id WHERE x.merchant_id=m.id AND x.user_id<>OLD.id AND x.role='OWNER' AND x.active AND u.active)) THEN RAISE EXCEPTION 'last active owner cannot be deactivated' USING ERRCODE='23514'; END IF;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER app_user_last_owner BEFORE UPDATE ON app_user FOR EACH ROW EXECUTE FUNCTION protect_user_owner();
CREATE FUNCTION limit_memberships() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.active AND (TG_OP='INSERT' OR NOT OLD.active) THEN
   PERFORM 1 FROM merchant WHERE id=NEW.merchant_id FOR UPDATE;
   IF (SELECT count(*) FROM membership WHERE merchant_id=NEW.merchant_id AND active)>=100 THEN RAISE EXCEPTION 'membership limit' USING ERRCODE='23514'; END IF;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER membership_limit BEFORE INSERT OR UPDATE ON membership FOR EACH ROW EXECUTE FUNCTION limit_memberships();
CREATE TRIGGER account_event_immutable BEFORE UPDATE OR DELETE ON account_event FOR EACH ROW EXECUTE FUNCTION immutable_history();
