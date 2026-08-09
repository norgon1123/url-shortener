"""Journal integrity, lineage, and input hashing.

The tamper tests are the substantive ones: they simulate an attacker editing
the audit log three different ways and assert each is detected. A claim of
"audit-grade" that isn't tested is just a word in a README.
"""

from __future__ import annotations

import itertools
import json
from pathlib import Path

import pytest

from sdlc.audit import GENESIS_HASH, Journal, TamperError, hash_inputs


def fixed_clock():
    """Deterministic timestamps so hashes are reproducible across runs."""
    counter = itertools.count()
    return lambda: f"2026-08-08T12:00:{next(counter):02d}+00:00"


@pytest.fixture
def journal(tmp_path: Path) -> Journal:
    return Journal(tmp_path / "journal.jsonl", run_id="run-1", clock=fixed_clock())


class TestChain:
    def test_first_entry_links_to_genesis(self, journal: Journal) -> None:
        entry = journal.append("run_started")
        assert entry.seq == 0
        assert entry.prev_hash == GENESIS_HASH
        assert len(entry.entry_hash) == 64

    def test_entries_link_forward(self, journal: Journal) -> None:
        a = journal.append("node_started", node_id="intake")
        b = journal.append("node_completed", node_id="intake")
        assert b.prev_hash == a.entry_hash
        assert b.seq == a.seq + 1

    def test_clean_journal_verifies(self, journal: Journal) -> None:
        for i in range(5):
            journal.append("gate_evaluated", node_id=f"n{i}", outcome="pass")
        journal.verify()  # must not raise

    def test_resumes_chain_across_reopen(self, tmp_path: Path) -> None:
        """A crash and restart must not fork the chain."""
        path = tmp_path / "j.jsonl"
        first = Journal(path, run_id="r", clock=fixed_clock())
        first.append("run_started")
        first.append("node_started", node_id="intake")

        resumed = Journal(path, run_id="r", clock=fixed_clock())
        entry = resumed.append("node_completed", node_id="intake")

        assert entry.seq == 2
        resumed.verify()


class TestTamperDetection:
    """Three distinct edits an attacker could attempt on the log."""

    def _seed(self, journal: Journal) -> None:
        journal.append("run_started")
        journal.append("gate_evaluated", node_id="verify", outcome="fail")
        journal.append("node_completed", node_id="verify", cost_usd=1.5)

    def test_detects_mutated_payload(self, journal: Journal) -> None:
        # The realistic attack: flip a recorded gate failure to a pass.
        self._seed(journal)
        lines = journal.path.read_text().splitlines()
        record = json.loads(lines[1])
        record["payload"]["outcome"] = "pass"
        lines[1] = json.dumps(record, sort_keys=True, separators=(",", ":"))
        journal.path.write_text("\n".join(lines) + "\n")

        with pytest.raises(TamperError, match="content tampered at entry 1"):
            journal.verify()

    def test_detects_deleted_entry(self, journal: Journal) -> None:
        self._seed(journal)
        lines = journal.path.read_text().splitlines()
        del lines[1]
        journal.path.write_text("\n".join(lines) + "\n")

        with pytest.raises(TamperError, match="sequence break"):
            journal.verify()

    def test_detects_reordered_entries(self, journal: Journal) -> None:
        self._seed(journal)
        lines = journal.path.read_text().splitlines()
        lines[1], lines[2] = lines[2], lines[1]
        journal.path.write_text("\n".join(lines) + "\n")

        with pytest.raises(TamperError, match="sequence break"):
            journal.verify()

    def test_detects_appended_forgery(self, journal: Journal) -> None:
        """Forging a new entry requires the previous hash, which chains."""
        self._seed(journal)
        forged = {
            "seq": 3,
            "run_id": "run-1",
            "ts": "2026-08-08T13:00:00+00:00",
            "event": "human_approved",
            "node_id": "release-readiness",
            "attempt": 0,
            "parent_ids": [],
            "input_hash": None,
            "payload": {"approver": "nobody"},
            "prev_hash": "f" * 64,
            "entry_hash": "e" * 64,
        }
        with journal.path.open("a") as fh:
            fh.write(json.dumps(forged, sort_keys=True, separators=(",", ":")) + "\n")

        with pytest.raises(TamperError, match="broken chain at entry 3"):
            journal.verify()


class TestLineage:
    def test_replan_traces_back_to_gate_failure(self, journal: Journal) -> None:
        """Decision lineage: 'why did this replan happen?'"""
        started = journal.append("node_started", node_id="verify")
        failure = journal.append(
            "gate_failed",
            node_id="verify",
            parent_ids=[started.entry_id],
            check="maven_verify",
        )
        replan = journal.append(
            "replan_triggered",
            node_id="decompose",
            parent_ids=[failure.entry_id],
            reason="verify failed twice",
        )

        chain = journal.lineage(replan.entry_id)
        assert [e.event for e in chain] == [
            "replan_triggered",
            "gate_failed",
            "node_started",
        ]

    def test_lineage_survives_missing_parents(self, journal: Journal) -> None:
        entry = journal.append("node_started", node_id="x", parent_ids=["99999-deadbeef"])
        assert len(journal.lineage(entry.entry_id)) == 1


class TestQueries:
    def test_cost_aggregation(self, journal: Journal) -> None:
        journal.append("node_completed", node_id="a", cost_usd=1.25)
        journal.append("node_completed", node_id="b", cost_usd=2.50)
        journal.append("gate_evaluated", node_id="b")
        assert journal.total_cost_usd() == pytest.approx(3.75)

    def test_last_input_hash_tracks_latest(self, journal: Journal) -> None:
        journal.append("node_started", node_id="design", input_hash="aaa")
        journal.append("node_started", node_id="design", input_hash="bbb")
        assert journal.last_input_hash("design") == "bbb"
        assert journal.last_input_hash("nonexistent") is None


class TestInputHashing:
    """Backing for change-driven replanning (§4.4 'when upstream outputs change')."""

    def test_stable_for_identical_content(self, tmp_path: Path) -> None:
        f = tmp_path / "requirement.json"
        f.write_text('{"goal": "shorten urls"}')
        assert hash_inputs([f]) == hash_inputs([f])

    def test_changes_when_content_changes(self, tmp_path: Path) -> None:
        f = tmp_path / "requirement.json"
        f.write_text('{"goal": "shorten urls"}')
        before = hash_inputs([f])
        f.write_text('{"goal": "shorten urls with analytics"}')
        assert hash_inputs([f]) != before

    def test_absent_file_is_distinct_from_empty(self, tmp_path: Path) -> None:
        missing = tmp_path / "gone.json"
        empty = tmp_path / "empty.json"
        empty.write_text("")
        assert hash_inputs([missing]) != hash_inputs([empty])

    def test_order_independent(self, tmp_path: Path) -> None:
        a, b = tmp_path / "a.json", tmp_path / "b.json"
        a.write_text("A")
        b.write_text("B")
        assert hash_inputs([a, b]) == hash_inputs([b, a])

    def test_path_is_part_of_identity(self, tmp_path: Path) -> None:
        """Same bytes at a different path is a different input."""
        a, b = tmp_path / "a.json", tmp_path / "b.json"
        a.write_text("same")
        b.write_text("same")
        assert hash_inputs([a]) != hash_inputs([b])
