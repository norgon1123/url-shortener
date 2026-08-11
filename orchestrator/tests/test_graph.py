"""Pipeline loading, validation, and scheduling.

These run in milliseconds with no API key. That is the point of Phase 0: the
governance machinery is testable without ever calling a model.
"""

from __future__ import annotations

import textwrap
from pathlib import Path

import pytest
import yaml

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
        assert len(p.nodes) == 21

    def test_both_fan_outs_fall_out_of_the_graph(self) -> None:
        # The §4.4 parallel-path requirement. This must fall out of the graph,
        # not out of a hardcoded branch in the scheduler -- which is why adding
        # the review fan-out needed no engine change at all.
        #
        # The two buy different things: the first buys segregation of duties,
        # the second buys independence of judgement. Neither is about speed.
        assert parallel_groups(load_pipeline(REAL_PIPELINE)) == [
            ("author-tests", "implement"),
            (
                "docs",
                "review-api-contract",
                "review-cleanliness",
                "review-performance",
                "review-security",
                "review-test-adequacy",
            ),
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

    def test_verify_is_deterministic_and_triages(self) -> None:
        v = load_pipeline(REAL_PIPELINE).node("verify")
        assert v.kind is NodeKind.DETERMINISTIC  # no model call in the gate
        # Triage first -- ask which artifact is wrong before re-deriving the
        # plan, the contract and both branches to fix a defect in one of them.
        assert v.on_failure is FailureAction.TRIAGE
        assert v.triage_node == "triage"
        # The sledgehammer survives as the fallback when triage cannot tell.
        assert v.replan_target == "decompose"
        assert v.replan_target == "decompose"
        assert v.max_replans == 2

    def test_review_cannot_auto_fail(self) -> None:
        """ADR-001: review is advisory; its model-driven gate escalates."""
        from sdlc.model import GateOutcome

        review = load_pipeline(REAL_PIPELINE).node("review-synthesis")
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
            "review-synthesis",
        }

    def test_review_is_fanned_out_into_independent_lenses(self) -> None:
        """Five briefs, no shared state: none of them can see another's findings."""
        p = load_pipeline(REAL_PIPELINE)
        lenses = [n for n in p.nodes if n.id.startswith("review-") and n.output_schema]
        lenses = [n for n in lenses if n.id != "review-synthesis"]
        assert {n.id for n in lenses} == {
            "review-security",
            "review-performance",
            "review-api-contract",
            "review-test-adequacy",
            "review-cleanliness",
        }
        # Each depends only on verify -- not on each other, and not on docs.
        assert all(n.depends_on == ("verify",) for n in lenses)
        # Distinct worktrees: a lens cannot see, or be blamed for, another's diff.
        assert len({n.worktree for n in lenses}) == len(lenses)

    def test_docs_and_review_are_no_longer_serialised(self) -> None:
        """The old docs -> review edge was ordering with no stated reason."""
        p = load_pipeline(REAL_PIPELINE)
        assert p.node("docs").depends_on == ("verify",)
        level = next(lvl for lvl in schedule(p) if "docs" in lvl)
        assert "review-security" in level  # same ready set, genuinely concurrent

    def test_the_join_cannot_silently_drop_a_lens_finding(self) -> None:
        """Without this gate the synthesis node undoes the fan-out."""
        checks = {g.check for g in load_pipeline(REAL_PIPELINE).node("review-synthesis").exit_gates}
        assert "lens_findings_preserved" in checks

    def test_analysis_nodes_precede_planning(self) -> None:
        """§4.3 codebase reasoning has to happen before the plan, not after it."""
        p = load_pipeline(REAL_PIPELINE)
        assert p.node("impact-analysis").depends_on == ("clarify",)
        assert "impact-analysis" in p.node("feasibility").depends_on
        assert {"impact-analysis", "feasibility"} <= set(p.node("decompose").depends_on)

    def test_the_spike_cannot_leave_code_behind(self) -> None:
        feasibility = load_pipeline(REAL_PIPELINE).node("feasibility")
        assert any("service" in d for d in feasibility.deny_paths)
        assert feasibility.write_paths == ("artifacts/**",)

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

    def test_rejects_concurrent_writers_sharing_a_checkout(self, tmp_path: Path) -> None:
        """Adding a second reviewer to a level is a one-line edit. Make it safe.

        Two writing nodes on one level share a git index, so their commits race,
        each one's diff check sees the other's files, and the checkpoint
        trailers stop being true. All three failures are silent.
        """
        path = write_pipeline(
            tmp_path,
            """
            version: 1
            nodes:
              - {id: a, prompt: p.md, write_paths: ["artifacts/**"]}
              - {id: b, prompt: p.md, write_paths: ["docs/**"]}
            """,
        )
        with pytest.raises(PipelineError, match="declare no 'worktree'"):
            load_pipeline(path)

    def test_rejects_concurrent_writers_sharing_a_worktree_name(
        self, tmp_path: Path
    ) -> None:
        path = write_pipeline(
            tmp_path,
            """
            version: 1
            nodes:
              - {id: a, prompt: p.md, write_paths: ["artifacts/**"], worktree: shared}
              - {id: b, prompt: p.md, write_paths: ["docs/**"], worktree: shared}
              - {id: j, type: barrier, depends_on: [a, b]}
            """,
        )
        with pytest.raises(PipelineError, match="share worktree"):
            load_pipeline(path)

    def test_a_read_only_node_may_share_a_level(self, tmp_path: Path) -> None:
        """The rule is about writers. A node that changes nothing cannot collide."""
        path = write_pipeline(
            tmp_path,
            """
            version: 1
            nodes:
              - {id: a, prompt: p.md, write_paths: ["artifacts/**"]}
              - {id: b, prompt: p.md}
            """,
        )
        load_pipeline(path)  # no raise

    def test_rejects_a_worktree_no_barrier_ever_merges(self, tmp_path: Path) -> None:
        """Work committed to a branch nothing merges is work that never happened."""
        path = write_pipeline(
            tmp_path,
            """
            version: 1
            nodes:
              - {id: a, prompt: p.md, write_paths: ["artifacts/**"], worktree: solo}
            """,
        )
        with pytest.raises(PipelineError, match="not a direct dependency of any barrier"):
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


class TestTriageConfiguration:
    """A repair path that can only ever escalate is a repair path in name only."""

    def _pipeline(self, tmp_path: Path, **overrides) -> Path:
        nodes = [
            {"id": "build", "prompt": "b.md", "repair_attempts": overrides.pop("budget", 1)},
            {
                "id": "verify",
                "type": "deterministic",
                "depends_on": ["build"],
                "on_failure": "triage",
                **overrides,
            },
            {"id": "triage", "type": overrides.pop("handler_type", "handler"), "prompt": "t.md"},
        ]
        path = tmp_path / "p.yaml"
        path.write_text(yaml.safe_dump({"version": 1, "nodes": nodes}))
        return path

    def test_triage_without_a_handler_is_rejected(self, tmp_path: Path) -> None:
        with pytest.raises(PipelineError, match="requires a 'triage_node'"):
            load_pipeline(self._pipeline(tmp_path))

    def test_an_unknown_handler_is_rejected(self, tmp_path: Path) -> None:
        with pytest.raises(PipelineError, match="unknown triage_node"):
            load_pipeline(self._pipeline(tmp_path, triage_node="nope"))

    def test_a_handler_that_is_not_a_handler_is_rejected(self, tmp_path: Path) -> None:
        """A scheduled handler declares no dependencies, so it lands on level 0
        and runs on every clean pass -- the opposite of what it is for."""
        nodes = [
            {"id": "build", "prompt": "b.md", "repair_attempts": 1},
            {"id": "verify", "type": "deterministic", "depends_on": ["build"],
             "on_failure": "triage", "triage_node": "triage"},
            {"id": "triage", "prompt": "t.md"},  # agent, not handler
        ]
        path = tmp_path / "p.yaml"
        path.write_text(yaml.safe_dump({"version": 1, "nodes": nodes}))
        with pytest.raises(PipelineError, match=r"must be\s+type: handler"):
            load_pipeline(path)

    def test_triage_with_nowhere_to_route_is_rejected(self, tmp_path: Path) -> None:
        with pytest.raises(PipelineError, match="could only ever escalate"):
            load_pipeline(self._pipeline(tmp_path, triage_node="triage", budget=0))

    def test_a_valid_configuration_loads(self, tmp_path: Path) -> None:
        p = load_pipeline(self._pipeline(tmp_path, triage_node="triage"))
        assert p.node("triage").kind is NodeKind.HANDLER

    def test_a_handler_is_not_scheduled(self, tmp_path: Path) -> None:
        p = load_pipeline(self._pipeline(tmp_path, triage_node="triage"))
        assert "triage" not in {nid for level in schedule(p) for nid in level}


class TestTurnBudget:
    """A limit that can end a node belongs in the pipeline, not in the backend."""

    def _pipeline(self, tmp_path: Path, nodes, **top) -> Path:
        path = tmp_path / "p.yaml"
        path.write_text(yaml.safe_dump({"version": 1, "nodes": nodes, **top}))
        return path

    def test_a_node_can_declare_its_own(self, tmp_path: Path) -> None:
        p = load_pipeline(
            self._pipeline(tmp_path, [{"id": "a", "prompt": "a.md", "max_turns": 600}])
        )
        assert p.node("a").max_turns == 600

    def test_the_default_applies_when_a_node_is_silent(self, tmp_path: Path) -> None:
        p = load_pipeline(
            self._pipeline(
                tmp_path, [{"id": "a", "prompt": "a.md"}], defaults={"max_turns": 150}
            )
        )
        assert p.node("a").max_turns == 150

    def test_a_node_overrides_the_default(self, tmp_path: Path) -> None:
        """author-tests needs a body per behaviour; intake emits one document."""
        p = load_pipeline(
            self._pipeline(
                tmp_path,
                [{"id": "a", "prompt": "a.md"}, {"id": "b", "prompt": "b.md", "max_turns": 600}],
                defaults={"max_turns": 150},
            )
        )
        assert (p.node("a").max_turns, p.node("b").max_turns) == (150, 600)

    def test_the_real_pipeline_budgets_the_branch_that_ran_out(self) -> None:
        p = load_pipeline(
            Path(__file__).resolve().parents[2] / "orchestrator/pipelines/sdlc.yaml"
        )
        assert p.node("author-tests").max_turns > p.node("intake").max_turns
