"""Reliability metrics, derived from the journal and nothing else.

Computing these from the hash-chained record rather than from counters kept
alongside it means the numbers cannot quietly disagree with the audit trail. If
the journal verifies, the metrics are what the journal says; if someone edits
the journal to improve the numbers, verification fails first.

MTTR needs its definition stated because the standard one does not transfer.
There is no "service restored" event in a pipeline, so it is measured here as
the elapsed time from a node's first failing gate to the next passing gate on
that same node -- how long the machinery took to get itself back to green,
including retries, rollbacks, replans, and any time spent waiting for a human.
Runs with no failures have no MTTR, which is why fault injection is a scenario
rather than an afterthought: a clean run cannot produce this number.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from statistics import mean
from typing import Any

from .audit import Journal, JournalEntry

TERMINAL_EVENTS = ("run_completed", "run_failed", "run_safe_stopped")


def _parse(ts: str) -> datetime | None:
    try:
        return datetime.fromisoformat(ts)
    except (ValueError, TypeError):
        return None


def _elapsed(start: str, end: str) -> float | None:
    a, b = _parse(start), _parse(end)
    if a is None or b is None:
        return None
    return (b - a).total_seconds()


@dataclass
class NodeMetrics:
    node_id: str
    attempts: int = 0
    attempt_failures: int = 0
    gate_passes: int = 0
    gate_failures: int = 0
    escalations: int = 0
    cost_usd: float = 0.0
    duration_seconds: float | None = None
    recovered_in_seconds: list[float] = field(default_factory=list)
    final_status: str = "pending"

    @property
    def retried(self) -> bool:
        return self.attempts > 1


@dataclass
class RunMetrics:
    run_id: str
    status: str = "unknown"
    nodes: dict[str, NodeMetrics] = field(default_factory=dict)
    total_cost_usd: float = 0.0
    e2e_latency_seconds: float | None = None
    replans: int = 0
    rollbacks: int = 0
    safe_stops: int = 0
    human_decisions: int = 0
    parallel_groups: int = 0

    # -- derived ---------------------------------------------------------

    @property
    def node_success_rate(self) -> float:
        """Share of attempted nodes that ended green."""
        attempted = [n for n in self.nodes.values() if n.attempts or n.final_status != "pending"]
        if not attempted:
            return 0.0
        return sum(1 for n in attempted if n.final_status == "passed") / len(attempted)

    @property
    def gate_pass_rate(self) -> float:
        passes = sum(n.gate_passes for n in self.nodes.values())
        total = passes + sum(n.gate_failures for n in self.nodes.values())
        return passes / total if total else 0.0

    @property
    def retry_frequency(self) -> float:
        """Retried nodes as a share of nodes that ran. The 'how often does the
        first attempt not land' number."""
        ran = [n for n in self.nodes.values() if n.attempts]
        return (sum(1 for n in ran if n.retried) / len(ran)) if ran else 0.0

    @property
    def rollback_frequency(self) -> float:
        ran = [n for n in self.nodes.values() if n.attempts]
        return (self.rollbacks / len(ran)) if ran else 0.0

    @property
    def mttr_seconds(self) -> float | None:
        samples = [s for n in self.nodes.values() for s in n.recovered_in_seconds]
        return mean(samples) if samples else None

    def as_dict(self) -> dict[str, Any]:
        return {
            "run_id": self.run_id,
            "status": self.status,
            "node_success_rate": round(self.node_success_rate, 4),
            "gate_pass_rate": round(self.gate_pass_rate, 4),
            "retry_frequency": round(self.retry_frequency, 4),
            "rollback_frequency": round(self.rollback_frequency, 4),
            "replans": self.replans,
            "rollbacks": self.rollbacks,
            "safe_stops": self.safe_stops,
            "human_decisions": self.human_decisions,
            "parallel_groups": self.parallel_groups,
            "mttr_seconds": round(self.mttr_seconds, 2) if self.mttr_seconds else None,
            "e2e_latency_seconds": (
                round(self.e2e_latency_seconds, 2) if self.e2e_latency_seconds else None
            ),
            "total_cost_usd": round(self.total_cost_usd, 4),
        }


def compute(journal: Journal, *, verify: bool = True) -> RunMetrics:
    """Fold the journal into a metrics record.

    `verify` defaults to True on purpose: reporting numbers derived from a log
    that has not been checked would be exactly the sort of assurance this
    system is meant to make unnecessary.
    """
    if verify:
        journal.verify()

    entries = journal.entries()
    if not entries:
        raise ValueError("cannot compute metrics from an empty journal")

    metrics = RunMetrics(run_id=entries[0].run_id)
    # When a node's gate last failed, so the next pass can be measured against it.
    broke_at: dict[str, str] = {}

    for entry in entries:
        node = _node_metrics(metrics, entry)
        payload = entry.payload
        metrics.total_cost_usd += float(payload.get("cost_usd") or 0.0)

        match entry.event:
            case "node_started":
                if node:
                    node.attempts += 1
            case "node_attempt_failed":
                if node:
                    node.attempt_failures += 1
                    broke_at.setdefault(entry.node_id, entry.ts)
            case "gate_evaluated":
                _record_gate(node, entry, broke_at, metrics)
            case "node_passed":
                if node:
                    node.final_status = "passed"
                    node.cost_usd += float(payload.get("cost_usd") or 0.0)
                    _record_recovery(node, entry, broke_at)
            case "node_failed":
                if node:
                    node.final_status = "failed"
                    broke_at.setdefault(entry.node_id, entry.ts)
            case "node_pending_approval":
                if node:
                    node.final_status = "pending_approval"
                    node.escalations += 1
            case "node_rolled_back":
                metrics.rollbacks += 1
            case "replan_triggered":
                metrics.replans += 1
            case "rejection_applied" | "human_decision":
                metrics.human_decisions += 1
            case "parallel_started":
                metrics.parallel_groups += 1
            case "run_safe_stopped":
                metrics.safe_stops += 1

    metrics.status = _final_status(entries)
    metrics.e2e_latency_seconds = _elapsed(entries[0].ts, entries[-1].ts)
    _fill_durations(metrics, entries)
    return metrics


def _node_metrics(metrics: RunMetrics, entry: JournalEntry) -> NodeMetrics | None:
    if not entry.node_id:
        return None
    return metrics.nodes.setdefault(entry.node_id, NodeMetrics(node_id=entry.node_id))


def _record_gate(
    node: NodeMetrics | None,
    entry: JournalEntry,
    broke_at: dict[str, str],
    metrics: RunMetrics,
) -> None:
    if node is None:
        return
    outcome = entry.payload.get("outcome")
    if outcome == "pass":
        node.gate_passes += 1
    elif outcome == "fail":
        node.gate_failures += 1
        broke_at.setdefault(entry.node_id, entry.ts)
    elif outcome == "escalate":
        node.escalations += 1


def _record_recovery(node: NodeMetrics, entry: JournalEntry, broke_at: dict[str, str]) -> None:
    """Close out an MTTR sample: this node was broken, and now it is green."""
    started = broke_at.pop(entry.node_id, None)
    if started is None:
        return
    if (elapsed := _elapsed(started, entry.ts)) is not None:
        node.recovered_in_seconds.append(elapsed)


def _final_status(entries: list[JournalEntry]) -> str:
    for entry in reversed(entries):
        if entry.event in TERMINAL_EVENTS:
            return entry.event.removeprefix("run_")
        if entry.event == "run_pending_approval":
            return "pending_approval"
    return "running"


def _fill_durations(metrics: RunMetrics, entries: list[JournalEntry]) -> None:
    started: dict[str, str] = {}
    for entry in entries:
        if not entry.node_id:
            continue
        if entry.event == "node_started":
            started[entry.node_id] = entry.ts
        elif entry.event in ("node_passed", "node_failed", "node_pending_approval"):
            if begin := started.pop(entry.node_id, None):
                node = metrics.nodes[entry.node_id]
                node.duration_seconds = _elapsed(begin, entry.ts)


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------


def render_text(metrics: RunMetrics) -> str:
    """Terminal report. The HTML one is a nicety; this is the one that must exist."""
    lines = [
        f"Run {metrics.run_id} — {metrics.status.upper()}",
        "",
        f"{'node':<20} {'status':<18} {'att':>4} {'gates':>7} {'cost':>9} {'secs':>8}",
        "-" * 70,
    ]
    for node_id, node in metrics.nodes.items():
        gates = f"{node.gate_passes}/{node.gate_passes + node.gate_failures}"
        secs = f"{node.duration_seconds:.1f}" if node.duration_seconds is not None else "-"
        lines.append(
            f"{node_id:<20} {node.final_status:<18} {node.attempts:>4} "
            f"{gates:>7} {node.cost_usd:>8.2f}$ {secs:>8}"
        )

    mttr = f"{metrics.mttr_seconds:.1f}s" if metrics.mttr_seconds is not None else "n/a (no failures)"
    latency = (
        f"{metrics.e2e_latency_seconds:.1f}s"
        if metrics.e2e_latency_seconds is not None
        else "n/a"
    )
    lines += [
        "-" * 70,
        f"node success rate    {metrics.node_success_rate:.0%}",
        f"gate pass rate       {metrics.gate_pass_rate:.0%}",
        f"retry frequency      {metrics.retry_frequency:.0%} of nodes needed more than one attempt",
        f"rollback frequency   {metrics.rollback_frequency:.0%}",
        f"replans              {metrics.replans}",
        f"human decisions      {metrics.human_decisions}",
        f"parallel groups      {metrics.parallel_groups}",
        f"MTTR                 {mttr}",
        f"end-to-end latency   {latency}",
        f"total cost           ${metrics.total_cost_usd:.2f}",
    ]
    return "\n".join(lines)
