# Node: impact-analysis

Work out what this change touches that already exists.

Read `artifacts/requirement.json`, `artifacts/clarification.json`, and then the
repository itself. The artifacts tell you what is wanted; only the code tells
you what is in the way.

## Start by deciding which world you are in

Set `scenario` honestly, because everything downstream reads differently
depending on it:

- **`greenfield`** — the behaviour does not exist yet and nothing depends on it.
  The correct impact set is small or empty. Say so plainly and move on. An
  invented impact wastes a reviewer's attention and trains them to skim.
- **`brownfield`** — something already runs, and someone is relying on it. The
  interesting work is here, and the rest of this prompt is aimed at it.

A repository with a skeleton but no behaviour is still greenfield. What matters
is whether working code exists that a caller could already be depending on.

## What counts as impacted

For each entry give the `path`, what `kind` of thing it is, whether it is
`added` / `modified` / `removed` / `behaviour_only`, and a `risk`.

`behaviour_only` is the one people miss and the one that causes incidents: the
file does not change, but what it does changes — because a shared method it
calls now behaves differently, a default moved, or a new row type reaches an
existing query. A change with no `behaviour_only` entries on a mature codebase
is usually an analysis that stopped at the diff.

Look for impact along all of these, not just the first:

- **Modules and services** — direct callers, and their callers.
- **APIs** — request and response shapes, status codes, headers, and
  pagination. Anything a client parses is a surface, whether or not it is
  documented as one.
- **Data flows** — what writes a row and what reads it afterwards, including
  the things that read it out of band: reports, exports, another service's job.
- **Schema and config** — migrations, indexes, defaults, environment variables.
- **Operational surface** — metrics and log lines someone alerts on. Renaming a
  log field is a breaking change to whoever wrote the alert.

## Breaking changes

`breaking_changes` is for anything a caller or operator could be relying on that
will not behave the same afterwards, each with a `mitigation` — a compatibility
shim, a two-phase migration, a version bump, a deprecation window. "Coordinate
with callers" is not a mitigation; naming which callers and what they need to do
is.

The decisive question, and worth answering explicitly: **can the old version and
the new version run at the same time?** During any rolling deploy they will.

## Blast radius and regression surface

`blast_radius` is one paragraph of plain prose answering: if this change is
wrong in the worst plausible way, what breaks, for whom, and how would anyone
find out? Write it for someone deciding how much review this deserves.

`regression_surface` is the existing behaviour most likely to break, stated
specifically enough that the test author downstream can aim at it. "The redirect
path" is aimable. "Everything" is not.

## Evidence, not recall

Every claim here should come from something you actually read — a file, a
grep, a migration, a call site. You have `Bash`, `Grep`, and `Glob`: use them.
An impact analysis assembled from what a service like this usually looks like is
worse than none, because it reads exactly like one that was checked.
