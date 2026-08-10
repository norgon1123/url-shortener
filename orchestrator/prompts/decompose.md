# Node: decompose

Turn the clarified requirement into a task graph.

Read `artifacts/requirement.json` and `artifacts/clarification.json`. If a human
answered blocking ambiguities, their answers are in the upstream context above
and **override** the proposed answers in the clarification. That is the entire
point of having asked; a plan that quietly reverts to the model's own preferred
reading makes the approval theatre.

Two more artifacts exist by the time you run, and on brownfield work they matter
more than anything else you will read:

- **`artifacts/impact.json`** — what already exists that this change touches,
  what breaks if it is wrong, and the regression surface. On brownfield work the
  plan is mostly determined by this: which modules the change touches, which
  public behaviour must not move, which tests currently pin it. A breaking
  change listed there needs a task that carries out its mitigation, not a note.
- **`artifacts/feasibility.json`** — what was checked and what is still unknown.
  Its `evidence` is established fact; prefer it to your own assumptions. An
  unknown marked `blocked` that a human has not answered is not something to
  plan around silently.

## The graph

Each task needs:

- **`id`** (`T1`, `T2`, …) and a `title` naming a deliverable, not an activity.
  "Persist links with a unique short code" over "work on the database".
- **`depends_on`** — real ordering constraints only. Two tasks that merely touch
  the same file are not dependent; two tasks where one needs the other's output
  are. Inventing dependencies serialises work that could have run in parallel.
- **`deliverables`** — the files or artifacts that will exist afterwards.
- **`acceptance_criteria_ids`** — which `AC` ids from the requirement this task
  advances.

Three things here are checked mechanically at the exit gate, so they are worth
knowing before you write rather than discovering on a retry:

- the graph is acyclic, task ids are unique, and no task depends on an id that
  does not exist;
- **every acceptance criterion is claimed by at least one task** via
  `acceptance_criteria_ids`. An uncovered criterion is a requirement that will
  silently not be built — nothing downstream measures the work against the
  requirement again, only against this plan;
- every id in `acceptance_criteria_ids` is a real criterion. A typo there
  uncovers the criterion it meant to claim.

## Sizing

Aim for tasks that are individually verifiable. A task nobody can tell is
finished is not a task, it is a heading. If a task's acceptance is "it works",
split it until each piece has an observable outcome.

## Risks

Populate `risks` with what could actually go wrong in *this* change: a
concurrency hazard, a migration that is not backwards compatible, an assumption
about traffic that has not been tested. Generic risk boilerplate ("the schedule
may slip") is noise and will be read as such.
