ALTER TABLE app_user
    ADD COLUMN email_normalized VARCHAR(320),
    ADD COLUMN username VARCHAR(64),
    ADD COLUMN username_normalized VARCHAR(64),
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE app_user
    ADD CONSTRAINT uq_app_user_email_normalized UNIQUE (email_normalized),
    ADD CONSTRAINT uq_app_user_username_normalized UNIQUE (username_normalized),
    ADD CONSTRAINT ck_app_user_email_normalized
        CHECK (
            email_normalized = lower(btrim(email_normalized))
            AND email_normalized !~ '[[:space:]]'
        ),
    ADD CONSTRAINT ck_app_user_username_normalized
        CHECK (username_normalized ~ '^[a-z0-9][a-z0-9_-]{2,63}$'),
    ADD CONSTRAINT ck_app_user_username
        CHECK (username IS NULL OR length(btrim(username)) BETWEEN 3 AND 64),
    ADD CONSTRAINT ck_app_user_login_identifiers_complete
        CHECK (
            (email_normalized IS NULL AND username IS NULL AND username_normalized IS NULL)
            OR (email_normalized IS NOT NULL AND username IS NOT NULL AND username_normalized IS NOT NULL)
        );

CREATE TABLE password_credential (
    user_id UUID PRIMARY KEY,
    password_hash VARCHAR(512) NOT NULL,
    failed_login_count SMALLINT NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_password_credential_user
        FOREIGN KEY (user_id)
        REFERENCES app_user (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_password_credential_hash_argon2id
        CHECK (password_hash LIKE '$argon2id$%'),
    CONSTRAINT ck_password_credential_failed_login_count
        CHECK (failed_login_count BETWEEN 0 AND 5)
);

CREATE TABLE auth_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    token_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_auth_session_token_digest UNIQUE (token_digest),
    CONSTRAINT fk_auth_session_user
        FOREIGN KEY (user_id)
        REFERENCES app_user (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_auth_session_token_digest
        CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_auth_session_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX ix_auth_session_user_expiry
    ON auth_session (user_id, expires_at DESC);

CREATE TABLE email_verification_token (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    token_digest VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT fk_email_verification_token_user
        FOREIGN KEY (user_id)
        REFERENCES app_user (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_email_verification_token_digest
        CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_email_verification_token_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX ix_email_verification_token_user_expiry
    ON email_verification_token (user_id, expires_at DESC);

CREATE TABLE password_reset_token (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    token_digest VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT fk_password_reset_token_user
        FOREIGN KEY (user_id)
        REFERENCES app_user (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_password_reset_token_digest
        CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_password_reset_token_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX ix_password_reset_token_user_expiry
    ON password_reset_token (user_id, expires_at DESC);

CREATE TABLE auth_rate_limit_bucket (
    bucket_key VARCHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_auth_rate_limit_bucket_key
        CHECK (bucket_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_auth_rate_limit_request_count
        CHECK (request_count > 0),
    CONSTRAINT ck_auth_rate_limit_expiry
        CHECK (expires_at > window_started_at)
);

CREATE INDEX ix_auth_rate_limit_bucket_expiry
    ON auth_rate_limit_bucket (expires_at);
