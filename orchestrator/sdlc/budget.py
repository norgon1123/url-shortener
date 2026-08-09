"""Spend and wall-clock ceilings.

An agentic pipeline that can retry, fall back, and re-plan can also loop, and a
loop that costs money is a different kind of incident from a loop that costs
CPU. The ceiling is checked at node boundaries, and a breach triggers safe-stop
rather than a hard kill: the run halts somewhere it can be resumed from, with
its journal intact and its worktree committed.

The pre-flight check matters more than the post-hoc one. Discovering the budget
is blown *after* paying for the node that blew it is an audit finding, not a
control -- so a node whose estimate would cross the line is refused before it
starts.
"""

from __future__ import annotations

import enum
from collections.abc import Callable
from dataclasses import dataclass, field
from time import monotonic

from .model import Budget


class BreachKind(str, enum.Enum):
    COST = "cost"
    WALLCLOCK = "wallclock"


@dataclass(frozen=True)
class Breach:
    kind: BreachKind
    limit: float
    actual: float
    detail: str

    def __str__(self) -> str:
        return self.detail


@dataclass
class BudgetGuard:
    """Tracks a run's spend and elapsed time against its ceilings."""

    budget: Budget
    spent_usd: float = 0.0
    clock: Callable[[], float] = monotonic
    _started_at: float = field(init=False)

    def __post_init__(self) -> None:
        self._started_at = self.clock()

    # -- accounting ------------------------------------------------------

    def record(self, cost_usd: float) -> float:
        self.spent_usd += max(0.0, cost_usd)
        return self.spent_usd

    @property
    def elapsed_seconds(self) -> float:
        return self.clock() - self._started_at

    @property
    def remaining_usd(self) -> float | None:
        if self.budget.max_cost_usd is None:
            return None
        return max(0.0, self.budget.max_cost_usd - self.spent_usd)

    @property
    def remaining_seconds(self) -> float | None:
        if self.budget.max_wallclock_seconds is None:
            return None
        return max(0.0, self.budget.max_wallclock_seconds - self.elapsed_seconds)

    # -- checks ----------------------------------------------------------

    def breach(self) -> Breach | None:
        """Has a ceiling already been crossed?"""
        if (limit := self.budget.max_cost_usd) is not None and self.spent_usd >= limit:
            return Breach(
                BreachKind.COST,
                limit,
                self.spent_usd,
                f"cost ceiling reached: ${self.spent_usd:.2f} of ${limit:.2f}",
            )
        if (limit := self.budget.max_wallclock_seconds) is not None:
            elapsed = self.elapsed_seconds
            if elapsed >= limit:
                return Breach(
                    BreachKind.WALLCLOCK,
                    limit,
                    elapsed,
                    f"wall-clock ceiling reached: {elapsed:.0f}s of {limit:.0f}s",
                )
        return None

    def check_before(self, estimated_cost_usd: float = 0.0) -> Breach | None:
        """Refuse a node that would cross the line, rather than noticing after.

        With no estimate this degrades to the plain breach check, which is the
        honest default: most nodes have no reliable prior cost.
        """
        if existing := self.breach():
            return existing
        limit = self.budget.max_cost_usd
        if limit is not None and estimated_cost_usd > 0:
            projected = self.spent_usd + estimated_cost_usd
            if projected > limit:
                return Breach(
                    BreachKind.COST,
                    limit,
                    projected,
                    f"node would cost about ${estimated_cost_usd:.2f}, taking the run "
                    f"to ${projected:.2f} against a ${limit:.2f} ceiling",
                )
        return None

    def summary(self) -> dict[str, float | None]:
        """What the report and the journal record at the end of a run."""
        return {
            "spent_usd": round(self.spent_usd, 4),
            "cost_limit_usd": self.budget.max_cost_usd,
            "elapsed_seconds": round(self.elapsed_seconds, 2),
            "wallclock_limit_seconds": self.budget.max_wallclock_seconds,
        }
