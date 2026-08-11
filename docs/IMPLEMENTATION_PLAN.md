# Implementation Plan — Agentic SDLC Orchestration System

This is the working plan of record. It is committed to the repository so the
design intent, the decisions, and the things deliberately *not* built are all
inspectable alongside the code they explain.

Status markers are maintained as the build progresses:
**DONE** / **IN PROGRESS** / **TODO**.

---

## 1. Context

An interview assignment for a Java Architect / Technical Program Manager
(AI Transformation) role. Two artifacts live in one repository:

1. **The work product** — a URL shortener service (Java 21, Spring Boot 3.x).
2. **The differentiator (§4.4)** — an *implemented* orchestration layer that
   drives the SDLC to produce (1): an explicit dependency graph, entry/exit
   gates, human checkpoints, bounded retries, fallback, rollback, safe-stop,
   policy guardrails, audit-grade traceability, reliability metrics, and
   dynamic re-planning.

Six of the eight §6 scoring criteria score the orchestrator, not the service.
The plan allocates effort accordingly.

---

## 2. Governing thesis — the gate taxonomy (ADR-001)

The absolutist claim "the LLM is never the gate" is falsifiable at two nodes
(`clarify` and `review-synthesis`) and hands a reviewer an easy counterexample. The
taxonomy is the defensible version:

| Gate class | Mechanism | Examples |
|---|---|---|
| **Mechanical** | Fully deterministic: process exit codes and thresholds | compile, `mvn verify`, JaCoCo floor, OpenAPI/route diff, secret scan, SpotBugs severity tiers |
| **Structured self-report** | Schema/threshold check on a field the LLM itself emitted — *always* backstopped by a downstream human checkpoint | `clarify` ambiguity count, `review-synthesis` findings |
| **Human** | Explicit approval, persisted with the approver's note | frozen-contract approval, release readiness, any high-impact action |

> **The LLM never approves — it can only satisfy a checkable predicate or escalate.**

Two consequences, both of which resolve real contradictions:

- `clarify` must emit `assumptions[]` **even when `ambiguities == 0`**, surfaced
  at the `design` human checkpoint. Under-reporting is caught by a human, not by
  the predicate the LLM populated.
- `review` is **advisory and cannot auto-fail**. Deterministic static analysis
  gates the run; LLM findings tagged `blocker` force `PENDING_APPROVAL`.
- Review is **fanned out into five independent lenses** (security, performance,
  API contract, test adequacy, cleanliness), each with its own prompt, worktree,
  and artifact, rejoined at a barrier. The synthesis node that folds them
  together may cluster and rank but is checked mechanically
  (`lens_findings_preserved`) for having dropped or softened nothing — otherwise
  the join quietly restores the single point of control the fan-out removed.

This is enforced structurally, not documented aspirationally: `graph.validate()`
rejects any pipeline in which a `self_report` gate has no human gate downstream
of it, and rejects any gate naming a check that is not implemented.

Framing for the audience: **this is CI/CD for agent work** — change control,
segregation of duties, four-eyes approval, audit trail.

---

## 3. Decisions locked

| Decision | Choice | Rationale |
|---|---|---|
| Service | Java 21 + Spring Boot 3.x | What the role hires for; the credibility artifact |
| Orchestrator runtime | Python + Claude Agent SDK | Built-in file/bash/grep tools, permission callbacks, hooks, subagents, enforced sandbox egress. No Agent SDK for Java; the Tool Runner would mean a day of undifferentiated tool plumbing |
| Deployment | Local CLI, CI-ready by construction | No daemon, no shared mutable state — SQLite + JSONL + git checkpoints — so the same entry point runs unchanged as a CI job |
| Service scope | Lean; Redis **and** Kafka documented, not built | Six of eight criteria score orchestration |
| **Rate limiting** | **Excluded from the base build** | It is the brownfield scenario's deliverable; shipping it in the base makes that demo incoherent |
| Provenance | Hand-built scaffold; orchestrator produces features | Stated openly in `ENGINEERING_SUMMARY.md`; commit trailers make it auditable per commit |

**The polyglot seam, defended proactively rather than when challenged:** the
orchestrator is developer tooling, not a production service — not in the
transaction path, no uptime SLA. Build tooling routinely differs in language
from the product it builds. The seam is narrow by design: it shells out to
`mvn`, `git`, and `gh`, so a Java port is mechanical.

**Provenance, stated honestly:** the scaffold (build configuration, compose
file, CI workflow) is hand-built. Orchestrator runs produce the features.
Checkpoint commits carry `Run-Id:` / `Node-Id:` / `Attempt:` trailers, so
`git log` is machine-verifiable provenance per commit. Ambiguity here reads as
staging; honesty reads as judgment.

---

## 4. Repository layout

```
url-shortener/
  service/                   # Java 21 + Spring Boot 3.x
  orchestrator/
    sdlc/
      graph.py               # DAG load, validation, topological scheduling, join barrier
      model.py               # node/gate/run types, statuses, approvals
      schemas.py             # JSON Schemas: SDK output_format at runtime, re-validated at the gate
      gates.py               # gate predicates, registered by taxonomy class
      policy.py              # path allowlist, forbidden commands, secret scan, high-impact classifier
      state.py               # SQLite run store — resumable across process restarts
      audit.py               # hash-chained JSONL journal + decision lineage
      nodes.py               # invocation record, backend protocol, prompt assembly
      mock.py                # scripted + recording node backends
      checkpoint.py          # git worktrees, commit-per-node, rollback, branch merge at join
      budget.py              # max_cost_usd / max_wallclock → safe-stop on breach
      engine.py              # the scheduler and the failure-handling state machine
      metrics.py             # success rate, retry/rollback frequency, MTTR, latency, cost
      cli.py                 # run | resume | approve | reject | stop | status | report | verify | replay | lineage
    pipelines/sdlc.yaml
    prompts/*.md
    tests/                   # pytest over mock backends — the orchestrator's own suite
    fixtures/runs/           # recorded journals shipped in-repo
  docs/
    IMPLEMENTATION_PLAN.md   # this file
    architecture.md
    deployment-staging.md
    demo-script.md
    ENGINEERING_SUMMARY.md
    adr/001..005
    scenarios/{greenfield,brownfield,ambiguous}.md
```

Node interiors call the Agent SDK's `query(prompt, options)` with a per-node
tool allowlist and a JSON output schema. **The graph, gates, journal,
checkpoints, policy, and metrics are hand-written** — no SDK supplies those,
which is precisely why §4.4 is the differentiator.

---

## 5. Build order (this is what protects the budget)

**Phase 0 — mock mode first. DONE.** Scripted node backends make the entire
graph/gate/checkpoint/approval/metrics machinery testable in *seconds* rather
than in 20–60 minute multi-dollar LLM runs. It is simultaneously the dev loop,
the orchestrator's own pytest suite (a governance tool with no tests of itself
is an irony the scoring criteria would punish), and the way an evaluator with
no API key can still exercise every control.

**Phase 1 — graph, gates, policy, checkpoints, journal, engine, CLI, metrics,
all against the mock. DONE.** 275 tests, ~2.5 s, no API key required.

**Phase 2 — the Java scaffold, then live Agent SDK nodes; run greenfield. DONE.**
`greenfield-3` completed: 148 tests, $108.70.

**Phase 3 — brownfield, ambiguous, and fault-injection scenarios. DONE, with
the scenario list revised.** `brownfield-1` completed: 276 tests, $98.16.
Ambiguity and fault injection turned out not to need staging — see §9.

**Phase 4 — metrics report, ADRs, documentation. DONE.**
[`METRICS.md`](METRICS.md), six ADRs in [`adr/`](adr), and this plan brought
back into line with what was built.

**Cut order under pressure:** separate `security` node (fold the scans into exit
gates) → HTML report (terminal/markdown table first) → service extras
(idempotency key, negative caching).

**What was actually cut, and why.** The separate `security` node: its scans are
exit gates on every node instead, which is stricter — a secret cannot reach a
checkpoint rather than being found at one. The HTML report: `report --json`
feeds anything, and the terminal table is what an operator reads mid-run. The
service extras survived; the review lenses asked for them and neither was
expensive.

---

## 6. The orchestration layer

### 6.1 DAG

```
intake → clarify → impact-analysis → feasibility → decompose → design → test-contract ─┬→ implement ────┐
                                                                                       └→ author-tests ─┴→ (join) → verify ─┬→ docs ───────────────┐
                                                                                                                            ├→ review-security ────┤
                                                                                            [verify fails] → triage         ├→ review-performance ─┤
                                                                                                   │                        ├→ review-api-contract ┼→ (review-join) → review-synthesis → release-readiness
                                                                                                   └→ routes a repair to    ├→ review-test-adequacy┤
                                                                                                      implement and/or      └→ review-cleanliness ─┘
                                                                                                      author-tests
```

21 nodes: 17 agent, 2 barrier (`join`, `review-join`), 1 deterministic
(`verify`, no LLM call at all), 1 handler (`triage`, invoked by failure and
never scheduled).

**Two nodes were added after the plan was first written**, and both were
earned rather than designed:

- **`test-contract`** — the executable half of the freeze. The original graph
  fanned `implement` and `author-tests` straight out of `design`, and they
  disagreed about method names, harness shape and fixture naming, so the join
  spent its time reconciling accidents. `test-contract` writes the test classes,
  behaviour-named signatures and harness — structure, no assertions — and both
  branches build against it. See [ADR-003](adr/003-segregation-of-duties.md).
- **`triage`** — a handler, not a stage. The original graph was strictly acyclic
  and a failing `verify` ended the run, which automates the easy half of the
  job. See [ADR-006](adr/006-bounded-repair-with-human-routing.md).

Each node declares `prompt`, `tools`, `write_paths`, `deny_paths`,
`output_schema`, `entry_gates`, `exit_gates`, `retry`, `autonomy`, `on_failure`.

**The parallel branch is the showpiece, and the thing most likely to break.**
Naive parallelism fails its own join: tests authored against classes that do not
exist will not compile. Three mechanisms make it work:

1. `design` emits a **frozen contract** — OpenAPI spec, compilable interface and
   DTO skeletons, and the **finalized `pom.xml`**. Dependencies are frozen here,
   so neither branch touches the build file and the otherwise-guaranteed merge
   conflict disappears.
2. `author-tests` writes **black-box HTTP/integration tests against the OpenAPI
   contract**, not unit tests against implementation classes. These genuinely
   can be authored blind, in parallel.
3. Branches run in **separate git worktrees**, merged at the barrier. A conflict
   escalates to a human rather than failing the run.

**Segregation of duties (ADR-003):** `implement`'s allowlist forbids
`service/src/test/**` and `author-tests`' forbids `service/src/main/**`. The
agent producing the code is structurally incapable of weakening the tests that
gate it. This is the answer to the hardest question a panel can ask.

**Non-linearity:** the replan edge (`verify` → `decompose`) and the ambiguity
edge (`clarify` → human → `intake`) make the graph genuinely
cyclic-with-bounds, not linear chaining.

### 6.2 Gate table (the whole graph, not half of it)

| Node | Entry gate | Exit gate | Class |
|---|---|---|---|
| `intake` | raw requirement present | `requirement.json` validates | mechanical |
| `clarify` | `requirement.json` exists | `assumptions[]` non-empty ∧ blocking ambiguities escalate | self-report + human |
| `decompose` | zero unresolved ambiguities | `plan.json` is a valid DAG, no orphan tasks | mechanical |
| `design` | `plan.json` valid | OpenAPI lints ∧ skeletons compile ∧ **human approves the contract** | mechanical + human |
| `implement` | frozen contract intact | compiles ∧ no writes outside the allowlist ∧ **no writes to `src/test/**`** ∧ secret scan clean | mechanical |
| `author-tests` | frozen contract intact | tests compile ∧ writes confined to `src/test/**` | mechanical |
| *(join)* | both branches checkpointed | merge clean; conflict → human | mechanical + human |
| `verify` | join complete | `mvn verify` == 0 ∧ JaCoCo line ≥ 70% ∧ routes match OpenAPI | mechanical |
| `impact-analysis` | `clarification.json` exists | `impact.json` validates ∧ writes confined | mechanical |
| `feasibility` | — | `feasibility.json` validates ∧ **no writes to `service/**`** | mechanical |
| `decompose` (cont.) | — | every acceptance criterion claimed by a task | mechanical |
| `docs` | verify green | writes confined to `docs/**` ∧ README links resolve | mechanical |
| `review-{security,performance,api-contract,test-adequacy,cleanliness}` | verify green | lens artifact validates ∧ writes confined | mechanical |
| *(review-join)* | all five lenses + docs checkpointed | merge clean; conflict → human | mechanical + human |
| `review-synthesis` | review-join complete | **every lens finding preserved and not downgraded** ∧ SpotBugs ≤ severity tier; LLM `blocker` findings → `PENDING_APPROVAL` (**never auto-fail**) | mechanical + human |
| `release-readiness` | all prior green | **human approval** before push/PR | human |

### 6.3 Failure handling — all four §4.4 controls, named separately

`on_failure: retry | fallback | rollback | replan | safe_stop`

- **retry** — bounded; attempt *N+1* receives the gate-failure output appended
  to its prompt. Retrying with an identical prompt would be superstition.
- **fallback** — autonomy degradation: `apply` → `propose`, so the node writes a
  scratch diff and a human applies it. Deterministic template path for `docs`.
- **rollback** — reset to the prior node's checkpoint commit. Resume after a
  crash reverts a dirty workspace to the last checkpoint, then re-runs.
- **replan** — two triggers, not one:
  - *failure-driven*: `verify` fails twice → back to `decompose` with the
    failure context. Bounded at 2.
  - *change-driven*: the journal records content-addressed input hashes. On
    resume, a node whose recorded input hash no longer matches marks itself and
    every descendant **stale** → replan. This is Make/Bazel-style invalidation,
    and it is what "re-plan when upstream outputs change" literally asks for.
- **safe-stop** — both graph-terminal *and* operator-initiated: `stop <run>`
  halts at the next node boundary.

**`reject` semantics:** `reject <run> <node> --note "..."` clears the recorded
decision, re-runs the node with the reviewer's note appended to its prompt, and
puts the revised result in front of a fresh decision. Both decisions survive in
the journal — that is the four-eyes record. Approve-only would be theatre.

**Budget guardrail:** `max_cost_usd` and `max_wallclock_seconds` per run, checked
*before* dispatching a node. Discovering the budget is blown after paying for the
node that blew it is an audit finding, not a control.

### 6.4 Controlled autonomy and high-impact detection

Per-node `autonomy`: `propose` → `apply` → `apply_and_push`.

The detection mechanism: an Agent SDK **permission callback** fires on writes to
protected paths (`pom.xml`, `db/migration/**`, `application*.yml`,
`docker-compose.yml`), and a **post-node diff classifier** catches anything the
callback missed — a Bash heredoc can bypass the callback; it cannot bypass the
diff. Either forces `PENDING_APPROVAL` regardless of configured autonomy.
Without this, "human approval for high-impact actions" is a claim with no
mechanism behind it.

### 6.5 Guardrails

Per-node tool allowlists; path allowlists and forbidden-path lists; a
secret-pattern scan on every diff that reports the pattern and line number and
**never echoes the matched secret**; no force-push; no commits to the default
branch; a feature branch per run.

Network egress is enforced by the Agent SDK's `SandboxNetworkConfig` — Maven
Central is reachable, nothing else is. This is genuine enforcement rather than
the best-effort command deny-list an earlier draft of this plan assumed.

### 6.6 Audit-grade observability

"Audit-grade" invites "can this be tampered with?", so:

- **Hash-chained JSONL journal** — every entry carries its predecessor's hash.
- **Full agent transcripts retained** — auditors want the prompt that produced
  the change, not just the outcome.
- **Commit trailers** `Run-Id:` / `Node-Id:` / `Attempt:` on every checkpoint.
- **Decision lineage** — replan entries reference the failing gate's journal
  entry; design artifacts cite the assumption IDs `clarify` emitted.
- **The journal is authoritative; SQLite is a materialized view.**
  `rebuild_from_journal()` proves the asymmetry, and it verifies the chain
  first, so a tampered log cannot launder itself into "state".

### 6.7 Metrics

`report <run>` → success rate, retry frequency, rollback frequency, MTTR,
end-to-end latency, and cost per node — computed **from the journal only**, so
the numbers cannot quietly disagree with the audit record.

MTTR has no standard meaning in a pipeline (there is no "service restored"
event), so it is defined explicitly: **first failing gate → next passing gate on
the same node**. A clean run reports `n/a (no failures)` rather than `0.0`,
which would read as instant recovery instead of never having broken — and a run
that broke and never came back reports `n/a (failed, never recovered)`, because
one sentence for both causes made the two indistinguishable.

**Rework cost** is reported beside the total: spend on attempts that a later
attempt replaced — a rejected contract, a retry after a failed gate, a node
re-entered by a replan. This is the question the system invites, so it is
answered explicitly rather than buried in an aggregate. The first live
greenfield run spent **71%** of its budget on rework, almost all of it on one
rejected contract; that is the honest cost of a human checkpoint that a
reviewer actually used.

Three defects in this accounting were found by running it and are worth
recording, because each made the report flatter than the truth:

- per-node cost accumulated only where a node *passed*, so a rejected or failed
  attempt showed `$0.00` next to ten minutes of work;
- resuming into an approved checkpoint re-gates the existing result rather than
  re-running it, and the replayed journal entry carried the original cost, so
  the same money was counted where it was spent and again where it was merely
  re-examined;
- an attempt rejected by a *gate* — as opposed to one that crashed — was
  journalled with no cost at all, which is why the run total and the ledger in
  the run store disagreed by the entire cost of the failed parallel branches.

The per-node column and the run total are now the same arithmetic, so they
cannot tell different stories.

---

## 7. The URL shortener (the work product)

- `POST /api/v1/links` (custom alias, TTL, `Idempotency-Key`),
  `GET /{code}` → **302** (301 caches away the analytics), `DELETE`,
  `GET /api/v1/links/{code}/stats`; **410** expired vs **404** unknown.
- **Code generation (ADR-004):** random 7-character base62 with a
  unique-constraint retry, *not* a sequence — sequential codes are enumerable,
  which is a real security defect for a shortener.
- **Security:** scheme allowlist (http/https); reject private, loopback, and
  link-local ranges (SSRF); reject self-referential hosts (redirect loops);
  negative-cache 404s against enumeration.
- **Data:** PostgreSQL via Testcontainers; **Caffeine only** — no Redis in the
  build.
- **Analytics:** transactional outbox → async consumer. Kafka is the documented
  scale path, not a dependency.
- Actuator/Micrometer, health probes, OpenAPI as a first-class artifact,
  integration tests over mocks.

---

## 8. Deployment staging

**Stage 1 — laptop.** Where the prototype demos. Fine for exploration,
unacceptable for regulated change control.

**Stage 2 — CI runner (the real answer for a team).** Self-hosted runners,
triggered by a ticket label or PR comment. The output is a **PR, not a merge**,
so human gates become **PR approvals** and inherit the organisation's existing
four-eyes controls instead of inventing parallel ones. The journal uploads as a
build artifact and ships to the SIEM.

**Stage 3 — orchestration service.** Queue-backed, multi-tenant, approval UI,
fleet-wide metrics.

**The property that makes this credible:** no daemon and no shared mutable state,
so the same entry point runs unchanged across all three stages.

**Financial-services controls:** does source leave the network — point the SDK at
Bedrock or Claude Platform on AWS inside the organisation's own account
(ADR-005; ⚠️ *verify Agent SDK Bedrock support before asserting it*).
Credentials in a vault rather than env files; sandbox egress policy; journal
retention.

---

## 9. The scenarios — planned, and as they actually ran

The plan called for three scenarios. Two ran, and the third turned out to be a
property of both rather than a run of its own.

**1. Greenfield — planned as "build shorten + redirect APIs", run as the full
requirement.** `orchestrator/fixtures/runs/greenfield-3`. The requirement grew
into [`input/greenfield.txt`](../input/greenfield.txt): 92 lines of business
voice covering accounts, abuse, expiry, analytics and scale. Full traversal, 148
tests, $108.70.

**2. Brownfield — planned as "add rate limiting", run as three real changes.**
`orchestrator/fixtures/runs/brownfield-1`. Rate limiting ended up inside the base
build, which would have made the brownfield diff artificial, so the ask became
[`input/brownfield.txt`](../input/brownfield.txt): close a URL filter that two
tricks walk past, add self-service sign-up, add anonymous links that expire after
a month. That mixture is a better exercise than the original — a defect, a
feature, and a feature that changes the security posture of an existing one.

**3. Ambiguous — planned as its own run, and it is not one.** The intent was
"make it reliable and add analytics", with `clarify` refusing to proceed. What
happened instead is that *both* runs escalated at `clarify` on their own terms,
which is the behaviour the scenario was meant to demonstrate:

- `greenfield-3` — two blocking ambiguities, resolved by a human before design.
- `brownfield-1` — two blocking ambiguities out of eleven questions, and the
  second is better than anything the scripted scenario would have produced:
  *any* visible refusal of a duplicate sign-up makes the endpoint an
  account-existence oracle, in a service that goes to deliberate lengths
  elsewhere to avoid being one. The node would not decide it, and said why.

A synthetic ambiguity run would have proved the node can escalate when handed
something obviously vague. Two real runs proved it escalates on the thing a
careful engineer would have escalated on, which is the claim worth making.

**Fault injection** was likewise not staged: two provider session limits, a
Spring context startup failure, a Testcontainers capacity exhaustion and a
Maven timeout arrived on their own. Each is in a journal, and each produced a
control that did not exist before — see §12.

---

## 10. Demo script

The halt-on-ambiguity moment alone reads as a scripted `if`. Four things make it
unfakeable:

1. **Differential behaviour, identical machinery** — the same DAG, the same
   prompts, zero scenario-specific branches. Greenfield passes through
   `clarify`; ambiguous halts. The journals side by side are the proof.
2. **Consequential approval** — run the ambiguous scenario **twice with
   different human answers** ("reliability = rate limiting + idempotency" vs.
   "reliability = health probes + retries") and show `decompose` producing
   different task graphs and different code. If approval merely resumes to a
   predetermined outcome, it is a pause button, not governance.
3. **Show `reject`, not only `approve`** — reject at `design` → the node re-runs
   with the note → a revised contract.
4. **Fault injection as co-headliner** — a forced `verify` failure → bounded
   retry → rollback → replan → safe-stop, visible in both the journal and the
   metrics. The halt proves *policy*; fault injection proves the *machinery*.

---

## 11. Verification

- `cd service && ./mvnw verify` — green, coverage at or above the floor.
- `docker compose up` plus a scripted `curl` walkthrough: create → redirect →
  stats → expire.
- `pytest orchestrator/tests` — graph, gates, rollback, replan, budget breach,
  all deterministic against mock backends, no API key.
- `run --scenario greenfield` end to end; `--scenario ambiguous` halts,
  `approve` resumes; the ambiguous scenario run twice with divergent answers
  producing divergent artifacts.
- `replay fixtures/runs/greenfield` — the full audit trail, inspectable with no
  API key and no spend.
- Fault injection: a forced `verify` failure through retry, rollback, replan,
  and safe-stop.
- `report <run>` → the reliability metrics, folded out of the journal.

---

## 12. Limitations, stated rather than discovered

Single machine; no distributed scheduler; metrics are per-run rather than
fleet-aggregated; the LLM reviewer is advisory by design and the mechanical
gates are authoritative; parallel branches mean two concurrent SDK sessions and
therefore a brief cost and rate-limit spike; cost scales with graph width; no
multi-tenant isolation; `routes_match_openapi` is a regex scan of Spring
annotations rather than an AST walk, so it handles literal-string mappings and
would need replacing for dynamically composed paths.

### 12.1 Limitations discovered, stated anyway

The ones above were foreseen. These were not, and each cost something to learn.
All are open; `docs/TODO.md` carries the fixes.

**The graph has no point where a new test meets the old code.** A bug fix is
supposed to produce a test that fails for the reason the bug exists.
`implement` and `author-tests` start together, `author-tests`' gates execute
nothing, and the first suite run is after the join with the fix already in the
tree. `brownfield-1`'s own `release-readiness` found this; the evidence was
produced by hand afterwards (`docs/evidence/`), and by hand is not a control.

**No review lens looks at the orchestrator.** Five lenses review the service.
`gates.py` was modified inside `brownfield-1`'s own history and nothing
examined that diff — while every mechanical outcome in the run is produced by
that code. The release node caught it; nothing structural would have.

**Re-planning has no cost ceiling.** Editing a prompt mid-run invalidated
everything downstream and spent $42.86 on re-runs in `greenfield-3`. The engine
does the right thing and never asks whether the operator meant to.

**The budget guard measures the wrong currency.** It counts estimated dollars
while the binding constraint is a subscription's rate limit. Both runs hit a
provider session limit with the guard reporting ~20% headroom.

**Approvals are per-node, not per-diff.** An approval clears any later
protected-path escalation from that node, including changes the human never saw.

**A node left `RUNNING` by a dead process needs a SQL update.** There is no
`reset` subcommand, which is a gap in a tool whose pitch is operability.

**Two gates were written against an empty repository** and failed on the first
run that inherited a real one. The general form is worth stating: *a gate whose
correctness depends on the starting state being empty is not a gate, it is a
coincidence.*
