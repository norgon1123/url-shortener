# Node: test-contract

Write the **executable half of the frozen contract**: the shape of the proof,
before anyone builds the thing being proven.

Read `artifacts/design.json`, `artifacts/openapi.yaml`, the skeletons `design`
left under `service/src/main/java/**`, and `artifacts/requirement.json` for the
acceptance criteria.

## What this is for

Immediately after you finish, two nodes run in parallel and in isolation: one
implements the contract, one fills in your test bodies with real assertions.
Neither sees the other's work. They can only agree if you hand them the same
specification — and the half `design` produced covers the HTTP surface and the
types, not what will be asserted about them or how a test reaches the service.

That gap has already cost a run. Tests written in isolation invented their own
way to restart the application, the harness did not support it, and the failure
surfaced only after everything had been built and merged.

So: you fix *what will be proven* and *how the harness reaches it*. Both
branches build against your output, and a human approves it alongside the API.

## Write structure. Do not write assertions.

This is the line that keeps two authors from becoming one, and it is checked
mechanically.

**Do write:**

- test classes under `service/src/test/java/**`, grouped by the behaviour they
  cover rather than by the class under test;
- one method per behaviour, named for the behaviour — `expiredLinkReturns410`,
  never `testRedirect`. The name *is* the specification;
- Javadoc on each method stating what must be true, and which acceptance
  criteria it demonstrates;
- the shared harness: base classes, fixtures, helpers for creating a link,
  following a redirect, advancing the clock, restarting the application.
  Anything two tests would otherwise each invent, and get differently;
- method bodies that fail by default — `fail("not implemented")` is exactly
  right. The suite must compile and must not pass.

**Do not write:** any assertion about the service's behaviour. No
`assertEquals`, no `assertThat`, no status-code checks. Deciding what counts as
proof is the next node's job, and a skeleton that arrives with assertions has
already done that job — at which point the implementer is reading finished
tests and the separation is gone.

A gate rejects the node if it finds assertions outside the harness.

## Coverage of the criteria

Every acceptance criterion in the requirement needs at least one behaviour, and
each behaviour records the criteria it demonstrates in `criteria_ids`. That is
the thread from requirement to proof, and this is the last node that can keep it
unbroken.

Cover, for each endpoint: the success path, every documented error status, the
boundaries, and anything the design's rationale calls out as a deliberate
decision. A choice worth explaining is worth pinning.

## The harness is where isolation actually fails

Be concrete about the mechanisms that are easy to get wrong alone, and record
each in `harness` with the `concern` and the `mechanism`:

- how a test creates a link it can then act on;
- how a test observes an expired link without waiting for real time to pass;
- how a test restarts or re-boots the application, if any behaviour depends on
  persistence surviving one;
- how a test asserts against a route that lives at the root of the namespace.

If a behaviour cannot be tested through the harness you are defining, say so in
`rationale` rather than leaving the next node to discover it.

## Output

- `contract_files` — every file you wrote. Hashed and verified at both branches'
  entry gates alongside the design contract, so drift is caught before the
  branches diverge on it.
- `behaviours` — id, test class, method name, what must be true, and the
  criteria ids it covers.
- `harness` — the mechanisms above, each with the concern it settles.
- `rationale` — what you deliberately did not cover, and why. A human reads
  this beside the API and approves both together.

The skeleton must compile (`mvn test-compile`). A skeleton that does not compile
is not a contract; it is a draft, and it fails both branches at once.
