# URL Shortener — runbook

Spring Boot 3.5 on Java 21. Depends on **PostgreSQL** (system of record) and
**Redis** (resolution cache, click counters, rate-limit buckets).

For request/response detail see [API.md](API.md) and
[`../artifacts/openapi.yaml`](../artifacts/openapi.yaml).

---

## Starting the service

`../docker-compose.yml` starts PostgreSQL **only**; Redis is not in it, so start
one yourself:

```bash
docker compose up -d postgres
docker run -d --name shortener-redis -p 6379:6379 redis:7-alpine

cd service
./mvnw spring-boot:run
```

Flyway applies the migrations at startup, including the seeded development
customers and threat denylist. The service listens on `http://localhost:8080`.

Smoke test:

```bash
curl -s http://localhost:8080/actuator/health          # {"status":"UP"}
curl -i http://localhost:8080/no-such-code             # 404 not_found
```

Tests use Testcontainers, not compose:

```bash
cd service && ./mvnw verify
```

## Configuration

Defaults live in
[`../service/src/main/resources/application.yml`](../service/src/main/resources/application.yml)
and in `AppProperties`. Everything below can be overridden as a Spring property
or as the equivalent environment variable.

| Setting | Env var | Default |
|---|---|---|
| Database URL | `DB_URL` | `jdbc:postgresql://localhost:5432/shortener` |
| Database user / password | `DB_USERNAME` / `DB_PASSWORD` | `shortener` / `shortener` |
| Redis host / port | `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |
| HTTP port | `SERVER_PORT` | `8080` |
| Public origin used to build `shortUrl` | `APP_BASE_URL` | `http://localhost:8080` |
| Link namespace (uniqueness key is `(domain, code)`) | `APP_DOMAIN` | `localhost` |
| Session signing keys (Ed25519, PKCS#8 / X.509 PEM) | `APP_SESSION_PRIVATE_KEY_PEM` / `APP_SESSION_PUBLIC_KEY_PEM` | empty |

| Property | Default | Notes |
|---|---|---|
| `app.links.default-ttl` | `P30D` | Applied at creation; changing it never rewrites existing links |
| `app.links.max-url-length` | `2048` | |
| `app.cache.ttl` / `app.cache.negative-ttl` | `PT60S` | **Must not exceed 60s** — see below |
| `app.session.ttl` | `PT24H` | Non-refreshable, non-revocable |
| `app.click.flush-interval` | `PT5S` | How often Redis deltas drain to PostgreSQL |
| `app.click.flush-batch-size` | `500` | Links per flush pass |
| `app.threat.enabled` | `true` | `false` disables denylist checking entirely |
| `app.threat.fail-open` | `true` | On `false`, creation returns 503 whenever the denylist is unreadable |
| `app.rate-limit.enabled` | `true` | |
| `app.rate-limit.{click,not-found,write,abuse-report,sign-in}-per-minute` | `3000` / `300` / `300` / `60` / `60` | |
| `app.rate-limit.window` | `PT1M` | Refill period; capacity is the per-minute figure |

Redis command and connect timeouts are pinned at `1s`: the click path talks to
Redis up to three times and must keep answering when Redis does not.

**Session keys.** If both PEMs are blank the service generates an ephemeral
keypair at startup and logs a WARN. That is fine for one instance and for tests
and wrong for replicas — a token issued by one instance will not verify on
another, and every session dies at restart. Any multi-instance deployment must
set both.

**Cache TTL.** The published takedown bound (60 seconds from a delete or an
abuse report) is delivered by *actively invalidating* the cache entry; the TTL is
only the floor under a missed invalidation. Raising `app.cache.ttl` above 60s
breaks that bound with nothing failing.

## Health and metrics

Actuator is outside the authentication filter (which is registered for
`/api/v1/*` only) and is unauthenticated.

| Endpoint | Use |
|---|---|
| `/actuator/health` | Aggregate; includes PostgreSQL and Redis indicators |
| `/actuator/health/liveness`, `/actuator/health/readiness` | Kubernetes probes |
| `/actuator/metrics`, `/actuator/prometheus` | Micrometer / Prometheus scrape |
| `/actuator/info` | Exposed, but empty — no build-info is generated |

Details are hidden (`show-details: when-authorized`), so an outward `/health`
reads `{"status":"UP"}` or `{"status":"DOWN"}` and nothing more.

`actuator`, `health`, `metrics`, `prometheus`, `api` and friends are reserved
aliases, so no customer link can shadow these routes.

## Failure modes, from the outside

| Symptom | Cause | Behaviour |
|---|---|---|
| `/actuator/health` DOWN, redirects still working | Redis unreachable | Redirects served from PostgreSQL; clicks are **not counted** and are lost, not queued (WARN per click). Rate limiting **fails open** — every bucket allows. Link creation returns `503` because a code cannot be issued whose stale negative cache entry we cannot clear |
| Everything 5xx / service will not start | PostgreSQL unreachable | Nothing works: it is the system of record. `ddl-auto: validate`, so a schema mismatch fails startup rather than mutating the schema |
| Sudden `401`s across all callers after a restart or a scale-out | No session keys configured; ephemeral keypair per instance | Set `APP_SESSION_PRIVATE_KEY_PEM` / `APP_SESSION_PUBLIC_KEY_PEM` |
| `503 service_unavailable` on create only | Redis eviction failed, or `app.threat.fail-open=false` with the denylist unreadable | By design: degradation is spent on accepting new links, never on serving clicks |
| Click counts briefly high | A flush wrote durably but could not subtract the pending delta (`ERROR` log: "could not settle the pending delta") | Self-correcting on the next pass; overcount is the chosen direction, clicks are never lost |
| `429` with `Retry-After` on clicks | not-found bucket exhausted by an enumeration sweep from one IP | Expected. The click bucket is separate, so genuine traffic to a popular link is unaffected |
| Nothing shortens; WARN "Accepting a link to … without a threat verdict" | Denylist unreadable, `fail-open` on | Links are accepted unchecked. Auditable by design |

The click path is built never to return 5xx: it constructs its own responses and
catches anything that escapes, answering `404` rather than a server error.

## Operational gaps

- **No admin endpoint for the threat denylist.** Adding a host in production is
  a Flyway migration today.
- **No moderation console.** An abuse report blocks the link immediately, and
  only a database write can unblock it.
- **No sign-out or token revocation.** A leaked token is valid until it expires
  (24h); the only mitigation is rotating the signing keys, which invalidates
  every session.
- **One Redis hash holds every pending click delta.** That is a single hot key
  at real volume; the scale path is sharding the hash by a link-id prefix, which
  needs no schema change.
