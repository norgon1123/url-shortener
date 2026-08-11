# URL Shortener — API guide

Two surfaces:

| Surface | Path | Auth |
|---|---|---|
| Management API | `/api/v1/**`, JSON | `Authorization: Bearer <token>`, except sign-in |
| Click path | `GET /{code}` at the root | none, ever |

The machine-readable contract is [`../artifacts/openapi.yaml`](../artifacts/openapi.yaml).
To start a service to run these commands against, see
[RUNBOOK.md](RUNBOOK.md#starting-the-service).

Every command below works verbatim against a local service on
`http://localhost:8080` with the seeded development accounts.

---

## 1. Sign in

There is no registration endpoint. Two accounts are created by migration
(`V2__seed_customers_and_denylist.sql`) and exist for local and test use only:

| Customer id | Email | Password |
|---|---|---|
| `00000000-0000-0000-0000-000000000001` | `alice@example.com` | `alice-dev-password` |
| `00000000-0000-0000-0000-000000000002` | `bob@example.com` | `bob-dev-password` |

```bash
curl -s -X POST http://localhost:8080/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"alice-dev-password"}'
```

```json
{
  "accessToken": "eyJhbGciOiJFZERTQSJ9...",
  "tokenType": "Bearer",
  "expiresAt": "2026-08-11T12:00:00Z",
  "customerId": "00000000-0000-0000-0000-000000000001"
}
```

Keep it for the rest of the session:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"alice-dev-password"}' \
  | sed -e 's/.*"accessToken":"//' -e 's/".*//')
```

The token is an Ed25519-signed JWT, valid 24 hours, verified locally by the
service. It is **not refreshable and cannot be revoked** — there is no sign-out
endpoint. A wrong password and an unknown email give the same 401
`invalid_credentials` and take the same time to answer.

## 2. Create a link

```bash
curl -s -X POST http://localhost:8080/api/v1/links \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/a/very/long/path?with=query"}'
```

```json
{
  "code": "7Qk2mZa9Xr4Lb0Nc8Tv1Ps",
  "shortUrl": "http://localhost:8080/7Qk2mZa9Xr4Lb0Nc8Tv1Ps",
  "longUrl": "https://example.com/a/very/long/path?with=query",
  "status": "ACTIVE",
  "createdAt": "2026-08-10T12:00:00Z",
  "expiresAt": "2026-09-09T12:00:00Z",
  "clickCount": 0
}
```

`201`, with `Location: /api/v1/links/{code}` — the API resource, not the short
URL (the short URL is in the body, because a client pastes it rather than
follows it).

Generated codes are 22 base62 characters from a CSPRNG (128 bits), derived from
nothing about the row, so holding issued codes tells you nothing about the next
one.

With a chosen alias and an explicit expiry:

```bash
curl -s -X POST http://localhost:8080/api/v1/links \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/spring-campaign","alias":"spring-sale","expiresAt":"2027-12-31T23:59:59Z"}'
```

Aliases: `^[A-Za-z0-9_-]{3,64}$`, case-sensitive, sharing one namespace with
generated codes. An alias is memorable and therefore guessable — the
unguessability property covers generated codes only.

Body rules:

- `longUrl` — required, absolute `http(s)` with a host, ≤ 2048 characters.
- `alias` — optional.
- `expiresAt` — optional ISO-8601 instant, must be in the future. Absent means
  30 days from creation. There is no "never expires".
- Any other property is a `400`. That is what makes `longUrl` immutable in
  practice.

Submitting the same long URL twice gives two independent links with independent
counts; there is no deduplication.

## 3. Follow a link

```bash
curl -i http://localhost:8080/7Qk2mZa9Xr4Lb0Nc8Tv1Ps
```

```
HTTP/1.1 302
Location: https://example.com/a/very/long/path?with=query
Cache-Control: no-store, no-cache, must-revalidate, max-age=0
Pragma: no-cache
Expires: 0
```

`302`, not `301`: a `301` is cached indefinitely by browsers and proxies, so
later clicks would never reach the service — counts would stop growing and a
deleted link would keep redirecting. The no-store headers are part of the
contract for the same reason.

`HEAD` is served by the same handler with the same status and headers and no
body. Every other method is `405`.

No credentials are accepted or required here. `Authorization` is ignored.

## 4. Read your links

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/links/7Qk2mZa9Xr4Lb0Nc8Tv1Ps

curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/v1/links?page=0&size=20'
```

The list is newest first (`createdAt` descending, `code` ascending as tiebreak)
and includes expired, deleted and blocked links of your own. `page` ≥ 0,
`size` 1..100 — an out-of-range `size` is refused with `400`, not clamped.

```json
{"items": [ ... ], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1}
```

`clickCount` is exact and current: the durable PostgreSQL total plus the delta
still pending in Redis, so a click made a second ago is already in the number.
Clicks that 404 are never counted.

## 5. Change the expiry

The only mutable property.

```bash
curl -s -X PATCH http://localhost:8080/api/v1/links/7Qk2mZa9Xr4Lb0Nc8Tv1Ps \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"expiresAt":"2027-01-31T00:00:00Z"}'
```

`{"longUrl":"..."}` here is a `400`, not a silent no-op. An expiry in the past
is a `400` too — `DELETE` is the takedown. Shortening the expiry takes effect
within the same bound as a delete, because the caches are invalidated on the
change.

## 6. Delete a link

```bash
curl -i -X DELETE http://localhost:8080/api/v1/links/7Qk2mZa9Xr4Lb0Nc8Tv1Ps \
  -H "Authorization: Bearer $TOKEN"
```

`204`, no body. The link stops redirecting within **60 seconds** of this
response (in practice immediately: the cache entry is actively invalidated; 60s
is the cache TTL that bounds a missed invalidation). Idempotent — deleting an
already-deleted link of yours is another `204`.

Soft delete: the row and its click total are retained, and the code is never
reissued to anyone.

## 7. Report abuse

```bash
curl -i -X POST http://localhost:8080/api/v1/links/7Qk2mZa9Xr4Lb0Nc8Tv1Ps/abuse-reports \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"reason":"Phishing page imitating a bank sign-in"}'
```

`202`, no body. The body is optional (`reason` ≤ 500 characters).

A report **blocks the link immediately** — there is no moderation queue in this
build. Any signed-in customer can report any code, including one they do not
own; the per-reporter rate limit and the stored audit row are the whole defence
against that. Only the link is blocked; its host is not added to the denylist.

The response is `202` whether or not the code exists, so this endpoint cannot be
used to discover which codes exist.

---

## Errors

Every non-2xx body has the same shape:

```json
{"error": "invalid_request", "message": "The request is not valid.", "fields": {"expiresAt": "must be in the future"}}
```

`fields` appears only on `invalid_request`. Messages are fixed per code and never
echo request content.

| `error` | Status | When |
|---|---|---|
| `invalid_request` | 400 | Malformed body, unknown property, bad `longUrl`/`alias`/`expiresAt`, bad paging, reserved alias |
| `invalid_credentials` | 401 | Sign-in rejected (unknown email or wrong password — indistinguishable) |
| `unauthorized` | 401 | Missing, malformed, expired or unverifiable token. Carries `WWW-Authenticate: Bearer` |
| `not_found` | 404 | Code unknown, expired, deleted, blocked, malformed, or owned by someone else |
| `alias_unavailable` | 409 | The requested alias is taken |
| `link_not_modifiable` | 409 | `PATCH` on your own deleted or blocked link |
| `url_rejected` | 422 | Denylisted host, internal/loopback/private/link-local host, or this service's own host |
| `rate_limited` | 429 | A token bucket is empty. Carries `Retry-After` in whole seconds, never 0 |
| `service_unavailable` | 503 | Only on link creation — a dependency it needs is down |

**There is no 403 and no 410.** Someone else's link, an expired link, a deleted
link and a code that was never issued all answer the identical
`{"error":"not_found","message":"Not found"}`, byte for byte, on both surfaces.
Either other status would confirm that a code exists.

A reserved alias (`api`, `actuator`, `health`, `admin`, `robots.txt`, … — the
full list is `AliasPolicy.RESERVED_CODES`) is a `400`, not a `409`: nobody holds
it, and `409` would imply somebody does.

### Edges worth knowing

```bash
# Expired, deleted, blocked, unknown, or another customer's code — all identical:
curl -i http://localhost:8080/does-not-exist

# Alias already taken -> 409 alias_unavailable
curl -s -X POST http://localhost:8080/api/v1/links -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/other","alias":"spring-sale"}'

# Denylisted host (seeded: malware.example.com, phishing.example.net) -> 422 url_rejected
curl -s -X POST http://localhost:8080/api/v1/links -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://malware.example.com/download"}'

# Internal host, or this service's own host -> 422 url_rejected (same message)
curl -s -X POST http://localhost:8080/api/v1/links -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"http://192.168.0.1/admin"}'

# Unknown property -> 400 invalid_request
curl -s -X PATCH http://localhost:8080/api/v1/links/spring-sale -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/somewhere-else"}'

# No token -> 401 unauthorized
curl -i http://localhost:8080/api/v1/links
```

Note that with the default `app.base-url` of `http://localhost:8080`, any
`localhost` target is refused with `422` — use `example.com` targets locally.

### Rate limits

Five independent token buckets, refilled over a one-minute window. Exceeding one
gives `429` with `Retry-After`.

| Bucket | Keyed by | Applies to | Default/min |
|---|---|---|---|
| click | client IP | `GET /{code}` | 3000 |
| not-found | client IP | `GET /{code}` that does not resolve | 300 |
| write | customer id | `POST`/`PATCH`/`DELETE` on links | 300 |
| abuse-report | customer id | `POST .../abuse-reports` | 60 |
| sign-in | client IP | `POST /api/v1/sessions` | 60 |

The not-found bucket is far tighter than the click bucket on purpose: an
enumeration sweep is a long run of 404s, while a popular link is a long run of
302s, and throttling the first must not throttle the second.

## Not in this build

Registration, sign-out, token refresh or revocation, editing a link's target
URL, permanent (non-expiring) links, custom domains, per-click analytics beyond
a total count, a moderation console, and any admin endpoint for the threat
denylist.
