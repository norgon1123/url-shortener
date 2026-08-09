"""Prompt assembly and the scripted backend.

Two things are worth asserting here and the rest is plumbing: that a retry
actually carries the reason the last attempt was rejected, and that the mock
performs real filesystem effects so the gates downstream of it are doing real
work rather than agreeing with a stub.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
import yaml

from sdlc.mock import MockBackend, RecordingBackend, ScriptError, ScriptedAttempt, load_script
from sdlc.model import (
    Autonomy,
    GateClass,
    GateOutcome,
    GateResult,
    NodeResult,
    NodeSpec,
)
from sdlc.nodes import NodeInvocation, PromptError, render_prompt


@pytest.fixture
def prompts(tmp_path: Path) -> Path:
    root = tmp_path / "prompts"
    root.mkdir()
    (root / "implement.md").write_text("Implement the frozen contract.")
    return root


def invocation(tmp_path: Path, **kw) -> NodeInvocation:
    node = kw.pop(
        "node",
        NodeSpec(
            id="implement",
            prompt_path="implement.md",
            write_paths=("service/src/main/**",),
            deny_paths=("service/src/test/**",),
        ),
    )
    return NodeInvocation(node=node, run_id="run-1", workspace=tmp_path, **kw)


class TestPromptAssembly:
    def test_missing_prompt_file_is_a_clear_error(self, tmp_path: Path) -> None:
        node = NodeSpec(id="implement", prompt_path="nope.md")
        with pytest.raises(PromptError, match="prompt not found"):
            render_prompt(invocation(tmp_path, node=node), tmp_path / "prompts")

    def test_base_prompt_and_boundaries_are_included(self, tmp_path: Path, prompts: Path) -> None:
        text = render_prompt(invocation(tmp_path), prompts)
        assert "Implement the frozen contract." in text
        assert "service/src/main/**" in text
        assert "service/src/test/**" in text

    def test_a_retry_carries_the_gate_failures_forward(self, tmp_path: Path, prompts: Path) -> None:
        """Without this a bounded retry is just a second roll of the same dice."""
        failure = GateResult(
            check="maven_compiles",
            gate_class=GateClass.MECHANICAL,
            outcome=GateOutcome.FAIL,
            detail="cannot find symbol: LinkRepository",
        )
        text = render_prompt(
            invocation(tmp_path, attempt=1, gate_failures=(failure,)), prompts
        )
        assert "Previous attempt failed" in text
        assert "cannot find symbol: LinkRepository" in text
        assert "do not work around the checks" in text

    def test_a_rejection_note_is_quoted_verbatim(self, tmp_path: Path, prompts: Path) -> None:
        text = render_prompt(
            invocation(tmp_path, rejection_note="302, not 301 -- we need the analytics"),
            prompts,
        )
        assert "302, not 301" in text and "not advisory" in text

    def test_feedback_comes_last_where_it_is_most_salient(
        self, tmp_path: Path, prompts: Path
    ) -> None:
        failure = GateResult("maven_compiles", GateClass.MECHANICAL, GateOutcome.FAIL, "boom")
        text = render_prompt(
            invocation(
                tmp_path, attempt=1, gate_failures=(failure,), rejection_note="fix the status code"
            ),
            prompts,
        )
        assert text.index("Boundaries") < text.index("Previous attempt failed")
        assert text.index("Previous attempt failed") < text.index("fix the status code")

    def test_upstream_context_is_passed_explicitly(self, tmp_path: Path, prompts: Path) -> None:
        """Cross-stage context should be a recorded input, not whatever it read."""
        text = render_prompt(
            invocation(tmp_path, context={"design": {"openapi_path": "artifacts/openapi.yaml"}}),
            prompts,
        )
        assert "From `design`" in text and "artifacts/openapi.yaml" in text

    def test_output_schema_is_embedded_when_declared(self, tmp_path: Path, prompts: Path) -> None:
        node = NodeSpec(id="implement", prompt_path="implement.md", output_schema="plan")
        text = render_prompt(invocation(tmp_path, node=node), prompts)
        assert "Required output" in text and '"tasks"' in text

    def test_propose_mode_is_stated(self, tmp_path: Path, prompts: Path) -> None:
        node = NodeSpec(id="implement", prompt_path="implement.md", autonomy=Autonomy.PROPOSE)
        assert "propose" in render_prompt(invocation(tmp_path, node=node), prompts)


class TestMockBackend:
    def test_writes_real_files_so_real_gates_can_judge_them(self, tmp_path: Path) -> None:
        backend = MockBackend(
            {"implement": [ScriptedAttempt(files={"service/src/main/java/A.java": "class A {}"})]}
        )
        result = backend.run(invocation(tmp_path))
        assert result.ok
        assert (tmp_path / "service/src/main/java/A.java").read_text() == "class A {}"
        assert result.files_written == ("service/src/main/java/A.java",)

    def test_declared_output_lands_where_the_exit_gate_looks(self, tmp_path: Path) -> None:
        node = NodeSpec(id="intake", prompt_path="p.md", output_schema="requirement")
        backend = MockBackend({"intake": [ScriptedAttempt(output={"goal": "shorten urls"})]})
        backend.run(invocation(tmp_path, node=node))
        written = json.loads((tmp_path / "artifacts/requirement.json").read_text())
        assert written == {"goal": "shorten urls"}

    def test_a_scripted_failure_reports_its_reason(self, tmp_path: Path) -> None:
        backend = MockBackend({"implement": [ScriptedAttempt(fail="compilation failed")]})
        result = backend.run(invocation(tmp_path))
        assert not result.ok and result.error == "compilation failed"

    def test_fail_then_pass_drives_the_retry_path(self, tmp_path: Path) -> None:
        """Deterministic fault injection, rather than hoping a model misbehaves on cue."""
        backend = MockBackend(
            {
                "implement": [
                    ScriptedAttempt(fail="attempt one"),
                    ScriptedAttempt(files={"service/src/main/java/A.java": "class A {}"}),
                ]
            }
        )
        assert not backend.run(invocation(tmp_path, sequence=0)).ok
        assert backend.run(invocation(tmp_path, sequence=1)).ok

    def test_entries_advance_by_sequence_not_by_attempt_number(self, tmp_path: Path) -> None:
        """A replan re-enters a node with its attempt counter reset to zero."""
        backend = MockBackend(
            {"implement": [ScriptedAttempt(fail="one"), ScriptedAttempt(fail="two")]}
        )
        assert backend.run(invocation(tmp_path, attempt=0, sequence=0)).error == "one"
        assert backend.run(invocation(tmp_path, attempt=0, sequence=1)).error == "two"

    def test_the_backend_is_stateless(self, tmp_path: Path) -> None:
        """Two backend instances must replay a sequence identically."""
        script = {"implement": [ScriptedAttempt(fail="one"), ScriptedAttempt(fail="two")]}
        assert MockBackend(dict(script)).run(invocation(tmp_path, sequence=1)).error == "two"
        assert MockBackend(dict(script)).run(invocation(tmp_path, sequence=1)).error == "two"

    def test_the_last_entry_repeats_past_the_end_of_the_script(self, tmp_path: Path) -> None:
        backend = MockBackend({"implement": [ScriptedAttempt(fail="always broken")]})
        for sequence in range(6):
            assert not backend.run(invocation(tmp_path, sequence=sequence)).ok

    def test_deletes_are_applied(self, tmp_path: Path) -> None:
        """Needed for the brownfield scenario, where a node removes code."""
        doomed = tmp_path / "service/src/main/java/Old.java"
        doomed.parent.mkdir(parents=True)
        doomed.write_text("class Old {}")
        backend = MockBackend(
            {"implement": [ScriptedAttempt(deletes=("service/src/main/java/Old.java",))]}
        )
        result = backend.run(invocation(tmp_path))
        assert not doomed.exists() and "service/src/main/java/Old.java" in result.files_written

    def test_an_unscripted_node_is_an_error_not_a_silent_pass(self, tmp_path: Path) -> None:
        """A silent pass would let a scenario claim coverage it never had."""
        with pytest.raises(ScriptError, match="no scripted attempts"):
            MockBackend({}).run(invocation(tmp_path))

    def test_costs_and_durations_are_reported_for_metrics(self, tmp_path: Path) -> None:
        backend = MockBackend({"implement": [ScriptedAttempt(cost_usd=1.5, duration_seconds=42.0)]})
        result = backend.run(invocation(tmp_path))
        assert result.cost_usd == 1.5 and result.duration_seconds == 42.0

    def test_a_script_can_stage_a_policy_violation(self, tmp_path: Path) -> None:
        """The path gates need a way to be shown failing, not just passing."""
        backend = MockBackend(
            {"implement": [ScriptedAttempt(files={"service/src/test/java/AppTest.java": "x"})]}
        )
        assert backend.run(invocation(tmp_path)).files_written == (
            "service/src/test/java/AppTest.java",
        )


class TestScriptLoading:
    def test_loads_attempts_in_order(self, tmp_path: Path) -> None:
        script = tmp_path / "greenfield.yaml"
        script.write_text(
            yaml.safe_dump(
                {
                    "nodes": {
                        "verify": [
                            {"fail": "coverage 0.62 below the 0.70 floor"},
                            {"cost_usd": 0.0},
                        ]
                    }
                }
            )
        )
        backend = load_script(script)
        assert backend.script["verify"][0].fail.startswith("coverage")
        assert backend.script["verify"][1].fail is None

    def test_a_single_attempt_need_not_be_wrapped_in_a_list(self, tmp_path: Path) -> None:
        script = tmp_path / "s.yaml"
        script.write_text(yaml.safe_dump({"nodes": {"intake": {"output": {"goal": "g"}}}}))
        assert len(load_script(script).script["intake"]) == 1

    def test_files_from_pulls_large_payloads_from_disk(self, tmp_path: Path) -> None:
        (tmp_path / "LinkService.java").write_text("class LinkService {}")
        script = tmp_path / "s.yaml"
        script.write_text(
            yaml.safe_dump(
                {
                    "nodes": {
                        "implement": [
                            {"files_from": {"service/src/main/java/LinkService.java": "LinkService.java"}}
                        ]
                    }
                }
            )
        )
        attempt = load_script(script).script["implement"][0]
        assert attempt.files["service/src/main/java/LinkService.java"] == "class LinkService {}"

    def test_a_missing_files_from_source_is_caught_at_load(self, tmp_path: Path) -> None:
        script = tmp_path / "s.yaml"
        script.write_text(yaml.safe_dump({"nodes": {"implement": [{"files_from": {"a": "gone.java"}}]}}))
        with pytest.raises(ScriptError, match="files_from source not found"):
            load_script(script)

    def test_a_typoed_key_is_rejected(self, tmp_path: Path) -> None:
        """Silently ignoring `cost` when the key is `cost_usd` corrupts the metrics."""
        script = tmp_path / "s.yaml"
        script.write_text(yaml.safe_dump({"nodes": {"intake": [{"cost": 1.0}]}}))
        with pytest.raises(ScriptError, match="unknown attempt key"):
            load_script(script)

    def test_a_script_without_nodes_is_rejected(self, tmp_path: Path) -> None:
        script = tmp_path / "s.yaml"
        script.write_text(yaml.safe_dump({"strict": True}))
        with pytest.raises(ScriptError, match="expected a mapping"):
            load_script(script)


class TestRecording:
    def test_a_live_run_becomes_a_replayable_fixture(self, tmp_path: Path) -> None:
        """This is how a real run ships in the repo for a no-API-key reviewer."""

        class FakeLive:
            def run(self, inv: NodeInvocation) -> NodeResult:
                target = inv.workspace / "service/src/main/java/A.java"
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("class A {}")
                return NodeResult(
                    node_id=inv.node.id,
                    ok=True,
                    output={"rationale": "because"},
                    files_written=("service/src/main/java/A.java",),
                    cost_usd=1.5,
                    duration_seconds=12.0,
                )

        recorder = RecordingBackend(inner=FakeLive(), out_dir=tmp_path / "fixture")
        recorder.run(invocation(tmp_path))
        saved = recorder.save()

        replayed = load_script(saved)
        attempt = replayed.script["implement"][0]
        assert attempt.files["service/src/main/java/A.java"] == "class A {}"
        assert attempt.output == {"rationale": "because"}
        assert attempt.cost_usd == 1.5

    def test_failures_are_recorded_too(self, tmp_path: Path) -> None:
        class FakeLive:
            def run(self, inv: NodeInvocation) -> NodeResult:
                return NodeResult(node_id=inv.node.id, ok=False, error="rate limited")

        recorder = RecordingBackend(inner=FakeLive(), out_dir=tmp_path / "fixture")
        recorder.run(invocation(tmp_path))
        assert load_script(recorder.save()).script["implement"][0].fail == "rate limited"
