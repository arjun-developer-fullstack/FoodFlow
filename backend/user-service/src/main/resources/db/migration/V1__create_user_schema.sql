-- FoodFlow user-service schema (database: user_db, owned exclusively by user-service).
-- Conventions: UUIDv7 ids, TIMESTAMPTZ (UTC), optimistic-locking "version" columns.

-- ---------------------------------------------------------------- users
CREATE TABLE users
(
    id                    UUID         NOT NULL PRIMARY KEY,
    email                 VARCHAR(254) NOT NULL,
    phone                 VARCHAR(20),
    password_hash         VARCHAR(255) NOT NULL,
    full_name             VARCHAR(100) NOT NULL,
    status                VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    email_verified        BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_attempts INT          NOT NULL DEFAULT 0 CHECK (failed_login_attempts >= 0),
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    version               BIGINT       NOT NULL
);

-- e-mails are stored normalized (trimmed, lower-case) by the application
CREATE UNIQUE INDEX ux_users_email ON users (email);
-- admin prefix search (LIKE 'x%') cannot use ux_users_email under a non-C collation
CREATE INDEX ix_users_email_prefix ON users (email varchar_pattern_ops);
CREATE UNIQUE INDEX ux_users_phone ON users (phone) WHERE phone IS NOT NULL;
CREATE INDEX ix_users_created_at ON users (created_at DESC, id);
CREATE INDEX ix_users_status_created_at ON users (status, created_at DESC);

CREATE TABLE user_roles
(
    user_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    VARCHAR(30) NOT NULL CHECK (role IN ('CUSTOMER', 'RESTAURANT_OWNER', 'COURIER', 'ADMIN')),
    PRIMARY KEY (user_id, role)
);

-- ---------------------------------------------------------------- addresses
CREATE TABLE addresses
(
    id          UUID             NOT NULL PRIMARY KEY,
    user_id     UUID             NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label       VARCHAR(50)      NOT NULL,
    line1       VARCHAR(200)     NOT NULL,
    line2       VARCHAR(200),
    city        VARCHAR(100)     NOT NULL,
    postal_code VARCHAR(20)      NOT NULL,
    country     VARCHAR(2)       NOT NULL,
    latitude    DOUBLE PRECISION CHECK (latitude BETWEEN -90 AND 90),
    longitude   DOUBLE PRECISION CHECK (longitude BETWEEN -180 AND 180),
    is_default  BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ      NOT NULL,
    updated_at  TIMESTAMPTZ      NOT NULL,
    version     BIGINT           NOT NULL,
    CONSTRAINT ck_addresses_coordinates CHECK ((latitude IS NULL) = (longitude IS NULL))
);

CREATE INDEX ix_addresses_user_id ON addresses (user_id);
-- at most one default address per user
CREATE UNIQUE INDEX ux_addresses_one_default ON addresses (user_id) WHERE is_default;

-- ---------------------------------------------------------------- refresh tokens (ADR-0007)
CREATE TABLE refresh_tokens
(
    id          UUID        NOT NULL PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   UUID        NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,          -- SHA-256 hex of the opaque token; the token itself is never stored
    issued_at   TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID,
    user_agent  VARCHAR(255),
    ip_address  VARCHAR(45),
    version     BIGINT      NOT NULL
);

CREATE UNIQUE INDEX ux_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_family_id ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_expires_at ON refresh_tokens (expires_at);

-- ---------------------------------------------------------------- transactional outbox (ADR-0004)
CREATE TABLE outbox_events
(
    seq            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id             UUID          NOT NULL UNIQUE,
    aggregate_type VARCHAR(100)  NOT NULL,
    aggregate_id   VARCHAR(100)  NOT NULL,
    event_type     VARCHAR(100)  NOT NULL,
    topic          VARCHAR(200)  NOT NULL,
    payload_type   VARCHAR(255)  NOT NULL,
    payload_schema TEXT          NOT NULL,     -- writer schema (Avro JSON): decodable after schema evolution
    payload        BYTEA         NOT NULL,     -- Avro single-object encoding
    correlation_id VARCHAR(64),
    traceparent    VARCHAR(128),
    created_at     TIMESTAMPTZ   NOT NULL,
    published_at   TIMESTAMPTZ,
    attempts       INT           NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000),
    parked_at      TIMESTAMPTZ                 -- undeliverable (e.g. undecodable); needs operator action
);

CREATE INDEX ix_outbox_events_pending ON outbox_events (seq) WHERE published_at IS NULL AND parked_at IS NULL;
CREATE INDEX ix_outbox_events_parked ON outbox_events (parked_at) WHERE parked_at IS NOT NULL;
CREATE INDEX ix_outbox_events_published_at ON outbox_events (published_at) WHERE published_at IS NOT NULL;
