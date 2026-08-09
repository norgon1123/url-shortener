"""Pipeline loading, validation, and scheduling.

These run in milliseconds with no API key. That is the point of Phase 0: the
governance machinery is testable without ever calling a model.
"""

from __future__ import annotations

import textwrap
from pathlib import Path

import pytest

from sdlc.graph import PipelineError, load_pipeline, parallel_groups, schedule
from sdlc.model import Autonomy, FailureAction, GateClass, NodeKind

REPO_ROOT = Path(__file__).resolve().parents[2]
REAL_PIPELINE = REPO_ROOT / "orchestrator" / "pipelines" / "sdlc.yaml"


def write_pipeline(tmp_path: Path, body: str) -> Path:
    path = tmp_path / "p.yaml"
    path.write_text(textwrap.dedent(body))
    return path


MINIMAL = """
    version: 1
    nodes:
      - id: a
        prompt: prompts/a.md
      - id: b
        prompt: prompts/b.md
        depends_on: [a]
"""


class TestRealPipeline:
    """The shipped pipeline must stay loadable and correctly shaped."""

    def test_loads(self) -> None:
        p = load_pipeline(REAL_PIPELINE)
        assert p.version == 1
        assert len(p.nodes) == 11

    def test_implement_and_tests_run_concurrently(self) -> None:
        # The §4.4 parallel-path requirement. This must fall out of the graph,
        # not out of a hardcoded branch in the scheduler.
        assert parallel_groups(load_pipeline(REAL_PIPELINE)) == [
            ("author-tests", "implement")
        ]

    def test_segregation_of_duties(self) -> None:
        """ADR-003: the implementer structurally cannot weaken its own gate."""
        p = load_pipeline(REAL_PIPELINE)
        impl = p.node("implement")
        tests = p.node("author-tests")

        assert any("src/test" in d for d in impl.deny_paths)
        assert not any("src/test" in w for w in impl.write_paths)
        assert any("src/test" in w for w in tests.write_paths)
        assert any("src/main" in d for d in tests.deny_paths)
        # pom.xml is frozen at design so the parallel branches cannot conflict.
        assert any("pom.xml" in d for d in impl.deny_paths)
        assert any("pom.xml" in d for d in tests.deny_paths)

    def test_branches_use_separate_worktrees(self) -> None:
        p = load_pipeline(REAL_PIPELINE)
        assert p.node("implement").worktree != p.node("author-tests").worktree
        assert p.node("implement").worktree is not None

    def test_verify_is_deterministic_and_replans(self) -> None:
        v = load_pipeline(REAL_PIPELINE).node("verify")
        assert v.kind is NodeKind.DETERMINISTIC  # no model call in the gate
        assert v.on_failure is FailureAction.REPLAN
        assert v.replan_target == "decompose"
        assert v.max_replans == 2

    def test_review_cannot_auto_fail(self) -> None:
        """ADR-001: review is advisory; its model-driven gate escalates."""
        from sdlc.model import GateOutcome

        review = load_pipeline(REAL_PIPELINE).node("review")
        self_report = [
            g for g in review.exit_gates if g.gate_class is GateClass.SELF_REPORT
        ]
        assert self_report, "review must carry a self-report gate"
        assert all(g.on_fail is GateOutcome.ESCALATE for g in self_report)

    def test_release_is_propose_only(self) -> None:
        rel = load_pipeline(REAL_PIPELINE).node("release-readiness")
        assert rel.autonomy is Autonomy.PROPOSE
        assert rel.has_human_gate

    def test_every_self_report_node_has_human_backstop(self) -> None:
        p = load_pipeline(REAL_PIPELINE)
        assert {n.id for n in p.nodes if n.has_self_report_gate} == {
            "clarify",
            "review",
        }

    def test_sandbox_egress_is_restricted(self) -> None:
        """Not a best-effort deny-list -- a real allowlist handed to the SDK."""
        settings = load_pipeline(REAL_PIPELINE).policy.sandbox.to_sdk_settings()
        assert settings["enabled"] is True
        assert "repo.maven.apache.org" in settings["network"]["allowedDomains"]


class TestScheduling:
    def test_levels_respect_dependencies(self, tmp_path: Path) -> None:
        assert schedule(load_pipeline(write_pipeline(tmp_path, MINIMAL))) == [
            ("a",),
            ("b",),
        ]

    def test_independent_nodes_share_a_level(self, tmp_path: Path) -> None:
        p = load_pipeline(
            write_pipeline(
                tmp_path,
                """
                version: 1
                nodes:
                  - {id: root, prompt: p.md}
                  - {id: left, prompt: p.md, depends_on: [root]}
                  - {id: right, prompt: p.md, depends_on: [root]}
                  - {id: join, prompt: p.md, depends_on: [left, right]}
                """,
            )
        )
        assert schedule(p) == [("root",), ("left", "right"), ("join",)]


class TestValidation:
    """Each of these is a real failure mode a hand-edited pipeline could hit."""

    def test_rejects_cycle(self, tmp_path: Path) -> None:
        path = write_pipeline(
            tmp_path,
            """
            version: 1
            nodes:
              - {id: a, prompt: p.md, depends_on: [b]}
              - {id: b, prompt: p.md, depends_on: [a]}
            """,
        )
        with pytest.raises(PipelineError, match="cycle"):
            load_pipeline(path)

    def test_rejects_unknown_dependency(self, tmp_path: Path) -> None:
        path = write_pipeline(
            tmp_path,
            """
            version: 1
            nodes:
              - {id: a, prompt: p.md, depends_on: [ghost]}
            """,
        )
        with pytest.raises(PipelineError, match="unknown dependency"):
            load_pipeline(path)

    def test_rejects_duplicate_ids(self, tmp_path: Path) -> None:
        path = write_pipeline(
            tmp_path,
            """
            version: 1
            nodes:
              - {id: a, prompt: p.md}
              - {id: a, prompt: p.md}
            """,
        )
        with pytest.raises(PipelineError, match="duplicate node ids"):
            load_pipeline(path)

    def test_rejects_replan_without_target(self, tmp_path: Path) -> None:
        path = write_pipeline(
            tmp_path,
            """
            version: 1
            nodes:
              - {id: a, prompt: p.md, on_failure: replan}
            """,
        )
        with pytest.raises(PipelineError, match="requires a 'replan_target'"):
            load_pipeline(path)

    def test_rejects_agent_node_without_prompt(self, tmp_path: Path) -> None:
        path = write_pipeline(
            tmp_path,
            """
            version: 1
            nodes:
              - {id: a}
            """,
        )
        with pytest.raises(PipelineError, match="require a 'prompt'"):
            load_pipeline(path)

    def test_rejects_ungoverned_self_report_gate(self, tmp_path: Path) -> None:
        """The structural enforcement of ADR-001.

        A gate that reads a model-populated field with no human downstream is
        the exact failure the taxonomy exists to prevent, so the loader refuses
        it rather than leaving the rule as prose in a design doc.
        """
        path = write_pipeline(
            tmp_path,
            """
            version: 1
            nodes:
              - id: a
                prompt: p.md
                exit_gates:
                  - {check: no_blocking_ambiguities, gate_class: self_report}
              - id: b
                prompt: p.md
                depends_on: [a]
            """,
        )
        with pytest.raises(PipelineError, match="no human gate downstream"):
            load_pipeline(path)

    def test_accepts_self_report_with_human_backstop(self, tmp_path: Path) -> None:
        path = write_pipeline(
            tmp_path,
            """
            version: 1
            nodes:
              - id: a
                prompt: p.md
                exit_gates:
                  - {check: no_blocking_ambiguities, gate_class: self_report}
              - id: b
                prompt: p.md
                depends_on: [a]
                exit_gates:
                  - {check: human_approval, gate_class: human}
            """,
        )
        assert len(load_pipeline(path).nodes) == 2
