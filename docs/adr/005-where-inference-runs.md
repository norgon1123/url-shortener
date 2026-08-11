# ADR-005: Where inference runs, and who pays for it

**Status:** accepted for the prototype; **the enterprise half is asserted, not
verified**
**Cited by:** `docs/OPERATIONS.md`

## Context

Every dollar figure in this repository comes from somewhere, and a buyer in a
regulated environment will ask three questions about that somewhere before they
ask anything about the pipeline: whose credential is it, where does the
inference physically happen, and what appears on the invoice.

Answering "it works on my laptop" is fine for a prototype and useless for the
decision the prototype exists to inform.

## Decision

**Today: a personal Claude Max subscription, and the figures are estimates.**

No API key is involved. `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` and the
Bedrock and Vertex switches are all unset. The only credential is
`~/.claude/.credentials.json`, an OAuth token with `subscriptionType: max`. The
Agent SDK spawns the bundled `claude` CLI, which authenticates exactly as an
interactive session does, so every node draws on the operator's subscription
quota.

Per-node cost comes from `ResultMessage.total_cost_usd`, which the CLI derives
from token counts priced at API list rates. **It is a good proxy for how much
model work a node did and a sound basis for comparing nodes against each other.
It is not an invoice, and nobody is billed it.**

**Intended: inference inside the organisation's own account** — Amazon Bedrock
or Claude Platform on AWS — where the spend is a metered line item against a cost
centre, the data path is one the organisation's own controls already cover, and
this estimate becomes an actual invoice.

## Consequences

**The budget guard is denominated in a currency nobody is spending.**
`max_cost_usd` is a real ceiling on how much model work a run may do, and it is
sized from measured runs — but it is not protecting a bill. The binding
constraint is the subscription's rate limits and quota, which the orchestrator
neither reads nor respects.

That is not theoretical. Both runs hit a session limit mid-node while the budget
guard sat at roughly 80% of its ceiling reporting plenty of headroom. The
orchestrator's model of "how much can we spend" and the provider's model of "how
much can you spend" are different models, and only one of them stops the run.

**The first encounter cost $7.42 to learn.** A session limit is deterministic —
it resets at a fixed hour — and the retry policy was treating it as weather:
three attempts, three identical failures. Provider quota exhaustion and turn
ceilings now abandon the retry budget rather than spending it, and the journal
records `retries_abandoned` with the reason and the attempts left unspent. The
second encounter, on `brownfield-1`, cost $0.91 and one attempt.

**This is the weakest claim in the repository and is flagged as such.** Agent
SDK support for Bedrock has not been verified in this project. Until it is, the
honest statement is the one above: a personal subscription, and an estimate.

## What would close it

1. Run one scenario end to end against Bedrock in a test account, and record the
   journal alongside the others. The manifest already carries everything needed
   to make that a configuration change rather than a code change.
2. Compare the estimated per-node figures against the actual Bedrock line items,
   and record the ratio. If they diverge, every cost number in this repository
   needs the correction factor stated next to it.
3. Make the budget guard aware of provider rate limits, or stop calling it a
   budget. A ceiling that reports headroom while the run is being throttled is
   measuring the wrong thing.
