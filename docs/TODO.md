# TODO

Work identified but not built. Each item says why it matters, so a future
reader can judge whether it still does.

---

## SEC-1: seeded customer accounts ship to production — accepted risk, must be fixed

**Status: risk accepted by neil at `review-synthesis`, greenfield-3, to let the
run finish. Not fixed. This is the first thing to fix in the next run.**

`service/src/main/resources/db/migration/V2__seed_customers_and_denylist.sql:13`
inserts two customer accounts through an ordinary Flyway migration. There is no
profile and no placeholder gate — `spring.flyway.enabled: true`,
`locations: classpath:db/migration` — so **a production deploy creates them**.
Their plaintext passwords are published in `artifacts/openapi.yaml:99-102`
(`alice-dev-password`, `bob-dev-password`). The migration comment says "local
and test use only"; nothing enforces that, and a comment is not a control.

Because the service has no registration endpoint, these *are* the accounts.
Either one can create links and, via the abuse endpoint (SEC-5, medium), take
any link on the service down permanently. Two lenses concurred at high
confidence: a published credential in an unconditional migration, not a
hardening nit.

**Fix.** Move the two customer `INSERT`s to `classpath:db/testdata`, added to
`spring.flyway.locations` only under a dev/test profile. Leave the denylist rows
in the main migration — they are real data. Provision real accounts by an
operator step. Rotate the plaintexts out of `openapi.yaml`, or mark them
explicitly as fixtures that exist only when the test location is active.

**Note the interaction with SEC-5.** As long as any account can take down any
link, the blast radius of one leaked credential is the whole service. Fixing
SEC-1 alone narrows who has the credential; it does not narrow what the
credential can do.

**Why it was accepted.** The build is green and the remaining budget did not
cover a reject-and-repair cycle ($106.11 of a $120 ceiling at the decision
point). Accepting it buys the completed run and its metrics; it does not buy a
shippable service. Nothing here should be deployed anywhere reachable until this
is closed.

---

## Maintain `CONTEXT.md`, `AGENTS.md` and `CLAUDE.md` from every run

Follow the established conventions rather than inventing a format:
[agents.md](https://agents.md) for `AGENTS.md`, and Anthropic's project-memory
guidance for `CLAUDE.md`. Both say the same things, and they are the things that
matter: short, specific, actionable, and about *this* repository. Runnable
commands over prose. No restating what the code already says.

**Length is the discipline.** `AGENTS.md` should fit on a screen — a file that
gets skimmed is worse than a short one, because its length buys nothing and
costs attention. `CONTEXT.md` can be longer, but only where it explains
something a reader could not get faster from the code.

**`AGENTS.md`** — how to work here: setup and test commands, the definition of
done for a bug (a test that fails for the reason the bug exists, written first,
kept afterwards as regression protection) and for a feature (every acceptance
criterion has a test that would fail without the behaviour), which kind of test
to write and when, and the boundaries that are enforced — path allowlists,
protected paths, the coverage floor.

**`CONTEXT.md`** — what the thing is: behaviour, project structure, the API in
summary pointing at `artifacts/openapi.yaml` as the authority, the data model,
and the decisions worth not re-litigating (302 not 301; 404 not 403 on a foreign
code; random base62, not a sequence).

**`CLAUDE.md`** — prefer an import over a symlink:

```markdown
@CONTEXT.md
```

Claude Code resolves `@path` imports, so the content stays in one file without a
symlink, which git and Windows checkouts both handle badly.

### Placement: a node in the post-`verify` fan-out

`project-memory`, on the level with `docs` and the review lenses, in its own
worktree. The code is final there (everything on that level runs after `verify`
passes), the level is already six-wide so a seventh node costs no wall clock,
and keeping it out of `docs` is what lets them run concurrently — the loader
requires concurrent writers to hold disjoint paths. `docs` owns `docs/**` and
`README.md`; this owns `CONTEXT.md`, `AGENTS.md` and `CLAUDE.md`.

Not inside `review-synthesis`: that node's output is checked mechanically
against the lens artifacts, and a second unrelated responsibility makes that
check harder to reason about for no gain.

### Two project-specific requirements

**A human region that runs never touch.** Sentinel markers, plus a
`human_sections_preserved` gate asserting the bytes between them are unchanged
and the markers still exist. Prompts asking politely are not controls.

```
<!-- BEGIN HUMAN -->
<!-- END HUMAN -->
```

**Staleness.** Regenerate from the repository each run, not from the run's
memory of what it did. A generated file that quietly stops matching the code is
worse than no file, because it is trusted.

### One consequence worth knowing

The pipeline's own nodes will not read these files — `setting_sources` is unset,
so no session inherits a `CLAUDE.md`, and a run that behaves differently
depending on workspace files is not reproducible. These are for humans and for
interactive tooling.

---

## Adjudicate the unresolved `greenfield-2` failure

`actuatorPrometheusIsNotShadowedByTheRedirect` expected 200 from
`/actuator/prometheus` and got 404. Either the endpoint genuinely is not exposed
— a gap in the hand-written scaffold, since `application.yml` lists `prometheus`
under the exposure include and `micrometer-registry-prometheus` is a runtime
dependency — or the test asserted an endpoint the contract never promised. It
has not been settled, and it is the one failure from that run nobody has
attributed.

The `triage` node now in the graph is exactly the thing that would answer it.

---

## Stage checkpoints from the write allowlist, not a denylist

**Problem.** `CheckpointManager.checkpoint` stages the whole working tree minus
the run's `forbidden_paths`. That is a denylist, and it only stops what somebody
predicted. Anything else present in the tree when a node finishes lands in that
node's commit, under `Run-Id` / `Node-Id` / `Attempt` trailers asserting the
node produced it.

Both ways this fails have now happened, hours apart:

- an operator editing the repository during a live run had their work committed
  under `Node-Id: clarify`. That is what the `forbidden_paths` exclusion was
  added for, and it closed that particular door;
- the Agent SDK's `bwrap` sandbox mounts a fake HOME at the workspace root, so
  every node that runs a Bash call drops `.bashrc`, `.profile`, `.zprofile`,
  `.gitconfig`, `.vscode`, `.mcp.json` and friends into the repository. No
  policy list anticipated them because the sandbox invents them. Currently
  handled by a `/.*` rule in `.gitignore`, which works and is not the same thing
  as being structurally impossible.

The second one is the tell: a denylist will keep meeting things nobody put on
it.

**Fix.** Stage only what the node was permitted to write — its `write_paths`,
minus its `deny_paths` and the run's `forbidden_paths`. `PolicyEngine.check_write`
already makes this decision per path, and `paths_confined` already computes the
set for the same node moments earlier, so this is reusing an existing judgement
rather than inventing a second one. A commit then contains exactly what the
gates verified, and debris cannot enter it whatever its name.

**Watch out for:**

- **Barriers and deterministic nodes** declare no `write_paths`. A merge commit
  produced by `join` legitimately carries both branches' files, so the barrier
  path must keep staging normally rather than staging nothing.
- **Deletions.** A node removing a file is a legitimate change inside its
  allowlist; pathspec staging has to cover removals, not just additions.
- **The escalation path.** A write to a protected path is *allowed and
  recorded*, not denied, so protected paths must still be staged — the human
  approval is what gates them, and a commit that silently omitted them would
  hide the very change being escalated.
- **Nothing to stage.** A node whose only changes were outside its allowlist
  should produce no commit, exactly as an empty checkpoint does today, rather
  than an empty one.

**Why it is not urgent.** `paths_confined` already *fails* a node that wrote
outside its allowlist, so a violation cannot pass silently; this is about what
the commit contains when it does not. The gitignore rule holds in the meantime.

---

## Run heartbeat, in two tiers

**Problem.** A run has no heartbeat. The journal only gains an entry when a node
*finishes*, and transcripts are written at node end, so a node that is thinking
hard and a node that is deadlocked look identical from outside — silence in both
cases. The only way to tell them apart today is `ps`: look for a live `bwrap`
child, or check whether the session has accumulated CPU. That is a fair thing to
point at a system whose pitch is audit-grade observability.

It bites hardest exactly where runs are least attended: a `high`-effort node
with Bash access can explore a codebase for ten minutes in complete silence, and
in CI nobody is watching `ps` at all.

**Tier 1 — journal `node_progress` (do this one first).**
The live backend already receives every message in `_record`, including each
`ToolUseBlock`. Emit a journal entry per tool call — node id, attempt, tool
name, and an elapsed counter — so the audited record itself becomes the live
feed. Cheap to add, and it puts the evidence where every other claim about the
run already lives rather than in a side channel.

Two things to get right:

- **Volume.** A node making a hundred tool calls would add a hundred entries and
  bloat the chain. Either sample (every Nth call, plus the first and last), or
  emit a single rolled-up entry per turn rather than per call.
- **Secrets.** Tool *inputs* are already retained in transcripts, and the same
  scan that guards the diff should guard anything that reaches the journal.
  `PolicyEngine.scan_secrets` reports the pattern and line, never the match, and
  progress entries must hold to that.

**Tier 2 — `status --watch`.**
A convenience view that tails the run: current node, elapsed, last progress
entry, spend against the budget ceiling. Strictly a reader over tier 1 — it
must not become a second source of truth, or the "metrics come from the journal
only" property quietly stops being true.

**Why this order.** Tier 2 alone would be the tempting shortcut and the wrong
one: it would put liveness information somewhere an auditor cannot replay. Tier
1 is the substance; tier 2 is the ergonomics on top.

**Related.** `metrics.py` already derives everything from the journal, so
per-node duration would become measurable *during* a run rather than only after
it, and a stuck node would be visible as a progress gap rather than as an
absence of events.
