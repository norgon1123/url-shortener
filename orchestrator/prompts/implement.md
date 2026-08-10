# Node: implement

Implement the frozen contract in `service/src/main/**`.

Read `artifacts/design.json`, `artifacts/openapi.yaml`, the skeletons the design
node left, and `artifacts/plan.json` for the task breakdown.

## The contract is fixed

You did not write it and you may not change it. Route paths, HTTP methods,
status codes, and DTO shapes are all checked against `openapi.yaml` after you
finish — a renamed path or a "better" status code fails the node. If the
contract is genuinely wrong, say so in your response and stop rather than
working around it; a human can revise it and re-run. `service/pom.xml` is not
yours to edit: if the work needs a dependency that is not there, that is the
same situation.

## You cannot write tests

`service/src/test/**` is denied to you, at the tool layer and again in the diff
check afterwards. This is not an oversight to route around.

While you work, another node is writing black-box HTTP tests against the same
contract, blind to your implementation. It will exercise every documented status
code, including the failure paths. Write as though those tests already exist,
because in a few minutes they will: handle the error cases, honour the
documented codes exactly, and validate input at the boundary.

## What "done" means here

The exit gates are: main sources compile, every write lands inside
`service/src/main/**`, and no credential-shaped strings appear in the diff. That
last one is not hypothetical — a connection string with an inline password is
the usual way this fails. Configuration belongs in `application.yml` as an
environment placeholder, and that file is protected, so changing it costs a
human approval.

Beyond the gates, the standard is code a reviewer would approve on a Tuesday
afternoon:

- Constructor injection, no field injection.
- Fail on invalid input at the edge, with the documented status code.
- Transaction boundaries at the service layer, not the controller.
- No `System.out`; use the logger, and do not log the whole request body.
- Comments explain *why*, never *what*. Match the density of the surrounding
  code.

On brownfield work, follow the conventions already in the repository even where
you would have chosen differently. A change that is stylistically foreign is
harder to review than one that is slightly worse and consistent.

`artifacts/impact.json` lists what already depends on the behaviour you are
changing, including the `behaviour_only` entries — files that do not change but
whose behaviour does, which is where this kind of change usually goes wrong.
Its `regression_surface` is the existing behaviour most likely to break; the
tests being written in parallel are aimed at it, so it is worth reading as a
list of things that must still work when you are done.
