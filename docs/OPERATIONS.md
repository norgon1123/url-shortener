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

```bash
python -m sdlc.cli report  <run-id>          # metrics, from the journal only
python -m sdlc.cli verify  <run-id>          # re-check the hash chain
python -m sdlc.cli replay  runs/<run-id>     # read a run without executing it
python -m sdlc.cli lineage <run-id> <entry>  # trace an entry to its cause
```

### `greenfield-1` — read the caveats before quoting its numbers

The first live run. Preserved because it is the best evidence of a human
checkpoint being *used*: the frozen contract was rejected over API versioning,
the node revised it, and both decisions are in the journal.

Two things make its metrics unreliable, and the journal is hash-chained so they
cannot be corrected retroactively:

- it predates the cost-accounting fix, so `implement` and `author-tests` report
  `$0.00` against real work, and re-gated entries are double-counted. Its
  journal says `$9.20`; the run store says `$17.43`, and the store is right;
- it ran against the 11-node pipeline. The graph is now 19 nodes.

Later runs are the ones to quote.

### `greenfield-3` — the one to quote

The 21-node graph end to end: completed, 148 tests green, 20 of 20 scheduled
nodes passed, $108.70 with 56% rework. `orchestrator/fixtures/runs/greenfield-3`
has the full account; the two things worth knowing before quoting it:

- **it was signed off over a `ready: false` verdict.** `release-readiness`
  refused, a human approved anyway to close the run, and the residual risks are
  in `docs/TODO.md`. "Completed" here means the pipeline reached its end with
  every decision recorded — not that the service is shippable, and the record
  says so in three places;
- **its rework figure is the most useful number in it.** $60.51 of $108.70 went
  on attempts that were thrown away. Four of the causes were defects in the
  orchestrator itself, each now a test.

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
