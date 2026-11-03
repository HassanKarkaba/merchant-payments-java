ALTER TABLE publication_outbox ADD COLUMN run_id uuid;
ALTER TABLE publication_outbox ADD COLUMN dispatch_epoch bigint;
