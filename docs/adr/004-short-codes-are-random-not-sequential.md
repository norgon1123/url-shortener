# ADR-004: Short codes are random base62, not a sequence

**Status:** accepted, implemented in the service
**Cited by:** `docs/IMPLEMENTATION_PLAN.md`, `docs/REQUIREMENTS_BRIEF.md`

This is the one architecture decision here about the *service* rather than the
pipeline. It is recorded because it is the clearest case in the requirement
where the efficient answer and the correct answer point in opposite directions,
and because it is the decision the pipeline had to be trusted not to quietly
re-litigate.

## Context

Five years at the requirement's stated growth — 100 million new links a day — is
roughly 180 billion links.

A sequence encoded to base62 is the obvious implementation. It never collides,
packs perfectly, needs no retry path, and costs exactly one database round trip
per creation.

It is also **walkable**. Anyone holding one code can enumerate the corpus by
incrementing. The requirement is explicit that short links are treated as
secrets by the people who hold them, and that somebody who was not given a link
should not be able to find it by guessing or work out what other links exist
from the ones they have.

## Decision

**Random 7-character base62, with a unique index and a bounded retry.**

Base62 at 7 characters is 3.5 × 10¹², so 180 billion links is about 5%
occupancy, and random generation collides on roughly 5% of inserts. Survivable
with a unique index and a retry, at a cost of about 1.05 database round trips
per creation. Eight characters would drop collisions to negligible at the cost
of one character of length; both are defensible and 7-with-retry is the better
trade.

**The retry path must be tested**, because it is the branch that only executes
under load — which is to say, the branch that only executes in production.

Two consequences that are easy to forget and were written into the brief so the
pipeline would not have to rediscover them:

- random base62 will eventually generate offensive strings, and a
  customer-facing link that does is a real incident. Filter them, along with
  confusable characters, and reserve codes that collide with the API's own
  paths;
- custom aliases share the same uniqueness constraint as generated codes. One
  index, one namespace, no special case.

## Consequences

**Unguessability is a property that has to be tested, not asserted.** The
generated suite covers it in `ShortCodeUnguessabilityTest`.

**The collision retry is a real code path with a real cost**, and the 5% figure
is a design input rather than an accident to be discovered in an incident.

**It cost a round trip and it was worth it.** This is the trade recorded here:
about 5% extra database work on the creation path — the path the requirement
explicitly says matters less than the click path — bought a property the
requirement calls out by name.

## Evidence

`greenfield-3` produced this implementation from the brief without argument, and
`routes_match_openapi` confirmed the surface matched the frozen contract exactly.
`brownfield-1`'s anonymous-link path reuses the same generator and the same
namespace — no special case, as the decision requires — which the review lenses
checked and the suite pins.
