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
import time
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
    Approval,
    ApprovalDecision,
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


# Failures a retry cannot change, matched against the error text a backend
# reports. Each is deterministic given the same inputs: a provider quota that
# resets at a fixed hour, a turn ceiling the next attempt hits identically, an
# empty account. A bounded retry policy is for flaky failures; spending it here
# buys nothing and costs a full attempt each time.
_TERMINAL_ERRORS: tuple[tuple[str, str], ...] = (
    ("session limit", "provider quota exhausted"),
    ("usage limit", "provider quota exhausted"),
    ("credit balance", "provider credit exhausted"),
    ("error_max_turns", "turn ceiling reached"),
)


def terminal_failure(error: str | None) -> str | None:
    """Name the wall if this error is one, else None."""
    if not error:
        return None
    lowered = error.lower()
    for needle, reason in _TERMINAL_ERRORS:
        if needle in lowered:
            return reason
    return None


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
        # Injected for the same reason the command runner is: a test that wants
        # to assert on a backoff should not spend the wall-clock, and patching
        # `time.sleep` globally to catch it also catches every other sleep in
        # the process. That made one test intermittently fail on delays it
        # never caused.
        sleep=time.sleep,
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
        self.sleep = sleep
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
        self._assert_prompts_exist()
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
            node.id
            for node in self.pipeline.nodes
            # A handler is invoked by a failure and never scheduled, so it has
            # no business being *required*. `_ready_nodes` has always skipped
            # them; requiring them here meant a run where every scheduled node
            # passed still reported FAILED -- because `triage` had never been
            # asked to do anything, which is the good case.
            if node.kind is not NodeKind.HANDLER
            and (
                states.get(node.id) is None
                or states[node.id].status is not NodeStatus.PASSED
            )
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
            # A handler runs when something fails, not when its inputs are
            # ready. It declares no dependencies, so a dependency-driven
            # scheduler considers it ready immediately -- which put `triage` on
            # level 0 racing the first node for the git index.
            if node.kind is NodeKind.HANDLER:
                continue
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

    def _assert_prompts_exist(self) -> None:
        """Preflight every prompt file before the run starts.

        The same argument as validating gate names at load time: a missing or
        misspelled prompt is a configuration error, and discovering it when the
        node is reached means discovering it forty minutes and several dollars
        into a run, with a half-built workspace to clean up.
        """
        missing = [
            f"{node.id} -> {self.prompts_root / node.prompt_path}"
            for node in self.pipeline.nodes
            if node.kind is NodeKind.AGENT
            and node.prompt_path
            and not (self.prompts_root / node.prompt_path).is_file()
        ]
        if missing:
            raise FileNotFoundError(
                "prompt file(s) not found:\n  " + "\n  ".join(missing)
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
        # A triage-routed repair carries its verdict the same way a human
        # rejection carries its note: appended to the prompt, last and therefore
        # most salient. A human rejection outranks it -- a person who has just
        # looked at this node knows something the adjudicator did not.
        rejection_note = self._repair_note(node.id)
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
            if attempt:
                self._backoff(node, attempt)

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
                if reason := terminal_failure(result.error):
                    # Some failures are walls, not weather. A provider quota
                    # that resets at a fixed hour, or a turn ceiling the next
                    # attempt would hit identically, does not become passable
                    # because we tried twice more -- it just costs twice more.
                    # Spend the remaining attempts on failures a retry can
                    # actually change, and leave this node resumable.
                    self._record(
                        "retries_abandoned",
                        node_id=node.id,
                        attempt=attempt,
                        parent_ids=self._parents(node.id),
                        reason=reason,
                        attempts_remaining=total - attempt - 1,
                    )
                    return self._fail(node, [], f"{reason}: {result.error}")
                gate_failures = ()
                continue

            execution = self._evaluate_exit(node, result, attempt=attempt)
            if execution.status is not NodeStatus.FAILED:
                return execution
            # An attempt rejected by a gate cost exactly as much as one that
            # crashed, and until this record existed the journal only carried
            # the cost of attempts that *succeeded*. Everything spent on work
            # that was thrown away was invisible -- which is precisely the
            # number anyone asks for when they ask what the governance costs.
            self._record(
                "node_attempt_failed",
                node_id=node.id,
                attempt=attempt,
                parent_ids=self._parents(node.id),
                error="; ".join(
                    f"{g.check}: {g.detail}"
                    for g in execution.gate_results
                    if g.outcome is GateOutcome.FAIL
                )[:500],
                cost_usd=result.cost_usd,
            )
            gate_failures = tuple(
                g for g in execution.gate_results if g.outcome is GateOutcome.FAIL
            )

        return self._fail(node, list(gate_failures), f"exhausted {total} attempt(s)")

    def _backoff(self, node: NodeSpec, attempt: int) -> None:
        """Wait before re-entering a node.

        The reason to declare a backoff at all is that the failures worth
        retrying are mostly not deterministic: a rate limit, a flapping
        dependency, a provider hiccup. Retrying those instantly converts a
        bounded retry policy into a burst of identical failures against a
        service that was already asking for room.

        It is journalled rather than merely slept, because the wait is charged
        to the run's wallclock budget and shows up in E2E latency. A delay that
        appears in the metrics but nowhere in the record is a delay nobody can
        account for.
        """
        delay = node.retry.backoff_seconds
        if delay <= 0:
            return
        self._record(
            "retry_backoff",
            node_id=node.id,
            parent_ids=self._parents(node.id),
            attempt=attempt,
            seconds=delay,
        )
        self.sleep(delay)

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
                    # Deliberately zero. This path exists so that resuming into
                    # an approved checkpoint re-gates the work instead of paying
                    # for it twice -- so carrying the original cost forward
                    # would have the journal record the same money twice, once
                    # where it was spent and again where it was merely
                    # re-examined. The cost belongs to the invocation that
                    # incurred it, and that entry is still in the journal.
                    cost_usd=0.0,
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

            if action is FailureAction.TRIAGE:
                status = self._triage(node, execution)
                if status is not None:
                    return status
                continue

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

    def _triage(self, node: NodeSpec, execution: NodeExecution) -> RunStatus | None:
        """Ask which artifact is actually wrong, then re-run only that one.

        The alternative already existed and is a sledgehammer: replanning
        re-derives the plan, the contract and both branches to fix a defect
        living in one of them. A run has already been lost that way, to two
        tests that invented a mechanism the harness does not support while the
        implementation was correct throughout.

        Triage is advisory in exactly the sense `review` is. It chooses where to
        spend the next attempt; it never decides the run passes, because the
        failing gate still has to go green afterwards on its own terms. A wrong
        verdict therefore costs one re-run, not a bad merge.
        """
        handler = self.pipeline.node(node.triage_node)
        verdict = self._invoke(handler, attempt=0, gate_failures=(), rejection_note="",
                               autonomy=handler.autonomy)
        self.budget.record(verdict.cost_usd)
        self.store.add_cost(self.run_id, verdict.cost_usd)

        if not verdict.ok:
            return self._pause_for_approval(
                self._pending_approval(
                    node, execution.gate_results,
                    f"triage could not run ({verdict.error}); {execution.detail}",
                )
            )

        approval = self.store.approvals(self.run_id).get(node.id)
        targets, reason = self._triage_targets(verdict.output, approval)
        route = self._adjudicated_route(approval if approval and approval.approved else None)
        self._record(
            "triage_verdict",
            node_id=handler.id,
            parent_ids=self._parents(node.id),
            triggered_by=node.id,
            verdict=(verdict.output or {}).get("verdict"),
            targets=targets,
            reason=reason,
            cost_usd=verdict.cost_usd,
        )

        if not targets:
            return self._pause_for_approval(
                self._pending_approval(node, execution.gate_results, reason)
            )

        routed: list[str] = []
        for target in targets:
            spec = self.pipeline.node(target)
            allowed = spec.repair_attempts + self._adjudicated_grants(target)
            state = self.store.get_node(self.run_id, target)
            used = (state.repairs if state else 0) + 1
            if used > allowed:
                self._record(
                    "repair_budget_exhausted",
                    node_id=target,
                    parent_ids=self._parents(node.id),
                    used=used,
                    allowed=allowed,
                )
                continue
            # Charged only once the routing is actually going to happen. The
            # earlier version incremented first and checked after, so a refused
            # routing still cost the branch a slot it never got to use -- and a
            # later human grant then had to pay off that phantom debt before it
            # bought anything.
            self.store.record_repair(self.run_id, target)
            affected = {target} | descendants(self.pipeline, target)
            for nid in sorted(affected):
                self.store.set_node(self.run_id, nid, NodeStatus.PENDING, attempt=0)
            routed.append(target)
            self._record(
                "repair_routed",
                node_id=target,
                parent_ids=self._parents(node.id),
                triggered_by=node.id,
                attempt=used,
                allowed=allowed,
                reason=self._repair_brief(
                    target,
                    verdict.output,
                    reason,
                    routed_contract_to=route[0] if route else "",
                    adjudication=approval.note if route and approval else "",
                ),
                reset_nodes=sorted(affected),
            )

        # Every implicated branch is out of budget. The sledgehammer is what is
        # left, and it is still bounded.
        if not routed:
            return self._replan(node, execution)
        return None

    @classmethod
    def _repair_brief(
        cls,
        target: str,
        output: dict[str, Any] | None,
        summary: str,
        routed_contract_to: str = "",
        adjudication: str = "",
    ) -> str:
        """What *this* branch is being asked to fix, itemised.

        The overall summary is not enough and the first live repair proved it:
        handed "23 failing methods across 7 classes, three root causes", the
        node found nothing addressed to it, concluded nothing was broken, and
        spent seven dollars verifying that. A branch needs the failures
        attributed to it and the evidence for each, or the attempt is a re-roll
        with extra steps.
        """
        output = output or {}
        mine = [
            f
            for f in (output.get("failures") or [])
            if cls._target_for(str(f.get("classification")), routed_contract_to) == target
        ]
        if not mine:
            return summary
        verdict = ""
        if adjudication:
            # A human who adjudicated a contract question said *why*, and the
            # why is the whole instruction: which side changes, and what must
            # not change to make the failure go away. Handing the branch the
            # routing without the reasoning invites it to fix the symptom.
            verdict = (
                "\n\n## Human adjudication\n\nA reviewer settled the contract "
                "question. This is the ruling, not advice:\n\n"
                f"> {adjudication}"
            )
        lines = [
            f"`verify` failed and {len(mine)} of its failures were attributed to "
            f"this node. Fix these and only these; another branch is repairing "
            f"the rest in parallel.",
            "",
        ]
        for f in mine:
            lines.append(f"- **{f.get('test')}** ({f.get('confidence')} confidence)")
            lines.append(f"  {f.get('evidence')}")
        lines += ["", f"Adjudicator's overall summary: {summary}{verdict}"]
        return "\n".join(lines)

    def _adjudicated_grants(self, target: str) -> int:
        """Extra repair attempts this branch has been granted by a human.

        `repair_attempts` bounds the *machine*: an agent that keeps re-running
        itself on its own judgment is the failure mode the bound exists for. A
        human who adjudicates a contract question and names the branch has
        supplied exactly the outside authority the bound was demanding, so the
        decision buys one attempt -- once per decision, counted from the
        journal, so it cannot be spent twice.

        Without this the run does something much worse than stop: the routed
        branch is refused, nothing routes, and the fallthrough replans from
        `decompose` -- re-deriving the whole pipeline to fix one over-asserting
        assertion, which is how a $2 repair becomes a $40 one.
        """
        grants = 0
        for entry in self.journal.entries():
            if entry.event != "human_decision":
                continue
            payload = entry.payload
            if payload.get("decision") != ApprovalDecision.APPROVED.value:
                continue
            route = str((payload.get("answers") or {}).get("route", ""))
            if target in [t.strip() for t in route.split(",") if t.strip()]:
                grants += 1
        return grants

    def _repair_note(self, node_id: str) -> str:
        """Why this node is being asked to run again, from the journal.

        A repair with no account of what it is repairing is a re-roll of the
        same dice, so the verdict has to reach the node. Copying the artifact
        into the branch's checkout would be the obvious way and is wrong twice
        over: it dirties the tree the barrier merges, and it puts a file in the
        node's diff that the node was never permitted to write, which
        `paths_confined` would rightly blame it for.

        The journal already holds the reason, it already survives a restart, and
        reading it back costs nothing.
        """
        for entry in reversed(self.journal.entries()):
            if entry.event == "node_passed" and entry.node_id == node_id:
                return ""  # repaired since; the note is spent
            # Two ways work comes back to a node, and they are not the same
            # claim: `triage` attributing a test failure, or a person reading a
            # review and deciding. Both reach the node as its brief; only the
            # journal has to keep them apart.
            if entry.event in ("repair_routed", "human_repair_requested") and (
                entry.node_id == node_id
            ):
                reason = str(entry.payload.get("reason") or "")
                if entry.event == "human_repair_requested":
                    approver = entry.payload.get("approver") or "a reviewer"
                    return (
                        f"{approver} read the review of your last attempt and is "
                        f"sending this back. Address it directly; it is a "
                        f"decision, not a suggestion.\n\n{reason}"
                    )
                return reason
        return ""

    _TARGET_FOR = {"implementation": "implement", "test": "author-tests"}

    @classmethod
    def _target_for(cls, classification: str, routed_contract_to: str = "") -> str:
        """Which branch owns a failure of this classification.

        `contract` deliberately has no entry: it is the one classification the
        machine cannot resolve, so it maps only to whatever branch a human named
        when they adjudicated it.
        """
        if classification == "contract":
            return routed_contract_to
        return cls._TARGET_FOR.get(classification, "")

    @staticmethod
    def _adjudicated_route(approval: Approval | None) -> list[str]:
        """The branch(es) a human named when clearing a contract question.

        `--answer route=author-tests` on the approval. Deciding a contract
        question *is* deciding which side has to change, so the approval is the
        natural place to carry it, and it lands in the journal with the note
        that justified it.
        """
        if not approval:
            return []
        raw = approval.answers.get("route", "")
        return [t.strip() for t in raw.split(",") if t.strip()]

    @classmethod
    def _triage_targets(
        cls, output: dict[str, Any] | None, approval: Approval | None = None
    ) -> tuple[list[str], str]:
        """Map a triage verdict onto the branches that should repair it.

        A mixed verdict routes to *every* implicated branch rather than
        stopping. The earlier version escalated on any mixture, on the grounds
        that a mixture cannot be sent to one branch -- which is true, and was
        the wrong conclusion. Choosing one branch would be wrong; asking each to
        fix its own side is exactly what a team does, and the branches are
        already isolated by path so they cannot tread on each other.

        Two things still stop the run instead:

        * a **contract** classification, because that is the two sides reading
          one document differently and no amount of re-running settles it;
        * **low confidence**, because a misrouted repair re-runs the innocent
          branch, leaves the defect in place, and spends an attempt doing it.

        Either can be released by a human: an approval on file for the failing
        node *is* the adjudication, and the run proceeds with the classifications
        the approver has now seen.
        """
        output = output or {}
        failures = output.get("failures") or []
        summary = output.get("summary", "")
        cleared = bool(approval and approval.approved)

        contract = [f for f in failures if f.get("classification") == "contract"]
        unsure = [f for f in failures if f.get("confidence") == "low"]
        if (contract or unsure) and not cleared:
            blocker = "a contract question" if contract else "low confidence"
            named = [str(f.get("test")) for f in (contract or unsure)][:3]
            return [], (
                f"{blocker} requires a human ({', '.join(named)}): {summary}"
            )

        route = cls._adjudicated_route(approval) if cleared else []
        targets = [
            t
            for t in dict.fromkeys(
                cls._target_for(str(f.get("classification")), route[0] if route else "")
                for f in failures
            )
            if t
        ]
        if not targets:
            # Clearing the block is only half a decision. A contract question
            # resolves to "one of these two sides has to change", and nothing in
            # the classification says which -- so an approval that names no
            # branch leaves the run exactly where it was, and saying so is more
            # use than escalating again with the same words.
            if cleared and contract:
                return [], (
                    "the contract question was adjudicated but names no branch to "
                    "repair it; re-approve with `--answer route=implement` or "
                    f"`--answer route=author-tests`: {summary}"
                )
            return [], f"triage attributed nothing to a repairable branch: {summary}"
        if cleared:
            summary = f"{summary} [contract question adjudicated by {approval.approver}]"
        return targets, summary

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
