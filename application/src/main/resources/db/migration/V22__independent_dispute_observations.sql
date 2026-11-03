-- Reservations were valid at admission. Independent dispute truth can later overlap an UNKNOWN
-- refund reservation, which must remain held until that original provider key has a proven outcome.
DO $$ DECLARE item record; BEGIN FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='payment'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%<= captured_minor%' LOOP EXECUTE format('ALTER TABLE payment DROP CONSTRAINT %I',item.conname); END LOOP; END $$;
ALTER TABLE payment ADD CONSTRAINT successful_refunds_within_capture CHECK(refunded_minor<=captured_minor);
