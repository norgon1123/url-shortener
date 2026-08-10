# Node: feasibility

Go and look at the things nobody has checked yet. Come back with questions and
evidence, not code.

Read `artifacts/clarification.json` — especially the ambiguities and the
assumptions — and `artifacts/impact.json`. Then investigate the parts where
being wrong would be expensive.

**A question a human has already answered is settled.** The approval recorded
against `clarify` is in your upstream context and it is authoritative: treat its
answers as fact and build on them. Do not re-open them, do not look for a
previous run's decision on the same point, and do not offer a better option than
the one the approver chose. Re-asking wastes the spike, and worse, it invites a
different answer than the one a person actually gave.

## This is a spike, and the boundary is enforced

You may write nothing outside `artifacts/**`; `service/**`, `docs/**`,
`orchestrator/**` and `runs/**` are denied to you at the tool layer and again in
the diff check afterwards. That is deliberate. A spike that leaves code behind has smuggled an implementation past
the frozen contract and the two blind branches that depend on it, and the code
would carry the authority of having survived a pipeline that never reviewed it.

Your subject is the service and the change being made to it. The pipeline that
governs this run — its gates, its runs directory, its own documentation — is not
yours to investigate, however interesting. On brownfield work that budget belongs
to the codebase under change.

Explore freely within that: read anything there, grep anything, run read-only
commands. Checking whether a library is actually on the classpath beats
reasoning about whether it should be.

## What deserves the time

Bounded exploration means picking. Rank candidates by *how much a wrong guess
costs*, not by how interesting they are:

- ambiguities the clarify node marked `blocking` or `major`;
- assumptions in the clarification that the design will be built directly on
  top of, where being wrong means rework rather than an edit;
- anything in the impact analysis marked `high` risk;
- claims about the existing system that everyone believes and nobody has
  checked — the shape of a table, whether a constraint exists, whether a
  dependency is really there.

Leave alone anything that is merely undecided but cheap to change later. A
question you can answer in the review is not worth a spike.

## Unknowns

Each unknown needs an `id` (`U1`, `U2`, …), the `question`, `why_it_matters`,
and `how_to_settle` — the specific thing that would answer it: a command to
run, a file to read, a person to ask, a measurement to take. Include
`current_best_answer` where you have one; a question with a provisional answer
lets the pipeline continue under a stated assumption instead of stopping.

Write questions a human can answer in a sentence. "How should this behave under
load?" cannot be answered in a sentence. "Is 500 requests/second the target, or
is that just the current peak?" can.

## Evidence

`evidence` is what you actually established, each `claim` paired with the
`source` that supports it — a file and line, a command and what it printed, a
section of the spec. This is the part with lasting value: it is the difference
between the design node reasoning from checked facts and reasoning from
plausible ones.

Record what you checked and found *fine*, too. "The `links` table already has a
unique index on `code` (`V1__init.sql:14`)" closes a question permanently.

## Options and verdict

Where a real fork exists, give the `options` with honest `cost` and `risk`, and
mark at most one `recommended`. Presenting one option is not presenting a
choice.

`verdict` is your read of the whole picture:

- **`feasible`** — no material unknowns left; proceed.
- **`feasible_with_risk`** — proceed, with named risks a human should see.
- **`blocked`** — something must be answered first. Use this when proceeding
  would mean building on a guess that is expensive to unwind, and say exactly
  which unknown blocks it.

`blocked` does not stop the run by itself — a human sees it at the design
approval gate. Which means the verdict is worth setting honestly rather than
strategically: it is read by a person, not by a machine that will punish it.
