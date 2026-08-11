# ADR-002: The journal is the record; the database is a view of it

**Status:** accepted
**Cited by:** `audit.py`, `state.py`, `metrics.py`

## Context

A run needs two things that pull in opposite directions.

It needs **current state**: which node is running, what its attempt counter is,
whether an approval is on file, whether a stop was requested. This is mutable by
definition, it is read and written by two branches concurrently, and one process
(`stop`) must be able to signal another (`run`).

It needs an **audit record**: what happened, in order, attributable, and not
quietly editable afterwards. Every claim this project makes — the metrics, the
four-eyes evidence, the cost of rework — is only worth as much as that record.

Serving both from one store means the audit trail is written by the same code
path that updates a counter, and an audit record that normal operation updates
in place is not an audit record.

## Decision

Two stores, with different jobs and different rules.

**`audit.Journal`** — append-only JSONL, hash-chained. Each entry carries the
previous entry's hash, so an alteration anywhere invalidates every entry after
it and `sdlc.cli verify` says so. Never updated, never deleted.

**`state.RunStore`** — SQLite. Node statuses, attempt counters, repair counters,
the live approval, the stop flag. Rows are updated in place, which is exactly
what an audit record must never do. SQLite rather than a JSON file for two
concrete reasons: the parallel branch means two node runners write
concurrently, and `stop` is a *different process* setting a flag the running
engine reads at its next node boundary.

**Metrics are derived from the journal only.** Not from the database, and not
accumulated in memory during a run. `metrics.py` reads the journal and computes.

**The database is reconstructible from the journal. The reverse is not true**,
and that asymmetry is the point.

## Consequences

**Metrics cannot flatter the run.** They are computed from what was recorded at
the time, by code that runs later and can be re-run by anyone, including on a
fixture with no API key. When the cost accounting was wrong, the fix had to be
made *at the journal* — at what gets recorded — rather than at the report.

**Mistakes are permanent, and say so.** `greenfield-1`'s journal under-reports
cost because it predates the accounting fix. It cannot be corrected
retroactively; `docs/OPERATIONS.md` tells the reader not to quote its numbers.
That is the property working, even though it looks like a defect.

**A number nobody records is a number nobody has.** Three cost defects were
invisible until the journal was asked the right question: per-node cost recorded
only on success, re-gated attempts double-counted, and gate-failed attempts
recorded as costless. All three made rework look cheaper than it was — which is
the direction a system flatters itself in when nobody checks.

**An operator can still weaken the record from outside.** The journal is
tamper-*evident*, not tamper-proof, and nothing stops someone editing the code
that writes it. `brownfield-1`'s `release-readiness` found exactly this: `gates.py`
was modified inside the run's own history with no review lens looking at that
diff, and every mechanical outcome in the run is produced by that code.

## Evidence

- `sdlc.cli verify greenfield-3` → 345 entries, chain intact.
  `sdlc.cli verify brownfield-1` → 250 entries, chain intact.
- Both runs replay from `orchestrator/fixtures/runs/` with no key and no spend,
  because the journal is the whole record.
- The rework figures — 56% and 53% — exist only because failed attempts are
  journalled with their cost. No system reports that number about itself unless
  it was designed to.
