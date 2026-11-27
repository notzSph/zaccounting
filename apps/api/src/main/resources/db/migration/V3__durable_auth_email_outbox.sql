CREATE TABLE auth_email_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_type VARCHAR(32) NOT NULL,
    encrypted_payload BYTEA,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts SMALLINT NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    lock_token UUID,
    locked_until TIMESTAMPTZ,
    CONSTRAINT ck_auth_email_outbox_message_type
        CHECK (message_type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'NOOP')),
    CONSTRAINT ck_auth_email_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
    CONSTRAINT ck_auth_email_outbox_attempts
        CHECK (attempts BETWEEN 0 AND 8),
    CONSTRAINT ck_auth_email_outbox_lease
        CHECK (
            (status = 'PROCESSING' AND lock_token IS NOT NULL AND locked_until IS NOT NULL)
            OR (status <> 'PROCESSING' AND lock_token IS NULL AND locked_until IS NULL)
        ),
    CONSTRAINT ck_auth_email_outbox_payload_retention
        CHECK (
            (status IN ('PENDING', 'PROCESSING') AND encrypted_payload IS NOT NULL)
            OR (status IN ('SENT', 'FAILED') AND encrypted_payload IS NULL)
        ),
    CONSTRAINT ck_auth_email_outbox_sent_at
        CHECK ((status = 'SENT' AND sent_at IS NOT NULL) OR status <> 'SENT')
);

CREATE INDEX ix_auth_email_outbox_pending
    ON auth_email_outbox (available_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX ix_auth_email_outbox_expired_lease
    ON auth_email_outbox (locked_until)
    WHERE status = 'PROCESSING';

CREATE INDEX ix_auth_email_outbox_terminal_retention
    ON auth_email_outbox (created_at)
    WHERE status IN ('SENT', 'FAILED');
