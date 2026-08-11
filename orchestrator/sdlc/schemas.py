"""JSON Schemas for node outputs.

These do double duty, which is the point of keeping them in one place:

  * at runtime they are handed to the Agent SDK as the node's `output_format`,
    so the model is constrained to emit conforming JSON rather than prose that
    a regex has to claw structured data out of;
  * at the exit gate they are re-validated, because "the SDK constrained it" is
    an assurance from the same system under test. The gate re-checks
    independently.

`additionalProperties: false` is deliberate throughout. A node that invents an
extra field is a node whose prompt and contract have drifted apart, and it is
much cheaper to find that at the gate than three nodes downstream.
"""

from __future__ import annotations

from typing import Any

SEVERITIES = ("blocker", "major", "minor", "nit")


def _array_of(item: dict[str, Any], **kw: Any) -> dict[str, Any]:
    return {"type": "array", "items": item, **kw}


_STR = {"type": "string"}


REQUIREMENT: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["goal", "in_scope", "out_of_scope", "acceptance_criteria"],
    "properties": {
        "goal": {"type": "string", "minLength": 1},
        "in_scope": _array_of(_STR, minItems=1),
        "out_of_scope": _array_of(_STR),
        "acceptance_criteria": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["id", "statement"],
                "properties": {"id": _STR, "statement": _STR},
            },
            minItems=1,
        ),
        "constraints": _array_of(_STR),
        "non_functional": _array_of(_STR),
    },
}


CLARIFICATION: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    # `assumptions` is required and must be non-empty even on a perfectly clear
    # requirement (ADR-001). A node that reports "nothing was ambiguous" while
    # having silently chosen a database, a code length, and a redirect status
    # is exactly the failure this forces into the open.
    "required": ["assumptions", "ambiguities"],
    "properties": {
        "assumptions": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["id", "statement", "rationale"],
                "properties": {"id": _STR, "statement": _STR, "rationale": _STR},
            },
            minItems=1,
        ),
        "ambiguities": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["id", "question", "severity", "proposed_answer"],
                "properties": {
                    "id": _STR,
                    "question": _STR,
                    # `blocking` halts the run for a human; `advisory` is
                    # recorded and carried forward as an assumption.
                    "severity": {"enum": ["blocking", "advisory"]},
                    "proposed_answer": _STR,
                },
            }
        ),
    },
}


PLAN: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["tasks"],
    "properties": {
        "tasks": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["id", "title", "depends_on", "deliverables"],
                "properties": {
                    "id": _STR,
                    "title": _STR,
                    "depends_on": _array_of(_STR),
                    "deliverables": _array_of(_STR),
                    "acceptance_criteria_ids": _array_of(_STR),
                },
            },
            minItems=1,
        ),
        "risks": _array_of(_STR),
    },
}


DESIGN: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["openapi_path", "contract_files", "endpoints", "rationale"],
    "properties": {
        "openapi_path": _STR,
        # Everything the parallel branches are permitted to rely on. The
        # contract_frozen entry gate hashes exactly this list, so a branch that
        # edits a contract file is caught before it can diverge the two sides.
        "contract_files": _array_of(_STR, minItems=1),
        "endpoints": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["method", "path", "success_status"],
                "properties": {
                    "method": {
                        "enum": ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"]
                    },
                    "path": _STR,
                    "success_status": {"type": "integer"},
                    "notes": _STR,
                },
            },
            minItems=1,
        ),
        "rationale": _STR,
        "assumption_ids": _array_of(_STR),
        # Stamped by the orchestrator after the node returns, never emitted by
        # the model. A hash the graded party computes about its own work is not
        # evidence, and asking for one here would also mean asking a model to
        # reproduce a hashing scheme by hand.
        "contract_hash": _STR,
        # path -> hash, stamped by the orchestrator beside the aggregate. Lets a
        # consumer verify the part of the freeze it does not own.
        "contract_file_hashes": {
            "type": "object",
            "additionalProperties": {"type": "string"},
        },
    },
}


IMPACT: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    # `impacted` is not required to be non-empty: on greenfield work the honest
    # answer is that nothing existing is touched, and a node forced to invent an
    # impact would be worse than one allowed to say so. `scenario` is required
    # precisely so that "nothing impacted" is a claim someone made rather than
    # an empty section nobody noticed.
    "required": ["scenario", "impacted", "blast_radius"],
    "properties": {
        "scenario": {"enum": ["greenfield", "brownfield"]},
        "impacted": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["path", "kind", "change", "risk"],
                "properties": {
                    "path": _STR,
                    "kind": {
                        "enum": ["module", "service", "api", "data_flow", "schema", "config"]
                    },
                    "change": {"enum": ["added", "modified", "removed", "behaviour_only"]},
                    "risk": {"enum": ["high", "medium", "low"]},
                    "notes": _STR,
                },
            }
        ),
        # The compatibility question a brownfield change lives or dies on, and
        # the one a greenfield-shaped pipeline never thinks to ask.
        "breaking_changes": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["surface", "detail", "mitigation"],
                "properties": {"surface": _STR, "detail": _STR, "mitigation": _STR},
            }
        ),
        "blast_radius": _STR,
        "regression_surface": _array_of(_STR),
    },
}


FEASIBILITY: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    # The output of a spike is questions and evidence, never code. `verdict` is
    # the only place it is allowed to be decisive, and even that routes to a
    # human via the design gate downstream.
    "required": ["verdict", "unknowns", "evidence"],
    "properties": {
        "verdict": {"enum": ["feasible", "feasible_with_risk", "blocked"]},
        "unknowns": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["id", "question", "why_it_matters", "how_to_settle"],
                "properties": {
                    "id": _STR,
                    "question": _STR,
                    "why_it_matters": _STR,
                    "how_to_settle": _STR,
                    "current_best_answer": _STR,
                },
            }
        ),
        "evidence": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["claim", "source"],
                "properties": {
                    "claim": _STR,
                    # A file, a command and its output, a spec section. A claim
                    # with no source is an opinion, and this node exists to
                    # replace opinions with checked facts.
                    "source": _STR,
                },
            },
            minItems=1,
        ),
        "options": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["summary", "cost", "risk"],
                "properties": {
                    "summary": _STR,
                    "cost": {"enum": ["low", "medium", "high"]},
                    "risk": {"enum": ["low", "medium", "high"]},
                    "recommended": {"type": "boolean"},
                },
            }
        ),
    },
}


# --------------------------------------------------------------------------
# Review lenses
# --------------------------------------------------------------------------
#
# Five independent reviewers, one shape. Each gets its own schema instance so
# the `lens` field is a constant the gate can rely on and a misfiled artifact
# is a schema error rather than a silent mix-up.
#
# Finding ids are namespaced per lens by pattern. That is not cosmetic: the
# synthesis node downstream is checked mechanically for having preserved every
# lens finding, and a check keyed on ids cannot work if two lenses can both
# emit "F1".

LENSES: dict[str, str] = {
    "review-security": "SEC",
    "review-performance": "PERF",
    "review-api-contract": "API",
    "review-test-adequacy": "TEST",
    "review-cleanliness": "CLEAN",
}


def _lens_schema(lens: str, prefix: str) -> dict[str, Any]:
    return {
        "type": "object",
        "additionalProperties": False,
        "required": ["lens", "findings", "summary", "not_examined"],
        "properties": {
            "lens": {"const": lens},
            "findings": _array_of(
                {
                    "type": "object",
                    "additionalProperties": False,
                    "required": ["id", "severity", "confidence", "file", "summary"],
                    "properties": {
                        "id": {"type": "string", "pattern": rf"^{prefix}-[0-9]+$"},
                        "severity": {"enum": list(SEVERITIES)},
                        # Severity and confidence are separate axes on purpose.
                        # Collapsing them is how a reviewer ends up suppressing
                        # a serious finding it is merely unsure about.
                        "confidence": {"enum": ["high", "medium", "low"]},
                        "file": _STR,
                        "line": {"type": "integer"},
                        "summary": _STR,
                        "suggestion": _STR,
                    },
                }
            ),
            "summary": _STR,
            # What this lens did *not* look at. Recall is impossible to assess
            # from a list of hits alone, and the human at the release gate is
            # entitled to know where nobody looked.
            "not_examined": _array_of(_STR),
        },
    }


TEST_CONTRACT: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    # The executable half of the frozen contract. `design` fixes the types and
    # the HTTP surface; this fixes what will be asserted about them and how the
    # harness reaches them, so the two branches agree on the shape of the proof
    # before either starts. Both are approved together by a human.
    "required": ["contract_files", "behaviours", "harness", "rationale"],
    "properties": {
        # Hashed at each branch's entry gate alongside the design contract, so
        # a skeleton that drifts between the freeze and the fan-out is caught
        # before the branches can diverge on it.
        "contract_files": _array_of(_STR, minItems=1),
        "behaviours": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["id", "test_class", "method", "statement", "criteria_ids"],
                "properties": {
                    "id": _STR,
                    "test_class": _STR,
                    # Named for the behaviour, not the method under test:
                    # `expiredLinkReturns410`, never `testRedirect`.
                    "method": _STR,
                    "statement": _STR,
                    # Which acceptance criteria this behaviour demonstrates.
                    # The thread from requirement to proof, kept unbroken.
                    "criteria_ids": _array_of(_STR),
                },
            },
            minItems=1,
        ),
        # How a test reaches the service: base class, fixtures, and the
        # mechanisms that are easy to get wrong alone. A run has already been
        # lost to a test inventing its own way to restart the application.
        "harness": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["concern", "mechanism"],
                "properties": {"concern": _STR, "mechanism": _STR},
            }
        ),
        "rationale": _STR,
        "contract_hash": _STR,
        # path -> hash, stamped by the orchestrator beside the aggregate. Lets a
        # consumer verify the part of the freeze it does not own.
        "contract_file_hashes": {
            "type": "object",
            "additionalProperties": {"type": "string"},
        },
    },
}


TRIAGE_CLASSIFICATIONS = ("implementation", "test", "contract")


TRIAGE: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    # Advisory, like `review`: it decides where to spend the next attempt, never
    # whether the run passes. `verify` still has to go green mechanically
    # afterwards, so a wrong verdict costs one re-run rather than a bad merge.
    "required": ["failures", "verdict", "summary"],
    "properties": {
        "failures": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["test", "classification", "confidence", "evidence"],
                "properties": {
                    "test": _STR,
                    "classification": {"enum": list(TRIAGE_CLASSIFICATIONS)},
                    "confidence": {"enum": ["high", "medium", "low"]},
                    # Why. A verdict without one is a guess wearing a label, and
                    # this is the field a human reads when overruling it.
                    "evidence": _STR,
                },
            },
            minItems=1,
        ),
        # The routing decision. `contract` and `mixed` both mean a human looks:
        # a contract both sides read differently is not something to repair
        # automatically, and a mixture cannot be sent to one branch.
        "verdict": {"enum": [*TRIAGE_CLASSIFICATIONS, "mixed"]},
        "summary": _STR,
    },
}


REVIEW: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["findings", "summary"],
    "properties": {
        "findings": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["id", "lens", "severity", "file", "summary"],
                "properties": {
                    "id": _STR,
                    "lens": {"enum": sorted(LENSES)},
                    "severity": {"enum": list(SEVERITIES)},
                    "confidence": {"enum": ["high", "medium", "low"]},
                    "file": _STR,
                    "line": {"type": "integer"},
                    "summary": _STR,
                    "suggestion": _STR,
                    # Two lenses reaching the same defect from different angles
                    # is a signal, not noise. Merging is allowed; dropping is
                    # not, and `lens_findings_preserved` reads this field to
                    # tell the two apart.
                    "merged_ids": _array_of(_STR),
                },
            }
        ),
        "summary": _STR,
        "top_risks": _array_of(_STR),
    },
}


RELEASE: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["ready", "checklist", "residual_risks"],
    "properties": {
        "ready": {"type": "boolean"},
        "checklist": _array_of(
            {
                "type": "object",
                "additionalProperties": False,
                "required": ["item", "status"],
                "properties": {
                    "item": _STR,
                    # `unknown` exists so that an item nobody could substantiate
                    # has somewhere to go other than `pass`. Without it the
                    # schema quietly forces a claim, and the one place this
                    # document has to be trustworthy is the place a reviewer
                    # stops reading it.
                    "status": {"enum": ["pass", "fail", "n/a", "unknown"]},
                    "evidence": _STR,
                },
            },
            minItems=1,
        ),
        "residual_risks": _array_of(_STR),
    },
}


REGISTRY: dict[str, dict[str, Any]] = {
    "requirement": REQUIREMENT,
    "clarification": CLARIFICATION,
    "impact": IMPACT,
    "feasibility": FEASIBILITY,
    "plan": PLAN,
    "design": DESIGN,
    "test-contract": TEST_CONTRACT,
    "triage": TRIAGE,
    "review": REVIEW,
    "release": RELEASE,
    **{lens: _lens_schema(lens, prefix) for lens, prefix in LENSES.items()},
}


class UnknownSchema(KeyError):
    pass


def get(name: str) -> dict[str, Any]:
    """Look up a schema by the name a node declares in `output_schema`."""
    key = name.removesuffix(".json")
    if key not in REGISTRY:
        raise UnknownSchema(
            f"unknown schema '{name}'; known: {', '.join(sorted(REGISTRY))}"
        )
    return REGISTRY[key]


def validate(name: str, instance: Any) -> list[str]:
    """Return a list of human-readable validation errors; empty means valid.

    Errors are returned rather than raised because a gate wants to report every
    problem in one pass -- an agent retrying with "field X is missing" only to
    be told "field Y is also missing" wastes a whole attempt per error.
    """
    import jsonschema

    validator = jsonschema.Draft202012Validator(get(name))
    errors = []
    for err in sorted(validator.iter_errors(instance), key=lambda e: list(e.path)):
        location = "/".join(str(p) for p in err.path) or "<root>"
        errors.append(f"{location}: {err.message}")
    return errors
