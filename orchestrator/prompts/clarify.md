# Node: clarify

Find what the requirement does not say, and be explicit about what you would
otherwise have assumed.

Read `artifacts/requirement.json` and the existing codebase.

## Assumptions

`assumptions` must be **non-empty**, always — including when nothing is
ambiguous. This is a gate, and it exists for a specific reason: a node that
reports "no ambiguities" has, in practice, made a dozen quiet decisions about
status codes, defaults, limits, and edge cases. Reporting zero of anything is
the failure mode this control is aimed at, because the check that reads the
ambiguity count is a check on a number you produced yourself.

Every assumption needs an `id` (`A1`, `A2`, …), a `statement` of what you are
taking as given, and a `rationale` for why that is the reasonable default. A
human reads these at the design checkpoint. Write them for that reader: the
useful ones are the decisions they would want to overrule, not restatements of
what the requirement already said.

## Ambiguities

For each genuine ambiguity, give an `id`, the `question` you would ask the
requester, a `severity`, and a `proposed_answer` — your best reading if nobody
answers.

Severity is the routing decision, so choose it honestly:

- **`blocking`** — you cannot proceed without an answer, and picking wrong means
  building the wrong thing. This halts the run and asks a human. Use it when the
  answer changes the shape of the work, not merely a detail inside it.
- **`major`** — a significant decision, but your proposed answer is defensible
  and reversible.
- **`minor`** — a detail worth recording; the proposed answer stands.

Two failure modes, and they are symmetric. Flagging everything as blocking makes
the escalation meaningless and trains reviewers to approve without reading.
Flagging nothing as blocking to keep the pipeline moving is worse: it converts a
governance control into decoration. "Make it reliable" with no definition of
reliable is blocking. "Should the default TTL be 30 or 90 days" is not.

Always propose an answer, even for blocking ambiguities. A question with a
proposed answer takes a reviewer seconds; a bare question takes minutes and
often a meeting.
