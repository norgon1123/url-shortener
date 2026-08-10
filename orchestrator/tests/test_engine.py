"""End-to-end pipeline execution against scripted backends.

These are the tests that exercise the claims: that the graph halts where it says
it halts, that approval is consequential rather than a pause button, that a
rejection changes what happens next, and that retry, rollback, replan, and
safe-stop are code paths rather than bullet points.

Everything runs against a real git repository and a real SQLite database with a
scripted backend standing in for the model. A full pipeline finishes in
milliseconds, which is the entire argument for building mock mode first.
"""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from sdlc.audit import Journal
from sdlc.budget import BudgetGuard
from sdlc.checkpoint import CheckpointManager, Git
from sdlc.engine import Engine
from sdlc.graph import load_pipeline
from sdlc.mock import MockBackend, ScriptedAttempt
from sdlc.model import Approval, ApprovalDecision, Budget, NodeStatus, RunStatus
from sdlc.state import RunStore

RUN_ID = "run-1"


# --------------------------------------------------------------------------
# Harness
# --------------------------------------------------------------------------

BASE_NODES = [
    {
        "id": "plan",
        "prompt": "plan.md",
        "write_paths": ["artifacts/**"],
        "exit_gates": [
            {"check": "artifact_present", "gate_class": "mechanical", "path": "artifacts/plan.txt"}
        ],
    },
    {
        "id": "left",
        "prompt": "left.md",
        "write_paths": ["src/left/**"],
        "deny_paths": ["src/right/**"],
        "depends_on": ["plan"],
        "worktree": "left",
        "exit_gates": [{"check": "paths_confined", "gate_class": "mechanical"}],
    },
    {
        "id": "right",
        "prompt": "right.md",
        "write_paths": ["src/right/**"],
        "deny_paths": ["src/left/**"],
        "depends_on": ["plan"],
        "worktree": "right",
        "exit_gates": [{"check": "paths_confined", "gate_class": "mechanical"}],
    },
    {
        "id": "join",
        "type": "barrier",
        "depends_on": ["left", "right"],
        "exit_gates": [
            {"check": "merge_clean", "gate_class": "mechanical", "on_fail": "escalate"}
        ],
    },
    {
        "id": "check",
        "type": "deterministic",
        "depends_on": ["join"],
        "exit_gates": [
            {"check": "artifact_present", "gate_class": "mechanical", "path": "src/left/a.txt"}
        ],
    },
]


def build_pipeline(tmp_path: Path, nodes: list[dict], **top) -> Path:
    path = tmp_path / "pipeline.yaml"
    path.write_text(
        yaml.safe_dump(
            {
                "version": 1,
                "policy": {"forbidden_paths": [".git/**"]},
                "budget": top.pop("budget", {}),
                "nodes": nodes,
                **top,
            }
        )
    )
    return path


def make_repo(tmp_path: Path) -> Git:
    root = tmp_path / "workspace"
    root.mkdir()
    git = Git(root=root)
    git._git("init", "-b", "main")
    git._git("config", "user.email", "orchestrator@example.com")
    git._git("config", "user.name", "SDLC Orchestrator")
    (root / "README.md").write_text("# workspace\n")
    git._git("add", "-A")
    git._git("commit", "-m", "initial")
    return git


def make_prompts(tmp_path: Path, names: list[str]) -> Path:
    root = tmp_path / "prompts"
    root.mkdir(exist_ok=True)
    for name in names:
        (root / name).write_text(f"Do the {name} work.")
    return root


class Harness:
    """Everything an Engine needs, assembled for a test."""

    def __init__(self, tmp_path: Path, nodes: list[dict], script: dict, **top):
        self.tmp_path = tmp_path
        self.git = make_repo(tmp_path)
        self.prompts = make_prompts(
            tmp_path, [n["prompt"] for n in nodes if n.get("prompt")]
        )
        self.pipeline = load_pipeline(build_pipeline(tmp_path, nodes, **top))
        self.store = RunStore(tmp_path / "state.db")
        self.journal = Journal(tmp_path / "journal.jsonl", run_id=RUN_ID)
        self.checkpoints = CheckpointManager(
            git=self.git, run_id=RUN_ID, worktree_root=tmp_path / "worktrees"
        )
        self.backend = MockBackend(script)
        self.store.create_run(RUN_ID, pipeline="test", scenario="unit")
        self.budget = BudgetGuard(self.pipeline.budget)

    def engine(self) -> Engine:
        return Engine(
            pipeline=self.pipeline,
            backend=self.backend,
            store=self.store,
            journal=self.journal,
            checkpoints=self.checkpoints,
            workspace=self.git.root,
            prompts_root=self.prompts,
            run_id=RUN_ID,
            budget=self.budget,
        )

    def run(self, **kw) -> RunStatus:
        return self.engine().run(**kw)

    def status(self, node_id: str) -> NodeStatus:
        return self.store.get_node(RUN_ID, node_id).status

    def events(self, name: str) -> list:
        return self.journal.by_event(name)


HAPPY_SCRIPT = {
    "plan": [ScriptedAttempt(files={"artifacts/plan.txt": "build a thing"})],
    "left": [ScriptedAttempt(files={"src/left/a.txt": "left"})],
    "right": [ScriptedAttempt(files={"src/right/b.txt": "right"})],
}


@pytest.fixture
def happy(tmp_path: Path) -> Harness:
    return Harness(tmp_path, BASE_NODES, dict(HAPPY_SCRIPT))


# --------------------------------------------------------------------------
# The happy path, and what it proves
# --------------------------------------------------------------------------


class TestCompleteRun:
    def test_a_clean_run_passes_every_node(self, happy: Harness) -> None:
        assert happy.run() is RunStatus.COMPLETED
        assert all(happy.status(n) is NodeStatus.PASSED for n in happy.pipeline.node_ids)

    def test_the_parallel_branches_merge_into_the_workspace(self, happy: Harness) -> None:
        happy.run()
        assert (happy.git.root / "src/left/a.txt").exists()
        assert (happy.git.root / "src/right/b.txt").exists()

    def test_the_fan_out_is_derived_from_the_graph_not_hardcoded(self, happy: Harness) -> None:
        happy.run()
        parallel = happy.events("parallel_started")
        assert len(parallel) == 1
        assert sorted(parallel[0].payload["nodes"]) == ["left", "right"]

    def test_branches_write_to_genuinely_separate_checkouts(self, happy: Harness) -> None:
        """If they shared a tree the merge would be theatre."""
        happy.run()
        left = happy.checkpoints.worktree("left")
        assert not (left.root / "src/right/b.txt").exists()

    def test_every_node_leaves_a_traceable_commit(self, happy: Harness) -> None:
        happy.run()
        log = happy.git._git("log", "--format=%B").stdout
        assert log.count(f"Run-Id: {RUN_ID}") >= 3
        assert "Node-Id: plan" in log

    def test_the_journal_verifies_after_a_full_run(self, happy: Harness) -> None:
        happy.run()
        happy.journal.verify()

    def test_state_can_be_rebuilt_from_the_journal(self, happy: Harness, tmp_path: Path) -> None:
        """The dashboard and the audit trail must agree, and this is how you check."""
        happy.run()
        rebuilt = RunStore(tmp_path / "rebuilt.db")
        rebuilt.rebuild_from_journal(happy.journal)
        assert rebuilt.get_node(RUN_ID, "plan").status is NodeStatus.PASSED
        assert rebuilt.get_node(RUN_ID, "check").status is NodeStatus.PASSED

    def test_gate_evaluations_are_all_recorded(self, happy: Harness) -> None:
        happy.run()
        checks = {e.payload["check"] for e in happy.events("gate_evaluated")}
        assert {"artifact_present", "paths_confined", "merge_clean"} <= checks


# --------------------------------------------------------------------------
# Human checkpoints
# --------------------------------------------------------------------------

APPROVAL_NODES = [
    {
        "id": "design",
        "prompt": "design.md",
        "write_paths": ["artifacts/**"],
        "output_schema": "design",
        "exit_gates": [
            {"check": "artifact_present", "gate_class": "mechanical", "path": "artifacts/design.txt"},
            {"check": "human_approval", "gate_class": "human", "reason": "Frozen contract review"},
        ],
    },
    {
        "id": "build",
        "prompt": "build.md",
        "write_paths": ["src/**"],
        "depends_on": ["design"],
        "exit_gates": [{"check": "paths_confined", "gate_class": "mechanical"}],
    },
]


@pytest.fixture
def gated(tmp_path: Path) -> Harness:
    return Harness(
        tmp_path,
        APPROVAL_NODES,
        {
            "design": [
                ScriptedAttempt(
                    files={"artifacts/design.txt": "v1: 301 redirect"},
                    output={"rationale": "v1"},
                ),
                ScriptedAttempt(
                    files={"artifacts/design.txt": "v2: 302 redirect"},
                    output={"rationale": "v2"},
                ),
            ],
            "build": [ScriptedAttempt(files={"src/App.java": "class App {}"})],
        },
    )


class TestHumanCheckpoints:
    def test_the_run_halts_at_the_checkpoint(self, gated: Harness) -> None:
        assert gated.run() is RunStatus.PENDING_APPROVAL
        assert gated.status("design") is NodeStatus.PENDING_APPROVAL
        assert gated.status("build") is NodeStatus.PENDING

    def test_downstream_work_does_not_happen_while_paused(self, gated: Harness) -> None:
        gated.run()
        assert not (gated.git.root / "src/App.java").exists()

    def test_approval_resumes_and_completes(self, gated: Harness) -> None:
        gated.run()
        gated.store.record_approval(
            RUN_ID, Approval("design", ApprovalDecision.APPROVED, "neil", note="contract is right")
        )
        assert gated.run(resume=True) is RunStatus.COMPLETED
        assert (gated.git.root / "src/App.java").exists()

    def test_resuming_does_not_pay_for_the_node_again(self, gated: Harness) -> None:
        """The work is already on disk; resume re-gates rather than re-runs."""
        gated.run()
        gated.store.record_approval(RUN_ID, Approval("design", ApprovalDecision.APPROVED, "neil"))
        gated.run(resume=True)
        assert (gated.git.root / "artifacts/design.txt").read_text() == "v1: 301 redirect"

    def test_rejection_re_runs_the_node_with_the_note(self, gated: Harness) -> None:
        """Approve-only would be theatre. A rejection has to change the outcome."""
        gated.run()
        gated.store.record_approval(
            RUN_ID,
            Approval("design", ApprovalDecision.REJECTED, "neil", note="302, not 301"),
        )
        assert gated.run(resume=True) is RunStatus.PENDING_APPROVAL
        assert (gated.git.root / "artifacts/design.txt").read_text() == "v2: 302 redirect"

        applied = gated.events("rejection_applied")
        assert applied and applied[0].payload["note"] == "302, not 301"

    def test_a_revised_result_faces_a_fresh_decision(self, gated: Harness) -> None:
        gated.run()
        gated.store.record_approval(
            RUN_ID, Approval("design", ApprovalDecision.REJECTED, "neil", note="302, not 301")
        )
        gated.run(resume=True)
        assert gated.store.approvals(RUN_ID) == {}  # the rejection is spent
        gated.store.record_approval(RUN_ID, Approval("design", ApprovalDecision.APPROVED, "neil"))
        assert gated.run(resume=True) is RunStatus.COMPLETED

    def test_both_decisions_survive_in_the_audit_trail(self, gated: Harness) -> None:
        """The approvals table holds the live decision; the journal holds the record."""
        gated.run()
        gated.store.record_approval(
            RUN_ID, Approval("design", ApprovalDecision.REJECTED, "neil", note="302, not 301")
        )
        gated.run(resume=True)
        gated.store.record_approval(RUN_ID, Approval("design", ApprovalDecision.APPROVED, "neil"))
        gated.run(resume=True)
        gated.journal.verify()
        assert len(gated.events("rejection_applied")) == 1
        assert len(gated.events("node_pending_approval")) == 2


# --------------------------------------------------------------------------
# Failure handling
# --------------------------------------------------------------------------


def failure_nodes(on_failure: str, **extra) -> list[dict]:
    node = {
        "id": "work",
        "prompt": "work.md",
        "write_paths": ["src/**"],
        "retry": {"max_attempts": 2, "backoff_seconds": 0},
        "on_failure": on_failure,
        "exit_gates": [
            {"check": "artifact_present", "gate_class": "mechanical", "path": "src/out.txt"}
        ],
        **extra,
    }
    return [
        {
            "id": "setup",
            "prompt": "setup.md",
            "write_paths": ["artifacts/**"],
            "exit_gates": [
                {"check": "artifact_present", "gate_class": "mechanical", "path": "artifacts/s.txt"}
            ],
        },
        {**node, "depends_on": ["setup"]},
    ]


SETUP_OK = {"setup": [ScriptedAttempt(files={"artifacts/s.txt": "ready"})]}


class TestRetry:
    def test_a_transient_failure_is_retried_and_succeeds(self, tmp_path: Path) -> None:
        harness = Harness(
            tmp_path,
            failure_nodes("retry"),
            {
                **SETUP_OK,
                "work": [
                    ScriptedAttempt(fail="model returned nothing"),
                    ScriptedAttempt(files={"src/out.txt": "done"}),
                ],
            },
        )
        assert harness.run() is RunStatus.COMPLETED
        assert len(harness.events("node_attempt_failed")) == 1

    def test_retries_are_bounded(self, tmp_path: Path) -> None:
        harness = Harness(
            tmp_path,
            failure_nodes("retry"),
            {**SETUP_OK, "work": [ScriptedAttempt(fail="always broken")]},
        )
        assert harness.run() is RunStatus.FAILED
        assert len(harness.events("node_attempt_failed")) == 2  # max_attempts, not forever

    def test_the_next_attempt_is_told_why_the_last_one_failed(self, tmp_path: Path) -> None:
        """A gate failure, not a backend failure -- so there is something to report."""
        seen: list[tuple] = []

        class Spy(MockBackend):
            def run(self, invocation):
                seen.append(tuple(g.check for g in invocation.gate_failures))
                return super().run(invocation)

        harness = Harness(tmp_path, failure_nodes("retry"), {})
        harness.backend = Spy(
            {
                **SETUP_OK,
                "work": [
                    ScriptedAttempt(files={"src/wrong.txt": "oops"}),
                    ScriptedAttempt(files={"src/out.txt": "done"}),
                ],
            }
        )
        assert harness.run() is RunStatus.COMPLETED
        assert seen[-1] == ("artifact_present",)


class TestFallback:
    def test_fallback_buys_one_attempt_at_reduced_autonomy(self, tmp_path: Path) -> None:
        """Degrading to `propose` is a different move from rolling the dice again."""
        autonomies: list[str] = []

        class Spy(MockBackend):
            def run(self, invocation):
                autonomies.append(invocation.autonomy.value)
                return super().run(invocation)

        harness = Harness(tmp_path, failure_nodes("fallback"), {})
        harness.backend = Spy(
            {
                **SETUP_OK,
                "work": [
                    ScriptedAttempt(fail="one"),
                    ScriptedAttempt(fail="two"),
                    ScriptedAttempt(files={"src/out.txt": "proposed"}),
                ],
            }
        )
        assert harness.run() is RunStatus.COMPLETED
        assert autonomies[-3:] == ["apply", "apply", "propose"]
        assert harness.events("fallback_engaged")


class TestRollback:
    def test_a_failing_node_leaves_the_tree_at_the_last_good_checkpoint(
        self, tmp_path: Path
    ) -> None:
        harness = Harness(
            tmp_path,
            failure_nodes("rollback"),
            {
                **SETUP_OK,
                "work": [ScriptedAttempt(files={"src/half-written.txt": "partial"})],
            },
        )
        assert harness.run() is RunStatus.FAILED
        assert not (harness.git.root / "src/half-written.txt").exists()
        assert (harness.git.root / "artifacts/s.txt").exists()  # setup survives
        assert harness.git.is_clean()

    def test_the_rollback_target_is_recorded(self, tmp_path: Path) -> None:
        harness = Harness(
            tmp_path,
            failure_nodes("rollback"),
            {**SETUP_OK, "work": [ScriptedAttempt(files={"src/x.txt": "partial"})]},
        )
        harness.run()
        rolled = harness.events("node_rolled_back")
        assert rolled and rolled[0].payload["to_commit"]


class TestReplan:
    def _nodes(self, max_replans: int = 2) -> list[dict]:
        return failure_nodes(
            "replan",
            replan_target="setup",
            max_replans=max_replans,
        )

    def test_a_failure_sends_work_back_upstream(self, tmp_path: Path) -> None:
        harness = Harness(
            tmp_path,
            self._nodes(),
            {
                "setup": [
                    ScriptedAttempt(files={"artifacts/s.txt": "v1"}),
                    ScriptedAttempt(files={"artifacts/s.txt": "v2"}),
                ],
                "work": [
                    ScriptedAttempt(fail="cannot build from v1"),
                    ScriptedAttempt(fail="cannot build from v1"),
                    ScriptedAttempt(files={"src/out.txt": "built from v2"}),
                ],
            },
        )
        assert harness.run() is RunStatus.COMPLETED
        replans = harness.events("replan_triggered")
        assert len(replans) == 1
        assert replans[0].payload["triggered_by"] == "work"
        assert "work" in replans[0].payload["reset_nodes"]  # downstream reset too
        assert (harness.git.root / "artifacts/s.txt").read_text() == "v2"

    def test_replanning_is_bounded_and_ends_in_safe_stop(self, tmp_path: Path) -> None:
        """An unbounded replan loop looks like progress and bills like it too."""
        harness = Harness(
            tmp_path,
            self._nodes(max_replans=2),
            {
                "setup": [ScriptedAttempt(files={"artifacts/s.txt": "same every time"})],
                "work": [ScriptedAttempt(fail="never works")],
            },
        )
        assert harness.run() is RunStatus.SAFE_STOPPED
        assert len(harness.events("replan_triggered")) == 2
        stopped = harness.events("run_safe_stopped")
        assert "replan limit" in stopped[0].payload["reason"]

    def test_the_replan_is_traceable_to_the_gate_that_caused_it(self, tmp_path: Path) -> None:
        harness = Harness(
            tmp_path,
            self._nodes(),
            {
                "setup": [ScriptedAttempt(files={"artifacts/s.txt": "v1"})],
                "work": [
                    ScriptedAttempt(fail="boom"),
                    ScriptedAttempt(fail="boom again"),
                    ScriptedAttempt(files={"src/out.txt": "ok"}),
                ],
            },
        )
        harness.run()
        replan = harness.events("replan_triggered")[0]
        lineage = [e.event for e in harness.journal.lineage(replan.entry_id)]
        assert "replan_triggered" in lineage and "node_failed" in lineage


class TestSafeStop:
    def test_operator_stop_halts_at_the_next_node_boundary(self, happy: Harness) -> None:
        happy.store.request_stop(RUN_ID)
        assert happy.run() is RunStatus.SAFE_STOPPED
        assert happy.status("plan") is NodeStatus.PENDING  # never started

    def test_a_stopped_run_resumes_from_where_it_stopped(self, happy: Harness) -> None:
        happy.store.request_stop(RUN_ID)
        happy.run()
        assert happy.run(resume=True) is RunStatus.COMPLETED

    def test_a_budget_breach_stops_the_run(self, tmp_path: Path) -> None:
        harness = Harness(
            tmp_path,
            BASE_NODES,
            {
                "plan": [ScriptedAttempt(files={"artifacts/plan.txt": "x"}, cost_usd=5.0)],
                "left": [ScriptedAttempt(files={"src/left/a.txt": "left"})],
                "right": [ScriptedAttempt(files={"src/right/b.txt": "right"})],
            },
            budget={"max_cost_usd": 1.0},
        )
        assert harness.run() is RunStatus.SAFE_STOPPED
        stopped = harness.events("run_safe_stopped")
        assert "cost ceiling" in stopped[0].payload["reason"]

    def test_a_safe_stop_leaves_the_journal_verifiable(self, happy: Harness) -> None:
        happy.store.request_stop(RUN_ID)
        happy.run()
        happy.journal.verify()


# --------------------------------------------------------------------------
# Policy enforcement inside a run
# --------------------------------------------------------------------------


class TestPolicyInsideARun:
    def test_a_node_writing_outside_its_allowlist_fails_the_run(self, tmp_path: Path) -> None:
        """Segregation of duties, enforced at runtime rather than asserted in a doc."""
        harness = Harness(
            tmp_path,
            BASE_NODES,
            {
                "plan": [ScriptedAttempt(files={"artifacts/plan.txt": "x"})],
                "left": [ScriptedAttempt(files={"src/right/sneaky.txt": "not mine"})],
                "right": [ScriptedAttempt(files={"src/right/b.txt": "right"})],
            },
        )
        assert harness.run() is RunStatus.FAILED
        assert harness.status("left") is NodeStatus.FAILED
        failed = [e for e in harness.events("node_failed") if e.node_id == "left"]
        assert "paths_confined" in failed[0].payload["failed_checks"]

    def test_a_merge_conflict_escalates_rather_than_failing(self, tmp_path: Path) -> None:
        """A machine has no standing to choose between two generated versions."""
        # Both branches are permitted to write `shared/`, so the collision is a
        # genuine merge conflict rather than a policy violation caught earlier.
        nodes = [dict(n) for n in BASE_NODES]
        for node in nodes:
            if node["id"] in ("left", "right"):
                node["write_paths"] = [*node["write_paths"], "shared/**"]
        nodes = [n for n in nodes if n["id"] != "check"]

        harness = Harness(
            tmp_path,
            nodes,
            {
                "plan": [ScriptedAttempt(files={"artifacts/plan.txt": "x"})],
                "left": [ScriptedAttempt(files={"shared/contested.txt": "from left\n"})],
                "right": [ScriptedAttempt(files={"shared/contested.txt": "from right\n"})],
            },
        )
        assert harness.run() is RunStatus.PENDING_APPROVAL
        assert harness.status("join") is NodeStatus.PENDING_APPROVAL
        assert harness.git.is_clean()  # the merge was aborted, not half-applied


# --------------------------------------------------------------------------
# Change-driven replanning
# --------------------------------------------------------------------------


class TestStaleInputs:
    def test_editing_an_upstream_artifact_invalidates_downstream_work(
        self, gated: Harness
    ) -> None:
        """The literal reading of 're-plan when upstream outputs change'."""
        gated.store.record_approval(RUN_ID, Approval("design", ApprovalDecision.APPROVED, "neil"))
        assert gated.run() is RunStatus.COMPLETED
        assert gated.status("build") is NodeStatus.PASSED

        (gated.git.root / "artifacts/design.json").write_text('{"rationale": "edited by hand"}')
        gated.run(resume=True)

        stale = {e.node_id for e in gated.events("node_stale")}
        assert "build" in stale

    def test_an_unchanged_run_resumes_without_redoing_work(self, gated: Harness) -> None:
        gated.store.record_approval(RUN_ID, Approval("design", ApprovalDecision.APPROVED, "neil"))
        gated.run()
        gated.run(resume=True)
        assert gated.events("node_stale") == []


class TestPreflight:
    def test_a_missing_prompt_fails_before_anything_runs(self, tmp_path: Path) -> None:
        """The same argument as validating gate names at load time: discovering
        a typo when the node is reached means discovering it forty minutes and
        several dollars in, with a half-built workspace to clean up."""
        harness = Harness(tmp_path, BASE_NODES, dict(HAPPY_SCRIPT))
        (harness.prompts / BASE_NODES[0]["prompt"]).unlink()

        with pytest.raises(FileNotFoundError, match=BASE_NODES[0]["id"]):
            harness.run()

        assert harness.events("run_started") == []
