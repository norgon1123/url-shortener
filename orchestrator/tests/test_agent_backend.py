"""The live backend's enforcement layer, tested without a model.

The session itself needs an API key; the parts that decide what an agent is
allowed to *do* do not, and those are the parts worth testing. A permission
callback that silently allows a write outside the allowlist is the difference
between a governance control and a comment, and it would fail identically in
mock mode and live -- except mock mode never calls it.

So these tests drive `can_use_tool` directly, with the same policy the pipeline
loads, and assert on denials rather than on allowances.
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest

from sdlc.agent_backend import AgentSDKBackend
from sdlc.model import Autonomy, NodeSpec, Policy, SandboxPolicy
from sdlc.nodes import NodeInvocation
from sdlc.policy import EscalationLog, PolicyEngine

POLICY = Policy(
    protected_paths=("service/pom.xml",),
    forbidden_paths=(".git/**", "orchestrator/**", "**/*.pem"),
    forbidden_commands=("git push", "rm -rf /", "sudo"),
    secret_patterns=("AKIA[0-9A-Z]{16}",),
    sandbox=SandboxPolicy(enabled=True, allowed_domains=("repo.maven.apache.org",)),
)

NODE = NodeSpec(
    id="implement",
    prompt_path="implement.md",
    tools=("Read", "Write", "Edit", "Bash"),
    write_paths=("service/src/main/**", "service/pom.xml"),
    deny_paths=("service/src/test/**",),
)


@pytest.fixture
def workspace(tmp_path: Path) -> Path:
    root = tmp_path / "workspace"
    (root / "service/src/main/java").mkdir(parents=True)
    return root


@pytest.fixture
def backend(tmp_path: Path) -> AgentSDKBackend:
    prompts = tmp_path / "prompts"
    prompts.mkdir()
    (prompts / "implement.md").write_text("Implement the contract.")
    return AgentSDKBackend(
        prompts_root=prompts, policy=POLICY, transcripts_root=tmp_path / "transcripts"
    )


def invocation(workspace: Path, *, node: NodeSpec = NODE, **kwargs) -> NodeInvocation:
    return NodeInvocation(node=node, run_id="run-1", workspace=workspace, **kwargs)


def ask(backend: AgentSDKBackend, inv: NodeInvocation, tool: str, payload: dict):
    """Drive the permission callback the way the SDK would."""
    escalations = EscalationLog()
    engine = PolicyEngine(
        POLICY, write_paths=inv.node.write_paths, deny_paths=inv.node.deny_paths
    )
    callback = backend._permission_callback(inv, engine, escalations)
    return asyncio.run(callback(tool, payload, None)), escalations


class TestWritePermission:
    def test_a_write_inside_the_allowlist_is_allowed(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        result, escalations = ask(
            backend,
            invocation(workspace),
            "Write",
            {"file_path": "service/src/main/java/App.java"},
        )
        assert result.behavior == "allow"
        assert not escalations.triggered

    def test_the_segregation_of_duties_boundary_is_enforced_here(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        """ADR-003: `implement` cannot weaken the tests that gate it."""
        result, _ = ask(
            backend,
            invocation(workspace),
            "Write",
            {"file_path": "service/src/test/java/LinkTest.java"},
        )
        assert result.behavior == "deny"
        assert "deny list" in result.message

    def test_a_forbidden_path_beats_a_generous_allowlist(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        node = NodeSpec(id="n", write_paths=("**",))
        result, _ = ask(
            backend, invocation(workspace, node=node), "Write", {"file_path": ".git/config"}
        )
        assert result.behavior == "deny"

    def test_a_protected_path_is_allowed_but_recorded(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        """Blocking here would mean a dependency change could never be proposed."""
        result, escalations = ask(
            backend, invocation(workspace), "Write", {"file_path": "service/pom.xml"}
        )
        assert result.behavior == "allow"
        assert escalations.triggered
        assert "pom.xml" in escalations.entries[0]

    def test_an_absolute_path_inside_the_workspace_resolves(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        target = workspace / "service/src/main/java/App.java"
        result, _ = ask(backend, invocation(workspace), "Write", {"file_path": str(target)})
        assert result.behavior == "allow"

    def test_a_path_escaping_the_workspace_is_denied(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        """Resolved, not string-matched: traversal is not a spelling question."""
        result, _ = ask(
            backend,
            invocation(workspace),
            "Write",
            {"file_path": "service/../../etc/passwd"},
        )
        assert result.behavior == "deny"
        assert "outside this run's workspace" in result.message

    def test_notebook_edits_are_vetted_too(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        result, _ = ask(
            backend, invocation(workspace), "NotebookEdit", {"notebook_path": "notes.ipynb"}
        )
        assert result.behavior == "deny"

    def test_propose_mode_withholds_the_write_not_the_work(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        """Fallback autonomy: the node still produces a diff; a human applies it."""
        result, _ = ask(
            backend,
            invocation(workspace, autonomy=Autonomy.PROPOSE),
            "Write",
            {"file_path": "service/src/main/java/App.java"},
        )
        assert result.behavior == "deny"
        assert "propose mode" in result.message


class TestCommandPermission:
    def test_a_forbidden_command_is_refused_anywhere_in_the_line(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        result, _ = ask(
            backend,
            invocation(workspace),
            "Bash",
            {"command": "cd service && git push origin main"},
        )
        assert result.behavior == "deny"

    def test_an_ordinary_command_runs(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        result, _ = ask(
            backend, invocation(workspace), "Bash", {"command": "./mvnw -q compile"}
        )
        assert result.behavior == "allow"

    def test_reads_are_not_gated(self, backend: AgentSDKBackend, workspace: Path) -> None:
        result, _ = ask(backend, invocation(workspace), "Read", {"file_path": "/etc/hosts"})
        assert result.behavior == "allow"


class TestOptions:
    def _options(self, backend: AgentSDKBackend, inv: NodeInvocation):
        engine = PolicyEngine(POLICY, write_paths=inv.node.write_paths)
        return backend._options(inv, engine, EscalationLog())

    def test_the_sandbox_carries_the_pipeline_egress_policy(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        """Enforced at the network layer, not by pattern-matching commands."""
        options = self._options(backend, invocation(workspace))
        assert options.sandbox["enabled"] is True
        assert options.sandbox["network"]["allowedDomains"] == ["repo.maven.apache.org"]

    def test_operator_settings_are_not_inherited(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        """A pipeline that behaves differently per laptop is not reproducible."""
        assert self._options(backend, invocation(workspace)).setting_sources is None

    def test_tools_are_restricted_to_the_node_declaration(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        assert self._options(backend, invocation(workspace)).allowed_tools == list(NODE.tools)

    def test_a_node_with_a_schema_constrains_generation(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        node = NodeSpec(id="intake", prompt_path="implement.md", output_schema="requirement")
        options = self._options(backend, invocation(workspace, node=node))
        assert options.output_format["type"] == "json_schema"
        assert "goal" in options.output_format["schema"]["properties"]

    def test_a_node_without_a_schema_sets_no_output_format(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        assert self._options(backend, invocation(workspace)).output_format is None


class TestStructuredOutput:
    def test_a_missing_object_fails_the_node_with_a_usable_reason(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        node = NodeSpec(id="intake", output_schema="requirement")
        _, error = backend._structured_output(invocation(workspace, node=node), None)
        assert error is not None and "requirement" in error

    def test_a_json_string_is_parsed(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        node = NodeSpec(id="intake", output_schema="requirement")
        output, error = backend._structured_output(
            invocation(workspace, node=node), '{"goal": "shorten links"}'
        )
        assert error is None and output["goal"] == "shorten links"

    def test_unparseable_json_is_reported_rather_than_swallowed(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        node = NodeSpec(id="intake", output_schema="requirement")
        _, error = backend._structured_output(invocation(workspace, node=node), "{oops")
        assert error is not None and "valid JSON" in error

    def test_a_node_without_a_schema_needs_no_output(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        assert backend._structured_output(invocation(workspace), None) == ({}, None)

    def test_the_artifact_lands_where_the_gates_look(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        """Both backends write it identically; that identity is what makes a
        recorded run a faithful replay of a live one."""
        node = NodeSpec(id="intake", output_schema="requirement")
        inv = invocation(workspace, node=node)
        written = backend._persist_output(inv, {"goal": "shorten links"}, [])

        assert written == ["artifacts/requirement.json"]
        payload = json.loads((workspace / "artifacts/requirement.json").read_text())
        assert payload["goal"] == "shorten links"


class TestChangeDetection:
    def test_changed_paths_come_from_git_not_from_self_report(
        self, backend: AgentSDKBackend, tmp_path: Path
    ) -> None:
        """A Bash heredoc never reaches the permission callback; it does reach git."""
        from sdlc.checkpoint import Git

        root = tmp_path / "repo"
        root.mkdir()
        git = Git(root=root)
        git._git("init", "-b", "main")
        git._git("config", "user.email", "o@example.com")
        git._git("config", "user.name", "Orchestrator")
        (root / "README.md").write_text("x")
        git._git("add", "-A")
        git._git("commit", "-m", "initial")
        (root / "smuggled.txt").write_text("written without a Write tool call")

        assert backend._changed_paths(invocation(root)) == ["smuggled.txt"]

    def test_a_workspace_that_is_not_a_repo_is_not_fatal(
        self, backend: AgentSDKBackend, workspace: Path
    ) -> None:
        assert backend._changed_paths(invocation(workspace)) == []
