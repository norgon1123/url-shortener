"""Scripted node backends: the whole pipeline, in under a second, for free.

The critical property is what the mock does *not* replace. It substitutes the
model call and nothing else: it writes real files into the real workspace, so
the gates that run afterwards run for real. `maven_compiles` still shells out,
`schema_valid` still validates, `paths_confined` still diffs, the journal still
chains, the checkpoints still commit. A mock that also stubbed the gates would
be testing that the test harness agrees with itself.

That buys three things at once:

  * a development loop measured in seconds rather than twenty-minute runs;
  * the orchestrator's own test suite -- a governance tool with no tests of
    itself is not an argument anyone should accept;
  * an evaluator with no API key can still exercise every control: run the
    ambiguous scenario, watch it halt, approve it, watch it resume.

Scripts index attempts positionally, so "fail verify twice, then pass" is three
lines of YAML. That is how the retry / rollback / replan / safe-stop paths get
exercised deterministically instead of by hoping a live model misbehaves on cue.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from .model import NodeResult
from .nodes import NodeInvocation


class ScriptError(ValueError):
    pass


@dataclass(frozen=True)
class ScriptedAttempt:
    """One canned attempt at one node."""

    output: dict[str, Any] | None = None
    files: dict[str, str] = field(default_factory=dict)
    deletes: tuple[str, ...] = ()
    fail: str | None = None
    cost_usd: float = 0.0
    duration_seconds: float = 0.0
    escalations: tuple[str, ...] = ()

    @classmethod
    def from_raw(cls, raw: Any, base_dir: Path) -> ScriptedAttempt:
        if raw is None:
            raw = {}
        if not isinstance(raw, dict):
            raise ScriptError(f"attempt must be a mapping, got {type(raw).__name__}")
        if unknown := set(raw) - {
            "output",
            "files",
            "files_from",
            "deletes",
            "fail",
            "cost_usd",
            "duration_seconds",
            "escalations",
        }:
            raise ScriptError(f"unknown attempt key(s): {', '.join(sorted(unknown))}")

        files = dict(raw.get("files") or {})
        # Large payloads (a Java class, a rendered OpenAPI document) live beside
        # the script rather than inside it -- an unreadable script is a script
        # nobody maintains.
        for dest, source in (raw.get("files_from") or {}).items():
            path = base_dir / source
            if not path.is_file():
                raise ScriptError(f"files_from source not found: {path}")
            files[dest] = path.read_text(encoding="utf-8")

        return cls(
            output=raw.get("output"),
            files=files,
            deletes=tuple(raw.get("deletes") or ()),
            fail=raw.get("fail"),
            cost_usd=float(raw.get("cost_usd", 0.0)),
            duration_seconds=float(raw.get("duration_seconds", 0.0)),
            escalations=tuple(raw.get("escalations") or ()),
        )


@dataclass
class MockBackend:
    """Replays a script. Node outputs land on disk exactly as a live run's would."""

    script: dict[str, list[ScriptedAttempt]]
    strict: bool = True
    _calls: dict[str, int] = field(default_factory=dict)

    def run(self, invocation: NodeInvocation) -> NodeResult:
        node = invocation.node
        attempts = self.script.get(node.id)
        if not attempts:
            if self.strict:
                raise ScriptError(
                    f"no scripted attempts for node '{node.id}'. "
                    f"Scripted: {', '.join(sorted(self.script)) or '<none>'}"
                )
            return NodeResult(node_id=node.id, ok=True)

        # Indexed by call count rather than by `attempt`, because a node can be
        # re-entered by a replan with its attempt counter reset. Call order is
        # the only thing that reliably advances, and it is what a script author
        # is thinking in anyway: "this happens, then this happens".
        index = self._calls.get(node.id, 0)
        self._calls[node.id] = index + 1
        # Past the end of the script the last entry repeats, so "fails forever"
        # is one line rather than one per possible retry.
        attempt = attempts[min(index, len(attempts) - 1)]

        if attempt.fail is not None:
            return NodeResult(
                node_id=node.id,
                ok=False,
                error=attempt.fail,
                cost_usd=attempt.cost_usd,
                duration_seconds=attempt.duration_seconds,
            )

        written = self._apply(attempt, invocation)
        return NodeResult(
            node_id=node.id,
            ok=True,
            output=attempt.output or {},
            files_written=tuple(written),
            cost_usd=attempt.cost_usd,
            duration_seconds=attempt.duration_seconds,
            escalations=attempt.escalations,
        )

    def _apply(self, attempt: ScriptedAttempt, invocation: NodeInvocation) -> list[str]:
        """Perform the node's real side effects on the real workspace."""
        written: list[str] = []
        for rel, content in sorted(attempt.files.items()):
            target = invocation.workspace / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
            written.append(rel)

        for rel in attempt.deletes:
            target = invocation.workspace / rel
            if target.is_file():
                target.unlink()
                written.append(rel)

        # A node that declares an output schema writes it where the exit gate
        # looks, mirroring what the live backend does with structured output.
        if attempt.output is not None and invocation.node.output_schema:
            rel = f"{invocation.artifacts_dirname}/{invocation.node.output_schema}.json"
            target = invocation.workspace / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(
                json.dumps(attempt.output, indent=2, sort_keys=True), encoding="utf-8"
            )
            if rel not in written:
                written.append(rel)
        return written


def load_script(path: str | Path) -> MockBackend:
    """Load a scenario script.

    Format::

        nodes:
          intake:
            - output: {goal: "...", ...}
              cost_usd: 0.12
          verify:
            - fail: "coverage 0.62 below the 0.70 floor"   # attempt 1
            - {}                                            # attempt 2 passes
    """
    path = Path(path)
    try:
        raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        raise ScriptError(f"{path}: invalid YAML: {exc}") from exc
    if not isinstance(raw, dict) or "nodes" not in raw:
        raise ScriptError(f"{path}: expected a mapping with a 'nodes' key")

    script: dict[str, list[ScriptedAttempt]] = {}
    for node_id, attempts in (raw["nodes"] or {}).items():
        if isinstance(attempts, dict):  # single attempt, unwrapped
            attempts = [attempts]
        if not isinstance(attempts, list):
            raise ScriptError(f"{path}: node '{node_id}' must map to a list of attempts")
        script[node_id] = [ScriptedAttempt.from_raw(a, path.parent) for a in attempts]
    return MockBackend(script=script, strict=bool(raw.get("strict", True)))


@dataclass
class RecordingBackend:
    """Wraps a live backend and writes a replayable script of what it did.

    This is how a real run becomes a fixture that ships in the repo. Someone
    reviewing the work can replay the exact artifacts a live run produced,
    inspect the journal, and re-verify its hash chain -- without an API key and
    without spending anything.
    """

    inner: Any
    out_dir: Path
    _recorded: dict[str, list[dict[str, Any]]] = field(default_factory=dict)

    def run(self, invocation: NodeInvocation) -> NodeResult:
        result = self.inner.run(invocation)
        entry: dict[str, Any] = {
            "cost_usd": round(result.cost_usd, 6),
            "duration_seconds": round(result.duration_seconds, 3),
        }
        if not result.ok:
            entry["fail"] = result.error or "node reported failure"
        else:
            if result.output:
                entry["output"] = result.output
            files = {}
            for rel in result.files_written:
                source = invocation.workspace / rel
                if source.is_file():
                    files[rel] = source.read_text(encoding="utf-8", errors="replace")
            if files:
                entry["files"] = files
            if result.escalations:
                entry["escalations"] = list(result.escalations)
        self._recorded.setdefault(invocation.node.id, []).append(entry)
        return result

    def save(self, name: str = "script.yaml") -> Path:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        target = self.out_dir / name
        target.write_text(
            yaml.safe_dump({"nodes": self._recorded}, sort_keys=True, width=100),
            encoding="utf-8",
        )
        return target
