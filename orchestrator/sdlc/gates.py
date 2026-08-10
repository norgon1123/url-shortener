"""Gate checks.

A gate is a named predicate plus its ADR-001 class. The class is what decides
how much authority the outcome carries:

  * **mechanical** -- an exit code, a threshold, a parsed artifact. Authoritative.
  * **self_report** -- a predicate over a field the model itself emitted. May
    pass or escalate; the pipeline loader guarantees a human gate downstream.
  * **human** -- a persisted approval record. Absence is not failure, it is
    ESCALATE: the run pauses rather than dying.

Every check has the same signature and is registered by name, so the YAML names
checks and nothing about the graph is hardcoded here. `known_checks()` is
consumed by the pipeline validator, which means a typo'd check name is a
load-time error rather than a surprise forty minutes into a run.

Checks take their inputs from a `GateContext` and never mutate anything. The
command runner is injectable for the same reason: the whole gate layer is
exercisable in milliseconds without Maven, git, or a model.
"""

from __future__ import annotations

import csv
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

import yaml

from . import schemas
from .model import (
    Approval,
    GateClass,
    GateOutcome,
    GateResult,
    GateSpec,
    NodeResult,
    NodeSpec,
)
from .policy import PolicyEngine


class GateError(RuntimeError):
    """Raised for a misconfigured gate -- a bad name or a missing parameter."""


# --------------------------------------------------------------------------
# Command execution
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class CommandResult:
    exit_code: int
    stdout: str = ""
    stderr: str = ""

    @property
    def ok(self) -> bool:
        return self.exit_code == 0

    def tail(self, lines: int = 15) -> str:
        """Last few lines of output -- what a failing gate should report.

        Maven prints thousands of lines and the reason is always at the end;
        putting the whole log in the journal buries it.
        """
        merged = (self.stdout + "\n" + self.stderr).strip().splitlines()
        return "\n".join(merged[-lines:])


class CommandRunner(Protocol):
    def __call__(
        self, argv: list[str], cwd: Path, timeout: float = 1800.0
    ) -> CommandResult: ...


def subprocess_runner(
    argv: list[str], cwd: Path, timeout: float = 1800.0
) -> CommandResult:
    try:
        proc = subprocess.run(
            argv,
            cwd=cwd,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError:
        return CommandResult(127, stderr=f"command not found: {argv[0]}")
    except subprocess.TimeoutExpired:
        return CommandResult(124, stderr=f"timed out after {timeout}s: {' '.join(argv)}")
    return CommandResult(proc.returncode, proc.stdout, proc.stderr)


# --------------------------------------------------------------------------
# Context
# --------------------------------------------------------------------------


@dataclass
class GateContext:
    """Everything a check may read. Checks never reach outside this."""

    workspace: Path
    node: NodeSpec
    policy: PolicyEngine
    phase: str = "exit"  # "entry" | "exit"
    result: NodeResult | None = None
    approvals: Mapping[str, Approval] = field(default_factory=dict)
    run: CommandRunner = subprocess_runner
    artifacts_dirname: str = "artifacts"
    service_dirname: str = "service"

    @property
    def artifacts_dir(self) -> Path:
        return self.workspace / self.artifacts_dirname

    @property
    def service_dir(self) -> Path:
        return self.workspace / self.service_dirname

    def path(self, relative: str) -> Path:
        return self.workspace / relative

    def resolve_artifact(self, name: str) -> Path:
        """Find an artifact by bare name.

        Node outputs land in `artifacts/`, but the design node also writes into
        the service tree, so fall back to a workspace-relative path before
        giving up.
        """
        candidate = self.artifacts_dir / name
        return candidate if candidate.exists() else self.workspace / name

    def read_json(self, name: str) -> Any:
        return json.loads(self.resolve_artifact(name).read_text(encoding="utf-8"))

    def files_written(self) -> tuple[str, ...]:
        return self.result.files_written if self.result else ()

    def maven(self, *goals: str) -> CommandResult:
        """Prefer the wrapper so the build uses the pinned Maven version."""
        wrapper = self.service_dir / "mvnw"
        exe = str(wrapper) if wrapper.exists() else (shutil.which("mvn") or "mvn")
        return self.run([exe, "-B", "-q", *goals], self.service_dir)


# --------------------------------------------------------------------------
# Registry
# --------------------------------------------------------------------------

CheckFn = Callable[[GateContext, dict[str, Any]], GateResult]
_REGISTRY: dict[str, CheckFn] = {}


def check(name: str) -> Callable[[CheckFn], CheckFn]:
    def register(fn: CheckFn) -> CheckFn:
        if name in _REGISTRY:
            raise GateError(f"duplicate gate check registration: {name}")
        _REGISTRY[name] = fn
        return fn

    return register


def known_checks() -> frozenset[str]:
    """Names the pipeline file is allowed to use. Used by graph validation."""
    return frozenset(_REGISTRY)


def _result(
    ctx: GateContext,
    spec_check: str,
    gate_class: GateClass,
    outcome: GateOutcome,
    detail: str = "",
    **evidence: Any,
) -> GateResult:
    return GateResult(
        check=spec_check,
        gate_class=gate_class,
        outcome=outcome,
        detail=detail,
        evidence=evidence,
    )


def evaluate(spec: GateSpec, ctx: GateContext) -> GateResult:
    """Run one gate, applying its `on_fail` override.

    The override is what lets the pipeline declare "this gate may pause the run
    but may never kill it" -- `review` and `merge_clean` both rely on it. A
    check therefore only ever needs to answer pass/fail honestly; the policy
    about what a failure *means* stays in the YAML.
    """
    fn = _REGISTRY.get(spec.check)
    if fn is None:
        raise GateError(
            f"node {ctx.node.id}: unknown gate check '{spec.check}'. "
            f"Known: {', '.join(sorted(_REGISTRY))}"
        )
    result = fn(ctx, spec.params)
    result.check = spec.check
    result.gate_class = spec.gate_class
    if result.outcome is GateOutcome.FAIL and spec.on_fail is GateOutcome.ESCALATE:
        result.outcome = GateOutcome.ESCALATE
        result.detail = f"{result.detail} (escalated to human, not failed)".strip()
    return result


def evaluate_all(specs: tuple[GateSpec, ...], ctx: GateContext) -> list[GateResult]:
    """Run every gate, even after one fails.

    Stopping at the first failure would hand a retrying agent one problem at a
    time; each round trip costs a model call, so report the full set.
    """
    return [evaluate(spec, ctx) for spec in specs]


def worst(results: list[GateResult]) -> GateOutcome:
    if any(r.outcome is GateOutcome.FAIL for r in results):
        return GateOutcome.FAIL
    if any(r.outcome is GateOutcome.ESCALATE for r in results):
        return GateOutcome.ESCALATE
    return GateOutcome.PASS


# --------------------------------------------------------------------------
# Structural / artifact checks
# --------------------------------------------------------------------------

_PASS = GateOutcome.PASS
_FAIL = GateOutcome.FAIL
_ESC = GateOutcome.ESCALATE
_MECH = GateClass.MECHANICAL


@check("artifact_present")
def _artifact_present(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    rel = params.get("path") or params.get("artifact")
    if not rel:
        raise GateError("artifact_present requires a 'path' or 'artifact' parameter")
    target = ctx.resolve_artifact(rel) if "artifact" in params else ctx.path(rel)
    if target.is_file():
        return _result(ctx, "artifact_present", _MECH, _PASS, f"found {rel}")
    return _result(ctx, "artifact_present", _MECH, _FAIL, f"missing required file: {rel}")


@check("schema_valid")
def _schema_valid(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    name = params.get("artifact")
    if not name:
        raise GateError("schema_valid requires an 'artifact' parameter")
    target = ctx.resolve_artifact(name)
    if not target.is_file():
        return _result(ctx, "schema_valid", _MECH, _FAIL, f"artifact not written: {name}")
    try:
        instance = json.loads(target.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return _result(ctx, "schema_valid", _MECH, _FAIL, f"{name} is not valid JSON: {exc}")

    schema_name = params.get("schema") or Path(name).stem
    errors = schemas.validate(schema_name, instance)
    if errors:
        return _result(
            ctx,
            "schema_valid",
            _MECH,
            _FAIL,
            f"{name} violates schema '{schema_name}': " + "; ".join(errors[:5]),
            error_count=len(errors),
        )
    return _result(ctx, "schema_valid", _MECH, _PASS, f"{name} conforms to '{schema_name}'")


@check("paths_confined")
def _paths_confined(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """Post-hoc verification that the node stayed inside its allowlist.

    The Agent SDK permission callback already refuses these writes live, so in
    the normal case this passes trivially. It exists because the callback only
    sees tool calls: a `Bash` heredoc writes files without ever invoking Write.
    This layer sees the diff, so it cannot be sidestepped that way.
    """
    written = ctx.files_written()
    violations, protected = ctx.policy.classify_diff(list(written))
    if violations:
        return _result(
            ctx,
            "paths_confined",
            _MECH,
            _FAIL,
            "; ".join(str(v) for v in violations[:5]),
            violations=[v.path for v in violations],
            files_checked=len(written),
        )
    if protected:
        return _result(
            ctx,
            "paths_confined",
            _MECH,
            _ESC,
            f"wrote protected path(s): {', '.join(protected)}; human approval required",
            protected=protected,
        )
    return _result(
        ctx, "paths_confined", _MECH, _PASS, f"{len(written)} file(s) within allowlist"
    )


@check("no_secrets")
def _no_secrets(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    contents: dict[str, str] = {}
    for rel in ctx.files_written():
        path = ctx.path(rel)
        if not path.is_file():
            continue
        try:
            contents[rel] = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue  # binary or unreadable: nothing to pattern-match
    found = ctx.policy.scan_files(contents)
    if found:
        return _result(
            ctx,
            "no_secrets",
            _MECH,
            _FAIL,
            "; ".join(f"{v.path}: {v.detail}" for v in found[:5]),
            hits=len(found),
        )
    return _result(ctx, "no_secrets", _MECH, _PASS, f"scanned {len(contents)} file(s)")


# --------------------------------------------------------------------------
# Clarification / planning checks
# --------------------------------------------------------------------------


@check("assumptions_present")
def _assumptions_present(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """Force the implicit decisions into the open.

    Self-report class: the model populates `assumptions` itself, so this cannot
    detect an assumption that was made but not declared. It is not meant to --
    it guarantees the human at the `design` checkpoint has a list to review
    rather than an empty section and a green tick.
    """
    name = params.get("artifact", "clarification.json")
    try:
        data = ctx.read_json(name)
    except (OSError, json.JSONDecodeError) as exc:
        return _result(ctx, "assumptions_present", GateClass.SELF_REPORT, _FAIL, str(exc))
    assumptions = data.get("assumptions") or []
    if not assumptions:
        return _result(
            ctx,
            "assumptions_present",
            GateClass.SELF_REPORT,
            _FAIL,
            "no assumptions declared; every requirement leaves something unstated, "
            "so an empty list means they were made silently",
        )
    return _result(
        ctx,
        "assumptions_present",
        GateClass.SELF_REPORT,
        _PASS,
        f"{len(assumptions)} assumption(s) declared for human review",
        assumption_ids=[a.get("id") for a in assumptions],
    )


def _blocking(data: Any) -> list[dict[str, Any]]:
    return [
        a
        for a in (data.get("ambiguities") or [])
        if a.get("severity") == "blocking"
    ]


@check("no_blocking_ambiguities")
def _no_blocking_ambiguities(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    name = params.get("artifact", "clarification.json")
    try:
        data = ctx.read_json(name)
    except (OSError, json.JSONDecodeError) as exc:
        return _result(
            ctx, "no_blocking_ambiguities", GateClass.SELF_REPORT, _FAIL, str(exc)
        )
    blocking = _blocking(data)
    if blocking:
        questions = "; ".join(a.get("question", a.get("id", "?")) for a in blocking[:3])
        return _result(
            ctx,
            "no_blocking_ambiguities",
            GateClass.SELF_REPORT,
            _FAIL,  # the spec's on_fail: escalate converts this to a pause
            f"{len(blocking)} blocking ambiguity/ies: {questions}",
            ambiguity_ids=[a.get("id") for a in blocking],
        )
    return _result(
        ctx,
        "no_blocking_ambiguities",
        GateClass.SELF_REPORT,
        _PASS,
        "no blocking ambiguities reported",
    )


@check("no_unresolved_ambiguities")
def _no_unresolved_ambiguities(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """Entry gate on `decompose` -- and mechanical, not self-report.

    It reads the human's recorded answers, not a model field: every blocking
    ambiguity must have an answer in the approval record for `clarify`. Planning
    cannot begin on a question a person never answered.
    """
    name = params.get("artifact", "clarification.json")
    try:
        data = ctx.read_json(name)
    except (OSError, json.JSONDecodeError) as exc:
        return _result(ctx, "no_unresolved_ambiguities", _MECH, _FAIL, str(exc))

    blocking = _blocking(data)
    if not blocking:
        return _result(ctx, "no_unresolved_ambiguities", _MECH, _PASS, "nothing to resolve")

    approval = ctx.approvals.get(params.get("approval_node", "clarify"))
    answers = approval.answers if approval else {}
    unresolved = [a["id"] for a in blocking if not answers.get(a.get("id", ""))]
    if unresolved:
        return _result(
            ctx,
            "no_unresolved_ambiguities",
            _MECH,
            _FAIL,
            f"unanswered blocking ambiguity/ies: {', '.join(unresolved)}",
            unresolved=unresolved,
        )
    return _result(
        ctx,
        "no_unresolved_ambiguities",
        _MECH,
        _PASS,
        f"{len(blocking)} ambiguity/ies resolved by {approval.approver if approval else '?'}",
        resolved=[a["id"] for a in blocking],
    )


@check("plan_is_dag")
def _plan_is_dag(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """The plan the model produced must itself be a schedulable graph.

    A cycle or a dangling dependency here becomes an unschedulable task list
    three nodes later, at which point the cause is far from the symptom.
    """
    name = params.get("artifact", "plan.json")
    try:
        data = ctx.read_json(name)
    except (OSError, json.JSONDecodeError) as exc:
        return _result(ctx, "plan_is_dag", _MECH, _FAIL, str(exc))

    tasks = data.get("tasks") or []
    ids = [t.get("id") for t in tasks]
    problems: list[str] = []
    if len(ids) != len(set(ids)):
        problems.append(f"duplicate task ids: {sorted({i for i in ids if ids.count(i) > 1})}")

    known = set(ids)
    deps = {t["id"]: [d for d in (t.get("depends_on") or [])] for t in tasks if t.get("id")}
    for tid, ds in deps.items():
        for d in ds:
            if d not in known:
                problems.append(f"task '{tid}' depends on unknown task '{d}'")

    state: dict[str, int] = {}

    def visit(tid: str, trail: list[str]) -> None:
        if state.get(tid) == 2:
            return
        if state.get(tid) == 1:
            problems.append("cycle: " + " -> ".join(trail[trail.index(tid):] + [tid]))
            return
        state[tid] = 1
        for d in deps.get(tid, []):
            if d in known:
                visit(d, trail + [tid])
        state[tid] = 2

    for tid in deps:
        visit(tid, [])

    if problems:
        return _result(ctx, "plan_is_dag", _MECH, _FAIL, "; ".join(problems[:5]))
    return _result(
        ctx, "plan_is_dag", _MECH, _PASS, f"{len(tasks)} task(s) form a valid DAG"
    )


# --------------------------------------------------------------------------
# Contract checks
# --------------------------------------------------------------------------

_OPENAPI_METHODS = {"get", "put", "post", "delete", "patch", "head", "options"}


def _load_openapi(ctx: GateContext, name: str) -> tuple[dict[str, Any] | None, str]:
    target = ctx.resolve_artifact(name)
    if not target.is_file():
        return None, f"OpenAPI document not found: {name}"
    try:
        doc = yaml.safe_load(target.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        return None, f"{name} is not valid YAML: {exc}"
    if not isinstance(doc, dict):
        return None, f"{name}: top level must be a mapping"
    return doc, ""


@check("openapi_lints")
def _openapi_lints(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """Structural lint of the frozen contract.

    Deliberately not a full Spectral ruleset -- these are the rules that break
    the *downstream nodes*. `author-tests` writes tests against operationIds and
    documented response codes, so a missing operationId or an operation with no
    responses is what actually derails the parallel branch.
    """
    name = params.get("artifact", "openapi.yaml")
    doc, err = _load_openapi(ctx, name)
    if doc is None:
        return _result(ctx, "openapi_lints", _MECH, _FAIL, err)

    problems: list[str] = []
    if not str(doc.get("openapi", "")).startswith("3."):
        problems.append("missing or non-3.x 'openapi' version field")
    if not (doc.get("info") or {}).get("title"):
        problems.append("info.title is required")
    paths = doc.get("paths") or {}
    if not paths:
        problems.append("no paths defined")

    seen_ids: set[str] = set()
    for route, ops in paths.items():
        if not isinstance(ops, dict):
            problems.append(f"{route}: path item must be a mapping")
            continue
        for method, op in ops.items():
            if method.lower() not in _OPENAPI_METHODS or not isinstance(op, dict):
                continue
            label = f"{method.upper()} {route}"
            op_id = op.get("operationId")
            if not op_id:
                problems.append(f"{label}: missing operationId")
            elif op_id in seen_ids:
                problems.append(f"{label}: duplicate operationId '{op_id}'")
            else:
                seen_ids.add(op_id)
            if not (op.get("responses") or {}):
                problems.append(f"{label}: declares no responses")

    if problems:
        return _result(
            ctx,
            "openapi_lints",
            _MECH,
            _FAIL,
            "; ".join(problems[:6]),
            problem_count=len(problems),
        )
    return _result(
        ctx,
        "openapi_lints",
        _MECH,
        _PASS,
        f"{len(paths)} path(s), {len(seen_ids)} operation(s) lint clean",
    )


@check("contract_frozen")
def _contract_frozen(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """Entry gate on both parallel branches.

    Blind parallel authoring only works if both branches see byte-identical
    inputs, so `design` records a hash of the contract file set and each branch
    re-derives it before starting. A drifted contract is caught here rather than
    at the merge, where the symptom would be an unexplained pile of conflicts.
    """
    from .audit import hash_inputs

    name = params.get("artifact", "design.json")
    try:
        design = ctx.read_json(name)
    except (OSError, json.JSONDecodeError) as exc:
        return _result(ctx, "contract_frozen", _MECH, _FAIL, str(exc))

    files = design.get("contract_files") or []
    if not files:
        return _result(ctx, "contract_frozen", _MECH, _FAIL, "design declares no contract files")

    missing = [f for f in files if not ctx.path(f).is_file()]
    if missing:
        return _result(
            ctx,
            "contract_frozen",
            _MECH,
            _FAIL,
            f"contract file(s) absent from the worktree: {', '.join(missing[:5])}",
            missing=missing,
        )

    actual = hash_inputs([ctx.path(f) for f in files], root=ctx.workspace)
    recorded = design.get("contract_hash")
    if recorded and recorded != actual:
        return _result(
            ctx,
            "contract_frozen",
            _MECH,
            _FAIL,
            f"contract has changed since design froze it "
            f"(recorded {recorded[:12]}..., found {actual[:12]}...)",
            recorded=recorded,
            actual=actual,
        )
    return _result(
        ctx,
        "contract_frozen",
        _MECH,
        _PASS,
        f"{len(files)} contract file(s) intact at {actual[:12]}...",
        contract_hash=actual,
    )


# --------------------------------------------------------------------------
# Build checks
# --------------------------------------------------------------------------


def _maven_gate(ctx: GateContext, name: str, goals: list[str], label: str) -> GateResult:
    res = ctx.maven(*goals)
    if res.ok:
        return _result(ctx, name, _MECH, _PASS, label, exit_code=0)
    return _result(
        ctx,
        name,
        _MECH,
        _FAIL,
        f"`mvn {' '.join(goals)}` exited {res.exit_code}:\n{res.tail()}",
        exit_code=res.exit_code,
    )


@check("maven_compiles")
def _maven_compiles(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    return _maven_gate(ctx, "maven_compiles", ["-DskipTests", "compile"], "main sources compile")


@check("tests_compile")
def _tests_compile(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """Compile the tests without running them.

    `author-tests` runs blind against the contract, so its tests will not *pass*
    until the implementation lands. Compiling is the strongest signal available
    at that point, and it is a real one: it proves the tests were written
    against the frozen contract's types rather than invented ones.
    """
    return _maven_gate(ctx, "tests_compile", ["test-compile"], "test sources compile")


@check("maven_verify")
def _maven_verify(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    return _maven_gate(ctx, "maven_verify", ["verify"], "full build and test suite green")


@check("coverage_floor")
def _coverage_floor(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """Read JaCoCo's CSV report rather than trusting the build's own threshold.

    Parsing the report means the gate can state the actual number in the
    journal. "Coverage 0.62 < 0.70" is an auditable record; "the build failed"
    is not.
    """
    floor = float(params.get("min_line_coverage", 0.70))
    report = ctx.service_dir / params.get(
        "report", "target/site/jacoco/jacoco.csv"
    )
    if not report.is_file():
        return _result(
            ctx,
            "coverage_floor",
            _MECH,
            _FAIL,
            f"no JaCoCo report at {report}; coverage cannot be evidenced",
        )

    missed = covered = 0
    with report.open(newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            try:
                missed += int(row["LINE_MISSED"])
                covered += int(row["LINE_COVERED"])
            except (KeyError, ValueError):
                return _result(
                    ctx, "coverage_floor", _MECH, _FAIL, f"malformed JaCoCo report: {report}"
                )

    total = missed + covered
    ratio = covered / total if total else 0.0
    detail = f"line coverage {ratio:.1%} ({covered}/{total}) against a {floor:.0%} floor"
    outcome = _PASS if ratio >= floor else _FAIL
    return _result(
        ctx,
        "coverage_floor",
        _MECH,
        outcome,
        detail,
        line_coverage=round(ratio, 4),
        lines_covered=covered,
        lines_total=total,
        floor=floor,
    )


# --------------------------------------------------------------------------
# Cross-artifact consistency
# --------------------------------------------------------------------------

# Spring's mapping annotations. The class-level @RequestMapping supplies a
# prefix; the method-level annotation supplies the verb and the suffix.
_CLASS_MAPPING = re.compile(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')
_METHOD_MAPPING = re.compile(
    r'@(Get|Post|Put|Patch|Delete)Mapping\s*\(\s*(?:(?:value|path)\s*=\s*)?"([^"]*)"'
)
_BARE_METHOD_MAPPING = re.compile(r"@(Get|Post|Put|Patch|Delete)Mapping\s*(?:\(\s*\))?\s*$")


def _normalize_route(route: str) -> str:
    """Collapse path variables so `{code}` and `{shortCode}` compare equal."""
    collapsed = re.sub(r"\{[^}]*\}", "{}", route)
    collapsed = re.sub(r"/+", "/", collapsed)
    return collapsed.rstrip("/") or "/"


def _spring_routes(source_root: Path) -> set[tuple[str, str]]:
    routes: set[tuple[str, str]] = set()
    for java in source_root.rglob("*.java"):
        text = java.read_text(encoding="utf-8", errors="replace")
        if "Mapping" not in text:
            continue
        prefix_match = _CLASS_MAPPING.search(text)
        prefix = prefix_match.group(1) if prefix_match else ""
        for verb, suffix in _METHOD_MAPPING.findall(text):
            routes.add((verb.upper(), _normalize_route(f"{prefix}/{suffix}")))
        for line in text.splitlines():
            if m := _BARE_METHOD_MAPPING.search(line.strip()):
                routes.add((m.group(1).upper(), _normalize_route(prefix or "/")))
    return routes


@check("routes_match_openapi")
def _routes_match_openapi(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """Diff the implemented controllers against the frozen contract.

    This is the gate that catches the most expensive silent failure mode in the
    parallel branch: `implement` quietly renaming an endpoint. The tests were
    authored blind against the contract, so a rename shows up as a mysterious
    404 in a test failure. Comparing the two route sets directly names it.

    Known limitation, stated rather than hidden: this is a regex scan of the
    annotations, not a JavaParser AST walk. It handles the literal-string
    mappings this codebase uses and would need replacing for constant-valued or
    dynamically composed paths.
    """
    name = params.get("artifact", "openapi.yaml")
    doc, err = _load_openapi(ctx, name)
    if doc is None:
        return _result(ctx, "routes_match_openapi", _MECH, _FAIL, err)

    documented = {
        (method.upper(), _normalize_route(route))
        for route, ops in (doc.get("paths") or {}).items()
        if isinstance(ops, dict)
        for method in ops
        if method.lower() in _OPENAPI_METHODS
    }
    source_root = ctx.service_dir / params.get("source_root", "src/main/java")
    if not source_root.is_dir():
        return _result(
            ctx, "routes_match_openapi", _MECH, _FAIL, f"no Java sources at {source_root}"
        )
    implemented = _spring_routes(source_root)

    undocumented = implemented - documented
    unimplemented = documented - implemented
    if undocumented or unimplemented:
        parts = []
        if unimplemented:
            parts.append(
                "in contract but not implemented: "
                + ", ".join(f"{m} {p}" for m, p in sorted(unimplemented))
            )
        if undocumented:
            parts.append(
                "implemented but not in contract: "
                + ", ".join(f"{m} {p}" for m, p in sorted(undocumented))
            )
        return _result(
            ctx,
            "routes_match_openapi",
            _MECH,
            _FAIL,
            "; ".join(parts),
            unimplemented=sorted(f"{m} {p}" for m, p in unimplemented),
            undocumented=sorted(f"{m} {p}" for m, p in undocumented),
        )
    return _result(
        ctx,
        "routes_match_openapi",
        _MECH,
        _PASS,
        f"{len(documented)} route(s) match the contract exactly",
    )


_MD_LINK = re.compile(r"\[[^\]]*\]\(\s*(<[^>]*>|[^)\s]+)")


@check("links_resolve")
def _links_resolve(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """Every relative link in generated docs must point at something real.

    Documentation is the node most prone to confident invention -- a link to
    `docs/runbook.md` that was never written reads as complete right up until
    someone clicks it.
    """
    targets = [
        ctx.path(rel)
        for rel in ctx.files_written()
        if rel.endswith(".md") and ctx.path(rel).is_file()
    ]
    broken: list[str] = []
    checked = 0
    for doc in targets:
        text = doc.read_text(encoding="utf-8", errors="replace")
        for raw in _MD_LINK.findall(text):
            link = raw.strip("<>")
            if link.startswith(("http://", "https://", "mailto:", "#")):
                continue  # external liveness is not this gate's business
            checked += 1
            path_part = link.split("#", 1)[0]
            if not path_part:
                continue
            if not (doc.parent / path_part).exists():
                broken.append(f"{doc.name} -> {link}")

    if broken:
        return _result(
            ctx,
            "links_resolve",
            _MECH,
            _FAIL,
            f"{len(broken)} broken link(s): " + "; ".join(broken[:5]),
            broken=broken,
        )
    return _result(
        ctx,
        "links_resolve",
        _MECH,
        _PASS,
        f"{checked} relative link(s) across {len(targets)} document(s) resolve",
    )


# --------------------------------------------------------------------------
# Merge, review, and human checks
# --------------------------------------------------------------------------


@check("merge_clean")
def _merge_clean(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """Did the barrier merge the two branch worktrees without conflict?

    Conflicts are escalated rather than failed (see the `on_fail` in the YAML):
    a conflict between generated implementation and generated tests is exactly
    the case where a person should look, not where a machine should retry.
    """
    output = ctx.result.output if ctx.result else {}
    conflicts = output.get("conflicts") or []
    if conflicts:
        return _result(
            ctx,
            "merge_clean",
            _MECH,
            _FAIL,
            f"{len(conflicts)} conflicted path(s): " + ", ".join(conflicts[:5]),
            conflicts=conflicts,
        )
    merged = output.get("merged_branches") or []
    return _result(
        ctx,
        "merge_clean",
        _MECH,
        _PASS,
        f"merged {len(merged)} branch(es) cleanly" if merged else "nothing to merge",
        merged_branches=merged,
    )


_SPOTBUGS_PRIORITY = {1: "high", 2: "medium", 3: "low"}
_SEVERITY_RANK = {"low": 0, "medium": 1, "high": 2}


@check("static_analysis")
def _static_analysis(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """Deterministic analysis is what actually gates the review node.

    ADR-001: the model's own findings are advisory, so the authoritative signal
    at `review` has to come from a tool with a fixed rule set. SpotBugs findings
    at or above the configured severity fail the gate outright.
    """
    threshold = str(params.get("max_severity", "high")).lower()
    if threshold not in _SEVERITY_RANK:
        raise GateError(f"static_analysis: unknown max_severity '{threshold}'")

    report = ctx.service_dir / params.get("report", "target/spotbugsXml.xml")
    res = ctx.maven("-DskipTests", "spotbugs:spotbugs")
    if not report.is_file():
        return _result(
            ctx,
            "static_analysis",
            _MECH,
            _FAIL,
            f"no SpotBugs report at {report}; analysis did not run "
            f"(mvn exited {res.exit_code})\n{res.tail(8)}",
        )

    try:
        root = ET.parse(report).getroot()
    except ET.ParseError as exc:
        return _result(ctx, "static_analysis", _MECH, _FAIL, f"unparseable report: {exc}")

    counts = {"high": 0, "medium": 0, "low": 0}
    offenders: list[str] = []
    for bug in root.iter("BugInstance"):
        severity = _SPOTBUGS_PRIORITY.get(int(bug.get("priority", 3)), "low")
        counts[severity] += 1
        if _SEVERITY_RANK[severity] >= _SEVERITY_RANK[threshold]:
            offenders.append(f"{bug.get('type')} ({severity})")

    summary = ", ".join(f"{n} {sev}" for sev, n in counts.items() if n)
    if offenders:
        return _result(
            ctx,
            "static_analysis",
            _MECH,
            _FAIL,
            f"{len(offenders)} finding(s) at or above '{threshold}': "
            + "; ".join(sorted(set(offenders))[:5]),
            counts=counts,
        )
    return _result(
        ctx,
        "static_analysis",
        _MECH,
        _PASS,
        f"no findings at or above '{threshold}'" + (f" ({summary})" if summary else ""),
        counts=counts,
    )


@check("blocker_findings_escalate")
def _blocker_findings_escalate(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """The reviewing model can pause the run. It can never fail it.

    ADR-001 in its most concrete form: `review` reads its own findings, so
    letting it fail the run would make the model both author and judge of the
    verdict. A blocker finding routes to a human with the finding attached; the
    human decides. Note the YAML's `on_fail: escalate` -- this returning FAIL
    still cannot terminate the run.
    """
    name = params.get("artifact", "review.json")
    try:
        data = ctx.read_json(name)
    except (OSError, json.JSONDecodeError) as exc:
        return _result(
            ctx, "blocker_findings_escalate", GateClass.SELF_REPORT, _FAIL, str(exc)
        )
    findings = data.get("findings") or []
    blockers = [f for f in findings if f.get("severity") == "blocker"]
    if blockers:
        return _result(
            ctx,
            "blocker_findings_escalate",
            GateClass.SELF_REPORT,
            _FAIL,
            f"{len(blockers)} blocker finding(s) require human adjudication: "
            + "; ".join(f"{f.get('file')}: {f.get('summary')}" for f in blockers[:3]),
            blocker_ids=[f.get("id") for f in blockers],
            total_findings=len(findings),
        )
    return _result(
        ctx,
        "blocker_findings_escalate",
        GateClass.SELF_REPORT,
        _PASS,
        f"{len(findings)} advisory finding(s), no blockers",
        total_findings=len(findings),
    )


@check("human_approval")
def _human_approval(ctx: GateContext, params: dict[str, Any]) -> GateResult:
    """No approval on file is ESCALATE, not FAIL.

    That distinction is the whole checkpoint mechanism: the run pauses and
    persists, `orchestrator approve` records a decision, and the gate is
    re-evaluated. A rejection *is* a failure, and carries the reviewer's note
    forward so the replan attempt knows what was wrong.
    """
    reason = params.get("reason", "human approval required")
    approval = ctx.approvals.get(ctx.node.id)
    if approval is None:
        return _result(ctx, "human_approval", GateClass.HUMAN, _ESC, reason)
    if approval.approved:
        return _result(
            ctx,
            "human_approval",
            GateClass.HUMAN,
            _PASS,
            f"approved by {approval.approver}"
            + (f": {approval.note}" if approval.note else ""),
            approver=approval.approver,
            note=approval.note,
        )
    return _result(
        ctx,
        "human_approval",
        GateClass.HUMAN,
        _FAIL,
        f"rejected by {approval.approver}"
        + (f": {approval.note}" if approval.note else ""),
        approver=approval.approver,
        note=approval.note,
    )
