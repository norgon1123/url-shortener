"""Checkpoints, worktree isolation, rollback, and budget ceilings.

These run against a real git repository in tmp_path rather than a mock. The
whole argument for using git as the checkpoint store is that it is a mechanism
nobody has to trust me to have implemented correctly -- mocking it away would
discard exactly the property being claimed.
"""

from __future__ import annotations

import itertools
import threading
import time
from pathlib import Path

import pytest

from sdlc.budget import BreachKind, BudgetGuard
from sdlc.checkpoint import CheckpointManager, Git, GitError
from sdlc.gates import subprocess_runner
from sdlc.model import Budget


@pytest.fixture
def repo(tmp_path: Path) -> Git:
    root = tmp_path / "repo"
    root.mkdir()
    git = Git(root=root)
    git._git("init", "-b", "main")
    git._git("config", "user.email", "orchestrator@example.com")
    git._git("config", "user.name", "SDLC Orchestrator")
    (root / "README.md").write_text("# service\n")
    git._git("add", "-A")
    git._git("commit", "-m", "initial")
    return git


@pytest.fixture
def manager(repo: Git, tmp_path: Path) -> CheckpointManager:
    return CheckpointManager(git=repo, run_id="run-1", worktree_root=tmp_path / "worktrees")


def write(git: Git, rel: str, content: str) -> None:
    target = git.root / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)


class TestInspection:
    def test_untracked_files_are_reported(self, repo: Git) -> None:
        """A node writing where it shouldn't usually creates a file; diff alone misses it."""
        write(repo, "service/src/main/java/A.java", "class A {}")
        assert repo.changed_paths() == ["service/src/main/java/A.java"]

    def test_modifications_are_reported(self, repo: Git) -> None:
        write(repo, "README.md", "# changed\n")
        assert repo.changed_paths() == ["README.md"]

    def test_a_clean_tree_reports_nothing(self, repo: Git) -> None:
        assert repo.is_clean() and repo.changed_paths() == []


class TestCheckpoints:
    def test_commit_returns_a_sha(self, manager: CheckpointManager, repo: Git) -> None:
        write(repo, "service/src/main/java/A.java", "class A {}")
        sha = manager.checkpoint("implement", summary="add LinkService")
        assert sha and sha == repo.head()

    def test_a_node_that_changed_nothing_creates_no_commit(
        self, manager: CheckpointManager, repo: Git
    ) -> None:
        """An empty checkpoint would pollute the provenance log with noise."""
        before = repo.head()
        assert manager.checkpoint("review") is None
        assert repo.head() == before

    def test_trailers_make_the_repository_the_provenance_record(
        self, manager: CheckpointManager, repo: Git
    ) -> None:
        """`git log --grep` must answer 'what did run X do' with no database."""
        write(repo, "service/src/main/java/A.java", "class A {}")
        manager.checkpoint("implement", attempt=2, summary="add LinkService")
        message = repo._git("log", "-1", "--format=%B").stdout
        assert "Run-Id: run-1" in message
        assert "Node-Id: implement" in message
        assert "Attempt: 2" in message

        found = repo._git("log", "--grep=Run-Id: run-1", "--format=%H").stdout.strip()
        assert found == repo.head()

    def test_forbidden_paths_never_enter_a_nodes_commit(
        self, repo: Git, tmp_path: Path
    ) -> None:
        """An operator editing the repo mid-run must not have their work
        attributed to whichever node checkpoints next.

        This is not hypothetical: it happened during the first live run, and
        produced a commit trailed `Node-Id: clarify` whose diff touched the
        orchestrator itself -- exactly the claim the trailer exists to make
        impossible."""
        manager = CheckpointManager(
            git=repo,
            run_id="run-1",
            worktree_root=tmp_path / "worktrees",
            exclude_paths=("orchestrator/**", ".git/**"),
        )
        write(repo, "service/src/main/java/A.java", "class A {}")
        write(repo, "orchestrator/sdlc/gates.py", "# an operator's own edit")

        manager.checkpoint("implement")
        committed = repo._git("show", "--name-only", "--format=", "HEAD").stdout.split()
        assert committed == ["service/src/main/java/A.java"]
        assert repo.changed_paths() == ["orchestrator/sdlc/gates.py"]

    def test_a_node_whose_only_change_was_excluded_creates_no_commit(
        self, repo: Git, tmp_path: Path
    ) -> None:
        manager = CheckpointManager(
            git=repo,
            run_id="run-1",
            worktree_root=tmp_path / "worktrees",
            exclude_paths=("orchestrator/**",),
        )
        write(repo, "orchestrator/sdlc/gates.py", "# not the node's work")
        before = repo.head()
        assert manager.checkpoint("implement") is None
        assert repo.head() == before


class TestRollback:
    def test_rollback_discards_modifications(self, manager: CheckpointManager, repo: Git) -> None:
        write(repo, "service/src/main/java/A.java", "class A {}")
        good = manager.checkpoint("design")
        write(repo, "service/src/main/java/A.java", "class A { broken")
        manager.rollback(good)
        assert (repo.root / "service/src/main/java/A.java").read_text() == "class A {}"

    def test_rollback_also_removes_untracked_debris(
        self, manager: CheckpointManager, repo: Git
    ) -> None:
        """A failed node leaves half-written files behind; resume must not inherit them."""
        write(repo, "service/src/main/java/A.java", "class A {}")
        good = manager.checkpoint("design")
        write(repo, "service/src/main/java/Half.java", "class Half { // ...")
        manager.rollback(good)
        assert not (repo.root / "service/src/main/java/Half.java").exists()
        assert repo.is_clean()


class TestWorktreeIsolation:
    def test_branches_get_genuinely_separate_checkouts(
        self, manager: CheckpointManager, repo: Git
    ) -> None:
        impl = manager.worktree("implement")
        tests = manager.worktree("tests")
        assert impl.root != tests.root

        write(impl, "service/src/main/java/A.java", "class A {}")
        assert not (tests.root / "service/src/main/java/A.java").exists()

    def test_the_same_name_returns_the_same_worktree(self, manager: CheckpointManager) -> None:
        assert manager.worktree("implement").root == manager.worktree("implement").root

    def test_a_clean_join_merges_both_branches(
        self, manager: CheckpointManager, repo: Git
    ) -> None:
        """Segregated write paths are what make this merge boring by construction."""
        impl = manager.worktree("implement")
        write(impl, "service/src/main/java/LinkService.java", "class LinkService {}")
        impl.commit("implement", run_id="run-1", node_id="implement")

        tests = manager.worktree("tests")
        write(tests, "service/src/test/java/LinkApiTest.java", "class LinkApiTest {}")
        tests.commit("author-tests", run_id="run-1", node_id="author-tests")

        merged, conflicts = manager.merge_worktrees(["implement", "tests"])
        assert merged == ["implement", "tests"] and conflicts == []
        assert (repo.root / "service/src/main/java/LinkService.java").exists()
        assert (repo.root / "service/src/test/java/LinkApiTest.java").exists()

    def test_a_conflict_is_reported_and_the_tree_left_untouched(
        self, manager: CheckpointManager, repo: Git
    ) -> None:
        """The merge driver has no access to intent, so it escalates instead."""
        impl = manager.worktree("implement")
        write(impl, "shared.txt", "from implement\n")
        impl.commit("implement", run_id="run-1", node_id="implement")

        tests = manager.worktree("tests")
        write(tests, "shared.txt", "from tests\n")
        tests.commit("author-tests", run_id="run-1", node_id="author-tests")

        merged, conflicts = manager.merge_worktrees(["implement", "tests"])
        assert merged == ["implement"]
        assert conflicts == ["shared.txt"]
        assert repo.is_clean()  # merge aborted, nothing half-applied

    def test_concurrent_first_access_creates_the_worktree_once(
        self, repo: Git, tmp_path: Path
    ) -> None:
        """Worktree creation is what the fan-out hits first, from every thread at once.

        The engine's ready-set runs a whole level concurrently, and the first
        thing each node does is ask for its checkout. Two threads that both find
        the name absent both run `git worktree add`; the loser gets a GitError
        for a branch that now exists, and a node dies for no reason anyone can
        see in the journal.
        """
        seen: list[list[str]] = []

        def slow_runner(argv: list[str], cwd: Path, timeout: float = 1800.0):
            if argv[:3] == ["git", "worktree", "add"]:
                seen.append(argv)
                time.sleep(0.05)  # hold the door open for the other thread
            return subprocess_runner(argv, cwd, timeout)

        manager = CheckpointManager(
            git=Git(root=repo.root, run=slow_runner),
            run_id="run-1",
            worktree_root=tmp_path / "worktrees",
        )
        roots: list[Path] = []
        threads = [
            threading.Thread(target=lambda: roots.append(manager.worktree("shared").root))
            for _ in range(4)
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        assert len(seen) == 1, f"raced: {len(seen)} concurrent `git worktree add` calls"
        assert len(roots) == 4 and len(set(roots)) == 1

    def test_cleanup_removes_the_worktrees(self, manager: CheckpointManager) -> None:
        path = manager.worktree("implement").root
        manager.cleanup()
        assert not path.exists()


class TestGitErrors:
    def test_a_failing_command_raises_with_the_reason(self, repo: Git) -> None:
        with pytest.raises(GitError, match="git checkout"):
            repo.checkout("no-such-branch")

    def test_runs_do_not_commit_to_the_default_branch(self, manager: CheckpointManager, repo: Git) -> None:
        manager.start_branch("sdlc/run-1")
        assert repo.current_branch() == "sdlc/run-1"


# --------------------------------------------------------------------------
# Budget
# --------------------------------------------------------------------------


def fake_clock(*ticks: float):
    """Deterministic elapsed time. The last value repeats once exhausted."""
    values = list(ticks) or [0.0]
    counter = itertools.count()

    def clock() -> float:
        i = next(counter)
        return values[min(i, len(values) - 1)]

    return clock


class TestBudget:
    def test_no_ceiling_never_breaches(self) -> None:
        guard = BudgetGuard(Budget())
        guard.record(1_000.0)
        assert guard.breach() is None and guard.remaining_usd is None

    def test_cost_ceiling_breaches(self) -> None:
        guard = BudgetGuard(Budget(max_cost_usd=10.0))
        guard.record(9.5)
        assert guard.breach() is None
        guard.record(0.6)
        breach = guard.breach()
        assert breach and breach.kind is BreachKind.COST
        assert "$10.10" in breach.detail

    def test_a_node_that_would_cross_the_line_is_refused_before_it_runs(self) -> None:
        """Noticing after paying is an audit finding, not a control."""
        guard = BudgetGuard(Budget(max_cost_usd=10.0))
        guard.record(8.0)
        breach = guard.check_before(estimated_cost_usd=3.0)
        assert breach and "would cost" in breach.detail
        assert guard.spent_usd == 8.0  # nothing was spent finding out

    def test_an_affordable_node_is_allowed(self) -> None:
        guard = BudgetGuard(Budget(max_cost_usd=10.0))
        guard.record(8.0)
        assert guard.check_before(estimated_cost_usd=1.0) is None

    def test_wallclock_ceiling_breaches(self) -> None:
        guard = BudgetGuard(Budget(max_wallclock_seconds=60.0), clock=fake_clock(0.0, 30.0, 90.0))
        assert guard.breach() is None  # t=30
        breach = guard.breach()  # t=90
        assert breach and breach.kind is BreachKind.WALLCLOCK

    def test_remaining_never_goes_negative(self) -> None:
        guard = BudgetGuard(Budget(max_cost_usd=10.0))
        guard.record(25.0)
        assert guard.remaining_usd == 0.0

    def test_negative_costs_are_ignored(self) -> None:
        """A backend reporting a negative cost must not buy back budget."""
        guard = BudgetGuard(Budget(max_cost_usd=10.0))
        guard.record(5.0)
        guard.record(-100.0)
        assert guard.spent_usd == 5.0

    def test_summary_records_both_ceilings(self) -> None:
        guard = BudgetGuard(Budget(max_cost_usd=25.0, max_wallclock_seconds=5400.0))
        guard.record(3.5)
        summary = guard.summary()
        assert summary["spent_usd"] == 3.5 and summary["cost_limit_usd"] == 25.0


class TestWorktreesAcrossProcesses:
    """A run that pauses for approval resumes in a different process.

    The manager's registry of worktrees is in memory, so in that new process it
    is empty and every lookup is a first access. Creating unconditionally killed
    the first live run to get past a human checkpoint: the branch and the work
    were both intact on disk, and the run could not reach them.
    """

    def _fresh(self, repo: Git, tmp_path: Path) -> CheckpointManager:
        return CheckpointManager(
            git=repo, run_id="run-1", worktree_root=tmp_path / "worktrees"
        )

    def test_a_second_process_adopts_the_existing_checkout(
        self, repo: Git, tmp_path: Path
    ) -> None:
        first = self._fresh(repo, tmp_path)
        impl = first.worktree("implement")
        write(impl, "service/src/main/java/A.java", "class A {}")
        impl.commit("implement", run_id="run-1", node_id="implement")

        second = self._fresh(repo, tmp_path)  # the resume
        adopted = second.worktree("implement")

        assert adopted.root == impl.root
        assert (adopted.root / "service/src/main/java/A.java").exists()

    def test_a_surviving_branch_is_attached_not_recreated(
        self, repo: Git, tmp_path: Path
    ) -> None:
        """Re-creating would point the branch at base and discard the node's commits."""
        first = self._fresh(repo, tmp_path)
        impl = first.worktree("implement")
        write(impl, "service/src/main/java/A.java", "class A {}")
        sha = impl.commit("implement", run_id="run-1", node_id="implement")
        first.cleanup()  # checkout pruned, branch survives

        adopted = self._fresh(repo, tmp_path).worktree("implement")
        assert adopted.head() == sha
        assert (adopted.root / "service/src/main/java/A.java").exists()

    def test_a_brand_new_name_is_still_created(self, repo: Git, tmp_path: Path) -> None:
        tree = self._fresh(repo, tmp_path).worktree("docs")
        assert (tree.root / "README.md").exists()
