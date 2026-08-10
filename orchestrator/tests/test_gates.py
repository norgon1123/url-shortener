"""Gate checks.

A governance layer whose gates are untested is a governance layer that has
never been shown to say "no". These tests deliberately spend most of their
effort on the *failing* paths -- a gate that passes when it should is a nuisance,
a gate that passes when it shouldn't is the whole risk.

Nothing here runs Maven, git, or a model: the command runner is injected and
every artifact is a file in tmp_path.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
import yaml

from sdlc import gates
from sdlc.checkpoint import Git
from sdlc.gates import CommandResult, GateContext, GateError, subprocess_runner
from sdlc.graph import PipelineError, load_pipeline
from sdlc.model import (
    Approval,
    ApprovalDecision,
    GateClass,
    GateOutcome,
    GateSpec,
    NodeResult,
    NodeSpec,
    Policy,
    SandboxPolicy,
)
from sdlc.policy import PolicyEngine

PASS, FAIL, ESCALATE = GateOutcome.PASS, GateOutcome.FAIL, GateOutcome.ESCALATE


@pytest.fixture
def policy() -> Policy:
    return Policy(
        protected_paths=("service/pom.xml",),
        forbidden_paths=(".git/**", "orchestrator/**"),
        forbidden_commands=("git push",),
        secret_patterns=(r"AKIA[0-9A-Z]{16}",),
        sandbox=SandboxPolicy(),
    )


def make_ctx(
    tmp_path: Path,
    policy: Policy,
    *,
    node_id: str = "test-node",
    write_paths: tuple[str, ...] = ("service/**", "artifacts/**", "docs/**"),
    deny_paths: tuple[str, ...] = (),
    files_written: tuple[str, ...] = (),
    output: dict | None = None,
    approvals: dict[str, Approval] | None = None,
    runner=None,
) -> GateContext:
    node = NodeSpec(id=node_id, write_paths=write_paths, deny_paths=deny_paths)
    return GateContext(
        workspace=tmp_path,
        node=node,
        policy=PolicyEngine(policy, write_paths=write_paths, deny_paths=deny_paths),
        result=NodeResult(
            node_id=node_id, ok=True, files_written=files_written, output=output or {}
        ),
        approvals=approvals or {},
        run=runner or (lambda argv, cwd, timeout=1800.0: CommandResult(0)),
    )


def write_artifact(tmp_path: Path, name: str, payload) -> Path:
    target = tmp_path / "artifacts" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(payload) if not isinstance(payload, str) else payload,
        encoding="utf-8",
    )
    return target


def run(check_name: str, ctx: GateContext, **params):
    return gates.evaluate(GateSpec(check=check_name, gate_class=GateClass.MECHANICAL, params=params), ctx)


# --------------------------------------------------------------------------
# Registry and dispatch
# --------------------------------------------------------------------------


class TestRegistry:
    def test_unknown_check_raises(self, tmp_path: Path, policy: Policy) -> None:
        with pytest.raises(GateError, match="unknown gate check"):
            run("does_not_exist", make_ctx(tmp_path, policy))

    def test_pipeline_rejects_a_typoed_check_at_load_time(self, tmp_path: Path) -> None:
        """The expensive alternative is discovering it mid-run."""
        bad = tmp_path / "bad.yaml"
        bad.write_text(
            yaml.safe_dump(
                {
                    "version": 1,
                    "nodes": [
                        {
                            "id": "a",
                            "prompt": "p.md",
                            "exit_gates": [{"check": "maven_compilez"}],
                        }
                    ],
                }
            )
        )
        with pytest.raises(PipelineError, match="is not implemented"):
            load_pipeline(bad)

    def test_real_pipeline_uses_only_implemented_checks(self) -> None:
        pipeline = load_pipeline(
            Path(__file__).resolve().parents[2] / "orchestrator/pipelines/sdlc.yaml"
        )
        used = {
            g.check
            for n in pipeline.nodes
            for g in (*n.entry_gates, *n.exit_gates)
        }
        assert used <= gates.known_checks()

    def test_on_fail_escalate_converts_a_failure_to_a_pause(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        """This is how `review` is made structurally incapable of failing a run."""
        write_artifact(
            tmp_path,
            "review.json",
            {"summary": "s", "findings": [{"id": "F1", "severity": "blocker", "file": "A.java", "summary": "npe"}]},
        )
        spec = GateSpec(
            check="blocker_findings_escalate",
            gate_class=GateClass.SELF_REPORT,
            on_fail=GateOutcome.ESCALATE,
        )
        result = gates.evaluate(spec, make_ctx(tmp_path, policy))
        assert result.outcome is ESCALATE
        assert "not failed" in result.detail

    def test_worst_ranks_fail_over_escalate_over_pass(self, tmp_path: Path, policy: Policy) -> None:
        ctx = make_ctx(tmp_path, policy)
        (tmp_path / "here.txt").write_text("x")
        ok = run("artifact_present", ctx, path="here.txt")
        bad = run("artifact_present", ctx, path="gone.txt")
        assert gates.worst([ok]) is PASS
        assert gates.worst([ok, bad]) is FAIL

    def test_evaluate_all_reports_every_failure_not_just_the_first(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        ctx = make_ctx(tmp_path, policy)
        specs = (
            GateSpec(check="artifact_present", gate_class=GateClass.MECHANICAL, params={"path": "a.txt"}),
            GateSpec(check="artifact_present", gate_class=GateClass.MECHANICAL, params={"path": "b.txt"}),
        )
        results = gates.evaluate_all(specs, ctx)
        assert [r.outcome for r in results] == [FAIL, FAIL]


# --------------------------------------------------------------------------
# Artifacts and schemas
# --------------------------------------------------------------------------


class TestSchemaValid:
    def _clarification(self, **overrides):
        base = {
            "assumptions": [{"id": "A1", "statement": "s", "rationale": "r"}],
            "ambiguities": [],
        }
        return {**base, **overrides}

    def test_conforming_artifact_passes(self, tmp_path: Path, policy: Policy) -> None:
        write_artifact(tmp_path, "clarification.json", self._clarification())
        assert run("schema_valid", make_ctx(tmp_path, policy), artifact="clarification.json").outcome is PASS

    def test_missing_required_field_fails(self, tmp_path: Path, policy: Policy) -> None:
        write_artifact(tmp_path, "clarification.json", {"ambiguities": []})
        result = run("schema_valid", make_ctx(tmp_path, policy), artifact="clarification.json")
        assert result.outcome is FAIL
        assert "assumptions" in result.detail

    def test_unexpected_field_fails(self, tmp_path: Path, policy: Policy) -> None:
        """Drift between prompt and contract is cheapest to catch immediately."""
        write_artifact(
            tmp_path, "clarification.json", self._clarification(confidence="high")
        )
        assert run("schema_valid", make_ctx(tmp_path, policy), artifact="clarification.json").outcome is FAIL

    def test_malformed_json_fails_without_raising(self, tmp_path: Path, policy: Policy) -> None:
        write_artifact(tmp_path, "clarification.json", "{not json")
        result = run("schema_valid", make_ctx(tmp_path, policy), artifact="clarification.json")
        assert result.outcome is FAIL and "not valid JSON" in result.detail

    def test_absent_artifact_fails(self, tmp_path: Path, policy: Policy) -> None:
        assert run("schema_valid", make_ctx(tmp_path, policy), artifact="plan.json").outcome is FAIL


class TestPathsConfined:
    def test_writes_inside_the_allowlist_pass(self, tmp_path: Path, policy: Policy) -> None:
        ctx = make_ctx(tmp_path, policy, files_written=("service/src/main/java/A.java",))
        assert run("paths_confined", ctx).outcome is PASS

    def test_a_write_outside_the_allowlist_fails(self, tmp_path: Path, policy: Policy) -> None:
        ctx = make_ctx(tmp_path, policy, files_written=("orchestrator/sdlc/gates.py",))
        result = run("paths_confined", ctx)
        assert result.outcome is FAIL
        assert result.evidence["violations"] == ["orchestrator/sdlc/gates.py"]

    def test_a_protected_write_escalates_rather_than_failing(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        """Adding a dependency is allowed -- but a person signs off on it."""
        ctx = make_ctx(tmp_path, policy, files_written=("service/pom.xml",))
        result = run("paths_confined", ctx)
        assert result.outcome is ESCALATE
        assert result.evidence["protected"] == ["service/pom.xml"]

    def test_catches_a_denied_write_the_callback_never_saw(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        """A Bash heredoc bypasses can_use_tool entirely; the diff still shows it."""
        ctx = make_ctx(
            tmp_path,
            policy,
            write_paths=("service/src/main/**",),
            deny_paths=("service/src/test/**",),
            files_written=("service/src/test/java/AppTest.java",),
        )
        assert run("paths_confined", ctx).outcome is FAIL


class TestPathsConfinedDiffsTheWorktree:
    """The layer that does not take the backend's word for it.

    Everything above reaches the gate as `NodeResult.files_written`, which is
    the backend's account of its own behaviour. For the live backend that
    account is already derived from git -- but a control that depends on the
    audited party assembling its own evidence correctly is a control with a
    hole in it. These run against a real repository: the gate goes and looks.
    """

    @staticmethod
    def _repo(tmp_path: Path) -> Path:
        root = tmp_path / "workspace"
        root.mkdir()
        git = Git(root=root)
        git._git("init", "-b", "main")
        git._git("config", "user.email", "o@example.com")
        git._git("config", "user.name", "SDLC")
        (root / "README.md").write_text("# workspace\n")
        git._git("add", "-A")
        git._git("commit", "-m", "initial")
        return root

    @staticmethod
    def _ctx(root: Path, policy: Policy, **kw) -> GateContext:
        ctx = make_ctx(root, policy, **kw)
        ctx.run = subprocess_runner  # a real repository, really diffed
        return ctx

    def test_an_unreported_forbidden_write_is_still_caught(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        """The heredoc case, with a backend that reports nothing at all.

        `files_written=()` is what a crashed session, a buggy backend, or a
        node that wrote through a shell leaves behind. If that is enough to
        pass the gate, the diff layer is decorative.
        """
        root = self._repo(tmp_path)
        (root / "orchestrator").mkdir()
        (root / "orchestrator" / "gates.py").write_text("# smuggled past the callback\n")

        result = run("paths_confined", self._ctx(root, policy, files_written=()))
        assert result.outcome is FAIL
        assert result.evidence["violations"] == ["orchestrator/gates.py"]

    def test_an_unreported_protected_write_still_escalates(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        root = self._repo(tmp_path)
        (root / "service").mkdir()
        (root / "service" / "pom.xml").write_text("<project/>\n")

        result = run("paths_confined", self._ctx(root, policy, files_written=()))
        assert result.outcome is ESCALATE
        assert result.evidence["protected"] == ["service/pom.xml"]

    def test_a_clean_tree_passes(self, tmp_path: Path, policy: Policy) -> None:
        root = self._repo(tmp_path)
        assert run("paths_confined", self._ctx(root, policy)).outcome is PASS

    def test_secrets_are_scanned_in_files_the_node_never_admitted_to(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        root = self._repo(tmp_path)
        target = root / "service/src/main/java/Cfg.java"
        target.parent.mkdir(parents=True)
        target.write_text('String k = "AKIAIOSFODNN7EXAMPLE";')

        result = run("no_secrets", self._ctx(root, policy, files_written=()))
        assert result.outcome is FAIL
        assert "AKIAIOSFODNN7EXAMPLE" not in result.detail

    def test_the_self_report_is_still_honoured_where_git_cannot_see(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        """Union, not replacement.

        A workspace that is not a repository (or a file already committed by an
        earlier attempt) leaves git with nothing to say. The backend's account
        is weaker evidence, not worthless evidence, so it is added to the diff
        rather than replaced by it.
        """
        ctx = make_ctx(tmp_path, policy, files_written=("orchestrator/sdlc/gates.py",))
        assert run("paths_confined", ctx).outcome is FAIL


class TestCriteriaCovered:
    """The quietest failure in the pipeline: a criterion nobody planned for.

    Everything downstream measures the work against the plan. If the plan drops
    a criterion, the build is green, the tests pass, and the thing the requester
    asked for was never built.
    """

    def _setup(self, tmp_path: Path, criteria: list[str], claimed: list[list[str]]) -> None:
        write_artifact(
            tmp_path,
            "requirement.json",
            {"acceptance_criteria": [{"id": c, "statement": c} for c in criteria]},
        )
        write_artifact(
            tmp_path,
            "plan.json",
            {
                "tasks": [
                    {"id": f"T{i}", "title": "t", "depends_on": [],
                     "deliverables": [], "acceptance_criteria_ids": ids}
                    for i, ids in enumerate(claimed)
                ]
            },
        )

    def test_full_coverage_passes(self, tmp_path: Path, policy: Policy) -> None:
        self._setup(tmp_path, ["AC1", "AC2"], [["AC1"], ["AC2"]])
        result = run("criteria_covered", make_ctx(tmp_path, policy))
        assert result.outcome is PASS
        assert result.evidence["criteria"] == 2

    def test_an_uncovered_criterion_fails(self, tmp_path: Path, policy: Policy) -> None:
        self._setup(tmp_path, ["AC1", "AC2", "AC3"], [["AC1"], ["AC3"]])
        result = run("criteria_covered", make_ctx(tmp_path, policy))
        assert result.outcome is FAIL
        assert result.evidence["uncovered"] == ["AC2"]

    def test_a_reference_to_a_nonexistent_criterion_fails(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        """A typo here silently uncovers the criterion it meant to claim."""
        self._setup(tmp_path, ["AC1"], [["AC1"], ["AC7"]])
        result = run("criteria_covered", make_ctx(tmp_path, policy))
        assert result.outcome is FAIL
        assert result.evidence["unknown"] == ["AC7"]

    def test_one_task_may_cover_several_criteria(self, tmp_path: Path, policy: Policy) -> None:
        self._setup(tmp_path, ["AC1", "AC2"], [["AC1", "AC2"]])
        assert run("criteria_covered", make_ctx(tmp_path, policy)).outcome is PASS


class TestLensFindingsPreserved:
    """The gate that makes fanning review out worth doing.

    Five independent lenses stop one reviewer's blind spot deciding what ships.
    Funnelling them back through a single summarising model hands that power
    straight back unless something checks the join -- and a dropped finding
    looks exactly like a tidy summary.
    """

    LENSES = ("review-security", "review-test-adequacy")

    def _lens(self, tmp_path: Path, name: str, findings: list[dict]) -> None:
        write_artifact(
            tmp_path,
            f"{name}.json",
            {"lens": name, "findings": findings, "summary": "", "not_examined": []},
        )

    def _setup(self, tmp_path: Path, review: dict, **lenses: list[dict]) -> None:
        for name in self.LENSES:
            self._lens(tmp_path, name, lenses.get(name.replace("-", "_"), []))
        write_artifact(tmp_path, "review.json", review)

    def test_a_faithful_join_passes(self, tmp_path: Path, policy: Policy) -> None:
        self._setup(
            tmp_path,
            {
                "findings": [
                    {"id": "SEC-1", "lens": "review-security", "severity": "major",
                     "file": "A.java", "summary": "open redirect"},
                    {"id": "TEST-1", "lens": "review-test-adequacy", "severity": "minor",
                     "file": "AT.java", "summary": "no expiry test"},
                ],
                "summary": "one real issue",
            },
            review_security=[{"id": "SEC-1", "severity": "major", "confidence": "high",
                              "file": "A.java", "summary": "open redirect"}],
            review_test_adequacy=[{"id": "TEST-1", "severity": "minor", "confidence": "high",
                                   "file": "AT.java", "summary": "no expiry test"}],
        )
        result = run(
            "lens_findings_preserved", make_ctx(tmp_path, policy), lenses=list(self.LENSES)
        )
        assert result.outcome is PASS
        assert result.evidence["lens_findings"] == 2

    def test_a_dropped_finding_fails(self, tmp_path: Path, policy: Policy) -> None:
        self._setup(
            tmp_path,
            {"findings": [], "summary": "looks clean to me"},
            review_security=[{"id": "SEC-1", "severity": "blocker", "confidence": "medium",
                              "file": "A.java", "summary": "credentials in config"}],
        )
        result = run(
            "lens_findings_preserved", make_ctx(tmp_path, policy), lenses=list(self.LENSES)
        )
        assert result.outcome is FAIL
        assert result.evidence["missing"] == ["SEC-1 (review-security, blocker)"]

    def test_a_downgraded_finding_fails(self, tmp_path: Path, policy: Policy) -> None:
        """Softening is dropping with extra steps."""
        self._setup(
            tmp_path,
            {
                "findings": [
                    {"id": "SEC-1", "lens": "review-security", "severity": "nit",
                     "file": "A.java", "summary": "credentials in config"}
                ],
                "summary": "minor tidy-ups only",
            },
            review_security=[{"id": "SEC-1", "severity": "blocker", "confidence": "high",
                              "file": "A.java", "summary": "credentials in config"}],
        )
        result = run(
            "lens_findings_preserved", make_ctx(tmp_path, policy), lenses=list(self.LENSES)
        )
        assert result.outcome is FAIL
        assert result.evidence["downgraded"] == ["SEC-1: blocker -> nit"]

    def test_merging_two_lenses_onto_one_finding_is_allowed(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        """Convergence is a signal. Clustering it is judgement, not loss."""
        self._setup(
            tmp_path,
            {
                "findings": [
                    {
                        "id": "SEC-1",
                        "lens": "review-security",
                        "severity": "blocker",
                        "file": "A.java",
                        "summary": "two lenses converged on the unvalidated target",
                        "merged_ids": ["TEST-1"],
                    }
                ],
                "summary": "one issue, found twice",
            },
            review_security=[{"id": "SEC-1", "severity": "blocker", "confidence": "high",
                              "file": "A.java", "summary": "unvalidated redirect target"}],
            review_test_adequacy=[{"id": "TEST-1", "severity": "major", "confidence": "high",
                                   "file": "A.java", "summary": "nothing pins the validation"}],
        )
        assert (
            run("lens_findings_preserved", make_ctx(tmp_path, policy), lenses=list(self.LENSES)).outcome
            is PASS
        )

    def test_a_merge_may_not_soften_what_it_absorbs(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        self._setup(
            tmp_path,
            {
                "findings": [
                    {"id": "TEST-1", "lens": "review-test-adequacy", "severity": "minor",
                     "file": "A.java", "summary": "folded in", "merged_ids": ["SEC-1"]}
                ],
                "summary": "",
            },
            review_security=[{"id": "SEC-1", "severity": "blocker", "confidence": "high",
                              "file": "A.java", "summary": "unvalidated redirect target"}],
            review_test_adequacy=[{"id": "TEST-1", "severity": "minor", "confidence": "high",
                                   "file": "A.java", "summary": "nothing pins the validation"}],
        )
        result = run(
            "lens_findings_preserved", make_ctx(tmp_path, policy), lenses=list(self.LENSES)
        )
        assert result.outcome is FAIL
        assert result.evidence["downgraded"] == ["SEC-1: blocker -> minor"]

    def test_an_unreadable_lens_artifact_fails_rather_than_passing(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        """Unverifiable is not the same as verified."""
        write_artifact(tmp_path, "review.json", {"findings": [], "summary": ""})
        result = run(
            "lens_findings_preserved", make_ctx(tmp_path, policy), lenses=["review-security"]
        )
        assert result.outcome is FAIL
        assert "cannot verify the join" in result.detail


class TestNoSecrets:
    def test_flags_a_credential_in_a_written_file(self, tmp_path: Path, policy: Policy) -> None:
        target = tmp_path / "service/src/main/java/Cfg.java"
        target.parent.mkdir(parents=True)
        target.write_text('String k = "AKIAIOSFODNN7EXAMPLE";')
        ctx = make_ctx(tmp_path, policy, files_written=("service/src/main/java/Cfg.java",))
        result = run("no_secrets", ctx)
        assert result.outcome is FAIL
        assert "AKIAIOSFODNN7EXAMPLE" not in result.detail  # never echo the secret

    def test_clean_files_pass(self, tmp_path: Path, policy: Policy) -> None:
        target = tmp_path / "service/src/main/java/Cfg.java"
        target.parent.mkdir(parents=True)
        target.write_text("int x = 1;")
        ctx = make_ctx(tmp_path, policy, files_written=("service/src/main/java/Cfg.java",))
        assert run("no_secrets", ctx).outcome is PASS


# --------------------------------------------------------------------------
# Clarification and planning
# --------------------------------------------------------------------------


class TestClarificationGates:
    def test_empty_assumptions_fail(self, tmp_path: Path, policy: Policy) -> None:
        """ADR-001: 'nothing was ambiguous' is not the same as 'nothing was chosen'."""
        write_artifact(tmp_path, "clarification.json", {"assumptions": [], "ambiguities": []})
        result = run("assumptions_present", make_ctx(tmp_path, policy))
        assert result.outcome is FAIL
        assert "silently" in result.detail

    def test_declared_assumptions_pass_and_are_recorded(self, tmp_path: Path, policy: Policy) -> None:
        write_artifact(
            tmp_path,
            "clarification.json",
            {"assumptions": [{"id": "A1", "statement": "302 not 301", "rationale": "analytics"}], "ambiguities": []},
        )
        result = run("assumptions_present", make_ctx(tmp_path, policy))
        assert result.outcome is PASS and result.evidence["assumption_ids"] == ["A1"]

    def test_blocking_ambiguity_fails_the_predicate(self, tmp_path: Path, policy: Policy) -> None:
        write_artifact(
            tmp_path,
            "clarification.json",
            {
                "assumptions": [],
                "ambiguities": [
                    {"id": "Q1", "question": "what does reliable mean?", "severity": "blocking", "proposed_answer": "?"}
                ],
            },
        )
        result = run("no_blocking_ambiguities", make_ctx(tmp_path, policy))
        assert result.outcome is FAIL and result.evidence["ambiguity_ids"] == ["Q1"]

    def test_advisory_ambiguity_does_not_block(self, tmp_path: Path, policy: Policy) -> None:
        write_artifact(
            tmp_path,
            "clarification.json",
            {
                "assumptions": [],
                "ambiguities": [{"id": "Q1", "question": "TTL default?", "severity": "advisory", "proposed_answer": "30d"}],
            },
        )
        assert run("no_blocking_ambiguities", make_ctx(tmp_path, policy)).outcome is PASS


class TestUnresolvedAmbiguities:
    """The entry gate on `decompose`. Mechanical: it reads the human's answers."""

    def _seed(self, tmp_path: Path) -> None:
        write_artifact(
            tmp_path,
            "clarification.json",
            {
                "assumptions": [],
                "ambiguities": [
                    {"id": "Q1", "question": "reliability?", "severity": "blocking", "proposed_answer": "?"},
                    {"id": "Q2", "question": "analytics depth?", "severity": "blocking", "proposed_answer": "?"},
                ],
            },
        )

    def test_planning_cannot_start_with_unanswered_questions(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        self._seed(tmp_path)
        result = run("no_unresolved_ambiguities", make_ctx(tmp_path, policy))
        assert result.outcome is FAIL
        assert result.evidence["unresolved"] == ["Q1", "Q2"]

    def test_a_partial_answer_is_still_unresolved(self, tmp_path: Path, policy: Policy) -> None:
        self._seed(tmp_path)
        approval = Approval("clarify", ApprovalDecision.APPROVED, "neil", answers={"Q1": "rate limiting"})
        ctx = make_ctx(tmp_path, policy, approvals={"clarify": approval})
        assert run("no_unresolved_ambiguities", ctx).evidence["unresolved"] == ["Q2"]

    def test_fully_answered_passes(self, tmp_path: Path, policy: Policy) -> None:
        self._seed(tmp_path)
        approval = Approval(
            "clarify", ApprovalDecision.APPROVED, "neil", answers={"Q1": "rate limiting", "Q2": "click counts only"}
        )
        ctx = make_ctx(tmp_path, policy, approvals={"clarify": approval})
        assert run("no_unresolved_ambiguities", ctx).outcome is PASS

    def test_nothing_blocking_needs_no_approval(self, tmp_path: Path, policy: Policy) -> None:
        write_artifact(tmp_path, "clarification.json", {"assumptions": [], "ambiguities": []})
        assert run("no_unresolved_ambiguities", make_ctx(tmp_path, policy)).outcome is PASS


class TestPlanIsDag:
    def test_valid_plan_passes(self, tmp_path: Path, policy: Policy) -> None:
        write_artifact(
            tmp_path,
            "plan.json",
            {"tasks": [{"id": "T1", "depends_on": []}, {"id": "T2", "depends_on": ["T1"]}]},
        )
        assert run("plan_is_dag", make_ctx(tmp_path, policy)).outcome is PASS

    def test_cycle_fails(self, tmp_path: Path, policy: Policy) -> None:
        write_artifact(
            tmp_path,
            "plan.json",
            {"tasks": [{"id": "T1", "depends_on": ["T2"]}, {"id": "T2", "depends_on": ["T1"]}]},
        )
        result = run("plan_is_dag", make_ctx(tmp_path, policy))
        assert result.outcome is FAIL and "cycle" in result.detail

    def test_dangling_dependency_fails(self, tmp_path: Path, policy: Policy) -> None:
        write_artifact(tmp_path, "plan.json", {"tasks": [{"id": "T1", "depends_on": ["T9"]}]})
        result = run("plan_is_dag", make_ctx(tmp_path, policy))
        assert result.outcome is FAIL and "unknown task 'T9'" in result.detail

    def test_duplicate_ids_fail(self, tmp_path: Path, policy: Policy) -> None:
        write_artifact(
            tmp_path, "plan.json", {"tasks": [{"id": "T1", "depends_on": []}, {"id": "T1", "depends_on": []}]}
        )
        assert run("plan_is_dag", make_ctx(tmp_path, policy)).outcome is FAIL


# --------------------------------------------------------------------------
# Contract
# --------------------------------------------------------------------------

MINIMAL_OPENAPI = {
    "openapi": "3.0.3",
    "info": {"title": "Link API", "version": "1"},
    "paths": {
        "/api/v1/links": {
            "post": {"operationId": "createLink", "responses": {"201": {"description": "ok"}}}
        },
        "/{code}": {
            "get": {"operationId": "redirect", "responses": {"302": {"description": "found"}}}
        },
    },
}


def write_openapi(tmp_path: Path, doc: dict) -> None:
    target = tmp_path / "artifacts" / "openapi.yaml"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(yaml.safe_dump(doc))


class TestOpenApiLints:
    def test_clean_document_passes(self, tmp_path: Path, policy: Policy) -> None:
        write_openapi(tmp_path, MINIMAL_OPENAPI)
        assert run("openapi_lints", make_ctx(tmp_path, policy), artifact="openapi.yaml").outcome is PASS

    def test_missing_operation_id_fails(self, tmp_path: Path, policy: Policy) -> None:
        """author-tests is written blind against operationIds; they cannot be optional."""
        doc = json.loads(json.dumps(MINIMAL_OPENAPI))
        del doc["paths"]["/api/v1/links"]["post"]["operationId"]
        write_openapi(tmp_path, doc)
        result = run("openapi_lints", make_ctx(tmp_path, policy), artifact="openapi.yaml")
        assert result.outcome is FAIL and "missing operationId" in result.detail

    def test_duplicate_operation_id_fails(self, tmp_path: Path, policy: Policy) -> None:
        doc = json.loads(json.dumps(MINIMAL_OPENAPI))
        doc["paths"]["/{code}"]["get"]["operationId"] = "createLink"
        write_openapi(tmp_path, doc)
        assert run("openapi_lints", make_ctx(tmp_path, policy), artifact="openapi.yaml").outcome is FAIL

    def test_operation_without_responses_fails(self, tmp_path: Path, policy: Policy) -> None:
        doc = json.loads(json.dumps(MINIMAL_OPENAPI))
        doc["paths"]["/{code}"]["get"]["responses"] = {}
        write_openapi(tmp_path, doc)
        assert run("openapi_lints", make_ctx(tmp_path, policy), artifact="openapi.yaml").outcome is FAIL

    def test_absent_document_fails(self, tmp_path: Path, policy: Policy) -> None:
        assert run("openapi_lints", make_ctx(tmp_path, policy), artifact="openapi.yaml").outcome is FAIL


class TestContractFrozen:
    def _seed(self, tmp_path: Path) -> Path:
        contract = tmp_path / "service/src/main/java/Link.java"
        contract.parent.mkdir(parents=True)
        contract.write_text("record Link(String code) {}")
        return contract

    def _design(self, tmp_path: Path, contract_hash: str | None) -> None:
        payload = {
            "openapi_path": "artifacts/openapi.yaml",
            "contract_files": ["service/src/main/java/Link.java"],
            "endpoints": [],
            "rationale": "r",
        }
        if contract_hash is not None:
            payload["contract_hash"] = contract_hash
        write_artifact(tmp_path, "design.json", payload)

    def test_intact_contract_passes(self, tmp_path: Path, policy: Policy) -> None:
        from sdlc.audit import hash_inputs

        contract = self._seed(tmp_path)
        self._design(tmp_path, hash_inputs([contract], root=tmp_path))
        assert run("contract_frozen", make_ctx(tmp_path, policy)).outcome is PASS

    def test_a_modified_contract_fails(self, tmp_path: Path, policy: Policy) -> None:
        """Blind parallel authoring is only valid while both sides see the same bytes."""
        from sdlc.audit import hash_inputs

        contract = self._seed(tmp_path)
        self._design(tmp_path, hash_inputs([contract], root=tmp_path))
        contract.write_text("record Link(String code, long clicks) {}")
        result = run("contract_frozen", make_ctx(tmp_path, policy))
        assert result.outcome is FAIL and "has changed since design froze it" in result.detail

    def test_missing_contract_file_fails(self, tmp_path: Path, policy: Policy) -> None:
        self._design(tmp_path, None)
        result = run("contract_frozen", make_ctx(tmp_path, policy))
        assert result.outcome is FAIL and "absent from the worktree" in result.detail

    def test_the_hash_survives_the_move_into_a_worktree(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        """The contract is frozen in the main checkout and verified in two
        worktrees at different absolute paths. If the digest depended on where
        the files sit, both branches would report a mismatch caused by nothing
        but their own directory name -- and the gate that exists to catch a
        drifted contract would fail every run instead."""
        import shutil

        from sdlc.audit import hash_inputs

        contract = self._seed(tmp_path)
        self._design(tmp_path, hash_inputs([contract], root=tmp_path))

        worktree = tmp_path.parent / "worktree-implement"
        shutil.copytree(tmp_path, worktree)
        assert run("contract_frozen", make_ctx(worktree, policy)).outcome is PASS


# --------------------------------------------------------------------------
# Build gates
# --------------------------------------------------------------------------


class TestMavenGates:
    def test_compile_passes_on_exit_zero(self, tmp_path: Path, policy: Policy) -> None:
        ctx = make_ctx(tmp_path, policy, runner=lambda argv, cwd, timeout=1800.0: CommandResult(0))
        assert run("maven_compiles", ctx).outcome is PASS

    def test_compile_fails_and_reports_the_tail_of_the_log(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        noise = "\n".join(f"[INFO] line {i}" for i in range(200))

        def runner(argv, cwd, timeout=1800.0):
            return CommandResult(1, noise, "[ERROR] cannot find symbol: LinkService")

        result = run("maven_compiles", make_ctx(tmp_path, policy, runner=runner))
        assert result.outcome is FAIL
        assert "cannot find symbol" in result.detail
        assert "line 0" not in result.detail  # the log head is not dragged along

    def test_verify_uses_the_full_lifecycle(self, tmp_path: Path, policy: Policy) -> None:
        seen: list[list[str]] = []

        def runner(argv, cwd, timeout=1800.0):
            seen.append(argv)
            return CommandResult(0)

        run("maven_verify", make_ctx(tmp_path, policy, runner=runner))
        assert seen[0][-1] == "verify"

    def test_test_compile_does_not_run_tests(self, tmp_path: Path, policy: Policy) -> None:
        """author-tests writes blind, so its tests cannot pass yet -- only compile."""
        seen: list[list[str]] = []

        def runner(argv, cwd, timeout=1800.0):
            seen.append(argv)
            return CommandResult(0)

        run("tests_compile", make_ctx(tmp_path, policy, runner=runner))
        assert seen[0][-1] == "test-compile"


JACOCO_HEADER = (
    "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,"
    "BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,"
    "COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED"
)


def write_jacoco(tmp_path: Path, rows: list[tuple[int, int]]) -> None:
    report = tmp_path / "service/target/site/jacoco/jacoco.csv"
    report.parent.mkdir(parents=True, exist_ok=True)
    lines = [JACOCO_HEADER]
    for i, (missed, covered) in enumerate(rows):
        lines.append(f"svc,com.example,C{i},0,0,0,0,{missed},{covered},0,0,0,0")
    report.write_text("\n".join(lines) + "\n")


class TestCoverageFloor:
    def test_above_the_floor_passes(self, tmp_path: Path, policy: Policy) -> None:
        write_jacoco(tmp_path, [(10, 90)])
        result = run("coverage_floor", make_ctx(tmp_path, policy), min_line_coverage=0.70)
        assert result.outcome is PASS and result.evidence["line_coverage"] == 0.90

    def test_below_the_floor_fails_with_the_number_on_record(
        self, tmp_path: Path, policy: Policy
    ) -> None:
        write_jacoco(tmp_path, [(50, 50)])
        result = run("coverage_floor", make_ctx(tmp_path, policy), min_line_coverage=0.70)
        assert result.outcome is FAIL
        assert "50.0%" in result.detail and result.evidence["floor"] == 0.70

    def test_aggregates_across_classes(self, tmp_path: Path, policy: Policy) -> None:
        write_jacoco(tmp_path, [(0, 80), (20, 0)])
        result = run("coverage_floor", make_ctx(tmp_path, policy), min_line_coverage=0.70)
        assert result.evidence["lines_total"] == 100 and result.outcome is PASS

    def test_a_missing_report_is_a_failure_not_a_pass(self, tmp_path: Path, policy: Policy) -> None:
        """No evidence is not the same as no problem."""
        result = run("coverage_floor", make_ctx(tmp_path, policy))
        assert result.outcome is FAIL and "cannot be evidenced" in result.detail


# --------------------------------------------------------------------------
# Cross-artifact consistency
# --------------------------------------------------------------------------


def write_controllers(tmp_path: Path, stats_path: str = "/{code}/stats") -> None:
    root = tmp_path / "service/src/main/java/com/example"
    root.mkdir(parents=True, exist_ok=True)
    (root / "LinkController.java").write_text(
        f"""
package com.example;

@RestController
@RequestMapping("/api/v1/links")
class LinkController {{
    @PostMapping
    ResponseEntity<LinkResponse> create(@RequestBody CreateLinkRequest req) {{ return null; }}

    @GetMapping("{stats_path}")
    StatsResponse stats(@PathVariable String code) {{ return null; }}
}}
"""
    )
    (root / "RedirectController.java").write_text(
        """
package com.example;

@RestController
class RedirectController {
    @GetMapping("/{code}")
    ResponseEntity<Void> redirect(@PathVariable String code) { return null; }
}
"""
    )


ROUTES_OPENAPI = {
    "openapi": "3.0.3",
    "info": {"title": "Link API", "version": "1"},
    "paths": {
        "/api/v1/links": {"post": {"operationId": "createLink", "responses": {"201": {}}}},
        "/api/v1/links/{code}/stats": {"get": {"operationId": "stats", "responses": {"200": {}}}},
        "/{code}": {"get": {"operationId": "redirect", "responses": {"302": {}}}},
    },
}


class TestRoutesMatchOpenapi:
    def test_matching_routes_pass(self, tmp_path: Path, policy: Policy) -> None:
        write_openapi(tmp_path, ROUTES_OPENAPI)
        write_controllers(tmp_path)
        result = run("routes_match_openapi", make_ctx(tmp_path, policy), artifact="openapi.yaml")
        assert result.outcome is PASS, result.detail

    def test_path_variable_names_do_not_have_to_match(self, tmp_path: Path, policy: Policy) -> None:
        """`{code}` in the spec and `{shortCode}` in Java are the same route."""
        write_openapi(tmp_path, ROUTES_OPENAPI)
        write_controllers(tmp_path, stats_path="/{shortCode}/stats")
        assert run("routes_match_openapi", make_ctx(tmp_path, policy), artifact="openapi.yaml").outcome is PASS

    def test_a_silently_renamed_endpoint_is_caught(self, tmp_path: Path, policy: Policy) -> None:
        """Otherwise this surfaces as an inexplicable 404 in a blind-written test."""
        write_openapi(tmp_path, ROUTES_OPENAPI)
        write_controllers(tmp_path, stats_path="/{code}/statistics")
        result = run("routes_match_openapi", make_ctx(tmp_path, policy), artifact="openapi.yaml")
        assert result.outcome is FAIL
        assert "GET /api/v1/links/{}/stats" in result.evidence["unimplemented"]
        assert "GET /api/v1/links/{}/statistics" in result.evidence["undocumented"]

    def test_missing_sources_fail(self, tmp_path: Path, policy: Policy) -> None:
        write_openapi(tmp_path, ROUTES_OPENAPI)
        assert run("routes_match_openapi", make_ctx(tmp_path, policy), artifact="openapi.yaml").outcome is FAIL


class TestLinksResolve:
    def _doc(self, tmp_path: Path, body: str) -> None:
        (tmp_path / "docs").mkdir(parents=True, exist_ok=True)
        (tmp_path / "docs/README.md").write_text(body)

    def test_resolving_links_pass(self, tmp_path: Path, policy: Policy) -> None:
        self._doc(tmp_path, "See [the spec](openapi.yaml) and [home](https://example.com).")
        (tmp_path / "docs/openapi.yaml").write_text("openapi: 3.0.3")
        ctx = make_ctx(tmp_path, policy, files_written=("docs/README.md",))
        assert run("links_resolve", ctx).outcome is PASS

    def test_an_invented_document_is_caught(self, tmp_path: Path, policy: Policy) -> None:
        self._doc(tmp_path, "See the [runbook](runbook.md).")
        ctx = make_ctx(tmp_path, policy, files_written=("docs/README.md",))
        result = run("links_resolve", ctx)
        assert result.outcome is FAIL and "runbook.md" in result.detail

    def test_external_links_are_not_this_gates_business(self, tmp_path: Path, policy: Policy) -> None:
        self._doc(tmp_path, "[spring](https://spring.io) and [anchor](#usage)")
        ctx = make_ctx(tmp_path, policy, files_written=("docs/README.md",))
        assert run("links_resolve", ctx).outcome is PASS


# --------------------------------------------------------------------------
# Merge, review, approval
# --------------------------------------------------------------------------


class TestMergeClean:
    def test_clean_merge_passes(self, tmp_path: Path, policy: Policy) -> None:
        ctx = make_ctx(tmp_path, policy, output={"merged_branches": ["implement", "tests"]})
        assert run("merge_clean", ctx).outcome is PASS

    def test_conflicts_fail_the_predicate(self, tmp_path: Path, policy: Policy) -> None:
        ctx = make_ctx(tmp_path, policy, output={"conflicts": ["service/pom.xml"]})
        result = run("merge_clean", ctx)
        assert result.outcome is FAIL and result.evidence["conflicts"] == ["service/pom.xml"]

    def test_a_conflict_escalates_under_the_real_spec(self, tmp_path: Path, policy: Policy) -> None:
        spec = GateSpec(check="merge_clean", gate_class=GateClass.MECHANICAL, on_fail=GateOutcome.ESCALATE)
        ctx = make_ctx(tmp_path, policy, output={"conflicts": ["A.java"]})
        assert gates.evaluate(spec, ctx).outcome is ESCALATE


SPOTBUGS_XML = """<?xml version="1.0"?>
<BugCollection>
  <BugInstance type="{type}" priority="{priority}"/>
</BugCollection>
"""


class TestStaticAnalysis:
    def _report(self, tmp_path: Path, xml: str) -> None:
        report = tmp_path / "service/target/spotbugsXml.xml"
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(xml)

    def test_high_priority_finding_fails(self, tmp_path: Path, policy: Policy) -> None:
        self._report(tmp_path, SPOTBUGS_XML.format(type="SQL_INJECTION_JDBC", priority=1))
        result = run("static_analysis", make_ctx(tmp_path, policy), max_severity="high")
        assert result.outcome is FAIL and "SQL_INJECTION_JDBC" in result.detail

    def test_low_priority_finding_passes_a_high_threshold(self, tmp_path: Path, policy: Policy) -> None:
        self._report(tmp_path, SPOTBUGS_XML.format(type="DM_DEFAULT_ENCODING", priority=3))
        result = run("static_analysis", make_ctx(tmp_path, policy), max_severity="high")
        assert result.outcome is PASS and result.evidence["counts"]["low"] == 1

    def test_threshold_is_configurable(self, tmp_path: Path, policy: Policy) -> None:
        self._report(tmp_path, SPOTBUGS_XML.format(type="DM_DEFAULT_ENCODING", priority=2))
        assert run("static_analysis", make_ctx(tmp_path, policy), max_severity="medium").outcome is FAIL

    def test_a_missing_report_fails(self, tmp_path: Path, policy: Policy) -> None:
        assert run("static_analysis", make_ctx(tmp_path, policy)).outcome is FAIL

    def test_unknown_severity_is_a_configuration_error(self, tmp_path: Path, policy: Policy) -> None:
        with pytest.raises(GateError, match="unknown max_severity"):
            run("static_analysis", make_ctx(tmp_path, policy), max_severity="critical")


class TestBlockerFindings:
    def test_advisory_findings_pass(self, tmp_path: Path, policy: Policy) -> None:
        write_artifact(
            tmp_path,
            "review.json",
            {"summary": "ok", "findings": [{"id": "F1", "severity": "minor", "file": "A.java", "summary": "naming"}]},
        )
        result = run("blocker_findings_escalate", make_ctx(tmp_path, policy))
        assert result.outcome is PASS and result.evidence["total_findings"] == 1

    def test_a_blocker_names_the_finding_for_the_human(self, tmp_path: Path, policy: Policy) -> None:
        write_artifact(
            tmp_path,
            "review.json",
            {"summary": "x", "findings": [{"id": "F1", "severity": "blocker", "file": "LinkService.java", "summary": "SSRF"}]},
        )
        result = run("blocker_findings_escalate", make_ctx(tmp_path, policy))
        assert result.outcome is FAIL and "SSRF" in result.detail


class TestHumanApproval:
    def test_no_decision_on_file_pauses_rather_than_fails(self, tmp_path: Path, policy: Policy) -> None:
        """The difference between a checkpoint and a dead run."""
        result = run("human_approval", make_ctx(tmp_path, policy), reason="Frozen contract review")
        assert result.outcome is ESCALATE and result.detail == "Frozen contract review"

    def test_approval_passes_and_records_the_approver(self, tmp_path: Path, policy: Policy) -> None:
        approval = Approval("test-node", ApprovalDecision.APPROVED, "neil", note="contract looks right")
        ctx = make_ctx(tmp_path, policy, approvals={"test-node": approval})
        result = run("human_approval", ctx)
        assert result.outcome is PASS and result.evidence["approver"] == "neil"

    def test_rejection_fails_and_carries_the_note_forward(self, tmp_path: Path, policy: Policy) -> None:
        approval = Approval("test-node", ApprovalDecision.REJECTED, "neil", note="302 not 301")
        ctx = make_ctx(tmp_path, policy, approvals={"test-node": approval})
        result = run("human_approval", ctx)
        assert result.outcome is FAIL and result.evidence["note"] == "302 not 301"

    def test_an_approval_for_another_node_does_not_count(self, tmp_path: Path, policy: Policy) -> None:
        approval = Approval("design", ApprovalDecision.APPROVED, "neil")
        ctx = make_ctx(tmp_path, policy, node_id="release-readiness", approvals={"design": approval})
        assert run("human_approval", ctx).outcome is ESCALATE


class TestMavenInvocation:
    def test_the_wrapper_is_invoked_by_absolute_path(self, tmp_path: Path, policy: Policy) -> None:
        """The command runs *in* the service directory, so a workspace-relative
        `service/mvnw` is not `service/mvnw` once the cwd is `service`. The main
        checkout hid this because the CLI resolves the workspace; a worktree
        built from a relative runs directory did not, and the gate died with
        exit 127 on one branch while the other stayed green."""
        wrapper = tmp_path / "service" / "mvnw"
        wrapper.parent.mkdir(parents=True)
        wrapper.write_text("#!/bin/sh\n")
        seen: list[list[str]] = []

        def runner(cmd, cwd, timeout=None):
            seen.append(cmd)
            return CommandResult(exit_code=0, stdout="", stderr="")

        ctx = make_ctx(tmp_path, policy, runner=runner)
        ctx.maven("compile")
        assert Path(seen[0][0]).is_absolute()
        assert seen[0][0] == str(wrapper)
