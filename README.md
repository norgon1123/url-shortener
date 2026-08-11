# An agentic SDLC orchestrator, and the service it built

Two artifacts in one repository:

1. **[`orchestrator/`](orchestrator) — the deliverable.** A Python orchestration
   layer that drives a software delivery lifecycle: an explicit dependency graph,
   entry and exit gates, human checkpoints, bounded retries, fallback, rollback,
   safe-stop, policy guardrails, a hash-chained audit trail, reliability metrics,
   and dynamic re-planning.
2. **[`service/`](service) — the work product.** A URL shortener: Java 21, Spring
   Boot 3.5, PostgreSQL, Redis. **Every line of it was written by the
   orchestrator**, across two recorded runs, against the frozen contract in
   [`artifacts/openapi.yaml`](artifacts/openapi.yaml).

The interesting claim is not that an agent wrote a URL shortener. It is that
every decision it made is attributable, every gate it passed is checkable, and
the two runs cost $206.86 of which **$112 was rework — a number this repository
computes about itself and publishes.**

## Start here

```bash
# 1. The whole machine, deterministically, in six seconds. No API key, no spend.
pytest orchestrator/tests                       # 425 tests

# 2. Replay a real run, entry by entry. Still no API key, still no spend.
python -m sdlc.cli replay orchestrator/fixtures/runs/greenfield-3
python -m sdlc.cli verify greenfield-3          # re-check the hash chain yourself
python -m sdlc.cli report greenfield-3          # metrics, derived from the journal

# 3. The service it produced.
cd service && ./mvnw verify                     # 276 tests; needs a Docker daemon
```

Nothing in steps 1 and 2 calls a model. That is deliberate: an evaluator with no
credentials can exercise the graph, the gates, the checkpoints, the approvals,
the failure handling and the metrics end to end, in under a minute.

## The two runs

| | [`greenfield-3`](orchestrator/fixtures/runs/greenfield-3) | [`brownfield-1`](orchestrator/fixtures/runs/brownfield-1) |
|---|---|---|
| Ask | [build the service](input/greenfield.txt) | [three changes to it](input/brownfield.txt) |
| Outcome | completed, 148 tests green | completed, 276 tests green |
| Gate pass rate | 84.6% | 97.0% |
| Human decisions | 7 | 5 |
| Cost | $108.70 | $98.16 |
| Rework | $60.51 (56%) | $51.90 (53%) |

Both were **signed off by a human over a `ready: false` verdict.** Completed
means the pipeline reached its end with every decision recorded — not that the
service is shippable. The residuals are in [`docs/TODO.md`](docs/TODO.md), each
with its mechanism, blast radius and fix.

Full analysis, including what each metric hides: **[`docs/METRICS.md`](docs/METRICS.md)**.

## The one rule everything else follows from

**The LLM never approves.** It can satisfy a checkable predicate, or it can
escalate. Every gate declares a class — `mechanical` (a program decides),
`self_report` (the model populates a field a program then checks), or `human` (a
person decides) — and the taxonomy is enforced when the pipeline *loads*, not by
convention. A pipeline whose gates would let a model pass its own work is a
startup error.

See [ADR-001](docs/adr/001-the-llm-never-approves.md). The other five decision
records are [here](docs/adr/).

## Where each capability lives

| Capability | Implementation | Evidence it ran |
|---|---|---|
| Dependency graph | [`pipelines/sdlc.yaml`](orchestrator/pipelines/sdlc.yaml) — 21 nodes, declarative | both runs, `parallel_groups` in the metrics |
| Entry / exit gates | [`gates.py`](orchestrator/sdlc/gates.py) — 30 checks | 148 and 125 gate evaluations |
| Human checkpoints | `human` gate class; `approve` / `reject` CLI | 12 recorded decisions with notes |
| Bounded retries | `retry.max_attempts` per node, with the failure fed back into the next prompt | 17 failed attempts, none unbounded |
| Fallback | `on_failure: fallback` — one more attempt at reduced autonomy, proposing rather than applying | tested; not triggered live |
| Rollback | `on_failure: rollback` — reset the worktree to the last good checkpoint | tested; not triggered live |
| Safe-stop | budget breach, or `stop` from another process, halting at a node boundary | 1 in `greenfield-3` |
| Policy guardrails | [`policy.py`](orchestrator/sdlc/policy.py) — path allowlists at the tool layer, re-checked against the diff | `paths_confined` on every node |
| Audit trail | [`audit.py`](orchestrator/sdlc/audit.py) — hash-chained JSONL | 345 and 250 entries, both verify |
| Reliability metrics | [`metrics.py`](orchestrator/sdlc/metrics.py) — computed from the journal only | [`docs/METRICS.md`](docs/METRICS.md) |
| Dynamic re-planning | content-hashed inputs; stale nodes and their descendants re-run | 29 and 3 nodes invalidated |
| Failure triage | `triage` handler classifies and routes to the branch that owns the failure | 23 failing methods → 1 → 0 |

## What the runs found out

The parts worth reading are the ones where the machinery caught something a
person would have missed, or where it was wrong and the record says so.

- **A second-order security consequence, on a green build.** Adding self-service
  sign-up is unremarkable. What `review-security` saw is that it re-scopes every
  authenticated endpoint from "two hand-provisioned accounts" to "anyone on the
  internet" — and the one capability that crosses tenant boundaries is an
  irreversible link takedown, bounded only by a rate limit keyed on a customer id
  that had just become free to mint. Nothing was red. `verify` had passed.
- **The release node audited the pipeline, not just the code.** It found that a
  gate had been edited inside the run's own history with no review lens looking
  at that diff — *"a weakened gate would not have been visible from where any of
  the five lenses looked"* — and that an acceptance criterion's evidence existed
  only because a human produced it by hand.
- **The most expensive thing in either run was the operator.** $42.86 of
  `greenfield-3`'s spend was re-running nodes invalidated by prompt edits made
  while the run was in flight. Change-driven replanning working exactly as
  designed, and a self-inflicted 39% of that run's cost.
- **Six defects in the orchestrator, each now a test.** Retrying a provider quota
  wall three times at $7.42. An approval that cleared a contract question and
  then dead-ended because it could not name the branch. A repair budget that
  refused the branch a human had just named, and replanned from `decompose`
  instead. Two gates that only worked on an empty repository. A `_finish` that
  reported `FAILED` when everything had passed.

## Documentation

| Page | For |
|---|---|
| [docs/METRICS.md](docs/METRICS.md) | The two runs, measured, with what each metric hides |
| [docs/adr/](docs/adr) | Six decision records — what each cost, and what would reverse it |
| [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) | The design, and the plan it was built to |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Driving the orchestrator: commands, recorded runs, constraints |
| [docs/TODO.md](docs/TODO.md) | Known and not built, each with mechanism and fix |
| [docs/evidence/](docs/evidence) | A failing-test run the graph could not produce for itself |
| [docs/REQUIREMENTS_BRIEF.md](docs/REQUIREMENTS_BRIEF.md) | The service's design positions, and what the requirement withheld |
| [docs/API.md](docs/API.md) | Callers: endpoints, working `curl`, error codes |
| [docs/RUNBOOK.md](docs/RUNBOOK.md) | Operators: configuration, health, failure modes |
| [input/README.md](input/README.md) | Both requirements as the agents received them, and what changed |

## The service, briefly

Customers turn long URLs into short links; anyone may click one and is
redirected; every click is counted exactly and reported to the link's owner.
Anyone may create an account, or create a link without one — an anonymous link
nobody owns, which expires after 30 days.

```bash
docker compose up -d postgres                    # compose starts PostgreSQL only
docker run -d --name shortener-redis -p 6379:6379 redis:7-alpine
cd service && ./mvnw spring-boot:run

curl -s -X POST http://localhost:8080/api/v1/public/links \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/a/very/long/path?with=query"}'
```

**Do not deploy this anywhere reachable.** Two hand-installed accounts with
published passwords are created by an unconditional migration, and the residual
risks in [`docs/TODO.md`](docs/TODO.md) are open by decision, not by oversight.

## Costs

Every dollar figure here is an **estimate**, and nobody is billed it. The runs
draw on a Claude Max subscription rather than an API key; the figures come from
token counts priced at list rates. They are a sound basis for comparing nodes
against each other and are not an invoice.
[ADR-005](docs/adr/005-where-inference-runs.md) states what would have to change
for them to become one, and flags itself as the weakest claim in the repository.
