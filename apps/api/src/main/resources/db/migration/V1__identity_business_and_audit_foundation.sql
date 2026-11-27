CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_app_user_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED'))
);

CREATE TABLE currency (
    code CHAR(3) PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    minor_unit_exponent SMALLINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_currency_code_uppercase
        CHECK (code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_currency_minor_unit_exponent
        CHECK (minor_unit_exponent BETWEEN 0 AND 4)
);

CREATE TABLE business (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    functional_currency_code CHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_by_user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_business_functional_currency
        FOREIGN KEY (functional_currency_code)
        REFERENCES currency (code)
        ON DELETE RESTRICT,
    CONSTRAINT fk_business_created_by_user
        FOREIGN KEY (created_by_user_id)
        REFERENCES app_user (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_business_name_not_blank
        CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_business_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE business_membership (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    invited_by_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_business_membership_business_user
        UNIQUE (business_id, user_id),
    CONSTRAINT uq_business_membership_business_id_id
        UNIQUE (business_id, id),
    CONSTRAINT fk_business_membership_business
        FOREIGN KEY (business_id)
        REFERENCES business (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_business_membership_user
        FOREIGN KEY (user_id)
        REFERENCES app_user (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_business_membership_invited_by
        FOREIGN KEY (invited_by_user_id)
        REFERENCES app_user (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_business_membership_role
        CHECK (role IN ('OWNER', 'MEMBER')),
    CONSTRAINT ck_business_membership_status
        CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'REMOVED'))
);

CREATE INDEX ix_business_membership_user_status
    ON business_membership (user_id, status, business_id);

CREATE TABLE membership_permission (
    business_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    permission VARCHAR(48) NOT NULL,
    granted_by_user_id UUID NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (business_id, membership_id, permission),
    CONSTRAINT fk_membership_permission_membership
        FOREIGN KEY (business_id, membership_id)
        REFERENCES business_membership (business_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_membership_permission_granted_by
        FOREIGN KEY (granted_by_user_id)
        REFERENCES app_user (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_membership_permission_name
        CHECK (permission IN (
            'MANAGE_MEMBERS',
            'SUBMIT_ENTRIES',
            'APPROVE_ENTRIES',
            'POST_ENTRIES',
            'MANAGE_ACCOUNTS',
            'IMPORT_DATA',
            'EXPORT_DATA'
        ))
);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id UUID,
    actor_type VARCHAR(24) NOT NULL,
    actor_user_id UUID,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_id UUID,
    correlation_id VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    CONSTRAINT fk_audit_event_business
        FOREIGN KEY (business_id)
        REFERENCES business (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_actor_user
        FOREIGN KEY (actor_user_id)
        REFERENCES app_user (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_audit_event_actor_type
        CHECK (actor_type IN ('USER', 'SERVICE_ACCOUNT', 'SYSTEM')),
    CONSTRAINT ck_audit_event_actor_reference
        CHECK (
            (actor_type = 'USER' AND actor_user_id IS NOT NULL)
            OR (actor_type <> 'USER' AND actor_user_id IS NULL)
        )
);

CREATE INDEX ix_audit_event_business_occurred_at
    ON audit_event (business_id, occurred_at DESC);

CREATE INDEX ix_audit_event_actor_occurred_at
    ON audit_event (actor_user_id, occurred_at DESC)
    WHERE actor_user_id IS NOT NULL;

CREATE FUNCTION reject_audit_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_event rows are append-only';
END;
$$;

CREATE TRIGGER trg_audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW
    EXECUTE FUNCTION reject_audit_event_mutation();
