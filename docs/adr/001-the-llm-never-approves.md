# ADR-001: The LLM never approves

**Status:** accepted, and enforced as a load-time invariant
**Cited by:** `graph.py`, `gates.py`, `model.py`, `schemas.py`, `sdlc.yaml`, and
six tests

## Context

An agentic pipeline has to decide, at every stage boundary, whether the work is
good enough to continue. The obvious implementation is to ask the model: it has
just done the work, it has the most context, and it can produce a confident
paragraph about whether the work meets the bar.

That is the failure mode, not the feature. A model asked "is this good enough?"
about its own output is being asked to be both the audited party and the
auditor. It will answer yes at a rate that has nothing to do with whether the
answer is true, and the answer will be fluent, specific, and unfalsifiable. Any
governance story built on it is theatre — and this project's deliverable *is*
the governance story.

## Decision

Every gate declares a **class**, and the class decides what its verdict can do.

| Class | Who decides | What a failure does |
|---|---|---|
| `mechanical` | a program | fails the node |
| `self_report` | the model populates a field a program then checks | fails or escalates |
| `human` | a person | escalates and halts the run |

The rule the taxonomy exists to enforce: **the LLM never approves. It can only
satisfy a checkable predicate, or escalate.**

A `mechanical` gate runs a compiler, a test suite, a schema validator, a hash
comparison, a regex over a diff. It has no opinion.

A `self_report` gate is the interesting one, because some things genuinely
cannot be checked mechanically — "did you find any ambiguities?" has no
compiler. The model populates a structured field; a *program* then decides what
that field means. Reporting "no ambiguities" is not an approval; it is a claim,
and the schema requires it to be accompanied by the assumptions the node made
instead. `schemas.py` enforces the shape, and `graph.py` refuses to load a
pipeline where a self-report gate has no mechanical backstop.

A `human` gate escalates. Nothing the model can emit clears it.

## Consequences

**Load-time enforcement, not convention.** `graph.py` rejects a pipeline whose
gates violate the taxonomy — a self-report gate that could pass a node on the
model's word alone is a startup error, not a runtime surprise. This is why the
rule survived the parts of the project where it was inconvenient.

**Escalation needs an exit.** Two gates shipped as deadlocks — a node escalated,
a human approved, and the gate escalated again because it only knew how to look
at the artifact. The fix was to generalise clearing into `gates.evaluate()`: an
approval on file for the node clears any escalation it raised. The lesson is
that "the human decides" is only half a design; the other half is the decision
reaching the machinery.

**A human decision may need to carry more than yes.** Adjudicating a contract
question is deciding *which side changes*, and an approval that could only say
"approved" left the run with nowhere to send the work. `--answer route=<node>`
followed (see ADR-006).

## Evidence

- `greenfield-3`, journal seq 112-115: `triage` classified a failure as a
  contract question and refused to route it. A human ruled it a test defect. The
  repair honoured the ruling by normalising away the fields that legitimately
  differ rather than by weakening the assertion — the outcome the escalation
  existed to get.
- `brownfield-1`, journal seq 216: `review-synthesis` reported one blocker and
  could not clear it, because `blocker_findings_escalate` is a self-report gate
  whose escalation only a human closes. It had *already verified* the finding was
  superseded and still could not approve its own conclusion.
- `review-synthesis` is advisory by construction: it may cluster and rank
  findings but not drop them, and `lens_findings_preserved` checks that
  mechanically against the five lens artifacts. A join that can quietly drop a
  lens's blocker undoes the fan-out.
