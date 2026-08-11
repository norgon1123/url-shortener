# An agentic SDLC orchestrator, and the service it built

Two artifacts in one repository:

1. **[`orchestrator/`](orchestrator) — the deliverable.** A Python orchestration
   layer that drives a software delivery lifecycle: an explicit dependency graph,
   entry and exit gates, human checkpoints, bounded retries, fallback, rollback,
   safe-stop, policy guardrails, a hash-chained audit trail, reliability metrics,
   and dynamic re-planning.
2. **[`service/`](service) — the work product.** A URL shortener: Java 21, Spring
   Boot 3.5, PostgreSQL, Redis. **Every production class and test in it landed
   through an orchestrator run**, against the frozen contract in
   [`artifacts/openapi.yaml`](artifacts/openapi.yaml); node checkpoints carry
   `Run-Id` / `Node-Id` / `Attempt` trailers, so `git log` is provenance per
   commit. Two exceptions, both auditable: a hand-built initial scaffold
   (`e85ef36` — build files and compose, later superseded by `design` at
   `c557a64`), and one operator commit that swept a paused node's files into
   itself (`7753468` — the incident `docs/TODO.md` dissects).

The interesting claim is not that an agent wrote a URL shortener. It is that
every decision it made is attributable, every gate it passed is checkable, and
the two runs cost $206.86 of which **$112 was rework — a number this repository
computes about itself and publishes.**

## Start here

```bash
# 0. Once.
python -m venv .venv && .venv/bin/pip install -r orchestrator/requirements.txt

# 1. The whole machine, deterministically, in seven seconds. No API key, no spend.
.venv/bin/pytest orchestrator/tests                 # 429 tests

# 2. Replay a real run, entry by entry. Still no API key, still no spend.
export PYTHONPATH=orchestrator
alias sdlc='.venv/bin/python -m sdlc.cli --runs-dir orchestrator/fixtures/runs'

sdlc replay orchestrator/fixtures/runs/greenfield-3   # read the run, entry by entry
sdlc verify greenfield-3                              # re-check the hash chain yourself
sdlc report greenfield-3                              # metrics, derived from the journal

# 3. The service it produced.
cd service && ./mvnw verify                         # 276 tests; needs a Docker daemon
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
| Rework | $60.51 (56%; **27%** excluding operator-induced staleness) | $51.90 (53%) |

Both were **signed off by a human over a `ready: false` verdict.** Completed
means the pipeline reached its end with every decision recorded — not that the
service is shippable. The residuals are in [`docs/TODO.md`](docs/TODO.md), each
with its mechanism, blast radius and fix.

A third pair, [`ambiguous-1`](orchestrator/fixtures/runs/ambiguous-1) and
`ambiguous-2`, ran one deliberately underspecified requirement twice with
opposite answers to the same questions. Both safe-stopped after `decompose` on
purpose: **for an ambiguous requirement the artifact under test is the plan**, so
the validation *is* the differential — same graph, same prompts, opposite
answers, 14 tasks against 21. They also found that the answers were reaching
nobody. See below.

A fifth run, [`greenfield-2`](orchestrator/fixtures/runs/greenfield-2), ships
without being narrated here: it stopped at `verify` on an earlier 19-node graph
and is kept because how it ended is the useful part. Shipped fixtures total
$249.84.

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

## Where each §4.4 capability lives

| Capability | Implementation | Evidence it ran |
|---|---|---|
| Dependency graph | [`pipelines/sdlc.yaml`](orchestrator/pipelines/sdlc.yaml) — 21 nodes, declarative | both runs, `parallel_groups` in the metrics |
| Entry / exit gates | [`gates.py`](orchestrator/sdlc/gates.py) — 24 checks | 148 and 125 gate evaluations |
| Human checkpoints | `human` gate class; `approve` / `reject` / `repair` CLI | 17 recorded decisions across the shipped fixtures, whose answers reach the nodes |
| Bounded retries | `retry.max_attempts` per node, with the failure fed back into the next prompt | 17 failed attempts, none unbounded |
| Fallback | `on_failure: fallback` — one more attempt at reduced autonomy, proposing rather than applying | declared on `docs` (`sdlc.yaml:389`); covered by `test_engine.py::TestFallback`; never triggered live |
| Rollback | `on_failure: rollback` — reset the worktree to the last good checkpoint | covered by `test_engine.py::TestRollback` and `test_checkpoint.py`; declared on no node — see [ENGINEERING_SUMMARY §5](docs/ENGINEERING_SUMMARY.md#5-what-to-build-next) |
| Safe-stop | budget breach, or `stop` from another process, halting at a node boundary | 4 — one in `greenfield-3`, one in `ambiguous-1`, two in `ambiguous-2` (before and after `f4258e4`) |
| Policy guardrails | [`policy.py`](orchestrator/sdlc/policy.py) — path allowlists at the tool layer, re-checked against the diff | `paths_confined` on every node |
| Audit trail | [`audit.py`](orchestrator/sdlc/audit.py) — hash-chained JSONL | 345 and 250 entries, both verify |
| Reliability metrics | [`metrics.py`](orchestrator/sdlc/metrics.py) — computed from the journal only | [`docs/METRICS.md`](docs/METRICS.md) |
| Dynamic re-planning | content-hashed inputs; stale nodes and their descendants re-run | 29 and 3 nodes invalidated |
| Failure triage | `triage` handler classifies and routes to the branch that owns the failure | 23 failing methods → 1 → 0 |

**On "security, compliance, and change control".** Security is
[`policy.py`](orchestrator/sdlc/policy.py): path allowlists at the tool layer,
forbidden commands, and a secret scan that reports the pattern and line number
and **never echoes the match**. Change control is the branch discipline — no run
touches `main`, every node checkpoint carries `Run-Id` / `Node-Id` / `Attempt`
trailers, and protected paths (migrations, ADRs, CI config) escalate to a human
before a write lands. Compliance is what the first two produce together: a
tamper-evident record of who approved what, on which diff, with their reasoning
attached — four-eyes approval and segregation of duties, in the form an auditor
asks for. `sdlc.cli lineage` traces any journal entry back to the decision that
caused it.

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
- **A human's answers were never reaching the work.** For three runs, a person
  could answer a blocking ambiguity, the gate would confirm an answer existed,
  and every downstream node would keep building from the model's own proposal.
  The two earlier runs could not have caught it: the human agreed with the
  proposal both times, which makes the right answer and no answer produce
  identical output. **A control tested only by agreement is not tested.**
- **Seven defects in the orchestrator, each now a test.** Retrying a provider quota
  wall three times at $7.42. An approval that cleared a contract question and
  then dead-ended because it could not name the branch. A repair budget that
  refused the branch a human had just named, and replanned from `decompose`
  instead. Two gates that only worked on an empty repository. A `_finish` that
  reported `FAILED` when everything had passed.

## Documentation

| Page | For |
|---|---|
| [docs/ENGINEERING_SUMMARY.md](docs/ENGINEERING_SUMMARY.md) | **What happened**: four runs, seven defects, and what to build next |
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
