# What happened

A record of building an agentic SDLC orchestrator and running it four times, and
of what those runs taught that building it did not.

The orchestrator is in [`orchestrator/`](../orchestrator); the service it wrote
is in [`service/`](../service); the design is in
[`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) and the numbers are in
[`METRICS.md`](METRICS.md). This page is the part none of those hold: the
narrative, the mistakes, and what to build next because of them.

---

## 1. The shape of the work

Two artifacts: a URL shortener, and the pipeline that wrote it. The pipeline is
the deliverable — a 21-node graph with entry and exit gates, human checkpoints,
bounded retries, path-level guardrails, a hash-chained journal, and metrics
computed from that journal rather than asserted.

The whole thing rests on one rule, chosen before any code was written:

> **The LLM never approves. It can satisfy a checkable predicate, or it can
> escalate.**

Every gate declares a class — `mechanical`, `self_report`, or `human` — and the
class decides what its verdict is allowed to do. The rule is enforced when the
pipeline *loads*: a graph whose gates would let a model pass its own work is a
startup error, not a runtime surprise. ([ADR-001](adr/001-the-llm-never-approves.md))

**Mock mode came first**, before any live run. Scripted backends make the graph,
gates, checkpoints, approvals, failure handling and metrics testable in seconds
instead of twenty-minute multi-dollar runs. That decision paid for itself
repeatedly: 429 tests run in seven seconds with no credentials, every defect
found in a live run became a test in that suite, and an evaluator with no API
key can still exercise every control.

---

## 2. Four runs

| Run | Ask | Outcome | Cost |
|---|---|---|---|
| `greenfield-3` | build the service | completed, 148 tests green | $108.70 |
| `brownfield-1` | fix a filter, add sign-up, add anonymous links | completed, 276 tests green | $98.16 |
| `ambiguous-1` | "more reliable, and we want analytics" | safe-stopped after `decompose` | $6.73 |
| `ambiguous-2` | the same ask, answered the opposite way | safe-stopped after `decompose` | $11.37 |

**$224.96 total.** All four replay with no API key and no spend; the chains
verify at 345, 250, 35 and 52 entries.

Both completed runs were **signed off by a human over a `ready: false` verdict**
from `release-readiness`. Completed means the pipeline reached its end with every
decision recorded. It does not mean the service is shippable, and
[`TODO.md`](TODO.md) holds the residuals with their mechanisms and fixes.

### What the machinery caught that a person would have missed

- **A second-order security consequence on a green build.** Adding self-service
  sign-up is unremarkable in isolation. `review-security` saw that it re-scopes
  every authenticated endpoint from "two hand-provisioned accounts" to "anyone on
  the internet" — and the one capability that deliberately crosses tenant
  boundaries is an irreversible link takedown, bounded only by a rate limit keyed
  on a customer id that had just become free to mint. Any published short link
  could be permanently killed by anyone. Nothing was red; `verify` had passed.
- **`review-synthesis` auditing the tree instead of the findings.** The security
  lens re-raised that blocker against code that already contained the fix.
  Synthesis checked, found the premise superseded, named both commits — and kept
  it at blocker anyway, because *"the lens's underlying reasoning, that free
  accounts change the economics of every per-account limit in the service, is
  sound and survives the fix."*
- **`release-readiness` auditing the pipeline, not the code.** It reported that
  `gates.py` had been modified inside the run's own history with no review lens
  looking at that diff — *"a weakened gate would not have been visible from where
  any of the five lenses looked"* — and that an acceptance criterion's evidence
  existed only because a human had produced it by hand.
- **`clarify` turning one throwaway line into a falsifiable hypothesis.** From
  *"the last outage started somewhere else and we made it worse"*, it asked
  whether the platform routes on `/actuator/health` and whether a Redis blip
  therefore pulled every instance out of service at once. Nothing in the
  requirement said that. It is the difference between adding probes and removing
  a dependency from an existing one.
- **A repair that took a constraint further than the brief did.** Told not to
  disclose whether a code exists, the implementation worked out that *any* visible
  refusal breaks that — the endpoint already answers 202 regardless — so an
  unqualified report is filed and answered identically, and simply takes nothing
  down.

---

## 3. Seven defects, and what they have in common

Each was found by a live run, each cost real money, and each is now a test.

| | Defect | Cost | Fix |
|---|---|---|---|
| 1 | A provider session limit retried three times as though it were transient | $7.42 | [`fa232a2`](../../commit/fa232a2) |
| 2 | An approval cleared a contract question and then dead-ended, because `contract` maps to no branch and the approval could not name one | run halted | [`44c255e`](../../commit/44c255e) |
| 3 | A repair budget refused the branch a human had just named, so nothing routed and the run replanned from `decompose` | $1.05 + a near-miss on the whole pipeline | [`2d6d4d9`](../../commit/2d6d4d9) |
| 4 | `_finish` required every node to have passed, including the handler that is never scheduled — so a fully successful run reported `FAILED` | success unreportable | [`d4004ef`](../../commit/d4004ef) |
| 5 | `no_assertions` scanned the whole test tree and failed a node for 880 assertions it had not written | $6.91 | [`7753468`](../../commit/7753468) |
| 6 | `tests_not_weakened` kept a baseline from the previous run, so 18 inherited tests could have been deleted undetected | silent | [`7753468`](../../commit/7753468) |
| 7 | Human answers reached no node: the gate checked an answer *existed*, nothing read what it said | three runs of theatre | [`f4258e4`](../../commit/f4258e4) |

**None of them was the model doing something wrong.** That is the finding worth
carrying to the next project. The agents behaved well — better than expected at
`clarify`, `review-synthesis` and `release-readiness`. Every defect was in the
governance layer: in the human-in-the-loop path, or in an assumption about the
starting state.

Four patterns account for all seven.

### A control tested only by agreement is not tested

Defect 7 is the sharpest thing this project produced. For three runs, a human
could answer a blocking ambiguity, watch the gate accept it, watch the run
proceed — and change nothing about what was built. `answers` was read in exactly
two places: a gate checking an answer *existed*, and a routing key.

`greenfield-3` and `brownfield-1` had both "exercised" this control. Neither
could have detected its absence, because in both the human agreed with the
model's proposed answer — which makes the right answer and no answer produce
identical output.

It took a test designed so the human and the machine **disagree**. That is now
the rule: for any control where a human's decision is meant to change the
outcome, the test must be one where the two differ, or the test cannot fail.

### A gate whose correctness depends on an empty starting state is a coincidence

Defects 5 and 6 are the same mistake twice, and both surfaced within one node of
the first run against an existing codebase. Every gate had been written, tested
and exercised against a tree that was empty at that point in the graph. Handed
148 inherited tests, one failed a node for assertions it had not written and the
other silently under-protected the suite by eighteen tests.

A greenfield-only test suite cannot find this class of bug. Any pipeline meant
for real repositories needs a brownfield case from the first day, not the second
milestone.

### A bound that only refuses is an obstacle, not a control

Defect 3: `repair_attempts` correctly refused a branch that was out of attempts —
after a human had explicitly named that branch as the one to fix the problem.
Nothing routed, and the fallthrough replanned from `decompose`, re-deriving
design, the frozen contract and both branches to fix one over-asserting
assertion. The bound did not stop the run; it made it expensive.

The bound exists because an agent re-running itself on its own judgment never
converges. A human adjudicating and naming a branch *is* the outside authority
the bound was demanding. So the decision now buys an attempt, counted from the
journal so it cannot be spent twice. ([ADR-006](adr/006-bounded-repair-with-human-routing.md))

### A wall is not weather

Defect 1: bounded retry assumes failures are transient. A provider quota that
resets at a fixed hour and a turn ceiling the next attempt hits identically are
deterministic — retrying them converts a bounded policy into a burst of identical
failures. The first encounter cost $7.42 across three attempts; the second, after
the fix, cost $0.91 and one attempt, with the journal recording
`retries_abandoned` and the attempts left unspent.

---

## 4. The most expensive thing was the operator

Of `greenfield-3`'s $60.51 of rework, **$42.86 was re-running nodes invalidated
by prompt edits made while the run was in flight** — 39% of everything that run
spent. Change-driven replanning working exactly as designed: inputs are
content-hashed, editing a prompt changes the hash, and every node downstream is
stale.

`brownfield-1`, run without touching prompts mid-flight, spent **$1.70** on the
same mechanism.

The general form: in a pipeline where upstream artifacts are content-addressed,
**the cost of a change scales with how late it is made** — and an operator with a
terminal open is the fastest source of late changes. The engine does the right
thing and never asks whether the human meant to spend $43. That control does not
exist yet.

Rework overall was 56% and 53% of the two full runs. Excluding operator-induced
staleness, `greenfield-3`'s genuine rework was **27%**. These numbers exist at
all only because failed attempts are journalled with their cost — three
accounting defects had to be fixed before they meant anything, and all three had
made rework look cheaper than it was.

---

## 5. What to build next

### The `discriminate` node — the top item

`brownfield-1` was asked for a bug fix, and the requirement set the terms: *"we
want to see the failing test before we see the fix, and we want that test kept
afterwards."* The pipeline cannot show that, and `release-readiness` found this
about itself:

> AC3 has two halves and only one is evidenced. The tests exist and are retained,
> but "fail against the pre-change code" was never observed … By inspection the
> test does discriminate — but "by inspection" is exactly what AC3 refuses.

It is structural, not an oversight. `implement` and `author-tests` start
together; `author-tests`' exit gates — `tests_compile`, `tests_not_weakened`,
`paths_confined` — execute nothing; and the first time the suite runs is
`maven_verify`, after the join, with the fix already in the tree. **There is no
point in the graph where a new test meets the old code.**

It was closed by hand for that run ([`evidence/`](evidence/README.md)) and by
hand is not a control.

**Placement** — between `author-tests` and `join`, in its own worktree:

```
test-contract ─┬→ implement ──────────────────┐
               └→ author-tests → discriminate ─┴→ join → verify
```

**What it does.** Builds a tree from the run's starting commit for
`service/src/main/**`, overlays the current test tree, runs the tests for this
change's acceptance criteria, and requires them to fail.

**Three details decide whether it is worth anything:**

1. **The baseline must be the run's starting commit, not the preceding one.**
   `design` stubs new classes, so tests run against the immediately preceding
   commit fail with `UnsupportedOperationException` — which proves only that a
   stub is a stub. Producing AC3's evidence by hand hit exactly this: the useful
   baseline was the greenfield tip, the code customers were actually served by.
2. **Failing is not enough; it has to fail for the right reason.** A compile
   error, a missing class or a stub all read as red and mean nothing. The gate
   must require an *assertion* failure. The good evidence from the manual run was
   a live `ACTIVE` short link for `https://malware.example.com./x` —
   `expected: <422> but was: <201>` — not a stack trace.
3. **The interesting failure is the inverse.** The node fails when a new test
   **passes** against old code, because that means the test does not
   discriminate — it would pass whether or not the fix existed. A green suite can
   never tell you this. It is the same class as a finding from that run: *an
   implementation that over-refused every IP-literal target would have passed all
   seventeen AC1/AC2 behaviours.*

**Test selection is already derivable.** `test-contract.json` lists every
behaviour with its `criteria_ids`, so "the tests for this change's acceptance
criteria" is a lookup rather than a guess.

**Output:** the failing run log as a first-class artifact on the run record —
which is what `release-readiness` asked for and had to be handed manually.

**Cost:** one extra suite run per change, on a tree that does not build the fix
(about six minutes here). **Conditional on the scenario** — a greenfield change
has no pre-change code to fail against, so it should key off whether any
acceptance criterion is a defect rather than a feature.

**How to know it worked:** re-run `brownfield-1` and see whether it produces
mechanically what was produced by hand. That is the honest test, and it is
cheap, because the requirement and the expected evidence both already exist.

### After that

- **Decision efficacy as a metric** — what fraction of recorded human decisions
  demonstrably changed a downstream artifact. It would have read **zero for three
  runs** and nothing in the report would have shown it. The defect that motivated
  it is fixed; the blindness that hid it is not.
- **A review lens on the orchestrator's own code.** Five lenses review the
  service. Nothing reviews the gates that produce every mechanical outcome, and a
  gate was in fact edited inside a run with nobody looking.
- **A cost ceiling on re-planning.** The engine will happily spend $43 re-running
  a graph because a prompt changed, and will not ask.
- **A budget guard that measures the right currency.** It counts estimated
  dollars while the binding constraint is subscription quota; both runs hit a
  provider limit with the guard reporting ~20% headroom.
- **Close SEC-1.** Seeded accounts with published passwords are created by an
  unconditional migration on any deploy. Sign-up landing in `brownfield-1`
  removed the only argument for keeping them.

---

## 6. What I would do differently

**Write the brownfield case first, or at least second.** Two of seven defects
were gates that only worked on an empty repository, and both were invisible until
the second run. The greenfield case is the easy one and it flatters everything.

**Design the human-in-the-loop tests as disagreements.** Four of the seven
defects were in the human path — an approval that could not route, a bound that
refused a human's instruction, a repair verb that did not exist, answers that
reached nobody. Every one of them passed a test where the human and the machine
happened to want the same thing.

**Stop editing prompts during a run.** It is the single largest line item in the
project, it is entirely self-inflicted, and the mechanism that punishes it is a
feature working correctly.

**Keep the unflattering numbers in the headline.** 56% rework, `ready: false`
signed off twice, a control that was theatre for three runs. Each of those is in
the README and the metrics report rather than a footnote, and they are the parts
that make the rest credible.

**Trust the mock suite more, earlier.** Every defect above became a test in a
suite that runs in seven seconds. Several could have been written *before* the
run that found them, if the question asked had been "what does this gate assume
about the state it starts from?" rather than "does this gate pass?"
