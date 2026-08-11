# `brownfield-1` — the same graph, against a codebase that already exists

Replayable with no API key and no spend:

```bash
PYTHONPATH=orchestrator python -m sdlc.cli replay orchestrator/fixtures/runs/brownfield-1
PYTHONPATH=orchestrator python -m sdlc.cli --runs-dir orchestrator/fixtures/runs verify brownfield-1      # re-check the hash chain yourself
```

**Outcome: completed.** 276 tests green, 88.3% line coverage, 10 routes matching
the frozen contract exactly. Signed off by a human over a `ready: false`
verdict, with the residual risks recorded in `docs/TODO.md` — the same shape of
close as `greenfield-3`, and the same distinction: the pipeline reached its end
with every decision recorded, which is not the same as the service being
shippable.

The ask is `input/brownfield.txt`: fix a URL filter that two tricks walk past,
add self-service sign-up, add anonymous links that expire after a month. It
names no class, no table and no endpoint.

| | |
|---|---|
| Nodes passed | 20 of 20 scheduled |
| Human decisions | 5 |
| Gate pass rate | 97% |
| Replans | 0 |
| Cost | $98.16, of which **$51.90 (53%) rework** |
| Wall clock | ~15.8h, most of it paused on humans |

## What this run shows that `greenfield-3` cannot

**`impact-analysis` had something to analyse.** It found the host normaliser is
shared by two call sites, so a fix that strips too much starts refusing
legitimate URLs for every existing customer with a 422 that cannot say why —
`url_rejected` deliberately does not distinguish denylist from internal-host. It
found `shouldNotFilter` is one exact string comparison standing in for the whole
access-control policy of `/api/v1/*`, so a loose exemption for the new
unauthenticated paths would open link listing and deletion to anyone **with a
green build**, because the existing tests assert 401 for a *missing token*, not
for a token-free request against a newly-exempted neighbour. And it found that a
missing `lower(email)` index would not fail at sign-up but later, as a 500 on
sign-in for one account — "it would look like an unrelated incident".

**A second-order security consequence, found by review rather than by tests.**
Sign-up is unremarkable on its own. What `review-security` saw is that it
silently re-scopes every authenticated endpoint from "two hand-provisioned
accounts" to "anyone on the internet" — and the one authenticated capability
that deliberately crosses tenant boundaries is the abuse-report takedown, which
is immediate, unmoderated and irreversible. Its only bound was a rate bucket
keyed by customer id, and customer ids had just become free. Any published short
link could be permanently killed by anyone.

Nothing in the build was red. `verify` had passed. This is what the review
fan-out is for.

**The requirement's phrasing did real work.** The two bypasses were given as
*examples* of a weak check, not as the list to fix. What came back closed a
family — decimal, hex, octal, leading-zero, trailing-dot, mixed-case, IDN — and
`HostNormalizer` fails closed on anything it cannot canonicalise.

## Three governance mechanisms that fired for the first time

1. **`repair`, a verb that did not exist.** `triage` routes repairs only out of a
   `verify` failure. This run's build was green and its *review* found a
   blocker, so there was no move: rejecting the reviewing node re-runs the
   reviewer, which writes artifacts and cannot change code; approving it accepts
   the finding. `sdlc.cli repair` resets the named nodes and records
   `human_repair_requested` — deliberately *not* `repair_routed`, because the
   journal must never say the machine decided something a person decided.

2. **The repair produced a better answer than the brief asked for.** Told not to
   disclose whether a code exists, the implementation worked out that *any*
   visible refusal breaks that — the endpoint already answers 202 whether or not
   the code resolves — so an unqualified report is filed and answered
   identically, and simply does not take anything down. That reasoning is in the
   source, not just in the diff.

3. **`review-synthesis` audited the tree rather than the findings.** The security
   lens re-raised SEC-1 against code that already contained the fix. Synthesis
   checked, found the premise superseded, named the two commits — and kept the
   finding at blocker anyway, because *"the lens's underlying reasoning, that
   free accounts change the economics of every per-account limit in the service,
   is sound and survives the fix"*. It asked a human to confirm two specific
   things and said what to do if both held.

## Two findings against the pipeline itself

`release-readiness` derived both from the run's own history, unprompted:

- **AC3's evidence was produced by hand.** The requirement asked to see the
  failing test before the fix. The graph cannot show that — `implement` and
  `author-tests` start together, `author-tests`' gates execute nothing, and the
  first suite run is after the join with the fix in the tree. Closed manually in
  `docs/evidence/`; the missing node is specified in `docs/TODO.md`.
- **A gate was edited inside the run, and no lens looked.** Commit `7753468`
  changed `gates.py` and `test_gates.py` mid-run. *"A weakened gate would not
  have been visible from where any of the five lenses looked — and every
  mechanical outcome cited above is produced by that code."* True, and the
  operator who made that commit had already found one instance of it by hand.

## The gate defects this run exposed

Both surfaced within one node, and both are the same mistake: a check written
when the test tree was always empty at that point, then handed a tree with 148
tests already in it.

- `no_assertions` scanned the whole tree and failed `test-contract` for **880
  assertions it had not written**. It now asks git what the node added.
- `tests_not_weakened` kept the first baseline it ever wrote — greenfield's 130
  — while the inherited suite held 148, so eighteen tests could have been
  deleted with the gate reporting no weakening.

70% of nodes needed more than one attempt, and that number is mostly this: the
graph was written against an empty repository, and a brownfield run is where
that assumption comes due.
