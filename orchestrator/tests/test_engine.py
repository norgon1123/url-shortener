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

import time
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

    # Swapped by the `recorded_sleeps` fixture. Injected rather than
    # monkeypatched onto the `time` module, which would also capture sleeps this
    # engine never asked for.
    sleep = staticmethod(time.sleep)

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
            sleep=Harness.sleep,
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


@pytest.fixture(autouse=True)
def recorded_sleeps() -> list[float]:
    """Retry backoff is recorded rather than served.

    Two reasons, and the second is the interesting one. No test should spend
    real wall-clock waiting out a backoff; and a delay that is only observable
    as elapsed time is a delay no test can assert on. Recording it makes the
    wait a fact in the journal of the test rather than a slow test.
    """
    delays: list[float] = []
    Harness.sleep = staticmethod(delays.append)
    yield delays
    Harness.sleep = staticmethod(time.sleep)


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

    def test_a_wall_is_not_retried(self, tmp_path: Path) -> None:
        """A provider quota reset hours from now is not a transient failure.

        The run that motivated this spent three attempts and $7.42 discovering
        the same session limit three times. Retrying a deterministic external
        limit is superstition; the attempts are better kept for failures a
        second try could plausibly clear.
        """
        harness = Harness(
            tmp_path,
            failure_nodes("retry"),
            {
                **SETUP_OK,
                "work": [
                    ScriptedAttempt(
                        fail="success; You've hit your session limit · resets 6:50pm"
                    )
                ],
            },
        )
        assert harness.run() is RunStatus.FAILED
        assert len(harness.events("node_attempt_failed")) == 1  # not 2
        abandoned = harness.events("retries_abandoned")
        assert len(abandoned) == 1
        assert abandoned[0].payload["reason"] == "provider quota exhausted"
        assert abandoned[0].payload["attempts_remaining"] == 1

    def test_a_retry_waits_out_the_declared_backoff(
        self, tmp_path: Path, recorded_sleeps: list[float]
    ) -> None:
        """`backoff_seconds` is a promise the engine has to keep.

        A declared-but-ignored backoff is worse than no backoff: the pipeline
        author believes a rate limit or a flapping dependency is being given
        time to recover, and it is not.
        """
        nodes = failure_nodes("retry")
        nodes[1]["retry"] = {"max_attempts": 2, "backoff_seconds": 0.25}
        harness = Harness(
            tmp_path,
            nodes,
            {
                **SETUP_OK,
                "work": [
                    ScriptedAttempt(fail="rate limited"),
                    ScriptedAttempt(files={"src/out.txt": "done"}),
                ],
            },
        )
        assert harness.run() is RunStatus.COMPLETED
        assert recorded_sleeps == [0.25]

    def test_the_backoff_is_between_attempts_not_after_the_last_one(
        self, tmp_path: Path, recorded_sleeps: list[float]
    ) -> None:
        """Sleeping after the final attempt delays a failure nobody is waiting on."""
        nodes = failure_nodes("retry")
        nodes[1]["retry"] = {"max_attempts": 3, "backoff_seconds": 0.5}
        harness = Harness(
            tmp_path,
            nodes,
            {**SETUP_OK, "work": [ScriptedAttempt(fail="always broken")]},
        )
        assert harness.run() is RunStatus.FAILED
        assert recorded_sleeps == [0.5, 0.5]  # 3 attempts, 2 gaps

    def test_a_zero_backoff_never_sleeps(
        self, tmp_path: Path, recorded_sleeps: list[float]
    ) -> None:
        harness = Harness(
            tmp_path,
            failure_nodes("retry"),  # declares backoff_seconds: 0
            {**SETUP_OK, "work": [ScriptedAttempt(fail="broken")]},
        )
        harness.run()
        assert recorded_sleeps == []

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


LENSES = (
    "review-security",
    "review-performance",
    "review-api-contract",
    "review-test-adequacy",
    "review-cleanliness",
)


def wide_fanout_nodes() -> list[dict]:
    """The review fan-out's shape: six concurrent writers, one barrier, one join."""
    lenses = [
        {
            "id": lens,
            "prompt": f"{lens}.md",
            "write_paths": ["artifacts/**"],
            "output_schema": lens,
            "depends_on": ["setup"],
            "worktree": lens,
            "exit_gates": [{"check": "paths_confined", "gate_class": "mechanical"}],
        }
        for lens in LENSES
    ]
    return [
        {
            "id": "setup",
            "prompt": "setup.md",
            "write_paths": ["artifacts/**"],
            "exit_gates": [
                {"check": "artifact_present", "gate_class": "mechanical", "path": "artifacts/s.txt"}
            ],
        },
        {
            "id": "docs",
            "prompt": "docs.md",
            "write_paths": ["docs/**"],
            "depends_on": ["setup"],
            "worktree": "docs",
            "exit_gates": [{"check": "paths_confined", "gate_class": "mechanical"}],
        },
        *lenses,
        {
            "id": "review-join",
            "type": "barrier",
            "depends_on": ["docs", *LENSES],
            "exit_gates": [
                {"check": "merge_clean", "gate_class": "mechanical", "on_fail": "escalate"}
            ],
        },
        {
            "id": "review-synthesis",
            "prompt": "review-synthesis.md",
            "write_paths": ["artifacts/**"],
            "depends_on": ["review-join", *LENSES],
            "exit_gates": [{"check": "paths_confined", "gate_class": "mechanical"}],
        },
    ]


def lens_output(lens: str) -> dict:
    return {
        "lens": lens,
        "findings": [
            {
                "id": f"{lens[:3].upper()}-1",
                "severity": "minor",
                "confidence": "high",
                "file": "A.java",
                "summary": "something",
            }
        ],
        "summary": "",
        "not_examined": [],
    }


class TestWideFanOut:
    """Six concurrent writers is a different problem from two.

    The `implement` / `author-tests` pair proved a fan-out works. It did not
    prove this one does: six nodes all demand a checkout in the same instant,
    six branches have to merge at one barrier, and the node after the barrier
    has to receive all six results as context. Each of those is somewhere the
    two-node case never went.
    """

    @pytest.fixture
    def harness(self, tmp_path: Path) -> Harness:
        return Harness(
            tmp_path,
            wide_fanout_nodes(),
            {
                "setup": [ScriptedAttempt(files={"artifacts/s.txt": "ready"})],
                "docs": [ScriptedAttempt(files={"docs/README.md": "# docs\n"})],
                **{lens: [ScriptedAttempt(output=lens_output(lens))] for lens in LENSES},
                "review-synthesis": [ScriptedAttempt(output={"findings": [], "summary": "ok"})],
            },
        )

    def test_the_whole_fan_out_runs_and_rejoins(self, harness: Harness) -> None:
        assert harness.run() is RunStatus.COMPLETED
        assert all(harness.status(n) is NodeStatus.PASSED for n in harness.pipeline.node_ids)

    def test_six_writers_run_as_one_ready_set(self, harness: Harness) -> None:
        harness.run()
        parallel = harness.events("parallel_started")
        assert len(parallel) == 1
        assert sorted(parallel[0].payload["nodes"]) == sorted(["docs", *LENSES])

    def test_every_branch_reaches_the_workspace(self, harness: Harness) -> None:
        """A branch nothing merges is work that never happened."""
        harness.run()
        assert (harness.git.root / "docs/README.md").exists()
        for lens in LENSES:
            assert (harness.git.root / "artifacts" / f"{lens}.json").exists()

    def test_no_lens_can_see_another_lens_diff(self, harness: Harness) -> None:
        """Isolation is what lets `paths_confined` blame the right node."""
        harness.run()
        security = harness.checkpoints.worktree("review-security")
        assert (security.root / "artifacts/review-security.json").exists()
        assert not (security.root / "artifacts/review-performance.json").exists()

    def test_the_join_node_receives_every_lens_as_context(self, harness: Harness) -> None:
        """Synthesis cannot preserve findings it was never handed."""
        seen: dict[str, list[str]] = {}

        class Spy(MockBackend):
            def run(self, invocation):
                seen[invocation.node.id] = sorted(invocation.context)
                return super().run(invocation)

        harness.backend = Spy(harness.backend.script)
        harness.run()
        assert seen["review-synthesis"] == sorted(LENSES)


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


# --------------------------------------------------------------------------
# Triage: repair the artifact that is wrong, not everything upstream of it
# --------------------------------------------------------------------------


TRIAGE_NODES = [
    {
        "id": "plan",
        "prompt": "plan.md",
        "write_paths": ["artifacts/**"],
        "exit_gates": [
            {"check": "artifact_present", "gate_class": "mechanical", "path": "artifacts/plan.txt"}
        ],
    },
    {
        "id": "implement",
        "prompt": "implement.md",
        "write_paths": ["src/main/**"],
        "depends_on": ["plan"],
        "worktree": "implement",
        "repair_attempts": 2,
    },
    {
        "id": "author-tests",
        "prompt": "author-tests.md",
        "write_paths": ["src/test/**"],
        "depends_on": ["plan"],
        "worktree": "tests",
        "repair_attempts": 1,
    },
    {"id": "join", "type": "barrier", "depends_on": ["implement", "author-tests"]},
    {
        "id": "verify",
        "type": "deterministic",
        "depends_on": ["join"],
        "on_failure": "triage",
        "triage_node": "triage",
        "replan_target": "plan",
        "max_replans": 1,
        "retry": {"max_attempts": 1, "backoff_seconds": 0},
        "exit_gates": [
            {"check": "artifact_present", "gate_class": "mechanical", "path": "artifacts/green.txt"}
        ],
    },
    {
        "id": "triage",
        "type": "handler",
        "prompt": "triage.md",
        "write_paths": ["artifacts/**"],
        "output_schema": "triage",
    },
]


def triage_script(verdict: dict, *, green_on: int | None = None) -> dict:
    """`verify` fails until `green_on` invocations of the repaired node."""
    return {
        "plan": [ScriptedAttempt(files={"artifacts/plan.txt": "p"})],
        "implement": [ScriptedAttempt(files={"src/main/A.java": "class A {}"})],
        "author-tests": [ScriptedAttempt(files={"src/test/ATest.java": "class ATest {}"})],
        "triage": [ScriptedAttempt(output=verdict, cost_usd=0.5)],
    }


IMPL_VERDICT = {
    "verdict": "implementation",
    "summary": "the code returns 301 where the contract says 302",
    "failures": [
        {"test": "redirectIs302", "classification": "implementation",
         "confidence": "high", "evidence": "openapi.yaml:208 says 302"}
    ],
}
TEST_VERDICT = {
    "verdict": "test",
    "summary": "the test boots a second context the harness does not support",
    "failures": [
        {"test": "linksSurviveRestart", "classification": "test",
         "confidence": "high", "evidence": "ATest.java:44 dials localhost:5432"}
    ],
}


class TestTriageRouting:
    """The cheap path. Replanning re-derives everything to fix one artifact."""

    def _harness(self, tmp_path: Path, verdict: dict) -> Harness:
        return Harness(tmp_path, TRIAGE_NODES, triage_script(verdict))

    def test_an_implementation_verdict_re_runs_only_implement(
        self, tmp_path: Path
    ) -> None:
        h = self._harness(tmp_path, IMPL_VERDICT)
        h.run()
        routed = h.events("repair_routed")
        assert routed and routed[0].node_id == "implement"
        # author-tests is upstream of nothing that failed, so it is untouched.
        assert h.status("author-tests") is NodeStatus.PASSED

    def test_a_test_verdict_re_runs_only_author_tests(self, tmp_path: Path) -> None:
        h = self._harness(tmp_path, TEST_VERDICT)
        h.run()
        routed = h.events("repair_routed")
        assert routed and routed[0].node_id == "author-tests"
        assert h.status("implement") is NodeStatus.PASSED

    def test_the_verdict_is_journalled_with_its_reason(self, tmp_path: Path) -> None:
        """A routing decision nobody can audit is a routing decision nobody can
        overrule."""
        h = self._harness(tmp_path, IMPL_VERDICT)
        h.run()
        verdicts = h.events("triage_verdict")
        assert verdicts[0].payload["verdict"] == "implementation"
        assert verdicts[0].payload["targets"] == ["implement"]
        assert verdicts[0].payload["triggered_by"] == "verify"

    def test_triage_costs_are_charged_to_the_run(self, tmp_path: Path) -> None:
        h = self._harness(tmp_path, IMPL_VERDICT)
        h.run()
        assert h.store.get_run(RUN_ID).cost_usd >= 0.5


class TestTriageEscalates:
    """Everything it cannot route cleanly goes to a person."""

    def _run(self, tmp_path: Path, verdict: dict) -> Harness:
        h = Harness(tmp_path, TRIAGE_NODES, triage_script(verdict))
        h.run()
        return h

    def test_a_contract_verdict_goes_to_a_human(self, tmp_path: Path) -> None:
        """Both sides read the same document differently. Not a repair."""
        h = self._run(tmp_path, {
            "verdict": "contract",
            "summary": "the contract is silent on trailing slashes",
            "failures": [{"test": "t", "classification": "contract",
                          "confidence": "high", "evidence": "openapi has no rule"}],
        })
        assert h.status("verify") is NodeStatus.PENDING_APPROVAL
        assert not h.events("repair_routed")

    def test_a_verdict_naming_no_repairable_branch_goes_to_a_human(
        self, tmp_path: Path
    ) -> None:
        """Nothing to route to is not the same as nothing wrong."""
        h = self._run(tmp_path, {
            "verdict": "contract",
            "summary": "the document is silent",
            "failures": [{"test": "a", "classification": "contract",
                          "confidence": "high", "evidence": "x"}],
        })
        assert h.status("verify") is NodeStatus.PENDING_APPROVAL

    def test_low_confidence_escalates_even_when_decisive(self, tmp_path: Path) -> None:
        """A misrouted repair re-runs the innocent branch and leaves the defect."""
        h = self._run(tmp_path, {
            "verdict": "implementation",
            "summary": "probably the code",
            "failures": [{"test": "a", "classification": "implementation",
                          "confidence": "low", "evidence": "stack does not reach our code"}],
        })
        assert h.status("verify") is NodeStatus.PENDING_APPROVAL
        assert not h.events("repair_routed")
        assert "low confidence" in h.events("triage_verdict")[0].payload["reason"]


class TestRepairBudget:
    """Asymmetric on purpose, and durable across the process."""

    def test_the_budget_is_exhausted_then_it_replans(self, tmp_path: Path) -> None:
        """author-tests gets one attempt: repairing a test to satisfy an
        implementation is the agent editing its own judge."""
        h = Harness(tmp_path, TRIAGE_NODES, triage_script(TEST_VERDICT))
        h.run()
        assert len(h.events("repair_routed")) == 1
        assert h.events("repair_budget_exhausted")
        assert h.events("replan_triggered")

    def test_implement_gets_two_attempts_before_the_fallback(
        self, tmp_path: Path
    ) -> None:
        h = Harness(tmp_path, TRIAGE_NODES, triage_script(IMPL_VERDICT))
        h.run()
        assert len(h.events("repair_routed")) == 2

    def test_the_counter_survives_a_process_restart(self, tmp_path: Path) -> None:
        """In-memory budgets reset when the process does, and a run pauses for
        approval in one process and resumes in another. Read back through a
        fresh store, which is what a resume actually does."""
        h = Harness(tmp_path, TRIAGE_NODES, triage_script(IMPL_VERDICT))
        h.run()
        recorded = h.store.get_node(RUN_ID, "implement").repairs
        assert recorded > 0

        reopened = RunStore(tmp_path / "state.db")
        assert reopened.get_node(RUN_ID, "implement").repairs == recorded


MIXED_VERDICT = {
    "verdict": "mixed",
    "summary": "a precision bug in the code and a test that leaks state between classes",
    "failures": [
        {"test": "expiryRoundTrips", "classification": "implementation",
         "confidence": "high", "evidence": "nanos vs micros on the same field"},
        {"test": "abuseReportAccepted", "classification": "test",
         "confidence": "high", "evidence": "an earlier class drained the rate limit"},
    ],
}
CONTRACT_IN_THE_MIX = {
    "verdict": "mixed",
    "summary": "one of each, and a question the document does not answer",
    "failures": [
        {"test": "expiryRoundTrips", "classification": "implementation",
         "confidence": "high", "evidence": "nanos vs micros"},
        {"test": "errorBodiesMatch", "classification": "contract",
         "confidence": "medium", "evidence": "the contract never promised equal bodies"},
    ],
}


CONTRACT_ONLY = {
    "verdict": "contract",
    "summary": "one side read line 47, the other read note 2",
    "failures": [
        {"test": "errorBodiesMatch", "classification": "contract",
         "confidence": "high", "evidence": "openapi.yaml:47 vs openapi.yaml:123"},
    ],
}


class TestMixedVerdicts:
    """Choosing one branch would be wrong. Asking each to fix its own side is
    what a team does, and the branches are already isolated by path."""

    def test_a_mixture_routes_to_every_implicated_branch(self, tmp_path: Path) -> None:
        h = Harness(tmp_path, TRIAGE_NODES, triage_script(MIXED_VERDICT))
        h.run()
        routed = {e.node_id for e in h.events("repair_routed")}
        assert routed == {"implement", "author-tests"}

    def test_a_contract_question_in_the_mixture_still_stops(self, tmp_path: Path) -> None:
        """No amount of re-running settles two sides reading one document
        differently."""
        h = Harness(tmp_path, TRIAGE_NODES, triage_script(CONTRACT_IN_THE_MIX))
        h.run()
        assert h.status("verify") is NodeStatus.PENDING_APPROVAL
        assert not h.events("repair_routed")

    def test_a_human_adjudication_releases_it(self, tmp_path: Path) -> None:
        """The approval on file *is* the contract decision."""
        h = Harness(tmp_path, TRIAGE_NODES, triage_script(CONTRACT_IN_THE_MIX))
        h.run()
        h.store.record_approval(
            RUN_ID,
            Approval("verify", ApprovalDecision.APPROVED, "neil", note="the test over-asserts"),
        )
        h.run(resume=True)
        routed = {e.node_id for e in h.events("repair_routed")}
        assert "implement" in routed
        assert "adjudicated by neil" in h.events("triage_verdict")[-1].payload["reason"]

    def test_an_adjudication_naming_no_branch_says_so(self, tmp_path: Path) -> None:
        """Clearing the block is only half a decision.

        A verdict that is *only* a contract question has no classification the
        machine can route on, so an approval that names no branch leaves the run
        exactly where it was. Escalating again with the same words wastes the
        human's second look; naming the missing flag does not.
        """
        h = Harness(tmp_path, TRIAGE_NODES, triage_script(CONTRACT_ONLY))
        h.run()
        h.store.record_approval(
            RUN_ID, Approval("verify", ApprovalDecision.APPROVED, "neil", note="test defect")
        )
        h.run(resume=True)
        assert not h.events("repair_routed")
        assert "--answer route=" in h.events("triage_verdict")[-1].payload["reason"]

    def test_an_adjudication_can_name_the_branch_that_repairs_it(
        self, tmp_path: Path
    ) -> None:
        """Deciding a contract question *is* deciding which side has to change."""
        h = Harness(tmp_path, TRIAGE_NODES, triage_script(CONTRACT_ONLY))
        h.run()
        h.store.record_approval(
            RUN_ID,
            Approval(
                "verify",
                ApprovalDecision.APPROVED,
                "neil",
                note="the contract never promised equal bodies; the test over-asserts",
                answers={"route": "author-tests"},
            ),
        )
        h.run(resume=True)
        routed = h.events("repair_routed")
        assert [e.node_id for e in routed] == ["author-tests"]
        brief = routed[-1].payload["reason"]
        assert "errorBodiesMatch" in brief
        assert "never promised equal bodies" in brief  # the ruling, not just the route

    def test_an_adjudication_buys_the_named_branch_an_attempt(
        self, tmp_path: Path
    ) -> None:
        """`repair_attempts` bounds the machine, not the human.

        author-tests gets one attempt and had already spent it. Refusing the
        human's routing did not stop the run -- it fell through to a replan from
        `decompose`, re-deriving the whole pipeline to fix one assertion.
        """
        h = Harness(tmp_path, TRIAGE_NODES, triage_script(TEST_VERDICT))
        h.run()  # spends author-tests' single machine attempt
        assert h.events("repair_budget_exhausted")
        assert h.events("replan_triggered")

        h.store.record_approval(
            RUN_ID,
            Approval(
                "verify",
                ApprovalDecision.APPROVED,
                "neil",
                note="the test over-asserts",
                answers={"route": "author-tests"},
            ),
        )
        assert h.engine()._adjudicated_grants("author-tests") == 0  # not yet journalled
        h.journal.append(
            "human_decision",
            node_id="verify",
            decision="approved",
            approver="neil",
            answers={"route": "author-tests"},
        )
        assert h.engine()._adjudicated_grants("author-tests") == 1
        assert h.engine()._adjudicated_grants("implement") == 0

    def test_the_verdict_reaches_the_branch_being_repaired(self, tmp_path: Path) -> None:
        """A repair with no account of what it is repairing is a re-roll.

        Carried through the prompt rather than by copying the artifact into the
        branch's checkout, which would dirty the tree the barrier merges and put
        a file in the node's diff it was never permitted to write.
        """
        h = Harness(tmp_path, TRIAGE_NODES, triage_script(MIXED_VERDICT))
        h.journal.append(
            "repair_routed", node_id="implement", reason="nanos vs micros on the same field"
        )
        assert "nanos vs micros" in h.engine()._repair_note("implement")

    def test_the_note_is_spent_once_the_branch_passes(self, tmp_path: Path) -> None:
        """Otherwise every later attempt carries feedback about a fixed defect."""
        h = Harness(tmp_path, TRIAGE_NODES, triage_script(MIXED_VERDICT))
        h.journal.append("repair_routed", node_id="implement", reason="nanos vs micros")
        h.journal.append("node_passed", node_id="implement")
        assert h.engine()._repair_note("implement") == ""

    def test_a_node_never_routed_a_repair_carries_no_note(self, tmp_path: Path) -> None:
        h = Harness(tmp_path, TRIAGE_NODES, triage_script(MIXED_VERDICT))
        assert h.engine()._repair_note("plan") == ""


class TestRepairBrief:
    """A branch needs the failures attributed to *it*, not the overall summary."""

    def test_the_brief_itemises_only_this_branch_failures(self) -> None:
        """Handed the summary alone, the first live repair found nothing
        addressed to it, concluded nothing was broken, and spent seven dollars
        verifying that."""
        brief = Engine._repair_brief("implement", MIXED_VERDICT, "overall")
        assert "expiryRoundTrips" in brief
        assert "abuseReportAccepted" not in brief
        assert "nanos vs micros" in brief

    def test_each_branch_gets_its_own(self) -> None:
        tests = Engine._repair_brief("author-tests", MIXED_VERDICT, "overall")
        assert "abuseReportAccepted" in tests
        assert "expiryRoundTrips" not in tests

    def test_it_says_not_to_chase_the_other_branch_failures(self) -> None:
        brief = Engine._repair_brief("implement", MIXED_VERDICT, "overall")
        assert "not yours to chase" in brief or "another branch" in brief.lower()

    def test_it_falls_back_to_the_summary_when_nothing_is_attributed(self) -> None:
        assert Engine._repair_brief("implement", {"failures": []}, "overall") == "overall"
