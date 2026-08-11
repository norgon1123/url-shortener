# Inputs

One file per run, kept side by side, because the interesting thing about a
brownfield change is *what changed about the ask* — and a single file overwritten
in place says only what was asked last.

| File | Run | What it asks for |
|---|---|---|
| `greenfield.txt` | `greenfield-3` | Build the URL shortener. 92 lines, no code exists. |
| `brownfield.txt` | `brownfield-1` | Change the running service: fix the URL filter's bypasses, add self-service sign-up, add anonymous links that expire after a month. 76 lines, written against a live codebase. |
| `requirement.txt` | whichever run is live | What `intake` actually reads. A copy of the run's file. |

Both are verbatim what the agents were handed, recoverable from git as well:
`greenfield.txt` is `4625ee9^:input/requirement.txt`.

## What is worth comparing

They are deliberately the same voice — a business describing a problem, not a
specification — and the difference in what that produces is most of the point of
running both:

- **greenfield** states a domain and its worries. `clarify` came back with
  questions about things the requirement never mentioned, and `design` had a
  blank repository to answer them in.
- **brownfield** states three changes and one *complaint* — a promise the service
  already makes and is not keeping. It never names a class, a table or an
  endpoint. Everything about blast radius came from the codebase:
  `impact-analysis` found that the host normaliser is shared by two call sites,
  that `shouldNotFilter` is one string comparison standing in for the whole
  access-control policy, and that a missing `lower(email)` index would surface
  as a 500 on sign-in for one account rather than as a failure at sign-up.

The two bypasses in `brownfield.txt` are given as *examples* of a weak check
rather than as the list to fix ("assume there are more ways past it than the two
we happened to find"). What came back closed a family — decimal, hex, octal,
leading-zero, trailing-dot, mixed-case — not two strings. That phrasing is doing
real work, and it is the kind of thing worth noticing when writing the next one.

## The duplication is deliberate

`requirement.txt` is a copy rather than a symlink or a rename, because the
pipeline hard-codes that path in two places — `intake`'s prompt and an
`artifact_present` gate — and changing them mid-run would invalidate `intake` by
content hash and re-run everything downstream of it. Making the path
scenario-derived (`input/<scenario>.txt`) is written up in `docs/TODO.md`; it is
a small change that has to be made between runs, not during one.
