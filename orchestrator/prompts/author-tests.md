# Node: author-tests

Write the tests that gate the implementation, **before it exists**.

Read `artifacts/openapi.yaml`, `artifacts/design.json`, and
`artifacts/requirement.json` for the acceptance criteria.

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
