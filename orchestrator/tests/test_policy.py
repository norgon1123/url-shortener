"""Policy enforcement.

The segregation-of-duties tests are the ones that matter: they prove the
implementer cannot reach the tests that gate it, which is the answer to the
hardest question this design will be asked.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from sdlc.graph import load_pipeline
from sdlc.model import Policy, SandboxPolicy
from sdlc.policy import (
    EscalationLog,
    PathVerdict,
    PolicyEngine,
    glob_match,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
REAL_PIPELINE = REPO_ROOT / "orchestrator" / "pipelines" / "sdlc.yaml"


@pytest.fixture
def policy() -> Policy:
    return Policy(
        protected_paths=("service/pom.xml", "service/**/db/migration/**"),
        forbidden_paths=(".git/**", "orchestrator/**", "**/.env"),
        forbidden_commands=("git push", "sudo", "rm -rf /"),
        secret_patterns=(
            r"sk-ant-[A-Za-z0-9_-]{20,}",
            r"AKIA[0-9A-Z]{16}",
        ),
        sandbox=SandboxPolicy(allowed_domains=("repo.maven.apache.org",)),
    )


class TestGlobMatch:
    @pytest.mark.parametrize(
        "path,pattern,expected",
        [
            ("service/src/main/java/App.java", "service/src/main/**", True),
            ("service/src/test/java/AppTest.java", "service/src/main/**", False),
            ("service/pom.xml", "service/pom.xml", True),
            (".git/config", ".git/**", True),
            ("orchestrator", "orchestrator/**", True),  # the dir itself
            ("app/.env", "**/.env", True),
            # `*` must not cross a directory separator.
            ("service/src/main/java/App.java", "service/*", False),
        ],
    )
    def test_matching(self, path: str, pattern: str, expected: bool) -> None:
        assert glob_match(path, pattern) is expected


class TestWritePermissions:
    def test_allows_path_in_allowlist(self, policy: Policy) -> None:
        engine = PolicyEngine(policy, write_paths=("service/src/main/**",))
        assert engine.check_write("service/src/main/java/App.java").verdict is (
            PathVerdict.ALLOWED
        )

    def test_denies_path_outside_allowlist(self, policy: Policy) -> None:
        engine = PolicyEngine(policy, write_paths=("service/src/main/**",))
        decision = engine.check_write("service/src/test/java/AppTest.java")
        assert decision.verdict is PathVerdict.DENIED
        assert "outside this node's write allowlist" in decision.reason

    def test_forbidden_paths_beat_a_generous_allowlist(self, policy: Policy) -> None:
        """A node cannot be granted access to .git by a wide write_paths entry."""
        engine = PolicyEngine(policy, write_paths=("**",))
        assert engine.check_write(".git/config").verdict is PathVerdict.DENIED
        assert engine.check_write("orchestrator/sdlc/gates.py").verdict is (
            PathVerdict.DENIED
        )

    def test_protected_path_is_allowed_but_needs_approval(self, policy: Policy) -> None:
        engine = PolicyEngine(policy, write_paths=("service/**",))
        decision = engine.check_write("service/pom.xml")
        assert decision.verdict is PathVerdict.PROTECTED
        assert decision.allowed and decision.needs_approval


class TestSegregationOfDuties:
    """ADR-003, enforced against the real pipeline rather than a fixture."""

    def _engine(self, node_id: str) -> PolicyEngine:
        pipeline = load_pipeline(REAL_PIPELINE)
        node = pipeline.node(node_id)
        return PolicyEngine(
            pipeline.policy,
            write_paths=node.write_paths,
            deny_paths=node.deny_paths,
        )

    def test_implementer_cannot_touch_tests(self) -> None:
        # If this ever passes, the green-build gate becomes meaningless.
        decision = self._engine("implement").check_write(
            "service/src/test/java/com/example/LinkApiTest.java"
        )
        assert decision.verdict is PathVerdict.DENIED

    def test_implementer_cannot_touch_pom(self) -> None:
        assert self._engine("implement").check_write("service/pom.xml").verdict is (
            PathVerdict.DENIED
        )

    def test_test_author_cannot_touch_implementation(self) -> None:
        decision = self._engine("author-tests").check_write(
            "service/src/main/java/com/example/LinkService.java"
        )
        assert decision.verdict is PathVerdict.DENIED

    def test_each_branch_can_write_its_own_side(self) -> None:
        assert self._engine("implement").check_write(
            "service/src/main/java/com/example/LinkService.java"
        ).allowed
        assert self._engine("author-tests").check_write(
            "service/src/test/java/com/example/LinkApiTest.java"
        ).allowed

    def test_docs_node_cannot_touch_source(self) -> None:
        assert self._engine("docs").check_write(
            "service/src/main/java/com/example/LinkService.java"
        ).verdict is PathVerdict.DENIED


class TestDiffClassifier:
    """The post-hoc layer that catches writes made via Bash heredoc."""

    def test_flags_violations_and_protected_paths(self, policy: Policy) -> None:
        engine = PolicyEngine(policy, write_paths=("service/**",))
        violations, protected = engine.classify_diff(
            [
                "service/src/main/java/App.java",  # fine
                "service/pom.xml",  # protected
                "orchestrator/sdlc/gates.py",  # forbidden
            ]
        )
        assert [v.path for v in violations] == ["orchestrator/sdlc/gates.py"]
        assert protected == ["service/pom.xml"]

    def test_clean_diff_yields_nothing(self, policy: Policy) -> None:
        engine = PolicyEngine(policy, write_paths=("service/src/main/**",))
        violations, protected = engine.classify_diff(
            ["service/src/main/java/A.java", "service/src/main/java/B.java"]
        )
        assert not violations and not protected


class TestCommands:
    @pytest.mark.parametrize(
        "command",
        ["git push origin main", "cd /tmp && git push", "sudo rm x", "rm -rf /"],
    )
    def test_rejects_forbidden(self, policy: Policy, command: str) -> None:
        assert not PolicyEngine(policy).check_command(command).allowed

    @pytest.mark.parametrize(
        "command", ["mvn -q verify", "git status", "git commit -m 'x'", "ls -la"]
    )
    def test_allows_ordinary(self, policy: Policy, command: str) -> None:
        assert PolicyEngine(policy).check_command(command).allowed

    def test_normalizes_whitespace(self, policy: Policy) -> None:
        """Extra spacing must not smuggle a forbidden command through."""
        assert not PolicyEngine(policy).check_command("git    push  origin").allowed


class TestSecretScanning:
    def test_detects_anthropic_key(self, policy: Policy) -> None:
        found = PolicyEngine(policy).scan_secrets(
            "key = 'sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAA'", source="Config.java"
        )
        assert len(found) == 1
        assert found[0].kind == "secret_detected"

    def test_never_echoes_the_secret(self, policy: Policy) -> None:
        """Logging the match would defeat the purpose of detecting it."""
        secret = "AKIAIOSFODNN7EXAMPLE"
        found = PolicyEngine(policy).scan_secrets(f"aws_key={secret}")
        assert found and secret not in str(found[0])

    def test_reports_line_number(self, policy: Policy) -> None:
        text = "line1\nline2\nAKIAIOSFODNN7EXAMPLE\n"
        assert "line 3" in PolicyEngine(policy).scan_secrets(text)[0].detail

    def test_clean_text_passes(self, policy: Policy) -> None:
        assert PolicyEngine(policy).scan_secrets("int x = 42;") == []

    def test_scans_multiple_files(self, policy: Policy) -> None:
        found = PolicyEngine(policy).scan_files(
            {
                "A.java": "clean",
                "B.java": "AKIAIOSFODNN7EXAMPLE",
            }
        )
        assert [v.path for v in found] == ["B.java"]


class TestEscalationLog:
    def test_dedupes_and_flags(self) -> None:
        log = EscalationLog()
        assert not log.triggered
        log.record("wrote service/pom.xml")
        log.record("wrote service/pom.xml")
        assert log.triggered and len(log.entries) == 1
