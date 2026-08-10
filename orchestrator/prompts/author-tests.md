# Node: author-tests

Write the tests that gate the implementation, **before it exists**.

Read `artifacts/openapi.yaml`, `artifacts/design.json`, and
`artifacts/requirement.json` for the acceptance criteria. Read
`artifacts/impact.json` too: it says whether this is new ground or a change to
behaviour something already depends on, and that changes what you write.

## You are writing blind, on purpose

`service/src/main/**` is denied to you. In parallel, another node is
implementing the contract; you will not see what it produces, and it cannot
touch your tests. That separation is the reason a green build here means
something: neither agent can bend to the other.

So write against the **contract only** — the documented URLs, methods, request
bodies, status codes, and response shapes. Drive the service over HTTP by
extending `AbstractIntegrationTest`, which gives you a `TestRestTemplate` and a
real PostgreSQL via Testcontainers. Do not import service classes, repositories,
or DTO internals: a test that names an implementation class is a test that
breaks on a refactor and, worse, is a test you had to guess the shape of.

## Two things must be covered, and they are not the same thing

**The contract.** Every endpoint, status code, and response shape in
`openapi.yaml`, including the failure paths. This is the part that does not
depend on what the change happens to be: the contract is what callers rely on,
and an endpoint documented but never exercised is an endpoint nobody has
checked.

**The new behaviour specifically.** Whatever this change actually introduces —
a whole feature, a small patch, a bug fix, a changed default — needs tests aimed
directly at it, on top of the contract coverage. The requirement's acceptance
criteria and the impact analysis tell you what that is.

The test for whether you have done this: **if the new behaviour were reverted
and your tests left alone, would the suite go red?** If not, the behaviour is
unpinned no matter how green the build looks or how high the coverage number is.
Work through it criterion by criterion.

The shape of the change decides where the effort goes:

- **A new feature** — the new endpoints and their whole documented surface.
- **A change to existing behaviour** — the new behaviour, *and* the old
  behaviour that must not move. The impact analysis names the regression
  surface; that list is a test list.
- **A bug fix** — a test that fails against the broken behaviour and passes
  against the fixed one, written from the conditions that triggered the bug
  rather than from the fix. A fix with no regression test gets reintroduced by
  the next refactor, and the green suite will say nothing.

## Each behaviour gets its own test

Give a new behaviour a test named after it, with its own setup and its own
reason to fail — not an extra assertion appended to a test that already exists
for something else.

An appended assertion looks like coverage and is not. The test's name stops
describing what it verifies, so a failure sends whoever is triaging it to the
wrong place. The behaviour is only ever checked under whatever arrangement that
test happened to have. If an earlier assertion in the test fails, yours never
runs at all. And the next person to simplify that test has no way to know a
separate behaviour was resting on the line they are deleting.

Adding an assertion to an existing test is right only when it is about the same
behaviour that test already covers — checking the `Location` header inside the
test that covers the redirect. If it is a different behaviour, it needs a
different test.

A downstream reviewer reads the tests specifically for this, so it is cheaper to
do now than to be told about later.

## Put each test where the behaviour is observable

- Anything a caller can see — status codes, bodies, headers, redirects, error
  shapes, whether the effect survives the request — belongs in a black-box HTTP
  test that drives the running service. That is where the wiring bugs are, and
  a helper tested in isolation cannot show that the endpoint behaves correctly.
- Logic with many cases — validation rules, expiry arithmetic, code generation,
  boundaries — belongs in fast unit tests where every case can be enumerated
  cheaply. Driving twenty validation cases over HTTP is slow enough that only
  three of them ever get written.

## Coverage

Every acceptance criterion in the requirement needs at least one test that
demonstrably fails if the behaviour is absent. Name tests after the behaviour
(`expiredLinkReturns410`), not after the method under test.

Cover, for each endpoint:

- the success path and its exact documented status code;
- every documented error status, including the ones that are easy to skip —
  malformed input, absent resource, conflicting resource, expired resource;
- boundaries: empty, oversized, and structurally invalid input;
- anything the design's `rationale` calls out as a deliberate decision. A choice
  worth explaining is worth pinning.

A line coverage floor is enforced downstream against the whole service, so thin
coverage here fails the run later rather than here. Chasing the number with
assertion-free tests defeats the purpose and is visible in review.

## Expect red first

Your tests will not pass when you write them — the implementation is not merged
yet. The gate here is that they **compile** (`mvn test-compile`), which proves
they were written against the frozen contract's real types rather than invented
ones. They run for the first time after the join, against code written by
someone who never saw them.
