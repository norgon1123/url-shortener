"""The CLI, end to end.

These drive the same commands a walkthrough would type, through the same entry
point, and assert on exit codes as well as output -- a CI job that halts for a
human has to be distinguishable from one that failed, and that distinction is
an exit code, not a sentence in a log.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
import yaml

from sdlc.audit import Journal
from sdlc.cli import main
from sdlc.model import NodeStatus
from sdlc.state import RunStore

NODES = [
    {
        "id": "design",
        "prompt": "design.md",
        "write_paths": ["artifacts/**"],
        "exit_gates": [
            {"check": "artifact_present", "gate_class": "mechanical", "path": "artifacts/design.txt"},
            {"check": "human_approval", "gate_class": "human", "reason": "Frozen contract review"},
        ],
    },
    {
        "id": "build",
        "prompt": "build.md",
        "write_paths": ["src/**"],
        "depends_on": ["design"],
        "exit_gates": [
            {"check": "artifact_present", "gate_class": "mechanical", "path": "src/App.java"}
        ],
    },
]

SCRIPT = {
    "nodes": {
        "design": [
            {"files": {"artifacts/design.txt": "v1: 301"}, "cost_usd": 0.5},
            {"files": {"artifacts/design.txt": "v2: 302"}, "cost_usd": 0.5},
        ],
        "build": [{"files": {"src/App.java": "class App {}"}, "cost_usd": 1.0}],
    }
}


@pytest.fixture
def env(tmp_path: Path) -> dict:
    from sdlc.checkpoint import Git

    workspace = tmp_path / "workspace"
    workspace.mkdir()
    git = Git(root=workspace)
    git._git("init", "-b", "main")
    git._git("config", "user.email", "o@example.com")
    git._git("config", "user.name", "Orchestrator")
    (workspace / "README.md").write_text("# workspace\n")
    git._git("add", "-A")
    git._git("commit", "-m", "initial")

    prompts = tmp_path / "prompts"
    prompts.mkdir()
    for name in ("design.md", "build.md"):
        (prompts / name).write_text(f"Do {name}.")

    pipeline = tmp_path / "pipeline.yaml"
    pipeline.write_text(yaml.safe_dump({"version": 1, "policy": {}, "nodes": NODES}))

    script = tmp_path / "script.yaml"
    script.write_text(yaml.safe_dump(SCRIPT))

    return {
        "runs": tmp_path / "runs",
        "workspace": workspace,
        "prompts": prompts,
        "pipeline": pipeline,
        "script": script,
        "git": git,
    }


def cli(env: dict, *args: str) -> int:
    return main(["--runs-dir", str(env["runs"]), *args])


def start(env: dict, run_id: str = "run-cli") -> int:
    return cli(
        env,
        "run",
        "--pipeline", str(env["pipeline"]),
        "--workspace", str(env["workspace"]),
        "--prompts", str(env["prompts"]),
        "--script", str(env["script"]),
        "--scenario", "test",
        "--run-id", run_id,
    )


class TestRunLifecycle:
    def test_a_run_that_halts_for_a_human_exits_distinctly(self, env: dict, capsys) -> None:
        """Exit 2 is 'waiting on you', not 'broken'. CI has to tell them apart."""
        assert start(env) == 2
        out = capsys.readouterr().out
        assert "PENDING_APPROVAL" in out
        assert "approve run-cli design" in out

    def test_approve_then_resume_completes(self, env: dict, capsys) -> None:
        start(env)
        assert cli(env, "approve", "run-cli", "design", "--approver", "neil", "--note", "ok") == 0
        assert cli(env, "resume", "run-cli") == 0
        assert (env["workspace"] / "src/App.java").exists()

    def test_reject_re_runs_the_node_and_pauses_again(self, env: dict) -> None:
        start(env)
        cli(env, "reject", "run-cli", "design", "--approver", "neil", "--note", "302 not 301")
        assert cli(env, "resume", "run-cli") == 2
        assert (env["workspace"] / "artifacts/design.txt").read_text() == "v2: 302"

    def test_repair_sends_a_finished_node_back_with_a_brief(
        self, env: dict, capsys
    ) -> None:
        """The move a human had no way to make.

        `triage` routes repairs, but only out of a `verify` failure. A green
        build whose review found a blocker left the reviewer with two useless
        options: reject the reviewing node, which re-runs the reviewer and
        cannot change code, or approve it, which accepts the finding.
        """
        start(env)
        cli(env, "approve", "run-cli", "design", "--approver", "neil", "--note", "ok")
        cli(env, "resume", "run-cli")
        store = RunStore(env["runs"] / "state.db")
        assert store.get_node("run-cli", "build").status is NodeStatus.PASSED

        assert (
            cli(
                env,
                "repair",
                "run-cli",
                "build",
                "--approver",
                "neil",
                "--note",
                "a fresh account can take down any link",
            )
            == 0
        )
        assert store.get_node("run-cli", "build").status is NodeStatus.PENDING

        entries = Journal(
            env["runs"] / "run-cli" / "journal.jsonl", run_id="run-cli"
        ).by_event("human_repair_requested")
        assert len(entries) == 1
        # Not `repair_routed`: the journal must never say the machine decided
        # something a person decided.
        assert entries[0].payload["approver"] == "neil"
        assert "take down any link" in entries[0].payload["reason"]

    def test_repair_rejects_a_node_the_pipeline_does_not_have(self, env: dict) -> None:
        start(env)
        with pytest.raises(SystemExit, match="unknown node"):
            cli(env, "repair", "run-cli", "nope", "--approver", "neil", "--note", "x")

    def test_a_manifest_makes_resume_self_contained(self, env: dict) -> None:
        """Resume takes a run id and nothing else -- CI does not re-supply flags."""
        start(env)
        manifest = json.loads((env["runs"] / "run-cli" / "manifest.json").read_text())
        assert manifest["scenario"] == "test"
        assert Path(manifest["pipeline"]).is_absolute()

    def test_a_run_can_start_on_its_own_branch(self, env: dict) -> None:
        cli(
            env,
            "run",
            "--pipeline", str(env["pipeline"]),
            "--workspace", str(env["workspace"]),
            "--prompts", str(env["prompts"]),
            "--script", str(env["script"]),
            "--branch", "sdlc/run-cli",
            "--run-id", "run-cli",
        )
        assert env["git"].current_branch() == "sdlc/run-cli"


class TestDecisions:
    def test_answers_resolve_blocking_ambiguities(self, env: dict) -> None:
        start(env)
        cli(
            env,
            "approve", "run-cli", "design",
            "--approver", "neil",
            "--answer", "Q1=rate limiting and idempotency",
        )
        from sdlc.state import RunStore

        approval = RunStore(env["runs"] / "state.db").approvals("run-cli")["design"]
        assert approval.answers == {"Q1": "rate limiting and idempotency"}

    def test_a_malformed_answer_is_rejected(self, env: dict) -> None:
        start(env)
        with pytest.raises(SystemExit, match="id=text"):
            cli(env, "approve", "run-cli", "design", "--approver", "neil", "--answer", "nope")

    def test_the_decision_lands_in_the_journal_not_only_the_table(self, env: dict) -> None:
        """The table holds the live decision; the journal is the four-eyes record."""
        start(env)
        cli(env, "reject", "run-cli", "design", "--approver", "neil", "--note", "302 not 301")
        from sdlc.audit import Journal

        journal = Journal(env["runs"] / "run-cli" / "journal.jsonl", run_id="run-cli")
        decisions = journal.by_event("human_decision")
        assert decisions[0].payload["approver"] == "neil"
        journal.verify()


class TestStopAndStatus:
    def test_stop_sets_a_flag_and_resume_is_the_explicit_undo(self, env: dict) -> None:
        """`stop` halts at a boundary; `resume` is the operator saying continue."""
        from sdlc.state import RunStore

        start(env)
        cli(env, "approve", "run-cli", "design", "--approver", "neil")
        assert cli(env, "stop", "run-cli") == 0

        store = RunStore(env["runs"] / "state.db")
        assert store.stop_requested("run-cli")
        assert cli(env, "resume", "run-cli") == 0
        assert not store.stop_requested("run-cli")

    def test_status_lists_runs_and_nodes(self, env: dict, capsys) -> None:
        start(env)
        cli(env, "status")
        assert "run-cli" in capsys.readouterr().out
        cli(env, "status", "run-cli")
        out = capsys.readouterr().out
        assert "design" in out and "pending_approval" in out


class TestReporting:
    def _complete(self, env: dict) -> None:
        start(env)
        cli(env, "approve", "run-cli", "design", "--approver", "neil")
        cli(env, "resume", "run-cli")

    def test_report_renders_the_metrics(self, env: dict, capsys) -> None:
        self._complete(env)
        assert cli(env, "report", "run-cli") == 0
        out = capsys.readouterr().out
        assert "success rate" in out and "MTTR" in out

    def test_report_json_is_machine_readable(self, env: dict, capsys) -> None:
        self._complete(env)
        capsys.readouterr()  # discard the run's own output
        cli(env, "report", "run-cli", "--json")
        payload = json.loads(capsys.readouterr().out)
        assert payload["status"] == "completed"
        # $0.50 for design plus $1.00 for build. This asserted $2.00 until the
        # cost accounting was fixed: approving design and resuming re-gated the
        # existing result without re-running it, and the replayed entry carried
        # the original cost, so the same $0.50 was counted where it was spent
        # and again where it was merely re-examined.
        assert payload["total_cost_usd"] == pytest.approx(1.5)
        assert sum(payload["node_cost_usd"].values()) == pytest.approx(
            payload["total_cost_usd"]
        )

    def test_verify_confirms_an_untouched_chain(self, env: dict, capsys) -> None:
        self._complete(env)
        assert cli(env, "verify", "run-cli") == 0
        assert "chain intact" in capsys.readouterr().out

    def test_verify_detects_an_edited_journal(self, env: dict, capsys) -> None:
        self._complete(env)
        path = env["runs"] / "run-cli" / "journal.jsonl"
        lines = path.read_text().splitlines()
        lines[1] = lines[1].replace('"node_started"', '"node_passed"')
        path.write_text("\n".join(lines) + "\n")
        assert cli(env, "verify", "run-cli") == 1
        assert "TAMPERED" in capsys.readouterr().err

    def test_replay_reads_a_recorded_run_without_executing_it(self, env: dict, capsys) -> None:
        self._complete(env)
        assert cli(env, "replay", str(env["runs"] / "run-cli")) == 0
        out = capsys.readouterr().out
        assert "node_passed" in out and "total cost" in out

    def test_lineage_traces_an_entry_to_its_cause(self, env: dict, capsys) -> None:
        self._complete(env)
        from sdlc.audit import Journal

        journal = Journal(env["runs"] / "run-cli" / "journal.jsonl", run_id="run-cli")
        target = journal.by_event("node_passed")[-1]
        assert cli(env, "lineage", "run-cli", target.entry_id) == 0
        assert "node_passed" in capsys.readouterr().out


class TestDuplicateRunId:
    def test_reusing_a_run_id_says_what_to_do(self, env: dict) -> None:
        """A raw IntegrityError reads as a bug in the orchestrator. It is
        usually an operator re-using an id after a run died early."""
        start(env)
        with pytest.raises(SystemExit, match="already exists"):
            start(env)
