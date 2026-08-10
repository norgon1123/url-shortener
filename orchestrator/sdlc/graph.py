"""Pipeline loading, validation, and scheduling.

The scheduler is level-based: each level is a set of nodes whose dependencies
are all satisfied, and nodes within a level may run concurrently. That is what
produces the `implement` / `author-tests` fan-out without any special-casing --
parallelism falls out of the graph shape rather than being hardcoded.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

from .model import (
    Autonomy,
    Budget,
    FailureAction,
    GateClass,
    GateSpec,
    NodeKind,
    NodeSpec,
    Pipeline,
    Policy,
    RetryPolicy,
    SandboxPolicy,
)

# Keys consumed directly by GateSpec; anything else in a gate mapping is
# forwarded to the check function as a parameter.
_GATE_RESERVED = {"check", "gate_class", "on_fail"}


class PipelineError(ValueError):
    """Raised for a malformed or internally inconsistent pipeline file."""


def load_pipeline(path: str | Path) -> Pipeline:
    """Parse and validate a pipeline YAML file."""
    path = Path(path)
    try:
        raw = yaml.safe_load(path.read_text())
    except yaml.YAMLError as exc:
        raise PipelineError(f"{path}: invalid YAML: {exc}") from exc
    if not isinstance(raw, dict):
        raise PipelineError(f"{path}: top level must be a mapping")

    defaults = raw.get("defaults") or {}
    nodes = tuple(_parse_node(n, defaults) for n in raw.get("nodes") or [])
    if not nodes:
        raise PipelineError(f"{path}: pipeline defines no nodes")

    pipeline = Pipeline(
        version=int(raw.get("version", 1)),
        nodes=nodes,
        policy=_parse_policy(raw.get("policy") or {}),
        budget=_parse_budget(raw.get("budget") or {}),
        defaults=defaults,
    )
    validate(pipeline)
    return pipeline


def _parse_gate(raw: Any) -> GateSpec:
    if not isinstance(raw, dict):
        raise PipelineError(f"gate must be a mapping, got {type(raw).__name__}")
    if "check" not in raw:
        raise PipelineError(f"gate missing 'check': {raw}")
    try:
        gate_class = GateClass(raw.get("gate_class", "mechanical"))
    except ValueError as exc:
        raise PipelineError(f"gate {raw['check']}: {exc}") from exc

    on_fail: Any = None
    if (raw_on_fail := raw.get("on_fail")) is not None:
        if raw_on_fail == "escalate":
            from .model import GateOutcome

            on_fail = GateOutcome.ESCALATE
        else:
            try:
                on_fail = FailureAction(raw_on_fail)
            except ValueError as exc:
                raise PipelineError(f"gate {raw['check']}: {exc}") from exc

    return GateSpec(
        check=raw["check"],
        gate_class=gate_class,
        on_fail=on_fail,
        params={k: v for k, v in raw.items() if k not in _GATE_RESERVED},
    )


def _parse_node(raw: Any, defaults: dict[str, Any]) -> NodeSpec:
    if not isinstance(raw, dict) or "id" not in raw:
        raise PipelineError(f"node must be a mapping with an 'id': {raw}")
    nid = raw["id"]

    def pick(key: str, fallback: Any = None) -> Any:
        return raw.get(key, defaults.get(key, fallback))

    retry_raw = pick("retry") or {}
    retry = RetryPolicy(
        max_attempts=int(retry_raw.get("max_attempts", 2)),
        backoff_seconds=float(retry_raw.get("backoff_seconds", 5.0)),
    )

    try:
        kind = NodeKind(raw.get("type", "agent"))
        on_failure = FailureAction(pick("on_failure", "retry"))
        autonomy = Autonomy(pick("autonomy", "apply"))
    except ValueError as exc:
        raise PipelineError(f"node {nid}: {exc}") from exc

    if kind is NodeKind.AGENT and not raw.get("prompt"):
        raise PipelineError(f"node {nid}: agent nodes require a 'prompt'")

    return NodeSpec(
        id=nid,
        kind=kind,
        description=raw.get("description", "").strip(),
        prompt_path=raw.get("prompt"),
        tools=tuple(raw.get("tools") or ()),
        write_paths=tuple(raw.get("write_paths") or ()),
        deny_paths=tuple(raw.get("deny_paths") or ()),
        output_schema=raw.get("output_schema"),
        depends_on=tuple(raw.get("depends_on") or ()),
        entry_gates=tuple(_parse_gate(g) for g in raw.get("entry_gates") or ()),
        exit_gates=tuple(_parse_gate(g) for g in raw.get("exit_gates") or ()),
        retry=retry,
        on_failure=on_failure,
        autonomy=autonomy,
        worktree=raw.get("worktree"),
        model=pick("model"),
        effort=pick("effort"),
        replan_target=raw.get("replan_target"),
        replan_after_attempts=int(raw.get("replan_after_attempts", 2)),
        max_replans=int(raw.get("max_replans", 2)),
    )


def _parse_policy(raw: dict[str, Any]) -> Policy:
    sb = raw.get("sandbox") or {}
    net = sb.get("network") or {}
    return Policy(
        protected_paths=tuple(raw.get("protected_paths") or ()),
        forbidden_paths=tuple(raw.get("forbidden_paths") or ()),
        forbidden_commands=tuple(raw.get("forbidden_commands") or ()),
        secret_patterns=tuple(raw.get("secret_patterns") or ()),
        sandbox=SandboxPolicy(
            enabled=bool(sb.get("enabled", True)),
            allowed_domains=tuple(net.get("allowedDomains") or ()),
            denied_domains=tuple(net.get("deniedDomains") or ()),
            allow_local_binding=bool(net.get("allowLocalBinding", True)),
        ),
    )


def _parse_budget(raw: dict[str, Any]) -> Budget:
    return Budget(
        max_cost_usd=(
            float(raw["max_cost_usd"]) if raw.get("max_cost_usd") is not None else None
        ),
        max_wallclock_seconds=(
            float(raw["max_wallclock_seconds"])
            if raw.get("max_wallclock_seconds") is not None
            else None
        ),
    )


# --------------------------------------------------------------------------
# Validation
# --------------------------------------------------------------------------


def validate(pipeline: Pipeline) -> None:
    """Fail fast on a structurally invalid pipeline.

    Beyond the usual DAG checks, this enforces ADR-001: a node whose gate reads
    a model-populated field must have a human gate reachable downstream. Without
    that rule the taxonomy is a comment; with it, the graph cannot express an
    ungoverned self-report.
    """
    ids = [n.id for n in pipeline.nodes]
    if len(ids) != len(set(ids)):
        dupes = sorted({i for i in ids if ids.count(i) > 1})
        raise PipelineError(f"duplicate node ids: {', '.join(dupes)}")

    known = set(ids)
    for node in pipeline.nodes:
        for dep in node.depends_on:
            if dep not in known:
                raise PipelineError(f"node {node.id}: unknown dependency '{dep}'")
        if node.replan_target and node.replan_target not in known:
            raise PipelineError(
                f"node {node.id}: unknown replan_target '{node.replan_target}'"
            )
        if node.on_failure is FailureAction.REPLAN and not node.replan_target:
            raise PipelineError(
                f"node {node.id}: on_failure=replan requires a 'replan_target'"
            )

    _assert_acyclic(pipeline)
    _assert_checks_exist(pipeline)
    _assert_concurrent_writers_are_isolated(pipeline)
    _assert_worktrees_are_joined(pipeline)

    for node in pipeline.nodes:
        if node.has_self_report_gate and not _has_downstream_human_gate(pipeline, node):
            raise PipelineError(
                f"node {node.id} has a self-report gate but no human gate downstream. "
                "ADR-001 requires every model-populated predicate to be backstopped "
                "by a human checkpoint."
            )


def _assert_checks_exist(pipeline: Pipeline) -> None:
    """Every gate must name a check that is actually implemented.

    Imported here rather than at module scope to keep the dependency one-way:
    gates reads the model, the graph reads gates, and neither imports the other
    at import time. The payoff is that a typo'd check name is a load-time error
    instead of a surprise forty minutes and several dollars into a run.
    """
    from .gates import known_checks

    available = known_checks()
    for node in pipeline.nodes:
        for gate in (*node.entry_gates, *node.exit_gates):
            if gate.check not in available:
                raise PipelineError(
                    f"node {node.id}: gate '{gate.check}' is not implemented. "
                    f"Available: {', '.join(sorted(available))}"
                )


def _assert_concurrent_writers_are_isolated(pipeline: Pipeline) -> None:
    """Nodes that can run at the same time and can both write need separate trees.

    The scheduler runs a whole ready set concurrently, so any two writing nodes
    on the same level share a checkout unless they say otherwise -- and sharing
    one breaks three things at once, none of them loudly. Their commits race on
    a single git index; `paths_confined` diffs a tree containing the other
    node's writes and blames the wrong node; and the checkpoint for one ends up
    carrying the other's changes, which quietly falsifies the provenance
    trailers the audit trail is built on.

    Adding a second reviewer to a level is a one-line edit that any author would
    expect to be safe. This makes it safe, or a load-time error -- rather than
    an intermittent one discovered from a corrupted journal.
    """
    for level in schedule(pipeline):
        writers = [
            pipeline.node(nid)
            for nid in level
            if pipeline.node(nid).write_paths
            and pipeline.node(nid).kind is not NodeKind.BARRIER
        ]
        if len(writers) < 2:
            continue
        unisolated = [n.id for n in writers if not n.worktree]
        if unisolated:
            raise PipelineError(
                f"nodes {', '.join(sorted(unisolated))} may run concurrently with "
                f"other writing nodes ({', '.join(sorted(n.id for n in writers))}) "
                "but declare no 'worktree'. Concurrent writers must be isolated."
            )
        trees = [n.worktree for n in writers]
        if len(set(trees)) != len(trees):
            shared = sorted({t for t in trees if trees.count(t) > 1})
            raise PipelineError(
                f"concurrent nodes share worktree(s): {', '.join(shared)}. "
                "A shared worktree is not isolation."
            )


def _assert_worktrees_are_joined(pipeline: Pipeline) -> None:
    """Work committed on a branch nothing merges is work that never happened.

    A barrier merges the branches of the nodes it directly depends on. A node
    that runs in a worktree without such a barrier downstream passes its gates,
    commits, and leaves its output stranded on a branch the main checkout never
    sees -- and the failure surfaces much later as a mysteriously absent file.
    """
    joined: set[str] = set()
    for node in pipeline.nodes:
        if node.kind is NodeKind.BARRIER:
            joined.update(node.depends_on)

    stranded = [n.id for n in pipeline.nodes if n.worktree and n.id not in joined]
    if stranded:
        raise PipelineError(
            f"node(s) {', '.join(sorted(stranded))} run in a worktree but are not a "
            "direct dependency of any barrier, so their commits are never merged "
            "back. Add a barrier node that depends on them."
        )


def _assert_acyclic(pipeline: Pipeline) -> None:
    """Detect dependency cycles.

    Note this covers only the *static* dependency edges. Replan edges are
    deliberately cyclic and are bounded at runtime by max_replans instead.
    """
    deps = {n.id: set(n.depends_on) for n in pipeline.nodes}
    temp: set[str] = set()
    done: set[str] = set()
    stack: list[str] = []

    def visit(nid: str) -> None:
        if nid in done:
            return
        if nid in temp:
            cycle = " -> ".join(stack[stack.index(nid) :] + [nid])
            raise PipelineError(f"dependency cycle: {cycle}")
        temp.add(nid)
        stack.append(nid)
        for dep in sorted(deps[nid]):
            visit(dep)
        stack.pop()
        temp.discard(nid)
        done.add(nid)

    for nid in deps:
        visit(nid)


def _has_downstream_human_gate(pipeline: Pipeline, start: NodeSpec) -> bool:
    """True if `start` itself, or anything reachable from it, has a human gate."""
    if start.has_human_gate:
        return True
    dependents: dict[str, list[str]] = {n.id: [] for n in pipeline.nodes}
    for n in pipeline.nodes:
        for dep in n.depends_on:
            dependents[dep].append(n.id)

    seen = {start.id}
    queue = list(dependents[start.id])
    while queue:
        nid = queue.pop()
        if nid in seen:
            continue
        seen.add(nid)
        if pipeline.node(nid).has_human_gate:
            return True
        queue.extend(dependents[nid])
    return False


# --------------------------------------------------------------------------
# Scheduling
# --------------------------------------------------------------------------


def schedule(pipeline: Pipeline) -> list[tuple[str, ...]]:
    """Group nodes into levels; nodes within a level may run concurrently.

    Ordering within a level is alphabetical purely so runs are reproducible and
    journals diff cleanly between runs.
    """
    remaining = {n.id: set(n.depends_on) for n in pipeline.nodes}
    levels: list[tuple[str, ...]] = []
    satisfied: set[str] = set()

    while remaining:
        ready = tuple(
            sorted(nid for nid, deps in remaining.items() if deps <= satisfied)
        )
        if not ready:
            raise PipelineError(
                f"cannot schedule; unsatisfiable dependencies among: "
                f"{', '.join(sorted(remaining))}"
            )
        levels.append(ready)
        satisfied.update(ready)
        for nid in ready:
            del remaining[nid]
    return levels


def descendants(pipeline: Pipeline, node_id: str) -> set[str]:
    """Everything downstream of a node.

    Used by both replan paths: when a node is re-run, whatever consumed its
    output is no longer valid and must run again too. Doing this by graph
    reachability rather than by a hand-maintained list is the difference
    between a rule and a habit.
    """
    dependents: dict[str, list[str]] = {n.id: [] for n in pipeline.nodes}
    for node in pipeline.nodes:
        for dep in node.depends_on:
            dependents[dep].append(node.id)

    out: set[str] = set()
    queue = list(dependents.get(node_id, []))
    while queue:
        nid = queue.pop()
        if nid in out:
            continue
        out.add(nid)
        queue.extend(dependents[nid])
    return out


def parallel_groups(pipeline: Pipeline) -> list[tuple[str, ...]]:
    """Levels that genuinely fan out. Used by the report to highlight concurrency."""
    return [lvl for lvl in schedule(pipeline) if len(lvl) > 1]
