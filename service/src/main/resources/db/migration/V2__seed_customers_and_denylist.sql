-- Fixtures the API contract names, so that both the service and anything written
-- against it agree on what exists.
--
-- Accounts arrive here because there is no registration endpoint: provisioning
-- was never in scope. These two exist for local and test use only; two of them,
-- because tenant isolation cannot be demonstrated with one.
--
-- The stored values below are Argon2id hashes produced by this service own
-- PasswordHasher at its configured parameters. No plaintext appears in this
-- repository; the credentials these correspond to are published in the API
-- specification and nowhere else.

INSERT INTO customers (id, email, password_hash, created_at) VALUES
    ('00000000-0000-0000-0000-000000000001',
     'alice@example.com',
     '$argon2id$v=19$m=16384,t=2,p=1$fWBsF24wbijSqTAz8KPioQ$0qn34sKiyql1T2MngDytMYoNWZxnvseY4MJvO4Q3CH8',
     TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00'),
    ('00000000-0000-0000-0000-000000000002',
     'bob@example.com',
     '$argon2id$v=19$m=16384,t=2,p=1$gNSDoRUbZ1tmuISsE+ckWw$pPz+8cEjVKhvunOFY0CYW/8OBXo8A/WpZZ4lhKDr0OU',
     TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00');

-- Both hosts are in the reserved example space and resolve nowhere, so the
-- takedown rule can be exercised without implicating anything real. There is no
-- admin endpoint, which does mean that adding a host in production is currently a
-- migration under an approval gate - a real operational gap, recorded rather than
-- quietly worked around.

INSERT INTO threat_denylist (id, host, created_at) VALUES
    ('00000000-0000-0000-0000-00000000000a',
     'malware.example.com',
     TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00'),
    ('00000000-0000-0000-0000-00000000000b',
     'phishing.example.net',
     TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00');
