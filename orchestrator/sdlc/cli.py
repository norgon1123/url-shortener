"""Command line interface.

Deliberately a plain CLI over a plain filesystem: no daemon, no server, no
shared mutable state beyond one SQLite file and one journal per run. That is
what lets the same code run unchanged on a laptop and as a CI job -- Stage 1 and
Stage 2 of the deployment story differ in *where* it is invoked, not in what it
is.

Every subcommand that changes anything prints the next command to run. During a
walkthrough that is the difference between a demo and a guided tour, and in CI
it is what a build log needs to say when a run stops for a human.

    python -m sdlc.cli run --script fixtures/scenarios/greenfield.yaml
    python -m sdlc.cli approve <run-id> design --approver neil --note "..."
    python -m sdlc.cli resume <run-id>
    python -m sdlc.cli report <run-id>
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

from .audit import Journal, TamperError
from .budget import BudgetGuard
from .checkpoint import CheckpointManager, Git
from .engine import Engine, new_run_id
from .graph import load_pipeline, parallel_groups
from .metrics import compute, render_text
from .mock import load_script
from .model import Approval, ApprovalDecision, RunStatus
from .state import RunStore

DEFAULT_PIPELINE = "orchestrator/pipelines/sdlc.yaml"
DEFAULT_PROMPTS = "orchestrator/prompts"
DEFAULT_RUNS = "runs"

MANIFEST = "manifest.json"


def run_dir(runs_root: Path, run_id: str) -> Path:
    return runs_root / run_id


def _store(runs_root: Path) -> RunStore:
    return RunStore(runs_root / "state.db")


def _journal(runs_root: Path, run_id: str) -> Journal:
    return Journal(run_dir(runs_root, run_id) / "journal.jsonl", run_id=run_id)


def _load_manifest(runs_root: Path, run_id: str) -> dict:
    path = run_dir(runs_root, run_id) / MANIFEST
    if not path.is_file():
        raise SystemExit(f"no manifest for run {run_id}; expected {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def _backend(manifest: dict, pipeline, transcripts: Path):
    """Mock or live.

    The live backend is imported lazily. Importing it pulls in the Agent SDK,
    which the mock path deliberately does not need -- an evaluator with no API
    key can still run everything the test suite runs.

    Note what gets handed across: the *pipeline's* policy, not a copy of it.
    The guardrails the backend enforces at the tool layer and the guardrails the
    gates enforce afterwards are the same object read from the same YAML, so
    they cannot drift apart into a live enforcement that is quietly laxer than
    the audit check.
    """
    if manifest.get("backend", "mock") == "mock":
        script = manifest.get("script")
        if not script:
            raise SystemExit("mock backend requires --script")
        return load_script(script)
    from .agent_backend import AgentSDKBackend

    return AgentSDKBackend(
        prompts_root=Path(manifest["prompts"]),
        policy=pipeline.policy,
        transcripts_root=transcripts,
    )


def _engine(runs_root: Path, run_id: str, manifest: dict) -> Engine:
    pipeline = load_pipeline(manifest["pipeline"])
    workspace = Path(manifest["workspace"]).resolve()
    store = _store(runs_root)
    return Engine(
        pipeline=pipeline,
        backend=_backend(manifest, pipeline, run_dir(runs_root, run_id) / "transcripts"),
        store=store,
        journal=_journal(runs_root, run_id),
        checkpoints=CheckpointManager(
            git=Git(root=workspace),
            run_id=run_id,
            worktree_root=run_dir(runs_root, run_id) / "worktrees",
            # A node's commit may not contain paths the node was forbidden to
            # write. Without this, an operator editing the repository while a
            # run is in flight has their work attributed to whichever node
            # checkpoints next, under that node's trailers.
            exclude_paths=pipeline.policy.forbidden_paths,
        ),
        workspace=workspace,
        prompts_root=Path(manifest["prompts"]),
        run_id=run_id,
        budget=BudgetGuard(pipeline.budget, spent_usd=store.get_run(run_id).cost_usd),
    )


# --------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------


def cmd_run(args: argparse.Namespace) -> int:
    runs_root = Path(args.runs_dir)
    pipeline = load_pipeline(args.pipeline)
    run_id = args.run_id or new_run_id()

    manifest = {
        "pipeline": str(Path(args.pipeline).resolve()),
        "workspace": str(Path(args.workspace).resolve()),
        "prompts": str(Path(args.prompts).resolve()),
        "script": str(Path(args.script).resolve()) if args.script else None,
        "scenario": args.scenario,
        "backend": args.backend,
    }
    directory = run_dir(runs_root, run_id)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / MANIFEST).write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    store = _store(runs_root)
    # A raw IntegrityError here reads as a bug in the orchestrator. It is
    # usually an operator re-using an id after a run died early, and the two
    # useful next steps are worth naming rather than leaving to be worked out
    # from a traceback.
    try:
        store.get_run(run_id)
    except KeyError:
        pass
    else:
        raise SystemExit(
            f"run {run_id} already exists in {runs_root}/state.db.\n"
            f"  resume it:      python -m sdlc.cli resume {run_id}\n"
            f"  or start fresh: pass a different --run-id"
        )

    store.create_run(
        run_id,
        pipeline=manifest["pipeline"],
        scenario=args.scenario,
        workspace=manifest["workspace"],
    )

    git = Git(root=Path(manifest["workspace"]))
    if args.branch:
        # Runs never commit to the default branch. A run's output is a branch
        # someone reviews, which is the same shape as any other change.
        git.create_branch(args.branch)

    print(f"run {run_id}")
    print(f"  pipeline   {pipeline.version} — {len(pipeline.nodes)} nodes")
    print(f"  parallel   {parallel_groups(pipeline) or 'none'}")
    print(f"  workspace  {manifest['workspace']}")
    print()

    status = _engine(runs_root, run_id, manifest).run()
    return _report_status(runs_root, run_id, status)


def cmd_resume(args: argparse.Namespace) -> int:
    runs_root = Path(args.runs_dir)
    manifest = _load_manifest(runs_root, args.run_id)
    status = _engine(runs_root, args.run_id, manifest).run(resume=True)
    return _report_status(runs_root, args.run_id, status)


def _report_status(runs_root: Path, run_id: str, status: RunStatus) -> int:
    """Print the outcome and, crucially, the next command."""
    store = _store(runs_root)
    print(f"\nrun {run_id}: {status.value.upper()}")

    if status is RunStatus.PENDING_APPROVAL:
        waiting = [
            nid
            for nid, state in store.node_states(run_id).items()
            if state.status.value == "pending_approval"
        ]
        for node_id in waiting:
            print(f"\n  waiting on a decision at `{node_id}`:")
            print(f"    python -m sdlc.cli approve {run_id} {node_id} --approver <you>")
            print(f"    python -m sdlc.cli reject  {run_id} {node_id} --approver <you> --note '...'")
        print(f"\n  then: python -m sdlc.cli resume {run_id}")
        return 2

    if status is RunStatus.SAFE_STOPPED:
        print(f"  halted at a node boundary; resume with: python -m sdlc.cli resume {run_id}")
        return 3
    if status is RunStatus.FAILED:
        print(f"  see: python -m sdlc.cli report {run_id}")
        return 1

    print(f"  report: python -m sdlc.cli report {run_id}")
    return 0


def cmd_decide(args: argparse.Namespace, decision: ApprovalDecision) -> int:
    runs_root = Path(args.runs_dir)
    answers = {}
    for pair in args.answer or []:
        if "=" not in pair:
            raise SystemExit(f"--answer expects id=text, got {pair!r}")
        key, value = pair.split("=", 1)
        answers[key] = value

    approval = Approval(
        node_id=args.node_id,
        decision=decision,
        approver=args.approver,
        note=args.note or "",
        answers=answers,
    )
    _store(runs_root).record_approval(args.run_id, approval)

    # The journal, not the approvals table, is the four-eyes record: the table
    # holds only the live decision, and a rejection is cleared once acted on.
    journal = _journal(runs_root, args.run_id)
    journal.append(
        "human_decision",
        node_id=args.node_id,
        decision=decision.value,
        approver=args.approver,
        note=approval.note,
        answers=answers,
    )
    print(f"{decision.value} {args.node_id} by {args.approver}")
    print(f"  next: python -m sdlc.cli resume {args.run_id}")
    return 0


def cmd_stop(args: argparse.Namespace) -> int:
    _store(Path(args.runs_dir)).request_stop(args.run_id)
    print(f"stop requested for {args.run_id}")
    print("  the run halts at its next node boundary, leaving a resumable checkpoint")
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    store = _store(Path(args.runs_dir))
    if not args.run_id:
        for record in store.list_runs():
            print(f"{record.run_id:<32} {record.status.value:<18} ${record.cost_usd:.2f}")
        return 0

    record = store.get_run(args.run_id)
    print(f"{record.run_id}  {record.status.value.upper()}")
    print(f"  scenario {record.scenario}   cost ${record.cost_usd:.2f}   replans {record.replans}")
    if record.stop_requested:
        print("  stop requested")
    print()
    for node_id, state in store.node_states(args.run_id).items():
        commit = (state.checkpoint_commit or "")[:8]
        print(f"  {node_id:<22} {state.status.value:<18} attempt {state.attempt}  {commit}")
    return 0


def cmd_report(args: argparse.Namespace) -> int:
    runs_root = Path(args.runs_dir)
    metrics = compute(_journal(runs_root, args.run_id))
    if args.json:
        print(json.dumps(metrics.as_dict(), indent=2))
    else:
        print(render_text(metrics))
    return 0


def cmd_verify(args: argparse.Namespace) -> int:
    journal = _journal(Path(args.runs_dir), args.run_id)
    try:
        journal.verify()
    except TamperError as exc:
        print(f"TAMPERED: {exc}", file=sys.stderr)
        return 1
    entries = journal.entries()
    print(f"journal verified: {len(entries)} entries, chain intact")
    print(f"  head {entries[-1].entry_hash[:16]}...")
    return 0


def cmd_replay(args: argparse.Namespace) -> int:
    """Inspect a recorded run with no API key and no execution."""
    journal = Journal(Path(args.fixture) / "journal.jsonl", run_id="<replay>")
    journal.verify()
    for entry in journal.entries():
        node = f" [{entry.node_id}]" if entry.node_id else ""
        detail = entry.payload.get("detail") or entry.payload.get("reason") or ""
        print(f"{entry.seq:>4} {entry.ts}  {entry.event}{node}  {detail}")
    print()
    print(render_text(compute(journal, verify=False)))
    return 0


def cmd_lineage(args: argparse.Namespace) -> int:
    """Answer 'why did this happen?' rather than merely 'what happened?'."""
    journal = _journal(Path(args.runs_dir), args.run_id)
    chain = journal.lineage(args.entry_id)
    if not chain:
        print(f"no entry {args.entry_id}", file=sys.stderr)
        return 1
    for depth, entry in enumerate(chain):
        indent = "  " * depth
        detail = entry.payload.get("reason") or entry.payload.get("error") or ""
        print(f"{indent}{entry.entry_id}  {entry.event}  {entry.node_id or ''}  {detail}")
    return 0


# --------------------------------------------------------------------------
# Argument parsing
# --------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="sdlc", description=__doc__)
    # Resolved, not stored as given. Worktree paths are derived from it, and a
    # relative one produces commands that only work from the directory the run
    # happened to start in -- which is the main checkout, never a worktree.
    parser.add_argument("--runs-dir", default=DEFAULT_RUNS, type=lambda p: Path(p).resolve())
    sub = parser.add_subparsers(dest="command", required=True)

    run = sub.add_parser("run", help="start a new run")
    run.add_argument("--pipeline", default=DEFAULT_PIPELINE)
    run.add_argument("--workspace", default=".")
    run.add_argument("--prompts", default=DEFAULT_PROMPTS)
    run.add_argument("--script", help="mock scenario script")
    run.add_argument("--scenario")
    run.add_argument("--backend", choices=("mock", "live"), default="mock")
    run.add_argument("--branch", help="create and switch to this branch first")
    run.add_argument("--run-id")
    run.set_defaults(func=cmd_run)

    resume = sub.add_parser("resume", help="continue a paused or stopped run")
    resume.add_argument("run_id")
    resume.set_defaults(func=cmd_resume)

    for name, decision in (
        ("approve", ApprovalDecision.APPROVED),
        ("reject", ApprovalDecision.REJECTED),
    ):
        cmd = sub.add_parser(name, help=f"record a {name} decision at a checkpoint")
        cmd.add_argument("run_id")
        cmd.add_argument("node_id")
        cmd.add_argument("--approver", required=True)
        cmd.add_argument("--note", default="")
        cmd.add_argument(
            "--answer",
            action="append",
            help=(
                "resolve a blocking ambiguity, as id=text (repeatable). "
                "`route=<node-id>` names the branch that repairs a contract "
                "question you are adjudicating"
            ),
        )
        cmd.set_defaults(func=lambda a, d=decision: cmd_decide(a, d))

    stop = sub.add_parser("stop", help="request a safe stop at the next node boundary")
    stop.add_argument("run_id")
    stop.set_defaults(func=cmd_stop)

    status = sub.add_parser("status", help="show run and node state")
    status.add_argument("run_id", nargs="?")
    status.set_defaults(func=cmd_status)

    report = sub.add_parser("report", help="reliability metrics for a run")
    report.add_argument("run_id")
    report.add_argument("--json", action="store_true")
    report.set_defaults(func=cmd_report)

    verify = sub.add_parser("verify", help="check a run journal's hash chain")
    verify.add_argument("run_id")
    verify.set_defaults(func=cmd_verify)

    replay = sub.add_parser("replay", help="inspect a recorded run, no API key needed")
    replay.add_argument("fixture")
    replay.set_defaults(func=cmd_replay)

    lineage = sub.add_parser("lineage", help="trace a journal entry back to its cause")
    lineage.add_argument("run_id")
    lineage.add_argument("entry_id")
    lineage.set_defaults(func=cmd_lineage)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
