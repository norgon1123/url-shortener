# Node: intake

Turn a raw requirement into a structured engineering problem.

Read `input/requirement.txt`. That is the entire input: a sentence or a
paragraph written by whoever wants the work done, with all the imprecision that
implies. Read the existing codebase before writing anything — a requirement for
a system that already exists means something different from the same words
aimed at an empty repository.

Your job is **normalisation, not interpretation**. Restate what was asked in a
form later nodes can act on. You are not deciding what the system should do.

Produce:

- **`goal`** — one sentence, in the requester's terms, not in implementation
  terms. "Users can create short links that redirect to a target URL", not
  "add a `LinkController` with a POST mapping".
- **`in_scope` / `out_of_scope`** — the second one matters more than it looks.
  Anything a reasonable engineer might assume is included but the requirement
  does not ask for belongs in `out_of_scope`, where a human can see the omission
  and object. Silent exclusions are how a delivered feature ends up wrong.
- **`acceptance_criteria`** — each with a stable `id` (`AC1`, `AC2`, …) and a
  statement that is **observable from outside the system**. "Requesting an
  unknown code returns 404" is observable. "Handles errors correctly" is not.
  These ids are referenced by the plan and by the tests; they are the thread
  that connects a requirement to the thing that proves it was met, so once
  emitted they must not be renumbered.
- **`constraints`** — anything that limits the solution space: existing stack,
  compatibility requirements, deadlines stated in the requirement.
- **`non_functional`** — performance, security, availability, and operability
  expectations, whether stated or clearly implied by the domain.

Two rules:

**Do not invent requirements.** If the requirement says nothing about
authentication, `out_of_scope` gets "authentication" — it does not get an
invented auth requirement, and it does not get silently ignored.

**Do not resolve ambiguity.** That is the next node's job, and it escalates to a
human. If a phrase could mean two things, capture it faithfully in the terms it
was given, ambiguity intact.
