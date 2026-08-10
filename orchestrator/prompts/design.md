# Node: design

Produce the **frozen contract**. This is the most consequential node in the
pipeline and the only one whose output a human approves before any code exists.

Read `artifacts/plan.json`, `artifacts/requirement.json`, and
`artifacts/clarification.json`, plus the existing service.

## Why "frozen"

Immediately after you finish, two nodes run **in parallel and in isolation**:
one implements the service, one writes the tests. The test author never sees the
implementation — that is a segregation-of-duties control, so the agent writing
the code cannot weaken the tests that gate it.

Blind parallel authoring only works if both branches are handed the same,
complete, unchanging contract. Everything they need to agree on must be fixed
here, because afterwards neither of them may change it:

1. **`artifacts/openapi.yaml`** — every endpoint, request and response schema,
   status code, and error shape. Tests are written against this document and
   nothing else, so an endpoint you leave vague becomes a test that cannot be
   written and an implementation nobody checks.
2. **Compilable Java skeletons** under `service/src/main/java/**` — interfaces,
   DTOs, records, enums, exception types. Types, signatures, and package
   locations only. Method bodies throw `UnsupportedOperationException` or return
   a default; implementing them is the next node's job.
3. **`service/pom.xml`** — the dependency set, final. Both branches build
   against it; neither may edit it. If the change needs a new dependency, add it
   **now**. `pom.xml` is a protected path, so touching it forces a human
   approval — that is deliberate, because a dependency is a supply-chain change.

## Status codes deserve a sentence each

Put the reasoning in `rationale`, not just the number. Whether a redirect is 301
or 302 is not a style question — 301 is cached by browsers and quietly destroys
the analytics the requirement asked for. Whether an expired resource returns 410
or 404 changes what a caller can distinguish. These are the decisions the human
reviewer is actually reading for.

## Output

- `openapi_path` — where you wrote the spec.
- `contract_files` — **every** file the parallel branches depend on: the spec,
  each skeleton, `service/pom.xml`. This list is what the branches verify is
  intact before they start, so an omission is a silent hole in the freeze.
- `endpoints` — method, path, success status, and notes. Checked against the
  implemented controllers later; a path here that no controller serves, or a
  route the implementation invents, fails the run.
- `rationale` — the design decisions and why. Written for the reviewer.
- `assumption_ids` — which clarification assumptions this design relies on. If
  one of them was wrong, this is the list that tells a human what to re-examine.

The skeletons must compile (`mvn -DskipTests compile`) before you finish. A
contract that does not compile is not frozen; it is a draft, and it will fail
both branches at once.
