# Node: docs

Document what was actually built, for someone who has to use or operate it.

The build is green and the tests pass by the time you run, so the code is the
authority — read it, and `artifacts/openapi.yaml`, rather than restating the
plan. Where the two disagree, the code won.

You may write under `docs/**` and `README.md` only.

Write for two readers:

- **A caller.** How to start the service, how to make each request, what comes
  back, what the error codes mean, and what happens at the edges — expiry,
  duplicates, invalid input. Include copy-pasteable `curl` commands that work
  against a locally running service, with real example values rather than
  `<YOUR_VALUE_HERE>`.
- **Whoever gets paged.** What configuration exists and what the defaults are,
  which endpoints report health, what the service depends on, and what the
  common failure looks like from the outside.

Do not document what is not there. Aspirational documentation of a feature that
was cut is worse than no documentation, because it costs someone an afternoon
before they conclude the docs are lying. If the design made a decision worth
knowing about — a status code, a caching choice, an excluded scale path — record
the decision and the reason, briefly.

Every link must resolve; that is checked mechanically. A relative link to a file
that does not exist fails this node.

Keep it short. A page someone reads is worth more than a manual they skim.
