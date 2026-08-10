"""Operational run state.

Two stores, deliberately, with different jobs:

  * `audit.Journal` is the append-only, hash-chained record of *what happened*.
    Authoritative, tamper-evident, never updated in place.
  * this module is the mutable record of *where the run is now* -- current node
    statuses, attempt counters, the latest approval. Rows are updated as the
    run progresses, which is exactly what an audit record must never do.

Keeping them separate means the audit trail cannot be quietly edited by normal
operation, and it makes `rebuild_from_journal()` possible: this database is a
materialized view and can be reconstructed from the journal if it is lost or
suspected. The reverse is not true, and that asymmetry is the point.

SQLite rather than a JSON file for two concrete reasons, not habit:

  * the parallel branch means two node runners write concurrently;
  * `orchestrator stop <run>` is a *different process* setting a flag that the
    running engine reads at its next node boundary. Safe-stop needs shared
    durable state, not an in-memory variable.
"""

from __future__ import annotations

import json
import sqlite3
import threading
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from .model import Approval, ApprovalDecision, NodeStatus, RunStatus

SCHEMA = """
CREATE TABLE IF NOT EXISTS runs (
    run_id          TEXT PRIMARY KEY,
    pipeline        TEXT NOT NULL,
    scenario        TEXT,
    status          TEXT NOT NULL,
    branch          TEXT,
    workspace       TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    cost_usd        REAL NOT NULL DEFAULT 0,
    replans         INTEGER NOT NULL DEFAULT 0,
    stop_requested  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS node_state (
    run_id             TEXT NOT NULL,
    node_id            TEXT NOT NULL,
    status             TEXT NOT NULL,
    attempt            INTEGER NOT NULL DEFAULT 0,
    input_hash         TEXT,
    checkpoint_commit  TEXT,
    started_at         TEXT,
    ended_at           TEXT,
    cost_usd           REAL NOT NULL DEFAULT 0,
    error              TEXT,
    -- Never reset, unlike `attempt`, which restarts whenever a replan
    -- re-enters the node. This is the durable "how many times has this node
    -- actually been invoked" counter, and it survives process restarts.
    invocations        INTEGER NOT NULL DEFAULT 0,
    repairs            INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (run_id, node_id)
);

-- One row per node: the latest decision wins. The full sequence of decisions,
-- including a rejection that was later superseded by an approval, lives in the
-- journal -- which is where an auditor should be looking anyway.
CREATE TABLE IF NOT EXISTS approvals (
    run_id    TEXT NOT NULL,
    node_id   TEXT NOT NULL,
    decision  TEXT NOT NULL,
    approver  TEXT NOT NULL,
    note      TEXT NOT NULL DEFAULT '',
    answers   TEXT NOT NULL DEFAULT '{}',
    ts        TEXT NOT NULL,
    PRIMARY KEY (run_id, node_id)
);
"""


def _utc_now() -> str:
    return datetime.now(UTC).isoformat()


@dataclass(frozen=True)
class RunRecord:
    run_id: str
    pipeline: str
    scenario: str | None
    status: RunStatus
    branch: str | None
    workspace: str | None
    created_at: str
    updated_at: str
    cost_usd: float = 0.0
    replans: int = 0
    stop_requested: bool = False


@dataclass(frozen=True)
class NodeState:
    run_id: str
    node_id: str
    status: NodeStatus
    attempt: int = 0
    input_hash: str | None = None
    checkpoint_commit: str | None = None
    started_at: str | None = None
    ended_at: str | None = None
    cost_usd: float = 0.0
    error: str | None = None
    invocations: int = 0
    # Times triage has routed a repair to this node. Never reset, so a node
    # cannot be sent back indefinitely by alternating verdicts.
    repairs: int = 0


@dataclass
class RunStore:
    """SQLite-backed run state. One database per orchestrator installation."""

    path: str | Path
    clock: Callable[[], str] = _utc_now
    _local: threading.local = field(init=False, repr=False, default_factory=threading.local)
    _memory_conn: sqlite3.Connection | None = field(init=False, repr=False, default=None)

    def __post_init__(self) -> None:
        self.path = Path(self.path)
        self._in_memory = str(self.path) == ":memory:"
        if not self._in_memory:
            self.path.parent.mkdir(parents=True, exist_ok=True)
        conn = self._conn
        # WAL so `orchestrator status` and `orchestrator stop` can read while a
        # run is mid-flight instead of blocking on the writer's lock.
        if not self._in_memory:
            conn.execute("PRAGMA journal_mode=WAL")
        conn.executescript(SCHEMA)
        conn.commit()

    @property
    def _conn(self) -> sqlite3.Connection:
        """One connection per thread.

        The parallel branch runs two node bodies concurrently and both write
        state, and a sqlite3 connection may not cross threads. Giving each
        thread its own connection over a WAL database is simpler and less
        deadlock-prone than serializing every call through a lock, and SQLite
        already knows how to arbitrate concurrent writers -- `timeout` is the
        busy-wait for that.
        """
        if self._in_memory:
            # An in-memory database is private to its connection, so it cannot
            # be shared across threads at all. Single-threaded use only.
            if self._memory_conn is None:
                self._memory_conn = sqlite3.connect(":memory:")
                self._memory_conn.row_factory = sqlite3.Row
            return self._memory_conn

        conn = getattr(self._local, "conn", None)
        if conn is None:
            conn = sqlite3.connect(str(self.path), timeout=30.0)
            conn.row_factory = sqlite3.Row
            self._local.conn = conn
        return conn

    def close(self) -> None:
        """Closes this thread's connection. Others are closed by their own threads."""
        if self._in_memory:
            if self._memory_conn is not None:
                self._memory_conn.close()
                self._memory_conn = None
            return
        if (conn := getattr(self._local, "conn", None)) is not None:
            conn.close()
            self._local.conn = None

    def __enter__(self) -> RunStore:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    # -- runs ------------------------------------------------------------

    def create_run(
        self,
        run_id: str,
        *,
        pipeline: str,
        scenario: str | None = None,
        branch: str | None = None,
        workspace: str | None = None,
    ) -> RunRecord:
        now = self.clock()
        with self._conn:
            self._conn.execute(
                "INSERT INTO runs (run_id, pipeline, scenario, status, branch, "
                "workspace, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                (
                    run_id,
                    pipeline,
                    scenario,
                    RunStatus.RUNNING.value,
                    branch,
                    workspace,
                    now,
                    now,
                ),
            )
        return self.get_run(run_id)

    def get_run(self, run_id: str) -> RunRecord:
        row = self._conn.execute(
            "SELECT * FROM runs WHERE run_id = ?", (run_id,)
        ).fetchone()
        if row is None:
            raise KeyError(f"no such run: {run_id}")
        return RunRecord(
            run_id=row["run_id"],
            pipeline=row["pipeline"],
            scenario=row["scenario"],
            status=RunStatus(row["status"]),
            branch=row["branch"],
            workspace=row["workspace"],
            created_at=row["created_at"],
            updated_at=row["updated_at"],
            cost_usd=row["cost_usd"],
            replans=row["replans"],
            stop_requested=bool(row["stop_requested"]),
        )

    def list_runs(self, limit: int = 50) -> list[RunRecord]:
        rows = self._conn.execute(
            "SELECT run_id FROM runs ORDER BY created_at DESC LIMIT ?", (limit,)
        ).fetchall()
        return [self.get_run(r["run_id"]) for r in rows]

    def set_run_status(self, run_id: str, status: RunStatus) -> None:
        self._update_run(run_id, status=status.value)

    def add_cost(self, run_id: str, amount: float) -> float:
        """Accumulate spend and return the new total.

        Returned rather than voided so the budget guard reads the committed
        value instead of a number it added up itself -- with the parallel
        branch, two nodes bill concurrently.
        """
        with self._conn:
            self._conn.execute(
                "UPDATE runs SET cost_usd = cost_usd + ?, updated_at = ? WHERE run_id = ?",
                (amount, self.clock(), run_id),
            )
        return self.get_run(run_id).cost_usd

    def increment_replans(self, run_id: str) -> int:
        with self._conn:
            self._conn.execute(
                "UPDATE runs SET replans = replans + 1, updated_at = ? WHERE run_id = ?",
                (self.clock(), run_id),
            )
        return self.get_run(run_id).replans

    def request_stop(self, run_id: str) -> None:
        """Operator-initiated safe-stop.

        Sets a flag; it does not kill anything. The engine checks it at node
        boundaries so the run halts somewhere it can be resumed from, rather
        than mid-write with a half-edited worktree.
        """
        self._update_run(run_id, stop_requested=1)

    def stop_requested(self, run_id: str) -> bool:
        row = self._conn.execute(
            "SELECT stop_requested FROM runs WHERE run_id = ?", (run_id,)
        ).fetchone()
        return bool(row and row["stop_requested"])

    def clear_stop(self, run_id: str) -> None:
        self._update_run(run_id, stop_requested=0)

    def _update_run(self, run_id: str, **fields: Any) -> None:
        if not fields:
            return
        # Callers may supply updated_at explicitly. Reconstruction does, because
        # a rebuilt run must reproduce the journal's timeline rather than record
        # when the rebuild happened -- otherwise rebuilding twice gives two
        # different answers and the comparison against the audit trail is
        # meaningless.
        fields.setdefault("updated_at", self.clock())
        assignments = ", ".join(f"{k} = ?" for k in fields)
        with self._conn:
            cursor = self._conn.execute(
                f"UPDATE runs SET {assignments} WHERE run_id = ?",
                (*fields.values(), run_id),
            )
        if cursor.rowcount == 0:
            raise KeyError(f"no such run: {run_id}")

    # -- nodes -----------------------------------------------------------

    def set_node(
        self,
        run_id: str,
        node_id: str,
        status: NodeStatus,
        **fields: Any,
    ) -> NodeState:
        """Upsert a node's state. Unspecified columns keep their current value."""
        allowed = {
            "attempt",
            "input_hash",
            "checkpoint_commit",
            "started_at",
            "ended_at",
            "cost_usd",
            "error",
        }
        if unknown := set(fields) - allowed:
            raise ValueError(f"unknown node_state column(s): {', '.join(sorted(unknown))}")

        columns = ["status", *fields]
        values = [status.value, *fields.values()]
        placeholders = ", ".join("?" for _ in columns)
        updates = ", ".join(f"{c} = excluded.{c}" for c in columns)
        with self._conn:
            self._conn.execute(
                f"INSERT INTO node_state (run_id, node_id, {', '.join(columns)}) "
                f"VALUES (?, ?, {placeholders}) "
                f"ON CONFLICT(run_id, node_id) DO UPDATE SET {updates}",
                (run_id, node_id, *values),
            )
        return self.get_node(run_id, node_id)

    def get_node(self, run_id: str, node_id: str) -> NodeState:
        row = self._conn.execute(
            "SELECT * FROM node_state WHERE run_id = ? AND node_id = ?",
            (run_id, node_id),
        ).fetchone()
        if row is None:
            # An unvisited node is PENDING, not an error -- callers ask about
            # nodes the scheduler has not reached yet on every pass.
            return NodeState(run_id=run_id, node_id=node_id, status=NodeStatus.PENDING)
        return NodeState(
            run_id=row["run_id"],
            node_id=row["node_id"],
            status=NodeStatus(row["status"]),
            attempt=row["attempt"],
            input_hash=row["input_hash"],
            checkpoint_commit=row["checkpoint_commit"],
            started_at=row["started_at"],
            ended_at=row["ended_at"],
            cost_usd=row["cost_usd"],
            error=row["error"],
            invocations=row["invocations"],
            repairs=row["repairs"],
        )

    def record_repair(self, run_id: str, node_id: str) -> int:
        """Count a triage-routed repair against this node, and return the total.

        Kept here rather than in the engine's memory for the same reason the
        invocation counter is: a run that pauses for approval resumes in another
        process, and a budget that resets when the process does is not a budget.
        """
        with self._conn:
            self._conn.execute(
                "INSERT INTO node_state (run_id, node_id, status, repairs) "
                "VALUES (?, ?, ?, 1) "
                "ON CONFLICT(run_id, node_id) DO UPDATE SET "
                "repairs = node_state.repairs + 1",
                (run_id, node_id, NodeStatus.PENDING.value),
            )
        return self.get_node(run_id, node_id).repairs

    def next_invocation(self, run_id: str, node_id: str) -> int:
        """Reserve the next invocation number for a node.

        Distinct from `attempt`, which counts tries within one execution and
        resets when a replan re-enters the node. This one only ever goes up, and
        it lives in the database rather than in the engine's memory so it means
        the same thing across a `run` and a later `resume` -- two different
        processes. The scripted backend indexes on it, which is what makes a
        replay identical no matter how many times the process restarted.
        """
        with self._conn:
            self._conn.execute(
                "INSERT INTO node_state (run_id, node_id, status, invocations) "
                "VALUES (?, ?, ?, 1) "
                "ON CONFLICT(run_id, node_id) DO UPDATE SET "
                "invocations = node_state.invocations + 1",
                (run_id, node_id, NodeStatus.RUNNING.value),
            )
        return self.get_node(run_id, node_id).invocations - 1

    def node_states(self, run_id: str) -> dict[str, NodeState]:
        rows = self._conn.execute(
            "SELECT node_id FROM node_state WHERE run_id = ?", (run_id,)
        ).fetchall()
        return {r["node_id"]: self.get_node(run_id, r["node_id"]) for r in rows}

    def mark_stale(self, run_id: str, node_ids: list[str]) -> None:
        """Change-driven replanning: a node whose inputs moved must run again.

        Only nodes that already completed are marked; a pending node is already
        going to run.
        """
        for node_id in node_ids:
            current = self.get_node(run_id, node_id)
            if current.status in (NodeStatus.PASSED, NodeStatus.SKIPPED):
                self.set_node(run_id, node_id, NodeStatus.STALE)

    def latest_checkpoint(self, run_id: str, node_ids: list[str]) -> str | None:
        """Most recent checkpoint commit among the given nodes, for rollback."""
        for node_id in reversed(node_ids):
            state = self.get_node(run_id, node_id)
            if state.checkpoint_commit:
                return state.checkpoint_commit
        return None

    # -- approvals -------------------------------------------------------

    def record_approval(self, run_id: str, approval: Approval) -> None:
        with self._conn:
            self._conn.execute(
                "INSERT INTO approvals (run_id, node_id, decision, approver, note, "
                "answers, ts) VALUES (?,?,?,?,?,?,?) "
                "ON CONFLICT(run_id, node_id) DO UPDATE SET "
                "decision=excluded.decision, approver=excluded.approver, "
                "note=excluded.note, answers=excluded.answers, ts=excluded.ts",
                (
                    run_id,
                    approval.node_id,
                    approval.decision.value,
                    approval.approver,
                    approval.note,
                    json.dumps(approval.answers, sort_keys=True),
                    approval.ts or self.clock(),
                ),
            )

    def clear_approval(self, run_id: str, node_id: str) -> None:
        """Discard a decision so the node must be approved again.

        Used after a rejection: the node re-runs with the reviewer's note, and
        the revised result has to face a fresh decision. Leaving the rejection
        on file would fail the gate forever; deleting it silently would lose
        the four-eyes record -- which is why the journal, not this table, is the
        evidence.
        """
        with self._conn:
            self._conn.execute(
                "DELETE FROM approvals WHERE run_id = ? AND node_id = ?",
                (run_id, node_id),
            )

    def approvals(self, run_id: str) -> dict[str, Approval]:
        """All recorded decisions, keyed by node -- handed straight to the gates."""
        rows = self._conn.execute(
            "SELECT * FROM approvals WHERE run_id = ?", (run_id,)
        ).fetchall()
        return {
            r["node_id"]: Approval(
                node_id=r["node_id"],
                decision=ApprovalDecision(r["decision"]),
                approver=r["approver"],
                note=r["note"],
                ts=r["ts"],
                answers=json.loads(r["answers"]),
            )
            for r in rows
        }

    # -- reconstruction --------------------------------------------------

    def rebuild_from_journal(self, journal: Any, *, verify: bool = True) -> RunRecord:
        """Reconstruct this run's state from its journal.

        The database is a convenience; the journal is the record. If the
        database is lost, corrupted, or simply not trusted, the run's state can
        be derived again from the hash-chained log -- and `verify=True` means
        the chain is checked before anything is believed.

        This is also the cheapest possible answer to "how do we know the status
        dashboard matches the audit trail?": rebuild and compare.
        """
        if verify:
            journal.verify()

        entries = journal.entries()
        if not entries:
            raise ValueError("cannot rebuild from an empty journal")
        run_id = entries[0].run_id

        with self._conn:
            self._conn.execute("DELETE FROM node_state WHERE run_id = ?", (run_id,))
            self._conn.execute("DELETE FROM approvals WHERE run_id = ?", (run_id,))
            self._conn.execute("DELETE FROM runs WHERE run_id = ?", (run_id,))

        first, last = entries[0], entries[-1]
        with self._conn:
            self._conn.execute(
                "INSERT INTO runs (run_id, pipeline, scenario, status, branch, "
                "workspace, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                (
                    run_id,
                    first.payload.get("pipeline", "<unknown>"),
                    first.payload.get("scenario"),
                    RunStatus.RUNNING.value,
                    first.payload.get("branch"),
                    first.payload.get("workspace"),
                    first.ts,
                    last.ts,
                ),
            )

        status_by_event = {
            "node_started": NodeStatus.RUNNING,
            "node_passed": NodeStatus.PASSED,
            "node_failed": NodeStatus.FAILED,
            "node_pending_approval": NodeStatus.PENDING_APPROVAL,
            "node_rejected": NodeStatus.REJECTED,
            "node_rolled_back": NodeStatus.ROLLED_BACK,
            "node_skipped": NodeStatus.SKIPPED,
            "node_stale": NodeStatus.STALE,
        }
        run_status = RunStatus.RUNNING
        cost = 0.0
        replans = 0

        for entry in entries:
            payload = entry.payload
            cost += float(payload.get("cost_usd", 0.0) or 0.0)
            if entry.event == "replan_triggered":
                replans += 1
            elif entry.event == "run_completed":
                run_status = RunStatus.COMPLETED
            elif entry.event == "run_failed":
                run_status = RunStatus.FAILED
            elif entry.event == "run_safe_stopped":
                run_status = RunStatus.SAFE_STOPPED
            elif entry.event == "human_decision" and entry.node_id:
                self.record_approval(
                    run_id,
                    Approval(
                        node_id=entry.node_id,
                        decision=ApprovalDecision(payload.get("decision", "approved")),
                        approver=payload.get("approver", "<unknown>"),
                        note=payload.get("note", ""),
                        ts=entry.ts,
                        answers=payload.get("answers") or {},
                    ),
                )
            if entry.node_id and (status := status_by_event.get(entry.event)):
                fields = {
                    "attempt": entry.attempt,
                    "input_hash": entry.input_hash,
                    "cost_usd": float(payload.get("cost_usd", 0.0) or 0.0),
                    "error": payload.get("error"),
                    "checkpoint_commit": payload.get("checkpoint_commit"),
                }
                # A later event carrying no checkpoint must not erase the one an
                # earlier event recorded, so absent fields are simply not written.
                self.set_node(
                    run_id,
                    entry.node_id,
                    status,
                    **{k: v for k, v in fields.items() if v is not None},
                )

        if run_status is RunStatus.RUNNING and any(
            s.status is NodeStatus.PENDING_APPROVAL
            for s in self.node_states(run_id).values()
        ):
            run_status = RunStatus.PENDING_APPROVAL

        self._update_run(
            run_id,
            status=run_status.value,
            cost_usd=cost,
            replans=replans,
            updated_at=last.ts,
        )
        return self.get_run(run_id)
