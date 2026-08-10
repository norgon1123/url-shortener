# Requirements brief

Companion to `input/requirement.txt`. **This file is not orchestrator input.**

`requirement.txt` is written in the voice of whoever wants the work, and it is
deliberately silent on how any of it should be built. This file holds the
engineering positions that were withheld from it. It exists so a run can be
graded rather than merely admired: if `clarify` escalates a question that is
answered below, the escalation was right and the answer is here. If `design`
lands somewhere different, that is a conversation, not automatically a defect —
but it should be a conversation someone actually has.

Keeping the two apart is the point. A requirement that already contains the
design gives `intake` nothing to normalise and `clarify` nothing to escalate,
and turns the approval gate into decoration — the failure `clarify.md` names.

---

## What the requirement withholds on purpose

These are the decisions a good `clarify` should surface. Each is phrased in
`requirement.txt` in business terms with the engineering answer removed.

| Requirement says | Decision withheld |
|---|---|
| "not something a browser can take away from us by remembering where the link went" | 302, never 301, plus `Cache-Control: no-store` |
| "a small minority of links… hit over and over" | Cache tiering, and the hit-rate the database is sized against |
| "should not be able to find it by guessing" | Random code generation, not a sequence |
| "stop working quickly", "a number someone should hold us to" | Cache staleness bound on delete |
| "expiry… we can change our minds about per link" | Per-row expiry column, not a global constant |
| "keeps the estate from growing without bound" | Partition-and-drop, not `DELETE` |
| "still defensible if the database is stolen" | argon2id/bcrypt with a cost factor |
| "carry across our services without every request going back to a login system" | JWT, and therefore the revocation problem |
| "add database capacity for reading" | Read replicas, and therefore read-your-writes |

The requirement is also genuinely silent — not coyly silent — on several things
where any answer is defensible and someone should just pick one: what "how a
link has performed" contains beyond a click count, whether an expired code can
ever be reissued, whether the same long URL submitted twice yields the same
code, and what the rate limits actually are. These should come back as
assumptions with proposed answers, not as blocking questions.

---

## Positions

### 302, not 301 — and the header matters more than the code

301 is cached by the browser indefinitely. The second click never reaches us,
the count is wrong forever, and the link can never be repointed. That is the
whole reason for 302 and it is correctly reasoned.

The part that gets missed: **a 302 is also cacheable** if the response permits
it. Without `Cache-Control: no-store` on the redirect, intermediaries and
browsers may serve it from cache and the counts leak away regardless of the
status code. The status code is necessary and not sufficient.

Two smaller ones on the same response. `Referrer-Policy` — by default the
target site learns the short URL, which for a link treated as a secret is a
leak. And bots, link scanners and messaging-app previewers will fetch links
without a human ever seeing them; if those are counted, every customer's
numbers are inflated on day one.

### The click write path is the scaling problem, not the click read path

This is the largest thing missing from most readings of the requirement.

At 100M creations/day the write load is ~1,200/s. That is unremarkable. But
10x clicks is ~1B/day, ~11.6k/s averaged, and the requirement is explicit that
traffic is spiky — a realistic peak is 40–60k/s. If each click synchronously
increments a counter, **the analytics write path is 50k/s of contended row
updates** and it is what kills the database, not the redirect lookup.

The redirect itself is a cache hit. The counter is not.

So click recording has to be decoupled from click serving: increment in Redis
and drain periodically, or emit to a queue and aggregate. The consequence is
that reported statistics are eventually consistent, and the acceptable lag
becomes a stated number rather than an accident. A customer refreshing a
dashboard and seeing a count that has not moved yet is fine, if we said so.

### Cache tiering

In-memory, then Redis, then Postgres, as intended. The requirement's "small
minority of links gets the great majority of clicks" is the justification —
link popularity is Zipfian, so a modest per-instance LRU should absorb most of
the traffic and the database should only see the tail.

Three things the happy path hides:

**Negative caching.** Misses are the attack. Somebody enumerating codes gets a
100% miss rate and every one of those requests falls through both cache tiers
into Postgres. Unknown and expired codes must be cached as unknown, or the
cache is bypassable by anyone who wants to bypass it.

**Invalidation on delete.** In-memory caches across N instances cannot be
invalidated synchronously without machinery. The cheap answer is a short TTL
and accepting staleness — which is fine, except the requirement ties this
directly to taking down a fraudulent link. That makes the staleness window a
number with a business meaning, and it should be chosen deliberately and
written down, not inherited from a default.

**Redis failing.** "Redis fallback" needs a stated behaviour when Redis is the
thing that is down. Falling through to Postgres at full click volume converts a
cache outage into a database outage. Whatever the answer — circuit breaker,
shed load, serve stale — it should be a decision.

### Code generation

Five years at 100M/day is ~180 billion links.

Base62 at 7 characters is 3.5e12 — enough space, but ~5% occupancy, so random
generation collides on roughly 5% of inserts. That is survivable with a unique
index and a retry, and it costs about 1.05 database round trips per creation.
8 characters drops collisions to negligible at the cost of one character. Both
are defensible; 7 with retry is the better trade and the retry path must be
tested, because it is the branch that only executes under load.

**Random rather than sequential is the load-bearing choice.** A sequence
encoded to base62 never collides and packs perfectly, and it is also walkable —
anyone holding one code can enumerate the corpus. The requirement says short
links are treated as secrets and must not be discoverable by guessing, which
rules the sequence out. This is a case where the security property and the
efficient implementation point in opposite directions and the security property
wins.

Also required, and easy to forget: random base62 will eventually generate
offensive strings, and a customer-facing link that does is a real incident.
Filter them, along with confusable characters, and reserve the codes that
collide with the API's own paths.

Custom aliases share the same uniqueness constraint as generated codes. One
index, one namespace, no special case.

### Expiry, and the reaper that the requirement implies but does not mention

Thirty-day expiry at 100M/day is ~3 billion live rows in steady state. The rows
must leave, and `DELETE` at that volume generates a vacuum load that will
degrade the database over time.

The answer is to partition by expiry date and drop whole partitions, which is
effectively free. The reason this belongs in the first build rather than a
later one: **retrofitting partitioning onto a 3-billion-row table is a
migration measured in downtime.** It is cheap now and expensive in a year, and
that asymmetry is the entire argument.

Expired and unknown codes should be indistinguishable to a caller. 410 Gone is
more semantically honest, but it confirms the code once existed, which is the
enumeration leak the requirement asks us to close. 404 for both — consistent
with the existing decision to return 404 rather than 403 for another
customer's code, for exactly the same reason.

### Authentication

Argon2id, or bcrypt with a real cost factor. "Hashed and salted" is satisfied
by salted SHA-256, which is broken for this purpose — salting defeats rainbow
tables, and the property that actually matters against a stolen database is
that each guess is slow. Say the algorithm and the cost, not the property.

**JWT's cost is revocation.** A stateless token cannot be withdrawn before it
expires, so there is no logout that means anything and no way to kill a stolen
session. The usual resolution is a short-lived access token with a
server-tracked refresh token, or a denylist in Redis; either is fine, but the
requirement's "carry across our services without going back to a login system"
is what buys the problem and it should be answered rather than discovered.

Pin the signing algorithm explicitly — `alg: none` and HS/RS confusion are
both live attacks against naive verification.

The redirect endpoint is unauthenticated. This is obvious and is exactly the
kind of obvious thing a global auth filter breaks.

### Rate limiting

Per-instance in-memory counters are bypassed by opening a connection to a
different instance, so shared state is needed for the limit to mean anything —
against the cost of a Redis round trip on the hot path.

**Limit clicks per source, never per link.** A link in a television advert is
supposed to receive enormous traffic from many sources; that is the product
working. Per-link limiting throttles exactly the customers who matter most.

Creation is the expensive operation and the one worth limiting per account.
Authenticated and anonymous callers do not deserve the same allowance.

### The abuse surface, which the requirement raises and which is easy to skim

A URL shortener is a phishing instrument. The requirement says so directly and
it is not decoration — a link that hides its destination and carries a
reputable name is the entire value proposition to an attacker.

The floor is a scheme allowlist: `http` and `https` only, which rejects
`javascript:` and `data:` targets outright. Then blocking private, loopback and
link-local destinations — this reads as paranoia until the QR and preview
features arrive, at which point the service fetches customer-supplied URLs and
becomes an SSRF proxy into our own network. And a takedown path that is
operable by a human under time pressure, since that is the scenario in which it
will always be used.

Reputation feeds are the obvious extension; the integration point should exist
even if nothing is plugged into it yet.

### Future features, and which one costs something today

**Custom domains change the schema now.** Uniqueness stops being "the code" and
becomes "the code, on that domain." Getting this wrong means an index rebuild
against billions of rows later. It is nearly free to model correctly today.

**QR codes cost nothing today.** They are derived from a link that already
exists and can be generated on demand. Nothing in the data model has to know.

The contrast is the useful part: "plan for the future" is not a uniform
instruction. One of these has a schema consequence in the first build and the
other does not, and telling them apart is the judgement being asked for.

**Paid permanent links** need expiry to be nullable per row rather than a
constant, which is already the position above.

**Read replicas break read-your-writes.** A customer creates a link and
immediately sends it; if that click reads a replica that has not caught up, the
link they just made is dead. This is the standard replica bug and it is
particularly bad here because it hits at the exact moment of highest customer
attention. Populating the cache on write, or routing recently-created codes to
the primary, both solve it — but it has to be a decision, because the default
behaviour is the bug.

---

## Loose ends worth a decision

- **Idempotency on create.** A retried request should not mint a second code
  for the same intent.
- **Deduplication.** The same long URL submitted twice: one code or two? Saving
  storage costs per-customer ownership and per-link reporting. Two codes is
  probably right, but say so.
- **IP addresses in click records are personal data.** Retention, truncation or
  hashing, and erasure on request. This is a compliance obligation, not a
  feature.
- **Versioned API path from the start.** Nearly free now.
- **Explicit SLOs.** Click-path latency and availability targets, stated as
  numbers. The requirement asserts the click path matters more than creation;
  an SLO is where that assertion becomes something operable.
