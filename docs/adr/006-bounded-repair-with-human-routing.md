# ADR-006: Failures route to a branch; humans decide what a machine cannot

**Status:** accepted, arrived at across three runs
**Supersedes:** the original "the graph is acyclic and a failure stops the run"

## Context

The first version of this graph was strictly acyclic. `verify` ran the suite; if
it failed, the run failed and a human read the journal. Defensible, honest, and
useless: the most common outcome of a real pipeline is *some tests fail*, and a
system whose answer to that is "stop, a human will look at it" has automated the
easy half of the job.

The obvious alternative — let the branches retry until green — is worse. An
agent asked to make a failing suite pass, given enough attempts, will eventually
make it pass, and the cheapest way is rarely the intended one.

The question is not whether to allow repair. It is **who decides what is
broken**, and **what stops the loop**.

## Decision

### `triage` is a handler, not a stage

It has no dependencies and is never scheduled. It runs only when `verify` fails,
reads the failures, and classifies each one: `implementation`, `test`, or
`contract`.

- `implementation` → route to `implement`
- `test` → route to `author-tests`
- a **mixture** → route to *every* implicated branch, each with its own itemised
  brief. Choosing one branch would be wrong; asking each to fix its own side is
  what a team does, and the branches are already isolated by path so they cannot
  tread on each other.
- `contract`, or **low confidence** → stop. Two sides reading one document
  differently is not settled by re-running, and a misrouted repair re-runs the
  innocent branch, leaves the defect in place, and spends an attempt doing it.

### Repair is bounded, and asymmetrically

`implement` gets two repair attempts, `author-tests` one — because a repair to
the test suite is the single case where an agent edits the thing that judges it.
Exhausting the budget falls through to a bounded replan, and exhausting *that*
is a safe stop.

### The brief is the whole mechanism

A repair with no account of what it is repairing is a re-roll of the same dice.
Each branch receives the failures attributed to *it*, itemised, with the
evidence for each — not the overall summary. This was learned the expensive way:
handed only "23 failing methods across 7 classes, three root causes", the node
found nothing addressed to it, concluded nothing was broken, wrote zero files,
and spent $6.92 doing so.

### A human decision can do three things a machine cannot

1. **Adjudicate a contract question** — and *name the branch that repairs it*,
   via `--answer route=<node>`, because deciding a contract question is deciding
   which side has to change. The approver's note travels into that branch's
   brief: the reasoning is the instruction.
2. **Buy an attempt.** `repair_attempts` bounds the *machine* — an agent
   re-running itself on its own judgment never converges. A human who
   adjudicates and names a branch has supplied exactly the outside authority the
   bound was holding out for, so each such decision buys that branch one
   attempt, counted from the journal so it cannot be spent twice.
3. **Send work back with no failure at all.** `sdlc.cli repair` — the verb that
   did not exist until a green build's *review* found a blocker and there was no
   move: rejecting the reviewing node re-runs the reviewer, which cannot change
   code, and approving it accepts the finding.

Recorded as `human_repair_requested`, deliberately **not** `repair_routed`. The
journal must never say the machine decided something a person decided.

## Consequences

**Some failures are walls, not weather.** A provider quota that resets at a fixed
hour and a turn ceiling the next attempt hits identically are deterministic;
retrying them converts a bounded policy into a burst of identical failures.
`terminal_failure()` names the wall, abandons the remaining attempts, and leaves
the node resumable.

**A bound that only refuses is not a control, it is an obstacle.** When the
routed branch was out of attempts, the run refused the routing, routed nothing,
and fell through to replanning from `decompose` — re-deriving design, the frozen
contract and both branches to fix one over-asserting assertion. The bound did
not stop the run; it made it expensive.

**Every routing decision is auditable and overrulable**, which is the actual
requirement. `triage_verdict` records the classification, the targets, the
reason and the cost, before anything is reset.

## Evidence

- `greenfield-3`: 23 failing methods across 7 classes → both branches repaired
  in parallel from separate briefs → 1 failure left. That one was a contract
  question, escalated, adjudicated by a human who named the branch, and the
  repair honoured the ruling rather than evading it.
- `brownfield-1`: `sdlc.cli repair` sent a review blocker to both branches on a
  green build. `implement` added the eligibility rule and worked out something
  the brief had not asked for — that *any* visible refusal would leak whether a
  code exists, so an unqualified report is filed and answered identically.
- `brownfield-1` seq 167: `triage` distinguished five Spring context startup
  errors from assertion failures — "nothing was asserted and lost" — and routed
  to one branch, which fixed it for $1.35.
- `triage` cost $7.79 across `greenfield-3` and $1.63 across `brownfield-1`. The
  replan it exists to avoid cost $1.05 in a single node re-run, plus everything
  downstream of `decompose`.
