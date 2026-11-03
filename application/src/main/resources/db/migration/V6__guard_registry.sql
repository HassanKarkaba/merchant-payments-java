ALTER TABLE system_control ADD COLUMN broker_ready boolean NOT NULL DEFAULT false;
ALTER TABLE system_control ADD COLUMN broker_checked_at timestamptz;
CREATE TABLE guard_registry(name text PRIMARY KEY,definition_hash text NOT NULL);
INSERT INTO guard_registry SELECT tgname,md5(pg_get_triggerdef(oid)||pg_get_functiondef(tgfoid)) FROM pg_trigger WHERE NOT tgisinternal AND tgrelid IN (SELECT oid FROM pg_class WHERE relnamespace='public'::regnamespace) AND tgname IN('journal_balanced','entry_balanced','journal_immutable','entry_immutable','source_identity','operation_identity','audit_immutable','allocation_guard','allocation_group_complete_guard','match_group_protection','statement_line_immutable','workflow_receipt_immutable','period_report_immutable','allocation_immutable','receipt_immutable','membership_last_owner','app_user_last_owner','membership_limit','account_event_immutable');
GRANT SELECT ON guard_registry TO mprp_runtime;
REVOKE INSERT,UPDATE,DELETE ON guard_registry FROM mprp_runtime;
ALTER TABLE notification ADD COLUMN event_id uuid;
CREATE UNIQUE INDEX notification_event ON notification(user_id,event_id) WHERE event_id IS NOT NULL;
