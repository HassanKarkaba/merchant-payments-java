CREATE TABLE beneficiary (
 id uuid PRIMARY KEY, merchant_id uuid NOT NULL REFERENCES merchant(id), maker uuid NOT NULL REFERENCES app_user(id), checker uuid REFERENCES app_user(id),
 token varchar(64) NOT NULL CHECK(token ~ '^acct_demo_[0-9a-f]{32}$'), status varchar(24) NOT NULL DEFAULT 'PROPOSED', version bigint NOT NULL DEFAULT 1,
 payload_hash varchar(64) NOT NULL, approved_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), CHECK(checker IS NULL OR checker<>maker)
);
CREATE UNIQUE INDEX beneficiary_one_active ON beneficiary(merchant_id) WHERE status='ACTIVE';
CREATE TABLE payout (
 id uuid PRIMARY KEY, merchant_id uuid NOT NULL REFERENCES merchant(id), maker uuid NOT NULL REFERENCES app_user(id), checker uuid REFERENCES app_user(id),
 beneficiary_id uuid NOT NULL REFERENCES beneficiary(id), beneficiary_version bigint NOT NULL, amount_minor bigint NOT NULL CHECK(amount_minor BETWEEN 1 AND 100000000),
 reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 2000), payload_hash varchar(64) NOT NULL, version bigint NOT NULL DEFAULT 1,
 status varchar(32) NOT NULL DEFAULT 'PROPOSED', reserved boolean NOT NULL DEFAULT false, approved_at timestamptz, approval_expires_at timestamptz,
 operation_id uuid, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), CHECK(checker IS NULL OR checker<>maker)
);
CREATE INDEX payout_reservations ON payout(merchant_id,status) WHERE reserved;
CREATE TABLE settlement_batch (id uuid PRIMARY KEY,merchant_id uuid NOT NULL REFERENCES merchant(id),provider_id text NOT NULL,net_minor bigint NOT NULL,
 cutoff text NOT NULL DEFAULT '',transfer_reference text NOT NULL DEFAULT '',sources_json text NOT NULL,occurred_at timestamptz NOT NULL,UNIQUE(merchant_id,provider_id));
CREATE TABLE bank_transfer(id uuid PRIMARY KEY,merchant_id uuid NOT NULL REFERENCES merchant(id),batch_id uuid REFERENCES settlement_batch(id),payout_id uuid REFERENCES payout(id),
 kind varchar(16) NOT NULL,reference text NOT NULL,amount_minor bigint NOT NULL CHECK(amount_minor<>0),occurred_at timestamptz NOT NULL,UNIQUE(merchant_id,reference),UNIQUE(batch_id),UNIQUE(payout_id));
CREATE TABLE dispute(id uuid PRIMARY KEY,merchant_id uuid NOT NULL REFERENCES merchant(id),payment_id uuid NOT NULL UNIQUE REFERENCES payment(id),provider_id text NOT NULL,
 amount_minor bigint NOT NULL CHECK(amount_minor>0),status varchar(16) NOT NULL CHECK(status IN('OPEN','RESPONDED','WON','LOST')),version bigint NOT NULL,
 response_deadline timestamptz NOT NULL,response_text text,response_operation_id uuid,reminder_48_at timestamptz,reminder_24_at timestamptz,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(merchant_id,provider_id));
CREATE TABLE workflow_receipt(id uuid PRIMARY KEY,merchant_id uuid NOT NULL REFERENCES merchant(id),actor uuid NOT NULL REFERENCES app_user(id),kind text NOT NULL,
 resource_id uuid NOT NULL,action_key varchar(128) NOT NULL,fingerprint varchar(64) NOT NULL,result_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(merchant_id,actor,kind,resource_id,action_key));
CREATE TABLE statement_import(id uuid PRIMARY KEY,merchant_id uuid NOT NULL REFERENCES merchant(id),uploader uuid NOT NULL REFERENCES app_user(id),kind varchar(24) NOT NULL,
 account_id text NOT NULL,statement_id varchar(64) NOT NULL,manifest_id text NOT NULL,period_start timestamptz NOT NULL,period_end timestamptz NOT NULL,
 opening_minor bigint NOT NULL,closing_minor bigint NOT NULL,declared_count integer NOT NULL CHECK(declared_count BETWEEN 0 AND 10000),declared_net bigint NOT NULL,
 file_hash varchar(64) NOT NULL,metadata_hash varchar(64) NOT NULL,content_cipher bytea,parsed_cipher bytea,errors_json text NOT NULL DEFAULT '[]',manifest_json text,
 status varchar(24) NOT NULL,version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),expires_at timestamptz NOT NULL,confirmed_at timestamptz,
 CHECK(period_end>period_start),CHECK(period_end-period_start<=interval '31 days'),UNIQUE(merchant_id,kind,account_id,statement_id,file_hash));
CREATE TABLE statement_line(id uuid PRIMARY KEY,merchant_id uuid NOT NULL REFERENCES merchant(id),kind varchar(24) NOT NULL,account_id text NOT NULL,line_id varchar(64) NOT NULL,
 source_id text NOT NULL,event_type varchar(32) NOT NULL,reference text NOT NULL,batch_id text NOT NULL,occurred_at timestamptz NOT NULL,amount_minor bigint NOT NULL,
 currency char(3) NOT NULL CHECK(currency='USD'),row_hash varchar(64) NOT NULL,canonical_json text NOT NULL,UNIQUE(merchant_id,kind,account_id,line_id));
CREATE INDEX statement_line_work ON statement_line(merchant_id,kind,occurred_at);
CREATE TABLE statement_import_row(import_id uuid NOT NULL REFERENCES statement_import(id),line_id uuid NOT NULL REFERENCES statement_line(id),position integer NOT NULL,
 PRIMARY KEY(import_id,position));
CREATE TABLE match_group(id uuid PRIMARY KEY,merchant_id uuid NOT NULL REFERENCES merchant(id),actor uuid NOT NULL REFERENCES app_user(id),status varchar(16) NOT NULL DEFAULT 'MATCHED',
 reason text NOT NULL DEFAULT '',version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),reversed_at timestamptz);
CREATE TABLE allocation(id uuid PRIMARY KEY,group_id uuid NOT NULL REFERENCES match_group(id),line_id uuid NOT NULL REFERENCES statement_line(id),source_id uuid NOT NULL REFERENCES economic_source(id),
 amount_minor bigint NOT NULL CHECK(amount_minor>=0),UNIQUE(group_id,line_id,source_id));
CREATE INDEX allocation_source ON allocation(source_id);
CREATE INDEX allocation_line ON allocation(line_id);
CREATE TABLE reconciliation_exception(id uuid PRIMARY KEY,merchant_id uuid NOT NULL REFERENCES merchant(id),category varchar(48) NOT NULL,source_reference text NOT NULL,
 line_id uuid REFERENCES statement_line(id),amount_minor bigint NOT NULL DEFAULT 0,state varchar(24) NOT NULL DEFAULT 'OPEN',owner uuid REFERENCES app_user(id),reason text NOT NULL DEFAULT '',
 history_json text NOT NULL DEFAULT '[]',version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),resolved_at timestamptz,UNIQUE(merchant_id,category,source_reference));
CREATE TABLE correction_proposal(id uuid PRIMARY KEY,merchant_id uuid NOT NULL REFERENCES merchant(id),source_id uuid NOT NULL UNIQUE REFERENCES economic_source(id),maker uuid NOT NULL REFERENCES app_user(id),checker uuid REFERENCES app_user(id),
 amount_minor bigint NOT NULL,posting_month date NOT NULL,rationale text NOT NULL,payload_hash varchar(64) NOT NULL,state varchar(16) NOT NULL DEFAULT 'PROPOSED',version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(),decided_at timestamptz,CHECK(checker IS NULL OR checker<>maker));
CREATE TABLE period_proposal(id uuid PRIMARY KEY,merchant_id uuid NOT NULL REFERENCES merchant(id),month date NOT NULL,maker uuid NOT NULL REFERENCES app_user(id),checker uuid REFERENCES app_user(id),
 decision_kind varchar(16) NOT NULL,reason text NOT NULL DEFAULT '',snapshot_json text NOT NULL,snapshot_hash varchar(64) NOT NULL,state varchar(16) NOT NULL DEFAULT 'PROPOSED',version bigint NOT NULL DEFAULT 1,
 prior_report_id uuid,created_at timestamptz NOT NULL DEFAULT now(),decided_at timestamptz,CHECK(checker IS NULL OR checker<>maker));
CREATE TABLE period_report(id uuid PRIMARY KEY,merchant_id uuid NOT NULL REFERENCES merchant(id),month date NOT NULL,version bigint NOT NULL,snapshot_json text NOT NULL,snapshot_hash varchar(64) NOT NULL,
 valid boolean NOT NULL DEFAULT true,proposal_id uuid NOT NULL UNIQUE REFERENCES period_proposal(id),created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(merchant_id,month,version));

CREATE FUNCTION enforce_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE l statement_line; s economic_source; g match_group; used_l numeric; used_s numeric; prior_l bigint; prior_s bigint;
BEGIN
 SELECT * INTO l FROM statement_line WHERE id=NEW.line_id FOR UPDATE;
 SELECT * INTO s FROM economic_source WHERE id=NEW.source_id FOR UPDATE;
 SELECT * INTO g FROM match_group WHERE id=NEW.group_id;
 IF g.status<>'MATCHED' OR l.merchant_id<>s.merchant_id OR g.merchant_id<>l.merchant_id OR l.currency<>'USD' OR NOT s.recognized OR NOT EXISTS(SELECT 1 FROM merchant m WHERE m.id=l.merchant_id AND l.account_id=CASE WHEN l.kind='BANK' THEN m.bank_account_id::text ELSE m.provider_account_id::text END) THEN RAISE EXCEPTION 'allocation scope'; END IF;
 IF EXISTS(SELECT 1 FROM accounting_period p WHERE p.merchant_id=l.merchant_id AND p.month=date_trunc('month',l.occurred_at AT TIME ZONE 'UTC')::date AND p.status IN('CLOSED','REOPEN_PROPOSED')) THEN RAISE EXCEPTION 'closed allocation period'; END IF;
 IF sign(l.amount_minor)<>sign(s.amount_minor) THEN RAISE EXCEPTION 'allocation sign'; END IF;
 IF l.kind='PROCESSOR_ECONOMIC' AND s.event_type IN('SETTLEMENT','PAYOUT','BATCH_ZERO') OR l.kind IN('PROCESSOR_TRANSFER','BANK') AND s.event_type NOT IN('SETTLEMENT','PAYOUT','BATCH_ZERO') THEN RAISE EXCEPTION 'allocation layer'; END IF;
 IF l.kind<>'BANK' AND (l.event_type<>s.event_type OR (s.event_type<>'BATCH_ZERO' AND NOT ((l.source_id=s.provider_source_id AND l.reference=s.provider_source_id) OR (l.source_id=s.batch_id AND l.reference=s.batch_id)))) THEN RAISE EXCEPTION 'allocation identity'; END IF;
 IF l.kind='BANK' AND l.reference<>s.provider_source_id AND NOT EXISTS(SELECT 1 FROM bank_transfer t LEFT JOIN settlement_batch b ON b.id=t.batch_id WHERE t.merchant_id=l.merchant_id AND t.reference=l.reference AND t.amount_minor=s.amount_minor AND ((s.event_type='PAYOUT' AND t.payout_id=s.resource_id) OR (s.event_type='SETTLEMENT' AND b.provider_id=s.batch_id))) THEN RAISE EXCEPTION 'bank reference'; END IF;
 SELECT coalesce(sum(a.amount_minor),0),count(*) INTO used_l,prior_l FROM allocation a JOIN match_group m ON m.id=a.group_id WHERE a.line_id=l.id AND m.status='MATCHED';
 SELECT coalesce(sum(a.amount_minor),0),count(*) INTO used_s,prior_s FROM allocation a JOIN match_group m ON m.id=a.group_id JOIN statement_line x ON x.id=a.line_id WHERE a.source_id=s.id AND x.kind=l.kind AND m.status='MATCHED';
 IF l.amount_minor=0 THEN
   IF NEW.amount_minor<>0 OR l.source_id<>s.provider_source_id OR s.provider_source_id<>s.batch_id OR l.batch_id<>s.batch_id OR prior_l>0 OR prior_s>0 THEN RAISE EXCEPTION 'zero allocation identity'; END IF;
 ELSIF NEW.amount_minor=0 OR used_l+NEW.amount_minor>abs(l.amount_minor::numeric) OR used_s+NEW.amount_minor>abs(s.amount_minor::numeric) THEN RAISE EXCEPTION 'allocation capacity'; END IF;
 IF EXISTS(SELECT 1 FROM allocation a JOIN statement_line x ON x.id=a.line_id WHERE a.group_id=g.id AND (x.kind<>l.kind OR sign(x.amount_minor)<>sign(l.amount_minor))) THEN RAISE EXCEPTION 'mixed allocation group'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER allocation_guard BEFORE INSERT ON allocation FOR EACH ROW EXECUTE FUNCTION enforce_allocation();
CREATE FUNCTION immutable_allocation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'allocation history is immutable'; END $$;
CREATE TRIGGER allocation_immutable BEFORE UPDATE OR DELETE ON allocation FOR EACH ROW EXECUTE FUNCTION immutable_allocation();
CREATE FUNCTION immutable_report() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF TG_OP='DELETE' OR NEW.snapshot_json<>OLD.snapshot_json OR NEW.snapshot_hash<>OLD.snapshot_hash THEN RAISE EXCEPTION 'report evidence immutable'; END IF; RETURN NEW; END $$;
CREATE TRIGGER period_report_immutable BEFORE UPDATE OR DELETE ON period_report FOR EACH ROW EXECUTE FUNCTION immutable_report();
ALTER TABLE accounting_period ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX accounting_period_public_id ON accounting_period(id);
CREATE FUNCTION allocation_group_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE gid uuid; total_lines bigint; total_sources bigint; bad bigint;
BEGIN
 gid:=NEW.group_id;
 SELECT count(DISTINCT line_id),count(DISTINCT source_id) INTO total_lines,total_sources FROM allocation WHERE group_id=gid;
 IF total_lines>1 AND total_sources>1 THEN RAISE EXCEPTION 'many-to-many allocation excluded'; END IF;
 IF (SELECT count(*) FROM allocation WHERE group_id=gid)>10000 THEN RAISE EXCEPTION 'allocation count exceeds bound'; END IF;
 SELECT count(*) INTO bad FROM statement_line l WHERE l.id IN(SELECT line_id FROM allocation WHERE group_id=gid) AND abs(l.amount_minor::numeric)<>(SELECT coalesce(sum(a.amount_minor),0) FROM allocation a JOIN match_group g ON g.id=a.group_id WHERE a.line_id=l.id AND g.status='MATCHED');
 IF bad>0 THEN RAISE EXCEPTION 'selected statement line not fully allocated'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER allocation_group_complete_guard AFTER INSERT ON allocation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION allocation_group_complete();

CREATE TRIGGER workflow_receipt_immutable BEFORE UPDATE OR DELETE ON workflow_receipt FOR EACH ROW EXECUTE FUNCTION immutable_history();
ALTER TABLE match_group ADD CONSTRAINT match_group_state CHECK(status IN('MATCHED','REVERSED'));
CREATE FUNCTION protect_match_group() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.id,NEW.merchant_id,NEW.actor,NEW.created_at) IS DISTINCT FROM (OLD.id,OLD.merchant_id,OLD.actor,OLD.created_at) THEN RAISE EXCEPTION 'match identity immutable'; END IF;
 IF NEW.status IS DISTINCT FROM OLD.status THEN
  IF OLD.status<>'MATCHED' OR NEW.status<>'REVERSED' THEN RAISE EXCEPTION 'match reversal is permanent'; END IF;
  IF EXISTS(SELECT 1 FROM allocation a JOIN statement_line l ON l.id=a.line_id JOIN accounting_period p ON p.merchant_id=l.merchant_id AND p.month=date_trunc('month',l.occurred_at AT TIME ZONE 'UTC')::date WHERE a.group_id=OLD.id AND p.status IN('CLOSED','REOPEN_PROPOSED')) THEN RAISE EXCEPTION 'reopen period before reversal'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER match_group_protection BEFORE UPDATE ON match_group FOR EACH ROW EXECUTE FUNCTION protect_match_group();
CREATE TRIGGER statement_line_immutable BEFORE UPDATE OR DELETE ON statement_line FOR EACH ROW EXECUTE FUNCTION immutable_history();
