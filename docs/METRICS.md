# Reliability metrics

Two complete runs, both recorded, both replayable with no API key and no spend.
Every number below is computed from the hash-chained journal by `metrics.py` —
not accumulated in memory during the run, not written by a node, not typed by
hand into this file. Reproduce any of it:

```bash
python -m sdlc.cli replay orchestrator/fixtures/runs/greenfield-3
python -m sdlc.cli verify greenfield-3          # 345 entries, chain intact
python -m sdlc.cli report greenfield-3 --json   # the numbers below
```

## The two runs

| | `greenfield-3` | `brownfield-1` |
|---|---|---|
| Ask | build the service | fix a filter, add sign-up, add anonymous links |
| Starting point | empty repository | the previous run's output |
| **Node success rate** | 100% | 100% |
| **Gate pass rate** | 84.6% | 97.5% |
| **Retry frequency** | 35% of nodes | 70% of nodes |
| **Rollback frequency** | 0% | 0% |
| Replans | 1 | 0 |
| Safe stops | 1 | 0 |
| Human decisions | 7 | 5 |
| **MTTR** | 10,231 s | 4,796 s |
| **E2E latency** | 10.5 h | 15.8 h |
| — of which machine | 9.5 h | 6.5 h |
| — of which waiting on a person | 1.1 h | 9.3 h |
| **Total cost** | $108.70 | $98.16 |
| **Rework cost** | $60.51 (56%) | $51.90 (53%) |
| Journal entries | 345 | 250 |
| Gate evaluations | 148 | 125 |
| Tests at the end | 148 green | 276 green |
| Coverage at the end | 86.9% | 88.3% |

Both runs **completed**, and both were signed off by a human **over a
`ready: false` verdict** from `release-readiness`. Completed means the pipeline
reached its end with every decision recorded. It does not mean the service is
shippable, and `docs/TODO.md` holds the residuals.

Note: E2E latency is inflated due to running the agents against my personal claude
subscription. Some runs exhausted my session limit and required to wait for the session
to refresh. Actual runtime was closer to 30 minutes to an hour.

## What the numbers say

### The most expensive thing in either run was the operator

Of `greenfield-3`'s $60.51 of rework, **$42.86 was re-running nodes invalidated
by prompt edits made while the run was in flight** — 71% of the rework and 39%
of everything the run spent.

That is change-driven replanning working exactly as designed: a node's inputs
are content-hashed, editing a prompt changes the hash, and every node downstream
of it is stale and must run again. It is also the single largest cost in this
project, and it was self-inflicted. `brownfield-1`, run without touching prompts
mid-flight, spent **$1.70** on the same mechanism.

The lesson generalises past this project. In a pipeline where upstream artifacts
are content-addressed, *the cost of a change scales with how late it is made* —
and an operator with a terminal open is the fastest source of late changes. The
control that would have caught it does not exist yet: the engine happily
re-plans mid-run and never asks whether the human meant to spend $43.

### Rework is over half of both runs, and that is the honest number

56% and 53%. This is the figure most systems do not report about themselves,
because reporting it requires recording the cost of attempts that were thrown
away — which only happens if someone decides to. Three defects in the cost
accounting had to be fixed before these numbers meant anything: per-node cost
recorded only on success, re-gated attempts double-counted, and gate-failed
attempts recorded as costless. All three made rework look cheaper than it was.

Excluding operator-induced staleness, `greenfield-3`'s genuine rework was
**$17.65 of $65.84 — 27%**. That is the number to compare against
`brownfield-1`'s, and against a human team's.

### The two runs fail in opposite directions

`brownfield-1` has a **higher gate pass rate** (97% vs 85%) and **double the
retry frequency** (70% vs 35%). Both are true and they are not in tension.

Its gates passed more often because it had a working codebase, a green suite and
a frozen contract to build against — `verify` passed first time, where
`greenfield-3` needed two rounds of triage.

Its retries were concentrated in two gates that had only ever seen an empty
repository: `no_assertions` failed `test-contract` for **880 assertions it had
not written** (the inherited suite), and `tests_not_weakened` was still holding
`greenfield`'s baseline of 130 tests against a tree containing 148. Neither
defect could exist in a greenfield run. A second run against real code is where
an empty-repository assumption comes due, and that is most of what the 70%
measures.

### MTTR halved, and the definition matters

**MTTR here is wall-clock from the journal entry recording a failure to the
journal entry recording that node's next pass.** A failure opens the sample and
a pass closes it; an approval pause with no failure behind it is not a sample at
all. It therefore *includes human thinking time* whenever a recovery waited on a
person, which is why
`greenfield-3`'s 10,231 s is dominated by one contract question a person had to
adjudicate.

`brownfield-1` halved it (4,796 s) mostly because its failures were the kind a
machine could route: five Spring context startup errors that `triage`
distinguished from assertion failures — "nothing was asserted and lost" — and
sent to one branch, which fixed them for $1.35.

An MTTR that excluded human time would be a smaller and much less useful number.
The interesting question about a governed pipeline is not how fast the machine
recovers; it is how long the whole loop takes when a person is in it.

### Latency is dominated by whoever is not at the keyboard

`brownfield-1` ran 3 hours *less* machine time than `greenfield-3` and took 5
hours *longer* end to end. The difference is 9.3 hours of a run sitting at a
human checkpoint, plus a provider session limit that paused it until quota
returned.

For a pipeline whose whole design premise is that humans decide the things
machines should not, this is the number that predicts the actual cycle time in
an organisation — not the machine time, and not the cost.

### What the failures were, by run

| | `greenfield-3` | `brownfield-1` |
|---|---|---|
| Failed attempts | 13 | 4 |
| `triage` invocations | 5 | 1 |
| Repairs routed by `triage` | 3 | 1 |
| Repairs ordered by a human | 0 | 2 |
| Nodes invalidated by input changes | 29 | 3 |
| Escalations to a person | 7 | 6 |
| Retry budgets abandoned as futile | 0 | 1 |

The last row is a control that did not exist for the first run.
`greenfield-3` spent three attempts and $7.42 rediscovering the same provider
session limit; `brownfield-1` hit the identical wall, recognised it, declined
the remaining attempt, and cost $0.91.

### Where the money went

| `greenfield-3` | | `brownfield-1` | |
|---|---|---|---|
| `implement` | $30.97 | `author-tests` | $17.12 |
| `author-tests` | $29.18 | `implement` | $13.28 |
| `test-contract` | $8.47 | `review-test-adequacy` | $11.02 |
| `triage` | $7.79 | `test-contract` | $10.35 |
| `design` | $7.24 | `design` | $8.95 |

The parallel branches dominate both, as expected. The one that repays study is
`triage` at $7.79 in `greenfield-3`: that is the price of *not* replanning. The
single replan that did happen — because a repair budget refused a branch a human
had just named — re-ran `decompose` at $1.05 and would have re-derived design,
the frozen contract and both branches had it not been stopped.

## The pair that measured something else

`ambiguous-1` and `ambiguous-2` are not comparable to the two above: both
safe-stopped after `decompose`, at $6.73 and $11.37, because the plan was the
only artifact under test. What they measure is whether a human's answer changes
the output, which no aggregate metric in this document captures.

| | `ambiguous-1` | `ambiguous-2` |
|---|---|---|
| Questions raised at `clarify` | 8, two blocking | 8, two blocking |
| Answers given | referrer; probe surgery | country-from-IP; circuit breaker |
| Tasks planned | 14 | 21 |

**The first pass produced near-identical plans**, because the human's answers
never reached any node. That defect is worth more than the metric it broke, and
it is the reason the pair exists — see
[`fixtures/runs/ambiguous-1/README.md`](../orchestrator/fixtures/runs/ambiguous-1/README.md).

The number this suggests the report is still missing: **decision efficacy** —
what fraction of recorded human decisions demonstrably changed a downstream
artifact. It would have been zero for three runs, and nothing here would have
shown it.

## Metric definitions, and what each one hides

**Node success rate** — nodes that ultimately passed, over nodes scheduled.
100% in both runs, and it is the least informative number here: it says nothing
about how many attempts it took. Handler nodes are excluded, because a `triage`
that never had to run is the good case.

**Gate pass rate** — passes over passes-plus-failures. **Escalations are excluded
from both sides**, deliberately: a gate that escalated is neither a pass nor a
defect, it is the machinery doing its job, and counting it as a failure would
score a pipeline down for having human checkpoints at all. The cost of that
choice is that the headline rate says nothing about how often a run stopped for
a person — which is why escalations are reported separately below. Including
them would give 81.8% and 92.0%.

**Retry frequency** — nodes needing more than one attempt, over nodes run. Does
not distinguish a retry caused by the node from one caused by an operator's
prompt edit, which is why the staleness figure is reported separately above.

**Rollback frequency** — 0% in both runs. Honest, and slightly flattering: the
rollback path exists and is tested, but no failure in either run was of the kind
that needed it.

**MTTR** — wall-clock from the journal entry recording a **failure** to the entry
recording that node's next pass. Only failures open a sample: a node that paused
for approval without failing first is not counted, so this is
time-to-recover-from-a-failure, not time-to-clear-a-checkpoint. Human time is
included whenever a recovery waited on a person, which is exactly why
`greenfield-3`'s figure is dominated by one contract question.

**E2E latency** — first journal entry to last, including every pause. Split into
machine and human above, because the aggregate hides which one is the
bottleneck.

**Cost** — `ResultMessage.total_cost_usd`, token counts priced at API list
rates. **These are estimates and nobody is billed them.** The runs draw on a
Claude Max subscription; see [ADR-005](adr/005-where-inference-runs.md).

**Rework cost** — spend on attempts that were rejected, retried, or replanned
away. Mechanically: once a node spends again, whatever it spent before counts as
rework. That over-counts a handler like `triage`, whose five invocations in
`greenfield-3` were five distinct useful adjudications rather than four wasted
ones — the error runs against the submission, not for it. The number this
project is most reluctant to publish, and the one most worth having.

## What is not measured, and should be

- **Cost attribution for staleness.** The $42.86 above was computed for this
  document by walking the journal. It is not a first-class metric, so the
  orchestrator cannot warn an operator that an edit is about to cost $43.
- **Anything during a node.** The journal gains an entry when a node *finishes*.
  A node thinking hard and a node deadlocked look identical from outside. Two
  tiers of fix are specified in `docs/TODO.md`.
- **Gate cost.** Mechanical gates run compilers and test suites — real minutes of
  wall clock — and none of it is attributed. `verify` shows $0.00 because it
  makes no model call, which is true and misleading: it is one of the slowest
  nodes in the graph.
- **Whether the gates were themselves sound.** `brownfield-1`'s
  `release-readiness` found that `gates.py` was modified inside the run's own
  history with no review lens auditing that diff — and every mechanical outcome
  in this document is produced by that code.
