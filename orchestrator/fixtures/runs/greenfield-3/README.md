# `greenfield-3` — the 21-node graph, end to end

Replayable with no API key and no spend:

```bash
python -m sdlc.cli replay orchestrator/fixtures/runs/greenfield-3
python -m sdlc.cli verify greenfield-3      # re-check the hash chain yourself
```

**Outcome: completed.** 148 tests, 0 failures. Every scheduled node passed. The
run was signed off by a human *over* a `ready: false` verdict, with the reasons
recorded in `docs/TODO.md` — which is a different thing from a clean bill, and
the record says so in both places.

## What it demonstrates

| | |
|---|---|
| Nodes passed | 20 of 20 scheduled (`triage` is a handler; it ran three times but is never *scheduled*) |
| Human decisions | 7 |
| Gate pass rate | 85% |
| Replans | 1 — and it was a mistake the engine has since been fixed not to make |
| Cost | $108.70 estimated, of which **$60.51 (56%) was rework** (see `docs/OPERATIONS.md` — a subscription, not a bill) |
| Wall clock | ~10.5h, including hours paused waiting on a human |

### Triage routed real repairs, twice

The graph's reason for existing. `verify` failed on 23 test methods across 7
classes; `triage` classified them, and — because the mixture implicated both
sides — routed each branch its own itemised brief instead of stopping. Both
repaired in parallel: 23 failures → 1.

The survivor was a contract question: a test demanded that a 405 on a live code
and a 405 on an unissued code return *byte-identical* bodies, which Spring's
dispatcher cannot do because its body echoes the request path. No amount of
re-running settles that, so it escalated. A human ruled it a test defect, named
the branch, and the repair honoured the ruling precisely — it introduced a
`refusalDisclosure()` normaliser that strips the path and timestamp the caller
already knows and asserts everything else matches, rather than loosening the
assertion to compare status only.

### The sign-off refused itself

`release-readiness` returned `ready: false` and re-derived its evidence rather
than trusting the journal: it recomputed coverage from `jacoco.csv` (86.9%),
confirmed the verified tree was byte-identical to `HEAD`, and checked the
repaired test had not been muted (0 skipped, 0 disabled; 130 → 148 methods, so
nothing was deleted to reach green).

It then found things five review lenses had not: a trailing dot
(`https://malware.example.com./x`) and an integer IP literal
(`http://2130706433/`) both defeat the URL denylist, confirmed with a standalone
JDK 21 probe — and **no test in the suite catches either**, so AC21 reads as
satisfied by a suite that would not notice.

### Four defects this run found in the orchestrator itself

Each one cost real money before it was understood, and each is now a test:

1. **Retrying a wall.** Three attempts and $7.42 rediscovering the same Max
   session limit. Provider quotas and turn ceilings are deterministic; they no
   longer consume the retry budget.
2. **An adjudication with nowhere to go.** Approving a contract question cleared
   the escalation and then dead-ended on "attributed nothing to a repairable
   branch", because `contract` maps to no branch. The approval now carries the
   routing (`--answer route=`), and the approver's note travels into the brief.
3. **A bound that made things worse.** The routed branch was out of repair
   attempts, so nothing routed, so the fallthrough replanned from `decompose` —
   re-deriving design, the frozen contract and both branches to fix one
   assertion. `repair_attempts` bounds the machine; a human adjudication now
   buys the named branch an attempt.
4. **Success was unreportable.** `_finish` required *every* node to have passed,
   including the handler that is never scheduled. A run where everything worked
   reported `FAILED` because `triage` had never been asked to do anything.

### What it cost to be wrong

56% rework is the headline number and it is not flattering, which is why it is
computed from the journal rather than asserted. The three largest contributors:
two `implement` attempts that produced *zero files* because the repair brief had
not reached them yet, and one `decompose` re-run caused by the replan above.
