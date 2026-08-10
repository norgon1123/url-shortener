"""Tamper-evident run journal.

"Audit-grade traceability" invites the obvious question: what stops someone
editing the log after the fact? So each entry commits to its predecessor's
hash. Altering or removing any entry breaks the chain from that point forward,
and `verify()` reports the exact sequence number where it broke.

The journal is also the substrate for two other §4.4 requirements:

  * decision lineage -- entries carry `parent_ids`, so a replan points at the
    gate failure that caused it, and a design artifact points at the assumption
    IDs that shaped it. That is a causal graph, not just a timeline.

  * change-driven replanning -- entries record the hash of each node's inputs,
    so a re-run can detect that an upstream artifact changed and mark
    downstream nodes stale, Make-style.
"""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
from collections.abc import Callable, Iterator
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

GENESIS_HASH = "0" * 64


class TamperError(RuntimeError):
    """Raised when the journal's hash chain does not verify."""


def canonical_json(payload: Any) -> str:
    """Stable serialization. Key order and spacing must never vary."""
    return json.dumps(payload, sort_keys=True, separators=(",", ":"), default=str)


@dataclass(frozen=True)
class JournalEntry:
    seq: int
    run_id: str
    ts: str
    event: str
    node_id: str | None = None
    attempt: int = 0
    parent_ids: tuple[str, ...] = ()
    input_hash: str | None = None
    payload: dict[str, Any] = field(default_factory=dict)
    prev_hash: str = GENESIS_HASH
    entry_hash: str = ""

    @property
    def entry_id(self) -> str:
        """Short stable handle used for lineage references."""
        return f"{self.seq:05d}-{self.entry_hash[:12]}"

    def compute_hash(self) -> str:
        body = {k: v for k, v in asdict(self).items() if k != "entry_hash"}
        return hashlib.sha256(canonical_json(body).encode()).hexdigest()

    def to_json(self) -> str:
        return canonical_json(asdict(self))

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> JournalEntry:
        return cls(
            seq=raw["seq"],
            run_id=raw["run_id"],
            ts=raw["ts"],
            event=raw["event"],
            node_id=raw.get("node_id"),
            attempt=raw.get("attempt", 0),
            parent_ids=tuple(raw.get("parent_ids") or ()),
            input_hash=raw.get("input_hash"),
            payload=raw.get("payload") or {},
            prev_hash=raw.get("prev_hash", GENESIS_HASH),
            entry_hash=raw.get("entry_hash", ""),
        )


def _utc_now() -> str:
    return datetime.now(UTC).isoformat()


class Journal:
    """Append-only hash-chained JSONL log.

    Appends are atomic: the chain state is only advanced after the line is
    durably on disk, so a crash mid-write cannot leave the in-memory head
    pointing at an entry the file does not contain.
    """

    def __init__(
        self,
        path: str | Path,
        run_id: str,
        clock: Callable[[], str] = _utc_now,
    ) -> None:
        self.path = Path(path)
        self.run_id = run_id
        self._clock = clock
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._seq, self._head = self._recover()

    def _recover(self) -> tuple[int, str]:
        """Resume an existing chain, or start a new one."""
        if not self.path.exists():
            return 0, GENESIS_HASH
        last: JournalEntry | None = None
        for entry in self.read():
            last = entry
        if last is None:
            return 0, GENESIS_HASH
        return last.seq + 1, last.entry_hash

    def append(
        self,
        event: str,
        *,
        node_id: str | None = None,
        attempt: int = 0,
        parent_ids: tuple[str, ...] | list[str] = (),
        input_hash: str | None = None,
        **payload: Any,
    ) -> JournalEntry:
        entry = JournalEntry(
            seq=self._seq,
            run_id=self.run_id,
            ts=self._clock(),
            event=event,
            node_id=node_id,
            attempt=attempt,
            parent_ids=tuple(parent_ids),
            input_hash=input_hash,
            payload=payload,
            prev_hash=self._head,
        )
        entry = JournalEntry(**{**asdict(entry), "entry_hash": entry.compute_hash()})

        with self.path.open("a", encoding="utf-8") as fh:
            fh.write(entry.to_json() + "\n")
            fh.flush()
            os.fsync(fh.fileno())

        self._seq += 1
        self._head = entry.entry_hash
        return entry

    def read(self) -> Iterator[JournalEntry]:
        if not self.path.exists():
            return
        with self.path.open(encoding="utf-8") as fh:
            for lineno, line in enumerate(fh, start=1):
                if not (line := line.strip()):
                    continue
                try:
                    yield JournalEntry.from_dict(json.loads(line))
                except (json.JSONDecodeError, KeyError) as exc:
                    raise TamperError(
                        f"{self.path}:{lineno}: unparseable journal entry: {exc}"
                    ) from exc

    def entries(self) -> list[JournalEntry]:
        return list(self.read())

    def verify(self) -> None:
        """Walk the chain. Raise TamperError naming the first bad entry.

        Catches three distinct edits: a mutated payload (hash mismatch), a
        deleted entry (link mismatch plus a sequence gap), and a reordered or
        spliced entry (sequence mismatch).
        """
        expected_prev = GENESIS_HASH
        expected_seq = 0
        for entry in self.read():
            if entry.seq != expected_seq:
                raise TamperError(
                    f"sequence break at entry {entry.seq}: expected seq {expected_seq} "
                    "(an entry was removed, reordered, or inserted)"
                )
            if entry.prev_hash != expected_prev:
                raise TamperError(
                    f"broken chain at entry {entry.seq}: prev_hash points at "
                    f"{entry.prev_hash[:12]}..., expected {expected_prev[:12]}..."
                )
            if entry.entry_hash != entry.compute_hash():
                raise TamperError(
                    f"content tampered at entry {entry.seq} "
                    f"(event={entry.event!r}, node={entry.node_id!r})"
                )
            expected_prev = entry.entry_hash
            expected_seq += 1

    # -- queries ---------------------------------------------------------

    def by_node(self, node_id: str) -> list[JournalEntry]:
        return [e for e in self.read() if e.node_id == node_id]

    def by_event(self, event: str) -> list[JournalEntry]:
        return [e for e in self.read() if e.event == event]

    def lineage(self, entry_id: str) -> list[JournalEntry]:
        """Walk `parent_ids` back to the root.

        This is what turns the journal from a timeline into an answer to
        "why did this happen?" -- the chain from a replan back through the gate
        failure that triggered it.
        """
        index = {e.entry_id: e for e in self.read()}
        chain: list[JournalEntry] = []
        seen: set[str] = set()
        queue = [entry_id]
        while queue:
            eid = queue.pop(0)
            if eid in seen or eid not in index:
                continue
            seen.add(eid)
            entry = index[eid]
            chain.append(entry)
            queue.extend(entry.parent_ids)
        return chain

    def last_input_hash(self, node_id: str) -> str | None:
        """Most recent recorded input hash for a node, for staleness checks."""
        result = None
        for entry in self.read():
            if entry.node_id == node_id and entry.input_hash:
                result = entry.input_hash
        return result

    def total_cost_usd(self) -> float:
        return sum(float(e.payload.get("cost_usd", 0.0)) for e in self.read())


def hash_inputs(
    paths: list[Path], extra: dict[str, Any] | None = None, root: Path | None = None
) -> str:
    """Content-address a node's inputs.

    Feeds change-driven replanning: if this differs from what a downstream node
    recorded consuming, that node is stale and must re-run. Missing files hash
    as a sentinel so appearance/disappearance is itself a change.

    `root` makes the digest location-independent by labelling each file
    relative to it. The frozen contract needs that: it is hashed once in the
    main workspace and re-derived in two git worktrees at different absolute
    paths, and without it every branch would report a mismatch for no reason
    but its own directory name.
    """
    digest = hashlib.sha256()
    for path in sorted(paths, key=lambda p: str(p)):
        label = str(path)
        if root is not None:
            try:
                label = str(path.resolve().relative_to(root.resolve()))
            except (ValueError, OSError):
                label = path.name
        digest.update(label.encode())
        digest.update(b"\0")
        digest.update(
            path.read_bytes() if path.is_file() else b"<absent>"
        )
        digest.update(b"\0")
    if extra:
        digest.update(canonical_json(extra).encode())
    return digest.hexdigest()


def export_journal(src: Path, dest: Path) -> Path:
    """Copy a journal to a fixture location, verifying it first.

    Fixtures ship in the repo so an evaluator with no API key can inspect a
    real run's audit trail -- and can re-verify the chain themselves.
    """
    journal = Journal(src, run_id="<export>")
    journal.verify()
    dest.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w", delete=False, dir=dest.parent, encoding="utf-8"
    ) as tmp:
        tmp.write(src.read_text(encoding="utf-8"))
        tmp_path = Path(tmp.name)
    tmp_path.replace(dest)
    return dest
