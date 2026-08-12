# ADR-004: Short codes are random, not sequential — and the run re-decided their length

**Status:** the *random-not-sequential* decision is accepted and implemented. The
**length was overruled by the run**: planned at 7 characters, built at 22.
**Cited by:** `docs/IMPLEMENTATION_PLAN.md`, `docs/REQUIREMENTS_BRIEF.md`

This is the one architecture decision here about the *service* rather than the
pipeline. It is recorded because it is the clearest case in the requirement where
the efficient answer and the correct answer point in opposite directions — and,
as it turned out, the clearest case of the pipeline disagreeing with the human
who wrote the brief and being right.

## Context

Five years at the requirement's stated growth — 100 million new links a day — is
roughly 180 billion links.

A sequence encoded to base62 is the obvious implementation. It never collides,
packs perfectly, needs no retry path, and costs exactly one database round trip
per creation.

It is also **walkable**. Anyone holding one code can enumerate the corpus by
incrementing. The requirement is explicit that short links are treated as secrets
by the people who hold them, and that somebody who was not given a link should
not be able to find it by guessing or work out what other links exist from the
ones they have. That became AC16.

## Decision, as planned

**Random 7-character base62, with a unique index and a bounded retry.** Base62 at
7 characters is 3.5 × 10¹², so 180 billion links is about 5% occupancy and random
generation collides on roughly 5% of inserts — survivable with a unique index and
a retry, at about 1.05 database round trips per creation. `REQUIREMENTS_BRIEF.md`
states this position, and calls 7-with-retry "the better trade".

## What was built, and why it is different

**22 characters, 128 bits of entropy**
(`ShortCodeGenerator.CODE_LENGTH = 22`). The `design` node found that the brief
contradicted itself and said so, in the class Javadoc that ships with the code:

> A2 says "128 bits of CSPRNG output rendered as ~11 base62 characters", and
> those two halves disagree: 11 base62 characters carry log2(62)×11 ≈ 65 bits,
> not 128. The bit strength is the load-bearing half — **AC16 is an acceptance
> criterion and "short" is not** — so this contract keeps 128 bits and corrects
> the character count to 22.

It then priced its own decision rather than hiding it:

> The cost is honest and belongs in front of the reviewer: a 22-character code
> makes a short link about 40 characters long, which is longer than the products
> this is modelled on. Those products buy their 7-character codes with aggressive
> enumeration defence; we have that too (the tight 404 bucket), but the
> requirement asks for unguessability as an acceptance criterion, so we do not
> spend the entropy. It is one constant, and trading it against the rate limit
> later is a one-line change plus a migration-free rollout, because old codes
> stay valid.

**That is the pipeline doing the thing it exists to do.** It did not silently
follow the brief, and it did not silently ignore it. It found an arithmetic
inconsistency in a human's stated position, chose the half that an acceptance
criterion made binding, wrote the trade-off down where a reviewer would meet it,
and named the one-line path back.

Two things survive from the plan unchanged, and one is now vestigial:

- **random, not sequential** — the load-bearing decision, upheld;
- **uniqueness by the `(domain, code)` constraint, not a pre-insert check** — "at
  the target write rate a check-then-insert is a race, and the constraint is the
  only thing that is actually atomic";
- **the collision retry** still exists and is effectively dead code at 22
  characters. Keeping it is right: it costs nothing, and it is what makes the
  length a one-constant decision rather than a redesign.

## Consequences

**Unguessability is a property that has to be tested, not asserted.** The
generated suite covers it in `ShortCodeUnguessabilityTest`.

**The trade is now the opposite of the planned one.** Rather than 5% extra
database work buying a security property, a 40-character link buys it and the
database work is gone. Whether that is the right call for a real business is a
product decision, and the code says exactly how to reverse it.

**A brief can be wrong, and saying which half of it is load-bearing is the
useful output.** The failure mode here was not "the agent ignored the spec" but
"the spec contained a contradiction nobody had noticed" — including the person
who wrote it.

## Evidence

`greenfield-3` produced the random-not-sequential property from the brief without
argument, and overrode the brief's *length* with the reasoning quoted above,
which ships in `ShortCodeGenerator.java`. `routes_match_openapi` confirmed the
surface matched the frozen contract exactly. `brownfield-1`'s anonymous-link path
reuses the same generator and the same namespace — no special case, as the
decision requires — which the review lenses checked and the suite pins.
