ALTER TABLE appointment
    ADD COLUMN idempotency_key VARCHAR(128) NULL,
    ADD COLUMN request_fingerprint VARCHAR(64) NULL,
    ADD CONSTRAINT uk_appointment_idempotency_key UNIQUE (idempotency_key);
