# `ambiguous-1` and `ambiguous-2` — the same ask, answered two ways

One deliberately underspecified requirement ([`input/ambiguous.txt`](../../../../input/ambiguous.txt)),
run twice from the same commit with the same prompts, differing only in **how a
human answered the questions the pipeline asked**. Both safe-stopped after
`decompose`, because the plan is what the comparison is about.

```bash
PYTHONPATH=orchestrator python -m sdlc.cli replay orchestrator/fixtures/runs/ambiguous-1
PYTHONPATH=orchestrator python -m sdlc.cli --runs-dir orchestrator/fixtures/runs verify ambiguous-1      # 35 entries
PYTHONPATH=orchestrator python -m sdlc.cli --runs-dir orchestrator/fixtures/runs verify ambiguous-2      # 52 entries
```

| | `ambiguous-1` | `ambiguous-2` |
|---|---|---|
| "Where the clicks came from" | the referring site | **the clicker's country, from IP** |
| "We made it worse" | the readiness probe included Redis, so a blip pulled every instance | **unbounded retries against a slow dependency** |
| Therefore | probe surgery, no new dependency | **circuit breaker, timeouts, a bundled geo database** |
| Tasks planned | 14 | **21** |
| Cost | $6.73 | $11.37 |

## What the run was for, and what it actually found

The claim being tested was that **approval is consequential rather than a pause
button** — that a human's answer changes what gets built. Two earlier runs had
escalated at `clarify` and been answered, which proves a run can *stop*. It does
not prove the answer *matters*.

It did not matter. The first pass of `ambiguous-2` produced a plan
substantially identical to `ambiguous-1`'s — built on the referring site, with no
circuit breaker — while the approvals table held the opposite answers and the
gate had accepted them. Its own first task said so:

> `Q1 source dimension = referring host from the Referer header` … `U5 no
> Resilience4j dependency added` … each entry flagged *"confirmed by human"* or
> *"operated on the model's reading, unconfirmed"*

**The human's answers never reached any node.** `answers` was read in exactly two
places: a gate checking an answer *existed*, and the `route` key. Nothing read
what an answer said. Every downstream node worked from `clarification.json`,
which has a field for the model's `proposed_answer` and none for the human's
reply.

`greenfield-3` and `brownfield-1` could not have detected this. In both, the
human agreed with the proposal — which makes the right answer and no answer
produce identical output. **A control tested only by agreement is not tested.**

Fixed in `f4258e4`: answers now reach every later node as settled decisions,
attributed to the person who gave them, run-scoped rather than node-scoped.

## After the fix

`ambiguous-2`'s `impact-analysis`, `feasibility` and `decompose` were re-run
against the same answers. `impact.json` went from *Referer* throughout and
*country* nowhere, to **country ×20, geo ×14, Referer ×0**.

The plans then diverged the way two engineering plans should:

| | Plan A | Plan B |
|---|---|---|
| Origin | `Referer`-to-origin derivation that cannot fail the redirect | bundled country database **as a pinned dependency with its licence**, an IP-to-country resolver, a retention decision |
| Reliability | per-dependency health detail, Redis degradation guard | circuit breaker with fallback, bounded retry, PostgreSQL per-query timeout |
| Health probes | changed as part of the work | **T9: "`/actuator/health` left exactly as documented, asserted rather than assumed"** |

That last row is the sharpest evidence. Answer A said change the probes; answer
B said leave them alone — and plan B turned "leave them alone" into a task that
**proves** they are unchanged, rather than silently not doing the work. A pause
button does not produce that.

Plan B is half again as large because country-from-IP genuinely costs more: a
licence, a database refresh story, and a retention policy that a `Referer`
header never needed. The human's answer moved the estimate, not just the wording.

## The other thing worth reading

Both runs asked **8 questions with 2 blocking**, from a cold start, and Q1 was
the same question in both. The blocking/advisory split differed on exactly one
item: `ambiguous-1` made the health-probe hypothesis blocking and the incident
history advisory; `ambiguous-2` reversed them. Same concerns, different
judgement about which one must stop the run.

`ambiguous-1` also inferred something the requirement never said. From one line
— *"the last outage started somewhere else and we made it worse"* — it asked
whether the platform routes on `/actuator/health` and whether a Redis blip
therefore pulled every instance out of service at once. That is a specific,
falsifiable mechanical hypothesis, and it is the difference between adding
probes and removing a dependency from an existing one.
