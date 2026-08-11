# AC3: the tests fail against the code they were written to catch

`brownfield-1` was asked for a bug fix, and the requirement set the terms: *"we
want to see the failing test before we see the fix, and we want that test kept
afterwards."* `review-synthesis` promoted the missing half to a blocker
(TEST-1), correctly:

> By inspection the test does discriminate — but "by inspection" is exactly what
> AC3 refuses. Do not close it by citing the green post-fix suite; a green suite
> is precisely the artefact AC3 says is insufficient.

This directory is the missing half.

## Why the pipeline could not produce it

Not an oversight in the run — a property of the graph. `implement` and
`author-tests` start together (journal seq 71-72) and `author-tests`' exit gates
are `tests_compile`, `tests_not_weakened` and `paths_confined`: **none of them
execute anything.** The first time the suite runs is `maven_verify`, at `verify`,
after the join — by which point the fix is in the tree. There is no point in the
graph where the new tests meet the old code.

`plan.json` T1 named "run log entry recording the failing run against pre-change
code" as a deliverable, and `test-contract.json`'s own rationale called it "the
single easiest thing here to lose". Both were right. Recorded in `docs/TODO.md`
as a node the graph is missing.

## How this was produced

A scratch worktree at the run's merge commit, with **only the two files that
carry the fix** reverted to the greenfield tip — the code that actually shipped
with the bypasses:

```bash
git worktree add --detach runs/brownfield-1/ac3-evidence HEAD
git -C runs/brownfield-1/ac3-evidence checkout sdlc/greenfield-3 -- \
  service/src/main/java/com/example/urlshortener/link/UrlValidator.java \
  service/src/main/java/com/example/urlshortener/threat/DenylistThreatCheck.java
./mvnw -o -Dtest=HostEvasionRefusalTest test        # → ac3-host-evasion-before-fix.log
```

Reverting to greenfield rather than to the immediately preceding commit matters.
`HostNormalizer` was a stub at that commit, so tests run against it fail with
`UnsupportedOperationException` — which proves only that a stub is a stub. The
greenfield tip is the code a customer was actually served by, and it is the only
baseline whose failures mean anything.

## The result

**11 tests run, 6 failed, 0 errors.** The failures are the bypasses themselves,
not scaffolding noise:

```
aDenylistedHostWithATrailingDotIsRefusedIdenticallyToTheHostItself:51
  {"code":"bSKOURWKbIySSscOm9wO23",
   "shortUrl":"http://localhost:8080/bSKOURWKbIySSscOm9wO23",
   "longUrl":"https://malware.example.com./x",
   "status":"ACTIVE", ...}
  ==> expected: <422> but was: <201>
  expected: <url_rejected> but was: <null>
  "the trailing dot is a spelling of the same host, not a different request"
```

A live, `ACTIVE`, redirecting short link on our domain pointing at a host we had
already decided was malware. That is the promise the requirement said was not
being kept, reproduced on demand.

Also red against the old code, and green after the fix:

| Test | What the old code did |
|---|---|
| `anInternalAddressWrittenAsOneDecimalNumberIsRefusedIdenticallyToTheDottedForm` | shortened `http://2130706433/` |
| `aDenylistedHostInMixedCaseWithATrailingDotIsRefused` | case plus trailing dot, both missed |
| `everyEquivalentSpellingOfARefusedHostIsRefusedTheSameWay` | 10 spellings, refused inconsistently |
| `aRefusedSpellingCreatesNoLink` | 7 rows created that should not exist |
| `theRefusalRevealsNothingAboutWhichCheckFired` | the refusals that did fire were distinguishable |

The same 11 pass at the run's merge commit (`verify`, journal seq 98: full suite
green). Failing before, passing after, same tests, retained in the suite.

## What this does not cover

`HostCanonicalisationTest` (10 unit tests) has no meaningful pre-change baseline:
it tests a class that did not exist before this change, so against the old tree
it can only fail as "class absent". Its value is regression protection from here
on, not discrimination against the defect.
