"""Git checkpoints, worktree isolation, and rollback.

Every node that changes the workspace commits when it passes. Three things fall
out of that, none of which need inventing:

  * **rollback** is `git reset --hard <sha>` to the previous node's checkpoint,
    not a bespoke snapshot mechanism nobody has tested;
  * **provenance** is a commit trailer. `git log --grep` answers "which run
    produced this line, at which node, on which attempt" from the repository
    itself, with no orchestrator database in the loop;
  * **parallel isolation** is a worktree per branch, so `implement` and
    `author-tests` write to genuinely separate checkouts and rejoin at a real
    merge -- with real conflicts, escalated to a human rather than resolved by
    a machine that has no standing to choose.

Note that `git reset --hard` and `git clean` appear in the pipeline's forbidden
command list. That list governs what *nodes* may run; the orchestrator's own
checkpoint machinery is the thing holding the safety net and necessarily uses
them. An agent that could reset the tree could erase the evidence of what it
did.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass, field
from pathlib import Path

from .gates import CommandResult, CommandRunner, subprocess_runner


class GitError(RuntimeError):
    pass


@dataclass
class Git:
    """Thin, explicit wrapper. No porcelain parsing beyond what is needed."""

    root: Path
    run: CommandRunner = subprocess_runner

    def _git(self, *args: str, check: bool = True) -> CommandResult:
        result = self.run(["git", *args], self.root, 120.0)
        if check and not result.ok:
            raise GitError(
                f"git {' '.join(args)} failed ({result.exit_code}): {result.tail(5)}"
            )
        return result

    # -- inspection ------------------------------------------------------

    def head(self) -> str:
        return self._git("rev-parse", "HEAD").stdout.strip()

    def current_branch(self) -> str:
        return self._git("rev-parse", "--abbrev-ref", "HEAD").stdout.strip()

    def is_clean(self) -> bool:
        return not self._git("status", "--porcelain").stdout.strip()

    def changed_paths(self) -> list[str]:
        """Every path the working tree differs from HEAD by, including untracked.

        This feeds the post-hoc policy check. Untracked files matter most: a
        node writing somewhere it should not usually *creates* a file rather
        than modifying one, and `git diff` alone would not see it.
        """
        out = self._git("status", "--porcelain", "--untracked-files=all").stdout
        paths: list[str] = []
        for line in out.splitlines():
            if not line.strip():
                continue
            path = line[3:].strip()
            # Renames are reported as "old -> new"; the new path is what exists.
            if " -> " in path:
                path = path.split(" -> ", 1)[1]
            paths.append(path.strip('"'))
        return paths

    def paths_changed_between(self, base: str, head: str = "HEAD") -> list[str]:
        out = self._git("diff", "--name-only", f"{base}..{head}").stdout
        return [line.strip() for line in out.splitlines() if line.strip()]

    # -- branches and commits --------------------------------------------

    def create_branch(self, name: str, base: str | None = None) -> None:
        args = ["checkout", "-b", name]
        if base:
            args.append(base)
        self._git(*args)

    def checkout(self, ref: str) -> None:
        self._git("checkout", ref)

    def commit(
        self,
        subject: str,
        *,
        run_id: str,
        node_id: str,
        attempt: int = 0,
        body: str = "",
        exclude: tuple[str, ...] = (),
    ) -> str | None:
        """Commit the node's work. Returns the sha, or None if nothing changed.

        The trailers are the point. They make the repository itself the
        provenance record: `git log --grep="Run-Id: run-42"` reconstructs
        exactly what that run touched, and it keeps working after the
        orchestrator's database is gone.

        Which is exactly why `exclude` exists. Staging the whole tree attributes
        everything sitting in it to the node -- including an operator's own
        edits, made in a different terminal while the run was in flight. That
        produced a commit here carrying `Node-Id: clarify` and a diff containing
        changes to the orchestrator itself, which is precisely the claim the
        trailer is supposed to make impossible. The run's forbidden paths are
        passed in, so a node's checkpoint cannot contain anything the node was
        never permitted to write.
        """
        pathspec = [f":(exclude,glob){pattern}" for pattern in exclude]
        self._git("add", "--all", "--", ".", *pathspec)
        if not self._git("diff", "--cached", "--quiet", check=False).exit_code:
            return None  # nothing staged: the node changed nothing

        message = subject
        if body:
            message += f"\n\n{body}"
        message += f"\n\nRun-Id: {run_id}\nNode-Id: {node_id}\nAttempt: {attempt}"
        self._git("commit", "--no-verify", "-m", message)
        return self.head()

    def reset_hard(self, sha: str) -> None:
        """Discard everything back to a checkpoint, untracked files included."""
        self._git("reset", "--hard", sha)
        self._git("clean", "-fd")

    # -- worktrees -------------------------------------------------------

    def add_worktree(self, path: Path, branch: str, base: str = "HEAD") -> Git:
        """Create an isolated checkout on a new branch, and return a Git for it."""
        self._git("worktree", "add", "-b", branch, str(path), base)
        return Git(root=path, run=self.run)

    def remove_worktree(self, path: Path) -> None:
        self._git("worktree", "remove", "--force", str(path), check=False)

    def merge(self, branch: str, message: str) -> list[str]:
        """Merge a branch. Returns the conflicted paths, or [] on success.

        A conflict aborts the merge and leaves the tree untouched. Deciding
        between generated implementation and generated tests is a judgement
        call about intent, and the merge driver has no access to intent -- so
        the barrier's gate escalates instead.
        """
        result = self._git("merge", "--no-ff", "-m", message, branch, check=False)
        if result.ok:
            return []
        conflicts = [
            line.strip()
            for line in self._git(
                "diff", "--name-only", "--diff-filter=U", check=False
            ).stdout.splitlines()
            if line.strip()
        ]
        self._git("merge", "--abort", check=False)
        if not conflicts:
            raise GitError(f"merge of '{branch}' failed without conflicts: {result.tail(5)}")
        return conflicts


@dataclass
class CheckpointManager:
    """Per-run checkpointing on top of `Git`."""

    git: Git
    run_id: str
    worktree_root: Path
    # Never staged into a node's checkpoint. Defaults to empty so a caller that
    # does not care still gets the old behaviour; the engine passes the run's
    # forbidden paths, which is where this matters.
    exclude_paths: tuple[str, ...] = ()
    _worktrees: dict[str, Git] = field(default_factory=dict)
    # Held across worktree *creation*, not merely around the dict. See
    # `worktree()` -- the thing being protected is git's ref store, not this
    # process's bookkeeping.
    _worktree_lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def start_branch(self, name: str) -> str:
        """Runs never commit to the default branch. Not policy -- mechanism."""
        self.git.create_branch(name)
        return name

    def checkpoint(
        self,
        node_id: str,
        *,
        attempt: int = 0,
        summary: str = "",
        worktree: str | None = None,
    ) -> str | None:
        """Commit where the node actually worked.

        A node running in a worktree must commit *there* -- committing in the
        main checkout would record an empty change and leave the branch the
        barrier later merges with nothing on it.
        """
        subject = f"{node_id}: {summary}" if summary else f"checkpoint after {node_id}"
        git = self.worktree(worktree) if worktree else self.git
        return git.commit(
            subject,
            run_id=self.run_id,
            node_id=node_id,
            attempt=attempt,
            exclude=self.exclude_paths,
        )

    def rollback(self, sha: str) -> None:
        self.git.reset_hard(sha)

    def worktree(self, name: str, *, base: str = "HEAD") -> Git:
        """Isolated checkout for a parallel branch. Idempotent within a run.

        Serialized, because this is the first thing every node in a fan-out
        does and they all do it at once. Two threads asking for the same name
        would both find it absent and both run `git worktree add`; the loser
        gets `cannot lock ref` and a node fails for a reason that has nothing
        to do with its work. Creation for *different* names is serialized by
        the same lock rather than a per-name one, because the contention is in
        git's ref store and index, which are shared across names.

        The lock is held across the git call, not just the dict access. Holding
        it only around the bookkeeping would leave exactly the race it is here
        to close.
        """
        if name in self._worktrees:
            return self._worktrees[name]
        with self._worktree_lock:
            # Re-checked under the lock: another thread may have created it
            # while this one was waiting.
            if name in self._worktrees:
                return self._worktrees[name]
            path = self.worktree_root / f"{self.run_id}-{name}"
            path.parent.mkdir(parents=True, exist_ok=True)
            branch = f"{self.run_id}/{name}"
            self._worktrees[name] = self.git.add_worktree(path, branch, base)
            return self._worktrees[name]

    def merge_worktrees(self, names: list[str]) -> tuple[list[str], list[str]]:
        """Merge each branch in turn. Returns (merged, conflicts).

        Stops at the first conflict: merging the rest on top of a half-joined
        tree would only make the conflict harder for the person who has to
        resolve it.
        """
        merged: list[str] = []
        for name in names:
            branch = f"{self.run_id}/{name}"
            conflicts = self.git.merge(branch, f"join: merge {name} branch")
            if conflicts:
                return merged, conflicts
            merged.append(name)
        return merged, []

    def cleanup(self) -> None:
        for git in self._worktrees.values():
            self.git.remove_worktree(git.root)
        self._worktrees.clear()
