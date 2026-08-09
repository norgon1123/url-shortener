"""Run state, resumability, and reconstruction from the audit trail.

The tests that matter here are the cross-process ones -- safe-stop and resume.
A run that cannot survive its own process dying is a demo, not a pipeline.
"""

from __future__ import annotations

import itertools
from pathlib import Path

import pytest

from sdlc.audit import Journal
from sdlc.model import Approval, ApprovalDecision, NodeStatus, RunStatus
from sdlc.state import RunStore


def fixed_clock():
    counter = itertools.count()
    return lambda: f"2026-08-09T09:00:{next(counter):02d}+00:00"


@pytest.fixture
def store(tmp_path: Path) -> RunStore:
    return RunStore(tmp_path / "state.db", clock=fixed_clock())


@pytest.fixture
def run_id(store: RunStore) -> str:
    store.create_run("run-1", pipeline="pipelines/sdlc.yaml", scenario="greenfield")
    return "run-1"


class TestRuns:
    def test_created_run_starts_running(self, store: RunStore, run_id: str) -> None:
        record = store.get_run(run_id)
        assert record.status is RunStatus.RUNNING
        assert record.scenario == "greenfield" and record.cost_usd == 0.0

    def test_unknown_run_raises(self, store: RunStore) -> None:
        with pytest.raises(KeyError):
            store.get_run("nope")

    def test_updating_a_missing_run_raises(self, store: RunStore) -> None:
        with pytest.raises(KeyError):
            store.set_run_status("nope", RunStatus.COMPLETED)

    def test_cost_accumulates_and_returns_the_committed_total(
        self, store: RunStore, run_id: str
    ) -> None:
        """The budget guard must read the committed value: two branches bill at once."""
        store.add_cost(run_id, 1.25)
        assert store.add_cost(run_id, 2.50) == pytest.approx(3.75)

    def test_runs_are_listed_newest_first(self, store: RunStore, run_id: str) -> None:
        store.create_run("run-2", pipeline="p.yaml")
        assert [r.run_id for r in store.list_runs()] == ["run-2", "run-1"]


class TestSafeStop:
    """`orchestrator stop` is a different process than the one running the graph."""

    def test_stop_flag_is_visible_to_another_connection(
        self, tmp_path: Path, run_id: str, store: RunStore
    ) -> None:
        operator = RunStore(tmp_path / "state.db", clock=fixed_clock())
        operator.request_stop(run_id)
        assert store.stop_requested(run_id) is True

    def test_stop_is_a_flag_not_a_status_change(self, store: RunStore, run_id: str) -> None:
        """It halts at the next node boundary, so the run is still running now."""
        store.request_stop(run_id)
        assert store.get_run(run_id).status is RunStatus.RUNNING

    def test_stop_can_be_cleared_for_resume(self, store: RunStore, run_id: str) -> None:
        store.request_stop(run_id)
        store.clear_stop(run_id)
        assert store.stop_requested(run_id) is False


class TestNodes:
    def test_an_unvisited_node_reads_as_pending(self, store: RunStore, run_id: str) -> None:
        assert store.get_node(run_id, "verify").status is NodeStatus.PENDING

    def test_upsert_preserves_unspecified_columns(self, store: RunStore, run_id: str) -> None:
        """Recording a status must not wipe the checkpoint rollback depends on."""
        store.set_node(run_id, "implement", NodeStatus.PASSED, checkpoint_commit="abc123")
        store.set_node(run_id, "implement", NodeStatus.STALE)
        state = store.get_node(run_id, "implement")
        assert state.status is NodeStatus.STALE and state.checkpoint_commit == "abc123"

    def test_unknown_column_is_rejected(self, store: RunStore, run_id: str) -> None:
        with pytest.raises(ValueError, match="unknown node_state column"):
            store.set_node(run_id, "implement", NodeStatus.PASSED, sneaky="x")

    def test_state_survives_a_process_restart(self, tmp_path: Path, run_id: str, store: RunStore) -> None:
        store.set_node(run_id, "design", NodeStatus.PASSED, attempt=2, input_hash="deadbeef")
        store.close()

        resumed = RunStore(tmp_path / "state.db", clock=fixed_clock())
        state = resumed.get_node(run_id, "design")
        assert state.status is NodeStatus.PASSED and state.attempt == 2
        assert state.input_hash == "deadbeef"

    def test_mark_stale_only_touches_completed_nodes(self, store: RunStore, run_id: str) -> None:
        """Change-driven replan: a pending node is already going to run."""
        store.set_node(run_id, "design", NodeStatus.PASSED)
        store.set_node(run_id, "implement", NodeStatus.FAILED)
        store.mark_stale(run_id, ["design", "implement", "verify"])
        assert store.get_node(run_id, "design").status is NodeStatus.STALE
        assert store.get_node(run_id, "implement").status is NodeStatus.FAILED
        assert store.get_node(run_id, "verify").status is NodeStatus.PENDING

    def test_latest_checkpoint_walks_backwards(self, store: RunStore, run_id: str) -> None:
        store.set_node(run_id, "design", NodeStatus.PASSED, checkpoint_commit="aaa")
        store.set_node(run_id, "implement", NodeStatus.PASSED, checkpoint_commit="bbb")
        store.set_node(run_id, "verify", NodeStatus.FAILED)
        order = ["design", "implement", "verify"]
        assert store.latest_checkpoint(run_id, order) == "bbb"

    def test_invocations_count_up_and_never_reset(self, store: RunStore, run_id: str) -> None:
        """Unlike `attempt`, which restarts whenever a replan re-enters a node."""
        assert store.next_invocation(run_id, "design") == 0
        assert store.next_invocation(run_id, "design") == 1
        store.set_node(run_id, "design", NodeStatus.PENDING, attempt=0)
        assert store.next_invocation(run_id, "design") == 2

    def test_invocations_survive_a_process_restart(self, tmp_path: Path, run_id: str, store: RunStore) -> None:
        """A run that pauses for approval resumes in a different process."""
        store.next_invocation(run_id, "design")
        store.close()
        resumed = RunStore(tmp_path / "state.db", clock=fixed_clock())
        assert resumed.next_invocation(run_id, "design") == 1

    def test_invocations_are_tracked_per_node(self, store: RunStore, run_id: str) -> None:
        store.next_invocation(run_id, "design")
        assert store.next_invocation(run_id, "implement") == 0

    def test_latest_checkpoint_is_none_when_nothing_committed(
        self, store: RunStore, run_id: str
    ) -> None:
        assert store.latest_checkpoint(run_id, ["design"]) is None


class TestApprovals:
    def test_approval_round_trips_with_answers(self, store: RunStore, run_id: str) -> None:
        store.record_approval(
            run_id,
            Approval("clarify", ApprovalDecision.APPROVED, "neil", answers={"Q1": "rate limiting"}),
        )
        approval = store.approvals(run_id)["clarify"]
        assert approval.approved and approval.answers == {"Q1": "rate limiting"}

    def test_a_later_decision_supersedes_an_earlier_one(self, store: RunStore, run_id: str) -> None:
        """Reject then approve after a revision. The journal keeps both."""
        store.record_approval(run_id, Approval("design", ApprovalDecision.REJECTED, "neil", note="302 not 301"))
        store.record_approval(run_id, Approval("design", ApprovalDecision.APPROVED, "neil", note="fixed"))
        approval = store.approvals(run_id)["design"]
        assert approval.decision is ApprovalDecision.APPROVED and approval.note == "fixed"

    def test_approvals_are_scoped_to_their_run(self, store: RunStore, run_id: str) -> None:
        store.create_run("run-2", pipeline="p.yaml")
        store.record_approval(run_id, Approval("design", ApprovalDecision.APPROVED, "neil"))
        assert store.approvals("run-2") == {}


class TestRebuildFromJournal:
    """The database is a materialized view; the journal is the record."""

    def _journal(self, tmp_path: Path) -> Journal:
        journal = Journal(tmp_path / "journal.jsonl", run_id="run-9", clock=fixed_clock())
        journal.append("run_started", pipeline="pipelines/sdlc.yaml", scenario="greenfield")
        journal.append("node_started", node_id="design")
        journal.append("node_passed", node_id="design", cost_usd=2.0, checkpoint_commit="aaa")
        journal.append(
            "human_decision",
            node_id="design",
            decision="approved",
            approver="neil",
            note="contract looks right",
        )
        journal.append("node_started", node_id="verify", attempt=1)
        journal.append("node_failed", node_id="verify", cost_usd=0.5, error="coverage 0.62")
        journal.append("replan_triggered", node_id="decompose", reason="verify failed twice")
        return journal

    def test_reconstructs_statuses_costs_and_approvals(self, tmp_path: Path, store: RunStore) -> None:
        record = store.rebuild_from_journal(self._journal(tmp_path))
        assert record.run_id == "run-9"
        assert record.cost_usd == pytest.approx(2.5)
        assert record.replans == 1
        assert store.get_node("run-9", "design").status is NodeStatus.PASSED
        assert store.get_node("run-9", "verify").status is NodeStatus.FAILED
        assert store.approvals("run-9")["design"].approver == "neil"

    def test_a_later_event_does_not_erase_an_earlier_checkpoint(
        self, tmp_path: Path, store: RunStore
    ) -> None:
        journal = self._journal(tmp_path)
        journal.append("node_stale", node_id="design")
        store.rebuild_from_journal(journal)
        state = store.get_node("run-9", "design")
        assert state.status is NodeStatus.STALE and state.checkpoint_commit == "aaa"

    def test_rebuild_refuses_a_tampered_journal(self, tmp_path: Path, store: RunStore) -> None:
        """Rebuilding from an edited log would launder the tampering into 'state'."""
        journal = self._journal(tmp_path)
        lines = journal.path.read_text().splitlines()
        lines[2] = lines[2].replace('"node_passed"', '"node_failed"')
        journal.path.write_text("\n".join(lines) + "\n")

        from sdlc.audit import TamperError

        with pytest.raises(TamperError):
            store.rebuild_from_journal(journal)

    def test_rebuild_is_idempotent(self, tmp_path: Path, store: RunStore) -> None:
        journal = self._journal(tmp_path)
        first = store.rebuild_from_journal(journal)
        second = store.rebuild_from_journal(journal)
        assert first == second
        assert len(store.node_states("run-9")) == 2

    def test_rebuild_detects_a_run_awaiting_approval(self, tmp_path: Path, store: RunStore) -> None:
        journal = Journal(tmp_path / "j2.jsonl", run_id="run-10", clock=fixed_clock())
        journal.append("run_started", pipeline="p.yaml")
        journal.append("node_pending_approval", node_id="design")
        assert store.rebuild_from_journal(journal).status is RunStatus.PENDING_APPROVAL

    def test_terminal_run_events_are_reflected(self, tmp_path: Path, store: RunStore) -> None:
        journal = Journal(tmp_path / "j3.jsonl", run_id="run-11", clock=fixed_clock())
        journal.append("run_started", pipeline="p.yaml")
        journal.append("run_safe_stopped", reason="budget exceeded")
        assert store.rebuild_from_journal(journal).status is RunStatus.SAFE_STOPPED

    def test_empty_journal_is_rejected(self, tmp_path: Path, store: RunStore) -> None:
        empty = Journal(tmp_path / "empty.jsonl", run_id="run-12", clock=fixed_clock())
        with pytest.raises(ValueError, match="empty journal"):
            store.rebuild_from_journal(empty)
