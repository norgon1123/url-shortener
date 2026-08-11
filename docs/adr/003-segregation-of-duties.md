# ADR-003: The implementer cannot write the tests

**Status:** accepted, enforced at the tool layer and re-checked afterwards
**Cited by:** `sdlc.yaml`, `test_policy.py`, `test_graph.py`, `test_agent_backend.py`

## Context

An agent asked to make a test suite pass, which also has write access to the
test suite, has two ways to succeed. One of them is much cheaper. This is not a
hypothetical about model misbehaviour — it is the same reason a developer does
not review their own pull request, and the same reason the person who writes the
cheque does not sign it.

The first attempt at this was "blind authoring": have the test author and the
implementer work without seeing each other's output at all. That produced tests
and code that disagreed about method names, harness shape, and what a fixture
was called, and the join spent its time reconciling accidents rather than
substance. Blindness was never the property worth having.

## Decision

**Path-level segregation, not information segregation.**

- `implement` may write `service/src/main/**`, and is denied
  `service/src/test/**`.
- `author-tests` may write `service/src/test/**`, and is denied
  `service/src/main/**`.

Both branches run in their own git worktree, concurrently, and a barrier merges
them.

**A shared executable specification, frozen before either starts.** The
`test-contract` node writes the test classes, the behaviour-named method
signatures, and the harness — structure with **no assertions**, every body
failing with `fail("not implemented")`. Both branches build against it, so they
agree on what will be proven and how a test reaches the service. Deciding what
counts as proof stays with `author-tests`.

Three gates hold the line:

- `no_assertions` — the skeleton may not arrive with assertions already in it,
  or the test author's job has been done by someone else and `implement` would
  be reading finished tests;
- `contract_frozen` — each branch re-derives the content hash of the files it is
  *not* allowed to write, at its entry gate, so a contract that drifted between
  the freeze and the fan-out is caught immediately rather than surfacing later
  as unexplained merge conflicts;
- `tests_not_weakened` — a repaired suite may not be a smaller one. Counting is
  crude and is not fooled by deleting, weakening, or `@Disabled`.

Enforcement is at the **tool layer** — a write outside the allowlist is refused
before it happens — and re-checked *afterwards* by `paths_confined` against the
actual diff. Both, not either: the first stops the violation, the second stops a
violation that got past the first.

## Consequences

**The join is boring, which is the point.** Both runs merged both branches with
zero conflicts.

**A repair to `author-tests` is the dangerous one**, because it is the single
case where an agent edits the thing that judges it. Hence the asymmetric repair
budget — `implement` gets two attempts, `author-tests` one — and hence
`tests_not_weakened` recording its baseline *itself*: a baseline the audited
party supplies is not a baseline.

**Gates written against an empty repository broke on the second run.**
`no_assertions` scanned the whole test tree and failed `test-contract` for 880
assertions it had not written — the suite inherited from the previous run. It
now asks git what the node *added*, diffed from the newest commit the node did
not make. `tests_not_weakened` kept the first baseline it ever wrote, so
`brownfield-1` inherited `greenfield`'s floor of 130 against a tree holding 148,
and eighteen tests could have been deleted with the gate reporting no weakening.

## Evidence

- `test_policy.py` asserts the segregation against the *real* pipeline file, not
  a fixture, so loosening `sdlc.yaml` fails the suite.
- `brownfield-1`: the SEC-1 repair went to both branches. `implement` added the
  eligibility rule; `author-tests` added `AbuseReportReporterAgeTest` pinning
  both directions. Neither could have done the other's half.
- `greenfield-3` seq 186: `contract_frozen` reported "0 of 53 contract file(s)
  verified … the rest are this node's to write" — the gate correctly checking
  only what the node was forbidden to touch, after an earlier version failed
  both branches for editing the files they were *supposed* to edit.
