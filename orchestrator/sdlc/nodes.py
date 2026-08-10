"""The node backend interface, and how a node's prompt is assembled.

One interface, two implementations: `mock.MockBackend` (scripted, no network)
and the live Agent SDK backend. The engine never knows which it has. That is
what makes the graph, gates, checkpoints, approvals, and metrics testable in
under a second instead of a twenty-minute multi-dollar run, and it is what lets
an evaluator with no API key exercise the machinery end to end.

Prompt assembly lives here rather than inside either backend because the
*content* of a retry is a governance concern, not a transport detail. Attempt
N+1 receives the gate failures that killed attempt N, and a replan receives the
reviewer's rejection note verbatim. Retrying with the identical prompt would be
superstition; it is the appended feedback that makes a bounded retry more than
a second roll of the dice.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from . import schemas
from .model import Autonomy, GateResult, NodeResult, NodeSpec


@dataclass
class NodeInvocation:
    """Everything a backend needs for one attempt at one node."""

    node: NodeSpec
    run_id: str
    workspace: Path
    attempt: int = 0
    # Monotonic across the whole run, unlike `attempt`, which resets when a
    # replan re-enters the node. Scripted backends index on it so a replay is
    # identical regardless of how many processes the run spanned.
    sequence: int = 0
    # Outputs of upstream nodes, keyed by node id. Passed explicitly rather
    # than left for the agent to go and find, so cross-stage context is a
    # recorded input rather than an emergent property of what it happened to read.
    context: dict[str, Any] = field(default_factory=dict)
    gate_failures: tuple[GateResult, ...] = ()
    rejection_note: str = ""
    autonomy: Autonomy = Autonomy.APPLY
    artifacts_dirname: str = "artifacts"

    @property
    def artifacts_dir(self) -> Path:
        return self.workspace / self.artifacts_dirname

    @property
    def is_retry(self) -> bool:
        return self.attempt > 0


class NodeBackend(Protocol):
    def run(self, invocation: NodeInvocation) -> NodeResult: ...


class PromptError(FileNotFoundError):
    pass


def load_prompt(node: NodeSpec, root: Path) -> str:
    if not node.prompt_path:
        raise PromptError(f"node {node.id} declares no prompt")
    path = root / node.prompt_path
    if not path.is_file():
        raise PromptError(f"node {node.id}: prompt not found at {path}")
    return path.read_text(encoding="utf-8").strip()


def render_prompt(invocation: NodeInvocation, root: Path) -> str:
    """Assemble the full prompt: base instructions plus run-specific context.

    The order is deliberate. Base task first, then the inputs, then -- last and
    therefore most salient -- what went wrong last time and what the human said.
    """
    node = invocation.node
    sections = [load_prompt(node, root)]

    if invocation.context:
        sections.append(_context_section(invocation.context))

    sections.append(_boundaries_section(node))

    if node.output_schema:
        sections.append(_output_section(node.output_schema))

    if invocation.gate_failures:
        sections.append(_failure_section(invocation))

    if invocation.rejection_note:
        sections.append(
            "## Human review feedback\n\n"
            "A reviewer rejected the previous result with this note. Address it "
            "directly; it is not advisory.\n\n"
            f"> {invocation.rejection_note}"
        )

    return "\n\n---\n\n".join(sections)


def finalize_output(output: dict[str, Any], workspace: Path) -> dict[str, Any]:
    """Stamp orchestrator-computed fields onto a node's structured output.

    There is exactly one today: a node that declares `contract_files` gets a
    content hash of them recorded alongside. The parallel branches re-derive it
    at their entry gate, so a contract that drifts between the freeze and the
    fan-out is caught immediately rather than surfacing later as an unexplained
    pile of merge conflicts.

    The orchestrator computes it, never the node. A hash self-reported by the
    party whose work it attests to is not evidence of anything.
    """
    files = output.get("contract_files")
    if not files:
        return output
    from .audit import hash_inputs

    digest = hash_inputs([workspace / f for f in files], root=workspace)
    return {**output, "contract_hash": digest}


def _context_section(context: dict[str, Any]) -> str:
    lines = ["## Upstream context", ""]
    for node_id, payload in sorted(context.items()):
        lines.append(f"### From `{node_id}`\n")
        lines.append("```json")
        lines.append(json.dumps(payload, indent=2, sort_keys=True, default=str))
        lines.append("```\n")
    return "\n".join(lines).strip()


def _boundaries_section(node: NodeSpec) -> str:
    """State the path policy in the prompt as well as enforcing it.

    Enforcement is what actually stops a violation; telling the agent up front
    is what stops it wasting an attempt discovering the wall. Both, not either.
    """
    lines = ["## Boundaries", ""]
    if node.write_paths:
        lines.append("You may create or modify files only under:")
        lines += [f"- `{p}`" for p in node.write_paths]
    if node.deny_paths:
        lines.append("\nYou must not touch these paths under any circumstances:")
        lines += [f"- `{p}`" for p in node.deny_paths]
        lines.append(
            "\nThis separation is a control, not a convenience. Writes outside "
            "the allowlist are rejected by the tool layer and, if smuggled past "
            "it, caught by a diff check afterwards."
        )
    if node.autonomy is Autonomy.PROPOSE:
        lines.append(
            "\nYou are running in **propose** mode: describe the change and "
            "produce the diff, but do not apply it. A human applies it."
        )
    return "\n".join(lines)


def _output_section(schema_name: str) -> str:
    schema = schemas.get(schema_name)
    return (
        f"## Required output\n\n"
        f"Emit a single JSON object conforming to this schema. It is validated "
        f"at the exit gate; a non-conforming object fails the node.\n\n"
        f"```json\n{json.dumps(schema, indent=2)}\n```"
    )


def _failure_section(invocation: NodeInvocation) -> str:
    """The retry feedback loop, in text.

    Without this, a bounded retry is just rerunning a coin flip. With it, the
    attempt has the specific, machine-checked reason the last one was rejected.
    """
    lines = [
        f"## Previous attempt failed (attempt {invocation.attempt} of this node)",
        "",
        "These gates rejected the previous result. Fix the causes; do not work "
        "around the checks.",
        "",
    ]
    for failure in invocation.gate_failures:
        lines.append(f"- **{failure.check}** ({failure.gate_class.value}): {failure.detail}")
    return "\n".join(lines)
