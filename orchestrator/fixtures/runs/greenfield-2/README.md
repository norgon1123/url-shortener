# `greenfield-2` — the first full traversal of the 19-node graph

Replayable with no API key and no spend:

```bash
PYTHONPATH=orchestrator python -m sdlc.cli replay orchestrator/fixtures/runs/greenfield-2
PYTHONPATH=orchestrator python -m sdlc.cli --runs-dir orchestrator/fixtures/runs verify greenfield-2      # re-check the hash chain yourself
```

**Outcome: stopped at `verify`, deliberately.** 163 tests ran, 160 passed. The
run is preserved as it ended rather than nursed to green, because how it ended
is the most useful thing in it.

## What it demonstrates

| | |
|---|---|
| Nodes passed | 9 of 10 attempted, through the parallel branches and a clean join |
| Human decisions | 3, all consequential |
| Gate pass rate | 95% |
| Cost | $24.88 estimated (see `docs/OPERATIONS.md` — a subscription, not a bill) |
| Wall clock | ~10.8h, including hours paused waiting on a human |

- **A frozen contract, approved before code existed.** 4 routes, 25 contract
  files. `routes_match_openapi` later confirmed the implementation matched it
  *exactly*.
- **Blind parallel authoring.** `implement` and `author-tests` ran in separate
  worktrees; the test author never saw the implementation and is denied
  `src/main/**`, the implementer denied `src/test/**` (ADR-003). The join merged
  both branches with no conflict — boring by construction, which is the point.
- **A decision that moved upstream.** In `greenfield-1` a human rejected the
  contract over API versioning. Here the same ruling was recorded at `clarify`,
  and `design` cited it by journal sequence number and overrode its own
  assumption A1 — which had proposed exactly the shape that was rejected before.
- **Evidence reaching the contract.** `feasibility` stood up a throwaway Spring
  app and found that `/favicon.ico` reaches the root `{code}` handler. That
  became a reserved code in the contract, and `author-tests` independently wrote
  `RootPathSurfaceTest` against the same hazard.
- **High-impact escalation on a real diff.** `implement` wrote a Flyway
  migration and an `application.yml` change; `paths_confined` stopped the branch
  and required a human, whatever the node's configured autonomy.

## Why it stopped, and what that exposed

Three failures. Two were defects in the **tests**, not the implementation:
`LinksSurviveRestartTest` boots a second Spring context by hand, which does not
inherit the Testcontainers wiring and so dials `localhost:5432`.

The graph had no way to say so. `verify` fails, retries, and replans to
`decompose` — re-running design, implement and author-tests to fix what a human
diagnoses in thirty seconds — and it cannot take the direct route, because
`implement` is denied `src/test/**` by the very control that makes the green
build meaningful.

That is the honest finding of this run: **blind authoring makes a wrong test
cost exactly as much as a wrong implementation.** The fix is a triage node and a
bounded repair path; see `docs/TODO.md`.

## Caveats

- One failure, `actuatorPrometheusIsNotShadowedByTheRedirect`, is unadjudicated:
  either `/actuator/prometheus` is genuinely not exposed, or the test asserted an
  endpoint the contract never promised.
- The 30-minute `verify` timeout earlier in this run was a defect in the
  hand-written test scaffold, not in the generated code: a Testcontainers
  lifecycle bug that stopped the database after the first test class. Fixed;
  the suite went from timing out to 41 seconds.
