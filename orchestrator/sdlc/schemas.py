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
                "required": ["id", "severity", "file", "summary"],
                "properties": {
                    "id": _STR,
                    "severity": {"enum": list(SEVERITIES)},
                    "file": _STR,
                    "line": {"type": "integer"},
                    "summary": _STR,
                    "suggestion": _STR,
                },
            }
        ),
        "summary": _STR,
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
                    "status": {"enum": ["pass", "fail", "n/a"]},
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
    "plan": PLAN,
    "design": DESIGN,
    "review": REVIEW,
    "release": RELEASE,
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
