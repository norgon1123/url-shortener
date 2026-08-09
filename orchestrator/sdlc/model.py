"""Domain types for the orchestrator.

Everything here is a plain dataclass with no I/O. The graph loader builds these
from YAML; the engine, gates, and journal all read them. Keeping them inert
makes the whole pipeline testable without an LLM, a network, or a clock.
"""

from __future__ import annotations

import enum
from dataclasses import dataclass, field
from typing import Any


class GateClass(str, enum.Enum):
    """ADR-001 gate taxonomy.

    The distinction is load-bearing, not cosmetic. A SELF_REPORT gate reads a
    field the model itself populated, so it can never be the last word -- the
    loader enforces that any node carrying one also has a human backstop
    somewhere downstream.
    """

    MECHANICAL = "mechanical"
    SELF_REPORT = "self_report"
    HUMAN = "human"


class GateOutcome(str, enum.Enum):
    PASS = "pass"
    FAIL = "fail"
    ESCALATE = "escalate"  # not a failure: hand to a human and pause


class NodeStatus(str, enum.Enum):
    PENDING = "pending"
    RUNNING = "running"
    PASSED = "passed"
    FAILED = "failed"
    PENDING_APPROVAL = "pending_approval"
    REJECTED = "rejected"
    ROLLED_BACK = "rolled_back"
    SKIPPED = "skipped"
    STALE = "stale"  # upstream input hash changed; must re-run


class RunStatus(str, enum.Enum):
    RUNNING = "running"
    PENDING_APPROVAL = "pending_approval"
    COMPLETED = "completed"
    FAILED = "failed"
    SAFE_STOPPED = "safe_stopped"


class FailureAction(str, enum.Enum):
    """§4.4 names retry, fallback, rollback, and safe-stop as distinct controls.

    FALLBACK is autonomy degradation: the node re-runs writing to a scratch
    diff instead of the worktree, and a human applies it. That is materially
    different from RETRY (same autonomy, fresh attempt) and from ROLLBACK
    (discard and revert), which is why the spec lists all three.
    """

    RETRY = "retry"
    FALLBACK = "fallback"
    ROLLBACK = "rollback"
    REPLAN = "replan"
    SAFE_STOP = "safe_stop"


class Autonomy(str, enum.Enum):
    PROPOSE = "propose"  # writes a scratch diff; a human applies it
    APPLY = "apply"  # writes to the worktree, commits a checkpoint
    APPLY_AND_PUSH = "apply_and_push"  # may open a PR


class NodeKind(str, enum.Enum):
    AGENT = "agent"  # invokes a model
    DETERMINISTIC = "deterministic"  # pure code, no model call
    BARRIER = "barrier"  # join point for parallel branches


class ApprovalDecision(str, enum.Enum):
    APPROVED = "approved"
    REJECTED = "rejected"


@dataclass(frozen=True)
class Approval:
    """A recorded human decision at a checkpoint.

    The note is not decoration: a rejection note is appended to the node's
    prompt on the replan attempt, so the human's reasoning materially changes
    what happens next. It is also the four-eyes evidence in the journal.
    """

    node_id: str
    decision: ApprovalDecision
    approver: str
    note: str = ""
    ts: str = ""
    answers: dict[str, str] = field(default_factory=dict)

    @property
    def approved(self) -> bool:
        return self.decision is ApprovalDecision.APPROVED


@dataclass(frozen=True)
class RetryPolicy:
    max_attempts: int = 2
    backoff_seconds: float = 5.0


@dataclass(frozen=True)
class GateSpec:
    check: str
    gate_class: GateClass
    on_fail: FailureAction | GateOutcome | None = None
    params: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.check:
            raise ValueError("gate spec requires a 'check' name")


@dataclass(frozen=True)
class NodeSpec:
    id: str
    kind: NodeKind = NodeKind.AGENT
    description: str = ""
    prompt_path: str | None = None
    tools: tuple[str, ...] = ()
    write_paths: tuple[str, ...] = ()
    deny_paths: tuple[str, ...] = ()
    output_schema: str | None = None
    depends_on: tuple[str, ...] = ()
    entry_gates: tuple[GateSpec, ...] = ()
    exit_gates: tuple[GateSpec, ...] = ()
    retry: RetryPolicy = field(default_factory=RetryPolicy)
    on_failure: FailureAction = FailureAction.RETRY
    autonomy: Autonomy = Autonomy.APPLY
    worktree: str | None = None
    model: str | None = None
    effort: str | None = None
    replan_target: str | None = None
    replan_after_attempts: int = 2
    max_replans: int = 2

    @property
    def has_self_report_gate(self) -> bool:
        return any(
            g.gate_class is GateClass.SELF_REPORT
            for g in (*self.entry_gates, *self.exit_gates)
        )

    @property
    def has_human_gate(self) -> bool:
        return any(
            g.gate_class is GateClass.HUMAN
            for g in (*self.entry_gates, *self.exit_gates)
        )


@dataclass(frozen=True)
class SandboxPolicy:
    enabled: bool = True
    allowed_domains: tuple[str, ...] = ()
    denied_domains: tuple[str, ...] = ()
    allow_local_binding: bool = True

    def to_sdk_settings(self) -> dict[str, Any]:
        """Render as the Agent SDK's SandboxSettings TypedDict.

        This is a genuinely enforced egress control rather than command
        filtering, which a Bash heredoc can trivially evade.
        """
        network: dict[str, Any] = {"allowLocalBinding": self.allow_local_binding}
        if self.allowed_domains:
            network["allowedDomains"] = list(self.allowed_domains)
        if self.denied_domains:
            network["deniedDomains"] = list(self.denied_domains)
        return {"enabled": self.enabled, "network": network}


@dataclass(frozen=True)
class Policy:
    protected_paths: tuple[str, ...] = ()
    forbidden_paths: tuple[str, ...] = ()
    forbidden_commands: tuple[str, ...] = ()
    secret_patterns: tuple[str, ...] = ()
    sandbox: SandboxPolicy = field(default_factory=SandboxPolicy)


@dataclass(frozen=True)
class Budget:
    max_cost_usd: float | None = None
    max_wallclock_seconds: float | None = None


@dataclass(frozen=True)
class Pipeline:
    version: int
    nodes: tuple[NodeSpec, ...]
    policy: Policy
    budget: Budget
    defaults: dict[str, Any] = field(default_factory=dict)

    def node(self, node_id: str) -> NodeSpec:
        for n in self.nodes:
            if n.id == node_id:
                return n
        raise KeyError(f"no such node: {node_id}")

    @property
    def node_ids(self) -> tuple[str, ...]:
        return tuple(n.id for n in self.nodes)


@dataclass
class GateResult:
    check: str
    gate_class: GateClass
    outcome: GateOutcome
    detail: str = ""
    evidence: dict[str, Any] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return self.outcome is GateOutcome.PASS


@dataclass
class NodeResult:
    """What a node backend returns. Deliberately backend-agnostic.

    The mock backend and the live Agent SDK backend both produce this, which is
    what lets the entire graph/gate/checkpoint machinery be tested in
    milliseconds with no API key.
    """

    node_id: str
    ok: bool
    output: dict[str, Any] = field(default_factory=dict)
    files_written: tuple[str, ...] = ()
    transcript_path: str | None = None
    cost_usd: float = 0.0
    duration_seconds: float = 0.0
    escalations: tuple[str, ...] = ()  # protected-path hits from can_use_tool
    error: str | None = None
