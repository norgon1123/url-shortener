"""The live node backend: one Agent SDK session per node attempt.

This is the *only* module that talks to a model. Everything the assignment
scores -- the graph, the gates, the journal, the checkpoints, the policy
engine, the metrics -- sits above this seam and is exercised without it, which
is why the test suite runs in two and a half seconds with no API key.

Three things are enforced here rather than merely requested in a prompt, and
each exists because the prompt-only version is defeated by an agent doing
something reasonable:

* **Writes** go through `can_use_tool`, which resolves the target against the
  node's allowlist before the write happens. A denied write never lands, so the
  post-hoc diff check has nothing to clean up. The callback sees only tool
  calls, though -- a Bash heredoc writes files without invoking Write -- so the
  `paths_confined` exit gate re-derives the change set from git afterwards.
  Neither layer is sufficient alone.
* **Commands** are screened against the forbidden list before execution.
* **Egress** is the Agent SDK sandbox, not a command deny-list. `curl` in the
  forbidden list stops a careless agent; it does nothing about `python -c
  "urllib..."`. `SandboxNetworkConfig` allows Maven Central and denies the rest
  at the network layer, where the distinction between a careless agent and a
  determined one stops mattering.

One deliberate omission: `setting_sources` is left unset, so the session does
not inherit the operator's `CLAUDE.md`, project settings, or user-level
permission rules. A pipeline whose behaviour depends on files in whoever's home
directory happened to run it is not reproducible, and "it worked on my laptop"
is not a defence anyone accepts about a change-control system.
"""

from __future__ import annotations

import asyncio
import json
import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from claude_agent_sdk import (
    AssistantMessage,
    ClaudeAgentOptions,
    ClaudeSDKClient,
    PermissionResultAllow,
    PermissionResultDeny,
    ResultMessage,
    TextBlock,
    ThinkingBlock,
    ToolResultBlock,
    ToolUseBlock,
    UserMessage,
)

from . import schemas
from .checkpoint import Git
from .model import Autonomy, NodeResult, Policy
from .nodes import NodeInvocation, finalize_output, render_prompt
from .policy import EscalationLog, PathVerdict, PolicyEngine

# Tools whose first argument is a path we must vet. `NotebookEdit` is in the
# list because it is an edit tool that does not look like one.
_WRITE_TOOLS = {"Write", "Edit", "MultiEdit", "NotebookEdit"}

SYSTEM_PROMPT = """\
You are one node in an audited software delivery pipeline. Your output is
checked by mechanical gates -- compilers, test runs, coverage thresholds,
schema validation, path and secret scans -- and by human reviewers who read
the diff.

Three consequences worth internalising:

1. Gates are the specification, not an obstacle. Never weaken, skip, disable,
   or special-case a check to make it pass. If a gate is wrong, say so in your
   output and stop; a human decides.
2. Stay inside your declared write paths. They are enforced, and a violation
   fails the node rather than producing a partial result.
3. Do exactly the node's job. You are not responsible for the whole system,
   and work outside your remit lands in someone's review queue as noise.
"""


@dataclass
class AgentSDKBackend:
    """Runs a node by driving one Agent SDK session, under the node's policy."""

    prompts_root: Path
    policy: Policy = field(default_factory=Policy)
    transcripts_root: Path | None = None
    default_model: str = "claude-opus-5"
    default_effort: str = "high"
    max_turns: int | None = 120

    def run(self, invocation: NodeInvocation) -> NodeResult:
        """Synchronous facade.

        The engine schedules parallel branches on a thread pool, and each thread
        gets its own event loop, so `asyncio.run` per invocation is correct here
        rather than merely convenient: two branches genuinely do run two
        concurrent sessions.
        """
        return asyncio.run(self._run(invocation))

    # -- session ---------------------------------------------------------

    async def _run(self, invocation: NodeInvocation) -> NodeResult:
        node = invocation.node
        started = time.monotonic()
        escalations = EscalationLog()
        engine = PolicyEngine(
            self.policy, write_paths=node.write_paths, deny_paths=node.deny_paths
        )
        transcript: list[dict[str, Any]] = []
        cost = 0.0
        structured: Any = None
        failure: str | None = None

        prompt = render_prompt(invocation, self.prompts_root)
        transcript.append({"role": "prompt", "text": prompt})

        try:
            options = self._options(invocation, engine, escalations)
            async with ClaudeSDKClient(options=options) as client:
                await client.query(prompt)
                async for message in client.receive_response():
                    self._record(message, transcript)
                    if isinstance(message, ResultMessage):
                        cost = message.total_cost_usd or 0.0
                        structured = message.structured_output
                        if message.is_error:
                            failure = self._error_detail(message)
        except Exception as exc:  # noqa: BLE001 -- a crashed session is a node failure
            # Deliberately broad. A transport error, a rate limit, or a crashed
            # CLI is a failed attempt, not a failed run: the engine's retry and
            # fallback policy decides what happens next, and it can only do that
            # if the exception becomes a NodeResult instead of a traceback.
            failure = f"{type(exc).__name__}: {exc}"

        transcript_path = self._save_transcript(invocation, transcript)
        written = self._changed_paths(invocation)
        duration = time.monotonic() - started

        if failure is not None:
            return NodeResult(
                node_id=node.id,
                ok=False,
                error=failure,
                files_written=tuple(written),
                transcript_path=transcript_path,
                cost_usd=cost,
                duration_seconds=duration,
                escalations=tuple(escalations.entries),
            )

        output, missing = self._structured_output(invocation, structured)
        if missing is not None:
            return NodeResult(
                node_id=node.id,
                ok=False,
                error=missing,
                files_written=tuple(written),
                transcript_path=transcript_path,
                cost_usd=cost,
                duration_seconds=duration,
                escalations=tuple(escalations.entries),
            )

        if output:
            # Stamped before it is persisted *and* before it becomes downstream
            # context, so the artifact on disk and the object the next node
            # reads are the same thing.
            output = finalize_output(output, invocation.workspace)
            written = self._persist_output(invocation, output, written)

        return NodeResult(
            node_id=node.id,
            ok=True,
            output=output,
            files_written=tuple(written),
            transcript_path=transcript_path,
            cost_usd=cost,
            duration_seconds=duration,
            escalations=tuple(escalations.entries),
        )

    def _options(
        self,
        invocation: NodeInvocation,
        engine: PolicyEngine,
        escalations: EscalationLog,
    ) -> ClaudeAgentOptions:
        node = invocation.node
        options = ClaudeAgentOptions(
            cwd=str(invocation.workspace),
            system_prompt=SYSTEM_PROMPT,
            # `tools` restricts what exists; `allowed_tools` pre-approves what
            # may run *without consulting the permission callback*. Putting the
            # node's tools in the second one -- the obvious reading of the name
            # -- silently shadows `can_use_tool` and turns every check below
            # into decoration. The SDK warns about it; the warning is easy to
            # miss in a run that otherwise succeeds. Empty here, deliberately:
            # every tool call falls through to the callback.
            tools=list(node.tools),
            allowed_tools=[],
            model=node.model or self.default_model,
            effort=node.effort or self.default_effort,  # type: ignore[arg-type]
            max_turns=node.max_turns or self.max_turns,
            permission_mode="default",
            can_use_tool=self._permission_callback(invocation, engine, escalations),
            env=dict(os.environ),
        )
        if self.policy.sandbox.enabled:
            options.sandbox = self.policy.sandbox.to_sdk_settings()  # type: ignore[assignment]
        if node.output_schema:
            # The schema constrains generation *and* is re-checked at the exit
            # gate. That is not redundant: "the SDK constrained it" is an
            # assurance from the same system under test.
            options.output_format = {
                "type": "json_schema",
                "schema": schemas.get(node.output_schema),
            }
        return options

    # -- permission enforcement -----------------------------------------

    def _permission_callback(
        self,
        invocation: NodeInvocation,
        engine: PolicyEngine,
        escalations: EscalationLog,
    ):
        workspace = invocation.workspace
        propose_only = invocation.autonomy is Autonomy.PROPOSE

        async def can_use_tool(tool_name: str, tool_input: dict[str, Any], context):
            if tool_name in _WRITE_TOOLS:
                if propose_only:
                    # Fallback autonomy: the node still reasons and still
                    # produces a diff, it just does not get to apply it.
                    return PermissionResultDeny(
                        behavior="deny",
                        message=(
                            "This node is running in propose mode after an earlier "
                            "failure. Do not write files: describe the change and "
                            "emit the diff in your response. A human applies it."
                        ),
                        interrupt=False,
                    )
                return self._vet_write(tool_input, workspace, engine, escalations)

            if tool_name == "Bash":
                decision = engine.check_command(str(tool_input.get("command", "")))
                if decision.verdict is PathVerdict.DENIED:
                    return PermissionResultDeny(
                        behavior="deny", message=decision.reason, interrupt=False
                    )

            return PermissionResultAllow(behavior="allow")

        return can_use_tool

    def _vet_write(
        self,
        tool_input: dict[str, Any],
        workspace: Path,
        engine: PolicyEngine,
        escalations: EscalationLog,
    ):
        raw = tool_input.get("file_path") or tool_input.get("notebook_path") or ""
        relative = self._relativize(str(raw), workspace)
        if relative is None:
            return PermissionResultDeny(
                behavior="deny",
                message=f"'{raw}' is outside this run's workspace ({workspace}).",
                interrupt=False,
            )

        decision = engine.check_write(relative)
        if decision.verdict is PathVerdict.DENIED:
            return PermissionResultDeny(
                behavior="deny", message=decision.reason, interrupt=False
            )
        if decision.verdict is PathVerdict.PROTECTED:
            # Allowed, but recorded. The node finishes; the escalation forces
            # PENDING_APPROVAL afterwards regardless of configured autonomy.
            # Blocking here instead would mean a high-impact change could never
            # be *proposed*, only forbidden -- which is not what the control is
            # for.
            escalations.record(decision.reason)
        return PermissionResultAllow(behavior="allow")

    @staticmethod
    def _relativize(raw: str, workspace: Path) -> str | None:
        """Workspace-relative path, or None if the target escapes the workspace.

        Resolved, not string-compared: `service/../../etc/passwd` is outside the
        workspace no matter how it is spelled.
        """
        if not raw:
            return None
        path = Path(raw)
        candidate = path if path.is_absolute() else workspace / path
        try:
            resolved = candidate.resolve()
            return str(resolved.relative_to(workspace.resolve()))
        except (ValueError, OSError):
            return None

    # -- outputs ---------------------------------------------------------

    def _structured_output(
        self, invocation: NodeInvocation, structured: Any
    ) -> tuple[dict[str, Any], str | None]:
        """Normalize the session's structured output; report a missing one."""
        schema_name = invocation.node.output_schema
        if not schema_name:
            return ({}, None)
        if isinstance(structured, str):
            try:
                structured = json.loads(structured)
            except json.JSONDecodeError as exc:
                return ({}, f"structured output was not valid JSON: {exc}")
        if not isinstance(structured, dict):
            return (
                {},
                f"node declares output schema '{schema_name}' but the session "
                f"returned no structured output",
            )
        return (structured, None)

    def _persist_output(
        self, invocation: NodeInvocation, output: dict[str, Any], written: list[str]
    ) -> list[str]:
        """Write the artifact the exit gates read.

        The gates validate a file on disk rather than an in-memory object, so
        the artifact is what gets committed, replayed, and diffed later. Both
        backends write it identically -- that identity is what makes a recorded
        run a faithful replay of a live one.
        """
        schema_name = invocation.node.output_schema
        if not schema_name:
            return written
        rel = f"{invocation.artifacts_dirname}/{schema_name}.json"
        target = invocation.workspace / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(output, indent=2, sort_keys=True), encoding="utf-8")
        return written if rel in written else [*written, rel]

    @staticmethod
    def _changed_paths(invocation: NodeInvocation) -> list[str]:
        """What the node actually changed, from git rather than from self-report.

        Asking the session what it wrote would be a self-report, and the whole
        point of the diff layer is that it is not one.
        """
        try:
            return Git(root=invocation.workspace).changed_paths()
        except Exception:  # noqa: BLE001 -- a non-repo workspace is not fatal
            return []

    # -- transcripts -----------------------------------------------------

    def _record(self, message: Any, transcript: list[dict[str, Any]]) -> None:
        """Retain enough to answer 'why did this change happen?'.

        Auditors want the reasoning and the tool calls, not only the outcome.
        Tool *inputs* are recorded because a write is only reviewable if you can
        see what was written.
        """
        if isinstance(message, AssistantMessage):
            for block in message.content:
                if isinstance(block, TextBlock):
                    transcript.append({"role": "assistant", "text": block.text})
                elif isinstance(block, ThinkingBlock):
                    transcript.append({"role": "thinking", "text": block.thinking})
                elif isinstance(block, ToolUseBlock):
                    transcript.append(
                        {"role": "tool_use", "tool": block.name, "input": block.input}
                    )
        elif isinstance(message, UserMessage):
            # Tool *results*, including denials. Recording the call without the
            # outcome would leave an auditor able to see that a node tried to
            # write outside its allowlist but not that it was stopped.
            content = message.content
            if isinstance(content, list):
                for block in content:
                    if isinstance(block, ToolResultBlock):
                        transcript.append(
                            {
                                "role": "tool_result",
                                "is_error": block.is_error,
                                "content": block.content,
                            }
                        )
        elif isinstance(message, ResultMessage):
            transcript.append(
                {
                    "role": "result",
                    "subtype": message.subtype,
                    "is_error": message.is_error,
                    "num_turns": message.num_turns,
                    "cost_usd": message.total_cost_usd,
                    "session_id": message.session_id,
                }
            )

    def _save_transcript(
        self, invocation: NodeInvocation, transcript: list[dict[str, Any]]
    ) -> str | None:
        if self.transcripts_root is None:
            return None
        directory = self.transcripts_root / invocation.run_id
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / f"{invocation.node.id}-{invocation.sequence:02d}.jsonl"
        with path.open("w", encoding="utf-8") as fh:
            for entry in transcript:
                fh.write(json.dumps(entry, default=str) + "\n")
        return str(path)

    @staticmethod
    def _error_detail(message: ResultMessage) -> str:
        parts = [message.subtype or "error"]
        if message.errors:
            parts.extend(message.errors[:3])
        elif message.result:
            parts.append(message.result[:400])
        return "; ".join(p for p in parts if p)
