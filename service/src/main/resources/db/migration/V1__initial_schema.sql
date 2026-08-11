-- Initial schema.
--
-- Code uniqueness is (domain, code) from the first migration, with one configured
-- domain in this build. Customer-owned domains then arrive as data rather than as
-- a unique-index change on the busiest table in the system.
--
-- Nothing is ever deleted. A link is soft-deleted so that its code can never be
-- reissued: reissuing would silently hand an old link audience to a new owner
-- target, which is a security problem rather than an untidiness.

CREATE TABLE customers (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(320) NOT NULL,
    -- The full encoded Argon2id value: algorithm, parameters and per-password
    -- salt included. Never the plaintext, and never a bare digest.
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ux_customers_email UNIQUE (email)
);

CREATE TABLE links (
    id          UUID          PRIMARY KEY,
    domain      VARCHAR(255)  NOT NULL,
    code        VARCHAR(64)   NOT NULL,
    customer_id UUID          NOT NULL REFERENCES customers (id),
    long_url    VARCHAR(2048) NOT NULL,
    -- ACTIVE, DELETED or BLOCKED. EXPIRED is derived from expires_at when the row
    -- is read and is never stored: storing it would make a link status depend on
    -- a sweeper having run, and a sweeper that fell behind would keep an expired
    -- link redirecting.
    status      VARCHAR(16)   NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- The durable total only. Clicks are counted in Redis on the hot path and
    -- drained into this column in batches; what a customer is shown is this plus
    -- whatever has not been drained yet.
    click_count BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ux_links_domain_code UNIQUE (domain, code)
);

-- Covers the owner listing exactly as it is ordered, so paging does not sort.
CREATE INDEX ix_links_owner_listing ON links (customer_id, created_at DESC, code ASC);

CREATE TABLE abuse_reports (
    id                   UUID         PRIMARY KEY,
    domain               VARCHAR(255) NOT NULL,
    code                 VARCHAR(64)  NOT NULL,
    -- Nullable, and the code is kept as text beside it: a report is accepted for
    -- any well-formed code whether or not it resolves, because refusing an
    -- unknown one would turn this endpoint into an existence oracle.
    link_id              UUID         REFERENCES links (id),
    reporter_customer_id UUID         NOT NULL REFERENCES customers (id),
    reason               VARCHAR(500),
    created_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_abuse_reports_link ON abuse_reports (link_id);

CREATE TABLE threat_denylist (
    id         UUID         PRIMARY KEY,
    -- Stored lower-cased so a lookup is an equality match on the index rather
    -- than a function over the table.
    host       VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ux_threat_denylist_host UNIQUE (host)
);
