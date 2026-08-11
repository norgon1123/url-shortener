# Architecture decision records

Decisions that were expensive to make, would be expensive to reverse, or look
arbitrary until you know what they are load-bearing for. Each one says what it
cost, and what evidence from the recorded runs bears on it.

| | Decision | Why it is here |
|---|---|---|
| [001](001-the-llm-never-approves.md) | The LLM never approves — gates are mechanical, self-report, or human | The rule the whole governance story rests on, enforced as a load-time invariant rather than a convention |
| [002](002-the-journal-is-the-only-source-of-truth.md) | The journal is the record; the database is a view of it | Why the metrics can be trusted, and why a mistake in them is permanent |
| [003](003-segregation-of-duties.md) | The implementer cannot write the tests | Path-level segregation plus a shared frozen specification, after "blind authoring" failed |
| [004](004-short-codes-are-random-not-sequential.md) | Short codes are random base62, not a sequence | The service decision where the efficient answer and the correct answer point in opposite directions |
| [005](005-where-inference-runs.md) | Where inference runs, and who pays | The weakest claim in the repository, flagged as such |
| [006](006-bounded-repair-with-human-routing.md) | Failures route to a branch; humans decide what a machine cannot | How the graph stopped being a one-way street, and what stops the loop |

Decisions that were *not* worth an ADR — 302 over 301, cache tiering, the expiry
reaper, rate-limit keying — are in
[`../REQUIREMENTS_BRIEF.md`](../REQUIREMENTS_BRIEF.md) under "Positions", with
the reasoning that produced them.
