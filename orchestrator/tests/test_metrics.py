"""Reliability metrics.

The property under test is that the numbers come from the journal and only the
journal, so they cannot drift away from the audit trail. The MTTR tests exist
because the definition is non-standard and a metric nobody can define is worse
than no metric.
"""

from __future__ import annotations

import itertools
from pathlib import Path

import pytest

from sdlc.audit import Journal, TamperError
from sdlc.metrics import compute, render_text


def clock_from(*seconds: int):
    """ISO timestamps at chosen offsets, so elapsed times are exact."""
    values = list(seconds)
    counter = itertools.count()

    def clock() -> str:
        i = next(counter)
        offset = values[min(i, len(values) - 1)]
        return f"2026-08-09T09:{offset // 60:02d}:{offset % 60:02d}+00:00"

    return clock


@pytest.fixture
def journal(tmp_path: Path) -> Journal:
    return Journal(tmp_path / "j.jsonl", run_id="run-1", clock=clock_from(0))


def seed_clean(journal: Journal) -> None:
    journal.append("run_started")
    for node in ("design", "implement"):
        journal.append("node_started", node_id=node)
        journal.append("gate_evaluated", node_id=node, outcome="pass", check="x")
        journal.append("node_passed", node_id=node, cost_usd=1.5)
    journal.append("run_completed")


class TestCleanRun:
    def test_everything_green(self, journal: Journal) -> None:
        seed_clean(journal)
        metrics = compute(journal)
        assert metrics.status == "completed"
        assert metrics.node_success_rate == 1.0
        assert metrics.gate_pass_rate == 1.0
        assert metrics.retry_frequency == 0.0
        assert metrics.total_cost_usd == pytest.approx(3.0)

    def test_a_clean_run_has_no_mttr(self, journal: Journal) -> None:
        """Which is exactly why fault injection is a scenario, not an afterthought."""
        seed_clean(journal)
        assert compute(journal).mttr_seconds is None

    def test_metrics_refuse_a_tampered_journal(self, journal: Journal) -> None:
        seed_clean(journal)
        lines = journal.path.read_text().splitlines()
        lines[2] = lines[2].replace('"outcome":"pass"', '"outcome":"fail"')
        journal.path.write_text("\n".join(lines) + "\n")
        with pytest.raises(TamperError):
            compute(journal)

    def test_an_empty_journal_is_rejected(self, tmp_path: Path) -> None:
        with pytest.raises(ValueError, match="empty journal"):
            compute(Journal(tmp_path / "empty.jsonl", run_id="r"))


class TestFailurePaths:
    def _failing(self, tmp_path: Path) -> Journal:
        # 0s start, fails at 10s, recovers at 40s: MTTR is 30 seconds.
        journal = Journal(
            tmp_path / "j.jsonl", run_id="run-1", clock=clock_from(0, 0, 10, 20, 40, 40, 50)
        )
        journal.append("run_started")
        journal.append("node_started", node_id="verify")
        journal.append("gate_evaluated", node_id="verify", outcome="fail", check="maven_verify")
        journal.append("node_started", node_id="verify", attempt=1)
        journal.append("gate_evaluated", node_id="verify", outcome="pass", check="maven_verify")
        journal.append("node_passed", node_id="verify", cost_usd=2.0)
        journal.append("run_completed")
        return journal

    def test_retry_frequency_counts_nodes_not_attempts(self, tmp_path: Path) -> None:
        metrics = compute(self._failing(tmp_path))
        assert metrics.nodes["verify"].attempts == 2
        assert metrics.retry_frequency == 1.0

    def test_mttr_measures_first_failure_to_next_green(self, tmp_path: Path) -> None:
        assert compute(self._failing(tmp_path)).mttr_seconds == pytest.approx(30.0)

    def test_gate_pass_rate_reflects_both_outcomes(self, tmp_path: Path) -> None:
        assert compute(self._failing(tmp_path)).gate_pass_rate == pytest.approx(0.5)

    def test_end_to_end_latency_spans_the_whole_run(self, tmp_path: Path) -> None:
        assert compute(self._failing(tmp_path)).e2e_latency_seconds == pytest.approx(50.0)

    def test_replans_rollbacks_and_stops_are_counted(self, journal: Journal) -> None:
        journal.append("run_started")
        journal.append("node_started", node_id="verify")
        journal.append("node_failed", node_id="verify", error="boom")
        journal.append("node_rolled_back", node_id="verify", to_commit="abc")
        journal.append("replan_triggered", node_id="decompose")
        journal.append("run_safe_stopped", reason="replan limit reached")
        metrics = compute(journal)
        assert metrics.rollbacks == 1 and metrics.replans == 1 and metrics.safe_stops == 1
        assert metrics.status == "safe_stopped"

    def test_a_failed_node_drags_the_success_rate_down(self, journal: Journal) -> None:
        journal.append("run_started")
        journal.append("node_started", node_id="a")
        journal.append("node_passed", node_id="a")
        journal.append("node_started", node_id="b")
        journal.append("node_failed", node_id="b", error="boom")
        journal.append("run_failed")
        assert compute(journal).node_success_rate == pytest.approx(0.5)


class TestHumanAndParallel:
    def test_escalations_and_decisions_are_visible(self, journal: Journal) -> None:
        journal.append("run_started")
        journal.append("node_started", node_id="design")
        journal.append("gate_evaluated", node_id="design", outcome="escalate", check="human_approval")
        journal.append("node_pending_approval", node_id="design")
        journal.append("human_decision", node_id="design", decision="approved", approver="neil")
        metrics = compute(journal)
        assert metrics.human_decisions == 1
        assert metrics.nodes["design"].escalations == 2  # the gate and the node state
        assert metrics.status == "running"

    def test_parallel_groups_are_counted(self, journal: Journal) -> None:
        journal.append("run_started")
        journal.append("parallel_started", nodes=["implement", "author-tests"])
        journal.append("parallel_joined", nodes=["implement", "author-tests"])
        assert compute(journal).parallel_groups == 1


class TestRendering:
    def test_the_report_names_every_metric_the_brief_asks_for(self, journal: Journal) -> None:
        seed_clean(journal)
        text = render_text(compute(journal))
        for label in ("success rate", "retry frequency", "rollback frequency", "MTTR", "latency"):
            assert label in text

    def test_missing_mttr_is_explained_rather_than_shown_as_zero(self, journal: Journal) -> None:
        """Zero MTTR would read as 'instant recovery' instead of 'never broke'."""
        seed_clean(journal)
        assert "n/a (no failures)" in render_text(compute(journal))

    def test_json_output_is_serializable(self, journal: Journal) -> None:
        import json

        seed_clean(journal)
        assert json.loads(json.dumps(compute(journal).as_dict()))["status"] == "completed"


class TestCostAccounting:
    """What a run cost, and how much of it bought nothing.

    Every number here was wrong before: the per-node column reported only the
    attempt that happened to be accepted, the total counted re-gated entries
    twice, and attempts rejected by a gate were recorded with no cost at all.
    Three different ways for the same report to understate or overstate the
    same spend.
    """

    def _spend(self, journal: Journal) -> None:
        journal.append("run_started")
        journal.append("node_started", node_id="design")
        # Rejected by a human: the money was spent and the work was thrown away.
        journal.append("node_pending_approval", node_id="design", cost_usd=4.0)
        journal.append("node_started", node_id="design", attempt=1)
        journal.append("node_pending_approval", node_id="design", cost_usd=1.0)
        # Approved, then resumed: re-gated, not re-run, so it cost nothing.
        journal.append("node_passed", node_id="design", cost_usd=0.0)
        journal.append("node_started", node_id="implement")
        journal.append("node_attempt_failed", node_id="implement", cost_usd=2.0)
        journal.append("node_started", node_id="implement", attempt=1)
        journal.append("node_passed", node_id="implement", cost_usd=3.0)

    def test_a_nodes_cost_includes_the_attempts_that_were_thrown_away(
        self, journal: Journal
    ) -> None:
        self._spend(journal)
        metrics = compute(journal, verify=False)
        assert metrics.nodes["design"].cost_usd == pytest.approx(5.0)
        assert metrics.nodes["implement"].cost_usd == pytest.approx(5.0)

    def test_the_total_is_the_sum_of_the_nodes(self, journal: Journal) -> None:
        """Same arithmetic in both places, so they cannot tell different stories."""
        self._spend(journal)
        metrics = compute(journal, verify=False)
        assert metrics.total_cost_usd == pytest.approx(
            sum(n.cost_usd for n in metrics.nodes.values())
        )

    def test_rework_is_the_spend_a_later_attempt_replaced(self, journal: Journal) -> None:
        """$4 on the rejected contract, $2 on the failed attempt. 60% of the run."""
        self._spend(journal)
        metrics = compute(journal, verify=False)
        assert metrics.rework_cost_usd == pytest.approx(6.0)
        assert metrics.rework_share == pytest.approx(0.6)

    def test_a_clean_run_reports_no_rework(self, journal: Journal) -> None:
        seed_clean(journal)
        metrics = compute(journal, verify=False)
        assert metrics.rework_cost_usd == pytest.approx(0.0)

    def test_the_report_states_the_rework_beside_the_total(self, journal: Journal) -> None:
        self._spend(journal)
        rendered = render_text(compute(journal, verify=False))
        assert "of which rework     $6.00" in rendered

    def test_mttr_distinguishes_never_broke_from_never_recovered(
        self, journal: Journal
    ) -> None:
        """Reporting one sentence for both read as a clean run either way."""
        journal.append("run_started")
        journal.append("node_started", node_id="verify")
        journal.append(
            "gate_evaluated",
            node_id="verify",
            check="maven_verify",
            outcome="fail",
            gate_class="mechanical",
            phase="exit",
        )
        journal.append("node_failed", node_id="verify")
        assert "never recovered" in render_text(compute(journal, verify=False))
