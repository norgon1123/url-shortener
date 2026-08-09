"""The execution engine: scheduling, gates, failure handling, checkpoints.

This is the part no SDK supplies, and it is the reason the orchestration layer
is the deliverable rather than the service it builds. The SDK runs one agent
well. It has nothing to say about what must be true before that agent starts,
what must be true before its work is accepted, who is allowed to decide, what
happens on the third failure, or how any of it is evidenced afterwards.

Scheduling is a ready-set loop rather than a fixed level order. A node is ready
when its dependencies have passed and it has not. That single rule gives the
`implement` / `author-tests` fan-out for free -- nothing special-cases it -- and
it survives replanning, where nodes are reset to PENDING mid-run and the graph
has to re-derive what can proceed.

Failure semantics, since `on_failure` is easy to misread: the retry policy is
exhausted *first*, and `on_failure` says what to do when retrying has not
worked.

  RETRY      stop after the bounded attempts; the run fails.
  FALLBACK   degrade autonomy to `propose` and try once more. The node writes a
             diff instead of applying one, and a human applies it.
  ROLLBACK   reset the worktree to the last good checkpoint, then fail. The
             workspace is left in a state someone can resume from.
  REPLAN     reset a named upstream node and everything downstream of it, then
             continue. Bounded by max_replans; exceeding it is a safe-stop.
  SAFE_STOP  halt at this boundary with state and journal intact.
"""

from __future__ import annotations

import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from . import gates as gate_lib
from .audit import Journal, hash_inputs
from .budget import BudgetGuard
from .checkpoint import CheckpointManager
from .gates import GateContext
from .graph import descendants
from .model import (
    Autonomy,
    FailureAction,
    GateOutcome,
    GateResult,
    NodeKind,
    NodeResult,
    NodeSpec,
    NodeStatus,
    Pipeline,
    RunStatus,
)
from .nodes import NodeBackend, NodeInvocation
from .policy import PolicyEngine
from .state import RunStore


def new_run_id(prefix: str = "run") -> str:
    """Sortable and unique: the timestamp is for humans, the suffix for collisions."""
    stamp = datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
    return f"{prefix}-{stamp}-{uuid.uuid4().hex[:6]}"


@dataclass
class NodeExecution:
    """What happened to one node on one pass, for the journal and the caller."""

    node_id: str
    status: NodeStatus
    gate_results: list[GateResult] = field(default_factory=list)
    result: NodeResult | None = None
    detail: str = ""


class Engine:
    """Runs a pipeline to completion, to a checkpoint, or to a stop."""

    def __init__(
        self,
        *,
        pipeline: Pipeline,
        backend: NodeBackend,
        store: RunStore,
        journal: Journal,
        checkpoints: CheckpointManager,
        workspace: Path,
        prompts_root: Path,
        run_id: str,
        budget: BudgetGuard | None = None,
        command_runner=gate_lib.subprocess_runner,
        max_parallel: int = 4,
    ) -> None:
        self.pipeline = pipeline
        self.backend = backend
        self.store = store
        self.journal = journal
        self.checkpoints = checkpoints
        self.workspace = workspace
        self.prompts_root = prompts_root
        self.run_id = run_id
        self.budget = budget or BudgetGuard(pipeline.budget)
        self.command_runner = command_runner
        self.max_parallel = max_parallel
        # The parallel branch means two node runners finish at unpredictable
        # moments. The journal's hash chain and the store's counters are both
        # order-sensitive, so every write to either goes through this.
        self._lock = threading.Lock()
        self._replans = 0
        self._last_entry_id: dict[str, str] = {}

    # -- journal helpers -------------------------------------------------

    def _record(self, event: str, **kw: Any) -> str:
        with self._lock:
            entry = self.journal.append(event, **kw)
            if entry.node_id:
                self._last_entry_id[entry.node_id] = entry.entry_id
            return entry.entry_id

    def _parents(self, node_id: str) -> tuple[str, ...]:
        """Lineage: link this entry to the last thing that happened to the node."""
        prior = self._last_entry_id.get(node_id)
        return (prior,) if prior else ()

    # -- run lifecycle ---------------------------------------------------

    def run(self, *, resume: bool = False) -> RunStatus:
        if not resume:
            self._record(
                "run_started",
                pipeline=str(self.pipeline.version),
                nodes=list(self.pipeline.node_ids),
            )
        else:
            self.store.clear_stop(self.run_id)
            self._record("run_resumed", replans=self._replans)
            self._detect_stale_inputs()

        self.store.set_run_status(self.run_id, RunStatus.RUNNING)

        while True:
            if breach := self.budget.breach():
                return self._safe_stop(str(breach))
            if self.store.stop_requested(self.run_id):
                return self._safe_stop("operator requested stop")

            ready = self._ready_nodes()
            if not ready:
                break

            executions = self._run_level(ready)

            for execution in executions:
                if execution.status is NodeStatus.PENDING_APPROVAL:
                    return self._pause_for_approval(execution)

            failed = [e for e in executions if e.status is NodeStatus.FAILED]
            if failed:
                outcome = self._handle_failures(failed)
                if outcome is not None:
                    return outcome

        return self._finish()

    def _finish(self) -> RunStatus:
        states = self.store.node_states(self.run_id)
        incomplete = [
            nid
            for nid in self.pipeline.node_ids
            if states.get(nid) is None or states[nid].status is not NodeStatus.PASSED
        ]
        if incomplete:
            self._record("run_failed", incomplete=incomplete)
            self.store.set_run_status(self.run_id, RunStatus.FAILED)
            return RunStatus.FAILED

        self._record("run_completed", **self.budget.summary())
        self.store.set_run_status(self.run_id, RunStatus.COMPLETED)
        return RunStatus.COMPLETED

    def _safe_stop(self, reason: str) -> RunStatus:
        """Halt at a node boundary, not mid-write.

        Everything already committed stays committed and the journal is intact,
        so `orchestrator run --resume` picks up from here rather than starting
        over.
        """
        self._record("run_safe_stopped", reason=reason, **self.budget.summary())
        self.store.set_run_status(self.run_id, RunStatus.SAFE_STOPPED)
        return RunStatus.SAFE_STOPPED

    def _pause_for_approval(self, execution: NodeExecution) -> RunStatus:
        self._record(
            "run_pending_approval",
            node_id=execution.node_id,
            parent_ids=self._parents(execution.node_id),
            reason=execution.detail,
        )
        self.store.set_run_status(self.run_id, RunStatus.PENDING_APPROVAL)
        return RunStatus.PENDING_APPROVAL

    # -- scheduling ------------------------------------------------------

    def _ready_nodes(self) -> list[NodeSpec]:
        """Dependencies passed, self not passed. That rule is the whole scheduler."""
        states = self.store.node_states(self.run_id)

        def status(nid: str) -> NodeStatus:
            state = states.get(nid)
            return state.status if state else NodeStatus.PENDING

        ready = []
        for node in self.pipeline.nodes:
            if status(node.id) in (NodeStatus.PASSED, NodeStatus.SKIPPED):
                continue
            if status(node.id) in (NodeStatus.FAILED, NodeStatus.REJECTED):
                continue  # already handled this pass; do not spin
            if all(status(dep) is NodeStatus.PASSED for dep in node.depends_on):
                ready.append(node)
        return ready

    def _run_level(self, nodes: list[NodeSpec]) -> list[NodeExecution]:
        """Run a ready set. More than one node means genuine concurrency."""
        if len(nodes) == 1:
            return [self._execute_node(nodes[0])]

        self._record("parallel_started", nodes=[n.id for n in nodes])
        with ThreadPoolExecutor(max_workers=min(self.max_parallel, len(nodes))) as pool:
            executions = list(pool.map(self._execute_node, nodes))
        self._record(
            "parallel_joined",
            nodes=[n.id for n in nodes],
            statuses={e.node_id: e.status.value for e in executions},
        )
        return executions

    # -- node execution --------------------------------------------------

    def _workspace_for(self, node: NodeSpec) -> Path:
        if node.worktree:
            return self.checkpoints.worktree(node.worktree).root
        return self.workspace

    def _context_for(self, node: NodeSpec) -> dict[str, Any]:
        """Upstream outputs, passed explicitly rather than left to be discovered."""
        context: dict[str, Any] = {}
        for dep_id in node.depends_on:
            dep = self.pipeline.node(dep_id)
            if not dep.output_schema:
                continue
            artifact = self.workspace / "artifacts" / f"{dep.output_schema}.json"
            if artifact.is_file():
                import json

                try:
                    context[dep_id] = json.loads(artifact.read_text(encoding="utf-8"))
                except json.JSONDecodeError:
                    continue
        return context

    def _gate_context(
        self, node: NodeSpec, phase: str, result: NodeResult | None
    ) -> GateContext:
        return GateContext(
            workspace=self._workspace_for(node),
            node=node,
            policy=PolicyEngine(
                self.pipeline.policy,
                write_paths=node.write_paths,
                deny_paths=node.deny_paths,
            ),
            phase=phase,
            result=result,
            approvals=self.store.approvals(self.run_id),
            run=self.command_runner,
        )

    def _input_hash(self, node: NodeSpec) -> str:
        """Content-address what this node consumes.

        Change-driven replanning compares this against what the node recorded
        last time. It is the same idea as a Make timestamp, except content-based,
        so an edit that reverts a file does not spuriously invalidate the world.
        """
        paths = []
        if node.prompt_path:
            paths.append(self.prompts_root / node.prompt_path)
        for dep_id in node.depends_on:
            dep = self.pipeline.node(dep_id)
            if dep.output_schema:
                paths.append(self.workspace / "artifacts" / f"{dep.output_schema}.json")
        return hash_inputs(paths, extra={"node": node.id})

    def _execute_node(self, node: NodeSpec) -> NodeExecution:
        approvals = self.store.approvals(self.run_id)
        approval = approvals.get(node.id)

        # A rejection is not a permanent verdict on the node -- it is feedback.
        # Clear it so the node re-runs carrying the note and then faces a fresh
        # decision. The journal keeps both decisions; this table holds only the
        # live one.
        rejection_note = ""
        if approval is not None and not approval.approved:
            rejection_note = approval.note
            self.store.clear_approval(self.run_id, node.id)
            self._record(
                "rejection_applied",
                node_id=node.id,
                parent_ids=self._parents(node.id),
                note=approval.note,
                approver=approval.approver,
            )

        state = self.store.get_node(self.run_id, node.id)

        # Resuming into an approved checkpoint: the work is already on disk, so
        # re-evaluate the gates rather than paying for the node twice.
        if state.status is NodeStatus.PENDING_APPROVAL and approval and approval.approved:
            prior = self._result_from_journal(node.id) or NodeResult(node.id, ok=True)
            return self._evaluate_exit(node, prior, attempt=state.attempt)

        input_hash = self._input_hash(node)
        self.store.set_node(
            self.run_id,
            node.id,
            NodeStatus.RUNNING,
            started_at=datetime.now(UTC).isoformat(),
            input_hash=input_hash,
        )
        self._record(
            "node_started",
            node_id=node.id,
            input_hash=input_hash,
            parent_ids=self._parents(node.id),
            kind=node.kind.value,
        )

        entry = gate_lib.evaluate_all(node.entry_gates, self._gate_context(node, "entry", None))
        self._journal_gates(node, entry, phase="entry")
        entry_outcome = gate_lib.worst(entry)
        if entry_outcome is GateOutcome.ESCALATE:
            return self._pending_approval(node, entry, "entry gate requires approval")
        if entry_outcome is GateOutcome.FAIL:
            return self._fail(node, entry, "entry gate failed")

        return self._attempt_loop(node, rejection_note)

    def _attempt_loop(self, node: NodeSpec, rejection_note: str) -> NodeExecution:
        gate_failures: tuple[GateResult, ...] = ()
        autonomy = node.autonomy
        attempts = max(1, node.retry.max_attempts)
        # FALLBACK buys one extra attempt at reduced autonomy: the node proposes
        # rather than applies, and a human decides. That is a materially
        # different move from retrying the same thing again, which is why §4.4
        # lists it separately.
        total = attempts + (1 if node.on_failure is FailureAction.FALLBACK else 0)

        for attempt in range(total):
            if attempt == attempts and node.on_failure is FailureAction.FALLBACK:
                autonomy = Autonomy.PROPOSE
                self._record(
                    "fallback_engaged",
                    node_id=node.id,
                    parent_ids=self._parents(node.id),
                    autonomy=autonomy.value,
                )

            if breach := self.budget.check_before():
                return self._fail(node, [], f"budget: {breach}")

            result = self._invoke(node, attempt, gate_failures, rejection_note, autonomy)
            self.budget.record(result.cost_usd)
            self.store.add_cost(self.run_id, result.cost_usd)

            if not result.ok:
                self._record(
                    "node_attempt_failed",
                    node_id=node.id,
                    attempt=attempt,
                    parent_ids=self._parents(node.id),
                    error=result.error,
                    cost_usd=result.cost_usd,
                )
                gate_failures = ()
                continue

            execution = self._evaluate_exit(node, result, attempt=attempt)
            if execution.status is not NodeStatus.FAILED:
                return execution
            gate_failures = tuple(
                g for g in execution.gate_results if g.outcome is GateOutcome.FAIL
            )

        return self._fail(node, list(gate_failures), f"exhausted {total} attempt(s)")

    def _invoke(
        self,
        node: NodeSpec,
        attempt: int,
        gate_failures: tuple[GateResult, ...],
        rejection_note: str,
        autonomy: Autonomy,
    ) -> NodeResult:
        """Run the node body. Barriers and deterministic nodes never call a model."""
        if node.kind is NodeKind.BARRIER:
            return self._run_barrier(node)
        if node.kind is NodeKind.DETERMINISTIC:
            return NodeResult(node_id=node.id, ok=True)

        invocation = NodeInvocation(
            node=node,
            run_id=self.run_id,
            workspace=self._workspace_for(node),
            attempt=attempt,
            sequence=self.store.next_invocation(self.run_id, node.id),
            context=self._context_for(node),
            gate_failures=gate_failures,
            rejection_note=rejection_note,
            autonomy=autonomy,
        )
        return self.backend.run(invocation)

    def _run_barrier(self, node: NodeSpec) -> NodeResult:
        """Join the parallel branches by merging their worktrees."""
        branches = [
            self.pipeline.node(dep).worktree
            for dep in node.depends_on
            if self.pipeline.node(dep).worktree
        ]
        merged, conflicts = self.checkpoints.merge_worktrees(branches)
        return NodeResult(
            node_id=node.id,
            ok=True,
            output={"merged_branches": merged, "conflicts": conflicts},
        )

    def _evaluate_exit(self, node: NodeSpec, result: NodeResult, *, attempt: int) -> NodeExecution:
        exit_results = gate_lib.evaluate_all(
            node.exit_gates, self._gate_context(node, "exit", result)
        )
        self._journal_gates(node, exit_results, phase="exit")
        outcome = gate_lib.worst(exit_results)

        if outcome is GateOutcome.ESCALATE:
            return self._pending_approval(node, exit_results, "exit gate requires approval", result)
        if outcome is GateOutcome.FAIL:
            return NodeExecution(node.id, NodeStatus.FAILED, exit_results, result)

        sha = self.checkpoints.checkpoint(
            node.id,
            attempt=attempt,
            summary=node.description.split("\n")[0][:60],
            worktree=node.worktree,
        )
        self.store.set_node(
            self.run_id,
            node.id,
            NodeStatus.PASSED,
            attempt=attempt,
            checkpoint_commit=sha,
            ended_at=datetime.now(UTC).isoformat(),
            cost_usd=result.cost_usd,
        )
        self._record(
            "node_passed",
            node_id=node.id,
            attempt=attempt,
            parent_ids=self._parents(node.id),
            checkpoint_commit=sha,
            cost_usd=result.cost_usd,
            files_written=list(result.files_written),
            output=result.output,
        )
        return NodeExecution(node.id, NodeStatus.PASSED, exit_results, result)

    def _journal_gates(self, node: NodeSpec, results: list[GateResult], *, phase: str) -> None:
        for gate in results:
            self._record(
                "gate_evaluated",
                node_id=node.id,
                parent_ids=self._parents(node.id),
                phase=phase,
                check=gate.check,
                gate_class=gate.gate_class.value,
                outcome=gate.outcome.value,
                detail=gate.detail,
                evidence=gate.evidence,
            )

    def _pending_approval(
        self,
        node: NodeSpec,
        results: list[GateResult],
        detail: str,
        result: NodeResult | None = None,
    ) -> NodeExecution:
        reasons = [
            g.detail for g in results if g.outcome is GateOutcome.ESCALATE
        ] or [detail]
        self.store.set_node(self.run_id, node.id, NodeStatus.PENDING_APPROVAL)
        self._record(
            "node_pending_approval",
            node_id=node.id,
            parent_ids=self._parents(node.id),
            reasons=reasons,
            files_written=list(result.files_written) if result else [],
            cost_usd=result.cost_usd if result else 0.0,
        )
        return NodeExecution(node.id, NodeStatus.PENDING_APPROVAL, results, result, "; ".join(reasons))

    def _fail(self, node: NodeSpec, results: list[GateResult], detail: str) -> NodeExecution:
        self.store.set_node(
            self.run_id,
            node.id,
            NodeStatus.FAILED,
            ended_at=datetime.now(UTC).isoformat(),
            error=detail,
        )
        self._record(
            "node_failed",
            node_id=node.id,
            parent_ids=self._parents(node.id),
            error=detail,
            failed_checks=[g.check for g in results if g.outcome is GateOutcome.FAIL],
        )
        return NodeExecution(node.id, NodeStatus.FAILED, results, None, detail)

    def _result_from_journal(self, node_id: str) -> NodeResult | None:
        """Recover a paused node's result so resume re-gates instead of re-running.

        The journal is already the record of what the node produced, so nothing
        extra needs persisting -- and the state a resume relies on is the same
        state an auditor reads.
        """
        for entry in reversed(self.journal.entries()):
            if entry.node_id == node_id and entry.event in (
                "node_pending_approval",
                "node_passed",
            ):
                return NodeResult(
                    node_id=node_id,
                    ok=True,
                    output=entry.payload.get("output") or {},
                    files_written=tuple(entry.payload.get("files_written") or ()),
                    cost_usd=float(entry.payload.get("cost_usd") or 0.0),
                )
        return None

    # -- failure handling ------------------------------------------------

    def _handle_failures(self, failures: list[NodeExecution]) -> RunStatus | None:
        """Apply each failed node's declared strategy. None means 'keep going'."""
        for execution in failures:
            node = self.pipeline.node(execution.node_id)
            action = node.on_failure

            if action is FailureAction.SAFE_STOP:
                return self._safe_stop(f"{node.id}: {execution.detail}")

            if action is FailureAction.ROLLBACK:
                self._rollback(node, execution)
                self._record("run_failed", node_id=node.id, error=execution.detail)
                self.store.set_run_status(self.run_id, RunStatus.FAILED)
                return RunStatus.FAILED

            if action is FailureAction.REPLAN:
                status = self._replan(node, execution)
                if status is not None:
                    return status
                continue

            # RETRY and FALLBACK have already run their course inside the
            # attempt loop; reaching here means they did not work.
            self._record("run_failed", node_id=node.id, error=execution.detail)
            self.store.set_run_status(self.run_id, RunStatus.FAILED)
            return RunStatus.FAILED
        return None

    def _rollback(self, node: NodeSpec, execution: NodeExecution) -> None:
        order = list(self.pipeline.node_ids)
        upstream = order[: order.index(node.id)]
        sha = self.store.latest_checkpoint(self.run_id, upstream)
        if sha is None:
            self._record("rollback_skipped", node_id=node.id, reason="no prior checkpoint")
            return
        self.checkpoints.rollback(sha)
        self.store.set_node(self.run_id, node.id, NodeStatus.ROLLED_BACK)
        self._record(
            "node_rolled_back",
            node_id=node.id,
            parent_ids=self._parents(node.id),
            to_commit=sha,
            reason=execution.detail,
        )

    def _replan(self, node: NodeSpec, execution: NodeExecution) -> RunStatus | None:
        """Send work back upstream with the failure attached.

        Bounded, because an unbounded replan loop is the expensive failure mode
        of any self-correcting pipeline: it looks like progress and bills like
        it too.
        """
        if self._replans >= node.max_replans:
            return self._safe_stop(
                f"{node.id}: replan limit ({node.max_replans}) reached after {execution.detail}"
            )

        target = node.replan_target
        self._replans += 1
        self.store.increment_replans(self.run_id)

        affected = {target} | descendants(self.pipeline, target)
        for nid in sorted(affected):
            self.store.set_node(self.run_id, nid, NodeStatus.PENDING, attempt=0)

        self._record(
            "replan_triggered",
            node_id=target,
            parent_ids=self._parents(node.id),
            triggered_by=node.id,
            reason=execution.detail,
            reset_nodes=sorted(affected),
            replan_number=self._replans,
        )
        return None

    # -- change-driven replanning ----------------------------------------

    def _detect_stale_inputs(self) -> None:
        """On resume, re-run anything whose inputs moved underneath it.

        This is the literal reading of "re-plan when upstream outputs change":
        a node that passed against one version of the design is not passed
        against a different one, and neither is anything downstream of it.
        """
        states = self.store.node_states(self.run_id)
        stale: set[str] = set()
        for node in self.pipeline.nodes:
            state = states.get(node.id)
            if not state or state.status is not NodeStatus.PASSED or not state.input_hash:
                continue
            if self._input_hash(node) != state.input_hash:
                stale.add(node.id)
                stale |= descendants(self.pipeline, node.id)

        if not stale:
            return
        for nid in sorted(stale):
            self.store.set_node(self.run_id, nid, NodeStatus.PENDING, attempt=0)
            self._record("node_stale", node_id=nid, reason="upstream inputs changed")
