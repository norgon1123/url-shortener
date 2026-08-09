"""Policy enforcement: what a node may touch, run, and reach.

Enforced in three places, deliberately overlapping:

  1. Ahead of the call -- the node's tool allowlist and the sandbox egress
     allowlist are handed to the Agent SDK, so violations are prevented rather
     than detected.
  2. During the call -- the `can_use_tool` callback vetoes writes to forbidden
     paths and escalates writes to protected ones.
  3. After the call -- the worktree diff is re-checked against the same rules.

Step 3 exists because step 2 is evadable: a `Bash` heredoc writes files without
ever invoking the Write tool, so the callback never fires. The post-hoc diff
classifier cannot be bypassed that way. Neither layer alone is sufficient.
"""

from __future__ import annotations

import enum
import re
from dataclasses import dataclass, field
from pathlib import PurePosixPath

from .model import Policy


class PathVerdict(str, enum.Enum):
    ALLOWED = "allowed"
    PROTECTED = "protected"  # permitted, but forces human approval
    DENIED = "denied"  # never permitted


@dataclass
class PolicyViolation:
    kind: str
    detail: str
    path: str | None = None

    def __str__(self) -> str:
        return f"{self.kind}: {self.detail}"


@dataclass
class PolicyDecision:
    verdict: PathVerdict
    reason: str = ""

    @property
    def allowed(self) -> bool:
        return self.verdict is not PathVerdict.DENIED

    @property
    def needs_approval(self) -> bool:
        return self.verdict is PathVerdict.PROTECTED


def glob_match(path: str, pattern: str) -> bool:
    """Path-aware glob supporting `**`.

    Uses PurePosixPath.full_match (3.13+), which gives correct `**` semantics
    rather than fnmatch's flat string matching where `*` happily crosses
    directory separators.
    """
    # removeprefix, not lstrip: lstrip("./") strips *characters*, so it would
    # turn ".git/config" into "git/config" and silently defeat the .git/**
    # protection. This was a real bug caught by test_forbidden_paths_beat_a_
    # generous_allowlist.
    normalized = str(PurePosixPath(path.removeprefix("./")))
    candidate = PurePosixPath(normalized)
    if candidate.full_match(pattern):
        return True
    # A bare directory prefix like "orchestrator/**" should also match the
    # directory itself, not only its contents.
    if pattern.endswith("/**") and candidate.full_match(pattern[:-3]):
        return True
    return False


def any_match(path: str, patterns: tuple[str, ...] | list[str]) -> str | None:
    for pattern in patterns:
        if glob_match(path, pattern):
            return pattern
    return None


class PolicyEngine:
    """Evaluates a single node's write/command permissions against the policy."""

    def __init__(
        self,
        policy: Policy,
        *,
        write_paths: tuple[str, ...] = (),
        deny_paths: tuple[str, ...] = (),
    ) -> None:
        self.policy = policy
        self.write_paths = write_paths
        self.deny_paths = deny_paths
        self._secret_res = [
            re.compile(p) for p in policy.secret_patterns
        ]

    # -- paths -----------------------------------------------------------

    def check_write(self, path: str) -> PolicyDecision:
        """Decide whether a node may write to `path`.

        Order matters. Global forbidden paths win over everything -- a node
        cannot be granted write access to `.git/**` by a generous write_paths
        entry. Node-level denies come next, then protection, then the allowlist.
        """
        if pattern := any_match(path, self.policy.forbidden_paths):
            return PolicyDecision(
                PathVerdict.DENIED,
                f"'{path}' matches globally forbidden path '{pattern}'",
            )
        if pattern := any_match(path, self.deny_paths):
            return PolicyDecision(
                PathVerdict.DENIED,
                f"'{path}' matches this node's deny list ('{pattern}')",
            )
        if self.write_paths and not any_match(path, self.write_paths):
            return PolicyDecision(
                PathVerdict.DENIED,
                f"'{path}' is outside this node's write allowlist "
                f"({', '.join(self.write_paths)})",
            )
        if pattern := any_match(path, self.policy.protected_paths):
            return PolicyDecision(
                PathVerdict.PROTECTED,
                f"'{path}' matches protected path '{pattern}'; requires approval",
            )
        return PolicyDecision(PathVerdict.ALLOWED)

    def classify_diff(self, changed_paths: list[str]) -> tuple[
        list[PolicyViolation], list[str]
    ]:
        """Post-hoc check of everything a node actually changed.

        Returns (violations, paths_needing_approval). This is the layer that
        catches writes made via Bash, which never reach `can_use_tool`.
        """
        violations: list[PolicyViolation] = []
        protected: list[str] = []
        for path in changed_paths:
            decision = self.check_write(path)
            if decision.verdict is PathVerdict.DENIED:
                violations.append(
                    PolicyViolation("forbidden_write", decision.reason, path)
                )
            elif decision.verdict is PathVerdict.PROTECTED:
                protected.append(path)
        return violations, protected

    # -- commands --------------------------------------------------------

    def check_command(self, command: str) -> PolicyDecision:
        """Reject forbidden commands anywhere in a shell string.

        Substring matching is intentional: `git push` must be caught inside
        `cd x && git push origin main`. It is a coarse net and does not claim
        to be airtight -- the sandbox and the post-hoc diff are the real
        controls; this catches the obvious cases early with a clear message.
        """
        collapsed = " ".join(command.split())
        for forbidden in self.policy.forbidden_commands:
            if forbidden in collapsed:
                return PolicyDecision(
                    PathVerdict.DENIED,
                    f"command contains forbidden operation '{forbidden}'",
                )
        return PolicyDecision(PathVerdict.ALLOWED)

    # -- secrets ---------------------------------------------------------

    def scan_secrets(self, text: str, *, source: str = "") -> list[PolicyViolation]:
        """Detect credential-shaped strings.

        Reports the matching pattern and location but never the matched text --
        echoing a leaked secret into the journal would defeat the purpose.
        """
        found: list[PolicyViolation] = []
        for regex in self._secret_res:
            for match in regex.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                found.append(
                    PolicyViolation(
                        "secret_detected",
                        f"pattern /{regex.pattern[:40]}/ matched at line {line}",
                        source or None,
                    )
                )
                break  # one report per pattern is enough to fail the gate
        return found

    def scan_files(self, files: dict[str, str]) -> list[PolicyViolation]:
        out: list[PolicyViolation] = []
        for path, content in sorted(files.items()):
            out.extend(self.scan_secrets(content, source=path))
        return out

    # -- SDK wiring ------------------------------------------------------

    def sdk_sandbox_settings(self) -> dict:
        return self.policy.sandbox.to_sdk_settings()


@dataclass
class EscalationLog:
    """Protected-path hits accumulated during a node run.

    A non-empty log forces PENDING_APPROVAL regardless of the node's configured
    autonomy level -- this is the mechanism behind "human approval checkpoints
    for high-impact actions".
    """

    entries: list[str] = field(default_factory=list)

    def record(self, reason: str) -> None:
        if reason not in self.entries:
            self.entries.append(reason)

    @property
    def triggered(self) -> bool:
        return bool(self.entries)
