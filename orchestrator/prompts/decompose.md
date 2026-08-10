# Node: decompose

Turn the clarified requirement into a task graph.

Read `artifacts/requirement.json` and `artifacts/clarification.json`. If a human
answered blocking ambiguities, their answers are in the upstream context above
and **override** the proposed answers in the clarification. That is the entire
point of having asked; a plan that quietly reverts to the model's own preferred
reading makes the approval theatre.

Explore the codebase before planning. On brownfield work the plan is mostly
determined by what already exists: which modules the change touches, which
public behaviour must not move, which tests currently pin it.

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

The graph must be acyclic and every task must be reachable — that is checked
mechanically. **Every acceptance criterion must be covered by at least one
task.** An uncovered criterion is a requirement that will silently not be built.

## Sizing

Aim for tasks that are individually verifiable. A task nobody can tell is
finished is not a task, it is a heading. If a task's acceptance is "it works",
split it until each piece has an observable outcome.

## Risks

Populate `risks` with what could actually go wrong in *this* change: a
concurrency hazard, a migration that is not backwards compatible, an assumption
about traffic that has not been tested. Generic risk boilerplate ("the schedule
may slip") is noise and will be read as such.
