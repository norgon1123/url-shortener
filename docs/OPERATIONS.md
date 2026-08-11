# Operations

Everything needed to drive this repository that is not recoverable from the
code, the tests, or `git log`. Written to be read cold.

---

## Running the orchestrator

```bash
# Deterministic, no API key, no spend. This is the development loop.
pytest orchestrator/tests

# A live run. Halts at every human checkpoint; exit code says which.
PYTHONPATH=orchestrator python -m sdlc.cli run \
  --pipeline orchestrator/pipelines/sdlc.yaml \
  --workspace . --prompts orchestrator/prompts \
  --backend live --scenario greenfield \
  --branch sdlc/<run-id> --run-id <run-id>
```

Exit codes are the interface, because a CI job that stops for a human has to be
distinguishable from one that broke: **0** completed, **1** failed, **2**
waiting on a decision, **3** safe-stopped.

### Docker

`verify` runs the Testcontainers suite and therefore needs a reachable Docker
daemon. Group membership is per-process and is not picked up by an existing
session, so wrap the command rather than relying on the ambient environment:

```bash
newgrp docker -c "PYTHONPATH=orchestrator python -m sdlc.cli --runs-dir runs resume <run-id>"
```

Every child process inherits the group, which is what Maven needs. `sg` is not
present on this machine; `newgrp -c` is.

---

## Branch discipline

**Run output never lands on `main`.** Each run gets `--branch sdlc/<run-id>`,
and node checkpoints commit there with `Run-Id` / `Node-Id` / `Attempt`
trailers. `main` carries the orchestrator, the hand-built scaffold, and the
documentation.

Do not fast-forward `main` to a run branch. It is a tempting way to "catch up"
on orchestrator fixes committed during a run, and it drags every unreviewed node
checkpoint onto the default branch with them. Cherry-pick the orchestrator
commits instead.

The related trap, which has already happened once: **edits made to this
repository while a run is in flight get swept into whichever node checkpoints
next**, under that node's trailers. `CheckpointManager` now excludes the run's
`forbidden_paths`, so `orchestrator/**` is safe — but anything outside that list
is not. Prefer not to edit the workspace during a live run.

---

## Recorded runs

`runs/` is gitignored; journals are exported to `orchestrator/fixtures/runs/`
deliberately, so an evaluator can replay one with no API key and no spend.

All of these need `sdlc` on the path; the package is not installed:

```bash
export PYTHONPATH=orchestrator
# Shipped runs live in fixtures, not in the gitignored `runs/`. Point at them:
alias sdlc='python -m sdlc.cli --runs-dir orchestrator/fixtures/runs'

sdlc report  <run-id>                              # metrics, from the journal only
sdlc verify  <run-id>                              # re-check the hash chain
sdlc replay  orchestrator/fixtures/runs/<run-id>   # read a run without executing it
sdlc lineage <run-id> <entry>                      # trace an entry to its cause
```

**Five runs ship** in `orchestrator/fixtures/runs/`: `greenfield-2`,
`greenfield-3`, `brownfield-1`, `ambiguous-1`, `ambiguous-2`. Each has its own
README. `greenfield-1` is **not** shipped — see below.

### `greenfield-1` — retained locally, deliberately not shipped

The first live run, on the 11-node pipeline. It is the best evidence of a human
checkpoint being *used* — the frozen contract was rejected over API versioning
and the node revised it — but it is not in `fixtures/` because its numbers are
wrong and, the journal being hash-chained, cannot be corrected: it predates the
cost-accounting fix, so `implement` and `author-tests` report `$0.00` against
real work and re-gated entries are double-counted. Its journal says `$9.20`; the
run store says `$17.43`, and the store is right.

Shipping a journal whose own metrics command produces a wrong answer would be
worse than not shipping it. What it demonstrated is demonstrated again, with
sound numbers, in `greenfield-3`.

### `greenfield-2` — the 19-node graph, stopped at `verify`

Shipped, and worth reading precisely because it did not finish: 163 tests ran,
160 passed, and the run is preserved as it ended rather than nursed to green.
The graph is now 21 nodes, so its shape is one generation behind — `test-contract`
and `triage` came out of what this run exposed.

### The ambiguous pair — `ambiguous-1` and `ambiguous-2`

One underspecified requirement, run twice with opposite answers, both
safe-stopped after `decompose`. Read
[`fixtures/runs/ambiguous-1/README.md`](../orchestrator/fixtures/runs/ambiguous-1/README.md)
— the pair found that human answers were reaching no node at all.

### `greenfield-3` — the one to quote

The 21-node graph end to end: completed, 148 tests green, 20 of 20 scheduled
nodes passed, $108.70 with 56% rework. `orchestrator/fixtures/runs/greenfield-3`
has the full account. Two things before quoting it: it was signed off over a
`ready: false` verdict (see [METRICS.md](METRICS.md)), and its most useful number
is the rework — $60.51 of $108.70 went on attempts that were thrown away, four of
them caused by defects in the orchestrator itself.

### `brownfield-1` — the same graph against code that exists

`orchestrator/fixtures/runs/brownfield-1`. Completed: 276 tests, 88.3% coverage,
97% gate pass rate, zero replans, $98.16 with 53% rework. Signed off the same way
as `greenfield-3`.

Read it for the things a greenfield run cannot show: an impact analysis with a
real codebase to analyse, a security finding that no test could have caught
because nothing was red, and two findings the release node made against the
pipeline itself.

### Sending work back to a branch

`triage` routes repairs out of a `verify` failure. When the build is green and a
*review* finds something, that route does not exist — rejecting the reviewing
node re-runs the reviewer, which cannot change code, and approving it accepts
the finding. Use:

```bash
python -m sdlc.cli repair <run-id> implement author-tests \
  --approver <you> --note "what to fix and why"
```

The note reaches each node as its brief, so it is the whole instruction rather
than a label; a repair with no account of what it is repairing is a re-roll of
the same dice. It resets the named nodes and everything downstream, and records
`human_repair_requested` — not `repair_routed`, because the journal must never
say the machine decided something a person decided.

Each such decision also buys the named branch one repair attempt beyond its
machine budget. See [ADR-006](adr/006-bounded-repair-with-human-routing.md).

### A note on resuming after a session limit

A Max subscription's session limit ends a node mid-run with
`success; You've hit your session limit · resets <time>`. The engine no longer
spends retry attempts on it (a wall is not weather), so the node fails cleanly
and the run is resumable once the quota returns:

```bash
python -m sdlc.cli status <run-id>       # see which node stopped
python -m sdlc.cli resume <run-id>
```

A node left at `RUNNING` because the *process* died — rather than the node
failing — needs its row reset to `pending` before a resume will schedule it.
That is a gap: there is no `reset` subcommand, and it is currently a SQL update
against `runs/state.db`.

---

## Cost

The review fan-out is the expensive half of a run: six concurrent sessions,
two at `xhigh` effort. Budget accordingly — `max_cost_usd` in the pipeline is
a hard ceiling enforced *before* a node is dispatched, and a breach safe-stops
the run rather than failing it, so raising the ceiling and resuming is the
recovery.

For scale: the 11-node graph with one rejected contract cost about $17 and
roughly an hour of wall clock.

### Where inference runs, and who pays

**These dollar figures are estimates, and nobody is billed them.** Read that
before quoting any number in this repository.

No API key is involved. `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` and the
Bedrock/Vertex switches are all unset; the only credential is
`~/.claude/.credentials.json`, an OAuth token with `subscriptionType: max`. The
Agent SDK spawns the bundled `claude` CLI, which authenticates exactly as an
interactive session does, so **every node draws on the operator's Claude Max
subscription quota.**

The per-node cost comes from `ResultMessage.total_cost_usd`, which the CLI
derives from token counts priced at API list rates. It is a good proxy for how
much model work a node did, and a sound basis for comparing nodes against each
other. It is not an invoice.

Two consequences follow, and both matter more than the numbers do:

- **The budget guard is denominated in a currency nobody is spending.**
  `max_cost_usd` is a real ceiling on how much model work a run may do, and it
  is sized from measured runs — but it is not protecting a bill. The binding
  constraint is the subscription's rate limits and quota, which the orchestrator
  neither reads nor respects. A long run can exhaust the allowance while the
  guard sits at half its ceiling reporting plenty of headroom.
- **This is a prototype-stage answer, and a regulated buyer will ask.** The
  deployment story points inference at Bedrock or Claude Platform on AWS inside
  the organisation's own account (ADR-005), where the spend is a metered line
  item against a cost centre and this estimate becomes an actual invoice. Until
  that is verified rather than assumed, the honest statement is the one above:
  a personal subscription, and an estimate.

---

## Known constraints

- **`routes_match_openapi` is a regex scan** of Spring annotations, not an AST
  walk. It handles literal-string mappings and would need replacing for
  dynamically composed paths.
- **Metrics are per-run**, not fleet-aggregated.
- **The LLM reviewers are advisory by design.** Mechanical gates are
  authoritative; a `blocker` finding escalates to a human and cannot fail a run.
- **Resuming a run after the pipeline file changes** triggers change-driven
  staleness: any node whose recorded input hash no longer matches is marked
  stale along with its descendants, and re-runs. This is correct behaviour and
  it is not free — a graph edit mid-run can mean replanning from `decompose`.
