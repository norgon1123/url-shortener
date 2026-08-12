# The graph, illustrated

Four views of the same 21-node pipeline, from "what runs" to "what stops it".
Everything here is generated from
[`orchestrator/pipelines/sdlc.yaml`](../orchestrator/pipelines/sdlc.yaml) — the
graph is declarative, so this page and the executable definition cannot drift
far without one of them being obviously wrong.

Legend, used in every diagram:

- 🟦 **agent** — a node that calls a model
- ⬛ **deterministic / barrier** — no model call at all
- 🟥 **handler** — invoked by a failure, never scheduled
- 🛑 **human gate** — the run stops here until a person decides
- ⚠️ **escalation** — a machine check that can only refer upward, never approve

---

## 1. The flow, end to end

```mermaid
flowchart TD
    intake[intake<br/><i>raw requirement → structured problem</i>]
    clarify[clarify<br/><i>find what the requirement does not say</i>]
    impact[impact-analysis<br/><i>blast radius in the existing code</i>]
    feas[feasibility<br/><i>can this be built as asked?</i>]
    decompose[decompose<br/><i>task DAG, every AC claimed</i>]
    design[design<br/><i>freeze the API contract</i>]
    contract[test-contract<br/><i>freeze the proof: test skeletons, no assertions</i>]

    impl[implement<br/><i>write src/main — cannot touch tests</i>]
    tests[author-tests<br/><i>write src/test — cannot touch main</i>]
    join([join<br/>barrier: merge both worktrees])
    verify[[verify<br/>build, suite, coverage, route diff<br/><b>no model call</b>]]
    triage{{triage<br/><i>whose failure is this?</i>}}

    docs[docs<br/><i>API reference, runbook</i>]
    rsec[review-security]
    rperf[review-performance]
    rapi[review-api-contract]
    rtest[review-test-adequacy]
    rclean[review-cleanliness]
    rjoin([review-join<br/>barrier])
    synth[review-synthesis<br/><i>fold 5 lenses, may rank, may not drop</i>]
    release[release-readiness<br/><i>re-derive the evidence, then refuse or sign</i>]

    intake --> clarify --> impact --> feas --> decompose --> design --> contract
    contract --> impl
    contract --> tests
    impl --> join
    tests --> join
    join --> verify
    verify -. on failure .-> triage
    triage -. repair .-> impl
    triage -. repair .-> tests
    verify --> docs & rsec & rperf & rapi & rtest & rclean
    docs & rsec & rperf & rapi & rtest & rclean --> rjoin --> synth --> release

    clarify -.->|⚠️ blocking ambiguity| H1{{🛑 human}}
    contract -->|🛑 four-eyes on the frozen contract| H2{{🛑 human}}
    synth -.->|⚠️ blocker finding| H3{{🛑 human}}
    release -->|🛑 release sign-off| H4{{🛑 human}}
    H1 -.-> impact
    H2 --> impl
    H3 -.-> release

    classDef agent fill:#dbeafe,stroke:#1e40af,color:#111
    classDef mech fill:#e5e7eb,stroke:#374151,color:#111
    classDef handler fill:#fee2e2,stroke:#b91c1c,color:#111
    classDef human fill:#fef3c7,stroke:#b45309,color:#111
    class intake,clarify,impact,feas,decompose,design,contract,impl,tests,docs,rsec,rperf,rapi,rtest,rclean,synth,release agent
    class join,verify,rjoin mech
    class triage handler
    class H1,H2,H3,H4 human
```

**Two fan-outs, both real concurrency.** `implement` and `author-tests` run in
separate git worktrees and are denied each other's paths; the six post-`verify`
nodes run concurrently and are merged by a barrier. Nothing special-cases
either — a node is ready when its dependencies have passed, and that one rule
produces both.

---

## 2. Where a human decides

Four places, and they are not the same kind of stop.

```mermaid
flowchart LR
    subgraph blocking["🛑 Hard gates — the run cannot pass without a person"]
        direction TB
        A["<b>test-contract</b><br/>four-eyes on the frozen contract<br/><i>the API and the proof, before any code</i>"]
        B["<b>release-readiness</b><br/>release sign-off<br/><i>reviewed the full diff</i>"]
    end

    subgraph cond["⚠️ Conditional — a machine check refers upward"]
        direction TB
        C["<b>clarify</b> → blocking ambiguity<br/><i>a question the run must not answer for you</i>"]
        D["<b>review-synthesis</b> → blocker finding<br/><i>advisory by design; can stop, cannot fail</i>"]
        E["<b>any node</b> → protected path written<br/><i>migrations, ADRs, CI config</i>"]
    end

    subgraph verbs["What a person can do"]
        direction TB
        F["<b>approve</b> — with a note, recorded"]
        G["<b>reject</b> — note becomes the node's next prompt"]
        H["<b>--answer id=text</b> — resolves a blocking question<br/>and reaches every later node"]
        I["<b>--answer route=node</b> — names the branch<br/>that repairs an adjudicated contract question"]
        J["<b>repair</b> — send work back on a green build"]
    end

    blocking --> verbs
    cond --> verbs

    classDef hard fill:#fef3c7,stroke:#b45309,color:#111
    classDef soft fill:#fff7ed,stroke:#c2410c,color:#111
    classDef act fill:#ecfdf5,stroke:#047857,color:#111
    class A,B hard
    class C,D,E soft
    class F,G,H,I,J act
```

> **The rule underneath all four:** the LLM never approves. It can satisfy a
> checkable predicate, or it can escalate — enforced when the pipeline *loads*,
> not by convention ([ADR-001](adr/001-the-llm-never-approves.md)).

---

## 3. What happens when something fails

```mermaid
flowchart TD
    run([node runs]) --> gate{exit gates}
    gate -->|pass| ok([checkpoint commit<br/>Run-Id / Node-Id / Attempt])
    gate -->|escalate| human[🛑 wait for a person]
    gate -->|fail| wall{is the failure<br/>a wall?}

    wall -->|"yes — quota, turn ceiling"| abandon["<b>retries abandoned</b><br/><i>a wall is not weather;<br/>attempts left unspent, node resumable</i>"]
    wall -->|no| budget{attempts left?}
    budget -->|yes| retry["<b>retry</b><br/><i>the failed gates are appended<br/>to the next prompt</i>"] --> run
    budget -->|no| onfail{on_failure}

    onfail -->|retry| fail([node fails → run fails])
    onfail -->|fallback| fb["<b>fallback</b><br/><i>one more attempt in propose mode:<br/>writes a diff, does not apply it</i>"] --> human
    onfail -->|rollback| rb["<b>rollback</b><br/><i>worktree reset to last good checkpoint</i>"] --> fail
    onfail -->|triage| tri{{"<b>triage</b><br/><i>classify each failure</i>"}}

    tri -->|implementation| ri[repair implement<br/><i>2 attempts</i>]
    tri -->|test| rt[repair author-tests<br/><i>1 attempt — it edits its own judge</i>]
    tri -->|mixed| both[repair both<br/><i>each gets its own itemised brief</i>]
    tri -->|contract / low confidence| human
    tri -->|budget exhausted| replan["<b>replan</b><br/><i>reset an upstream node<br/>and everything below it</i>"]
    replan -->|limit reached| stop([safe stop<br/><i>state and journal intact, resumable</i>])

    classDef good fill:#ecfdf5,stroke:#047857,color:#111
    classDef bad fill:#fee2e2,stroke:#b91c1c,color:#111
    classDef human fill:#fef3c7,stroke:#b45309,color:#111
    classDef act fill:#dbeafe,stroke:#1e40af,color:#111
    class ok,stop good
    class fail,abandon bad
    class human human
    class retry,fb,rb,ri,rt,both,replan act
```

**Why the asymmetry.** `implement` gets two repair attempts and `author-tests`
one, because repairing a test is the single case where an agent edits the thing
that judges it — and the obvious way to make a failing test pass is to delete it.
`tests_not_weakened` records its own baseline for the same reason
([ADR-003](adr/003-segregation-of-duties.md),
[ADR-006](adr/006-bounded-repair-with-human-routing.md)).

---

## 4. What each node does

| Node | Kind | In one line | Its own gate worth knowing |
|---|---|---|---|
| `intake` | agent | Turns a raw requirement into a structured problem with acceptance criteria | every AC is traceable to a sentence in the requirement |
| `clarify` | agent | Finds what the requirement does not say, and refuses to invent it | ⚠️ blocking ambiguities escalate; assumptions are required *even when there are none* |
| `impact-analysis` | agent | Blast radius against the existing codebase — modules, APIs, data flows | — |
| `feasibility` | agent | Can this be built as asked, with what is here? | — |
| `decompose` | agent | The task DAG | plan is a DAG; every acceptance criterion is claimed by a task |
| `design` | agent | Freezes the API contract | OpenAPI lints; the contract is content-hashed |
| `test-contract` | agent | Freezes *the proof*: test classes and method names, structure only | **no assertions** — deciding what counts as proof is the other branch's job; 🛑 four-eyes |
| `implement` | agent | Writes `src/main/**`; **denied** `src/test/**` | contract unchanged where it may not write; compiles; paths confined |
| `author-tests` | agent | Writes `src/test/**`; **denied** `src/main/**` | suite may not shrink, against a baseline the gate records itself |
| `join` | barrier | Merges both worktrees | ⚠️ a conflict escalates rather than being resolved by a model |
| `verify` | deterministic | Build, full suite, coverage floor, route-vs-contract diff. **No model call** | on failure → `triage` |
| `triage` | handler | Classifies each failure and routes it to the branch that owns it | contract questions and low confidence go to a person |
| `docs` | agent | API reference and runbook | links resolve; `fallback` on failure |
| `review-security` | agent | One of five independent lenses, each its own worktree and artifact | — |
| `review-performance` | agent | " | — |
| `review-api-contract` | agent | " | — |
| `review-test-adequacy` | agent | " | — |
| `review-cleanliness` | agent | " | — |
| `review-join` | barrier | Rejoins the five lenses | — |
| `review-synthesis` | agent | Folds five reviews into one brief. **Advisory: may rank, may not drop** | every lens finding is preserved, checked mechanically; ⚠️ blockers escalate |
| `release-readiness` | agent | Re-derives the evidence rather than trusting the journal, then refuses or signs | 🛑 release sign-off |

---

## 5. The same graph, as it actually ran

`greenfield-3`, compressed. The dotted paths are the ones a happy-path diagram
never shows, and they are most of what the pipeline is for.

```mermaid
flowchart LR
    A[intake → design] --> B[test-contract]
    B -->|🛑 approved| C[implement ∥ author-tests]
    C --> D([join]) --> E[[verify]]
    E -.->|23 failures<br/>7 classes| F{{triage}}
    F -.->|mixed verdict| C
    C --> D2([join]) --> E2[[verify]]
    E2 -.->|1 failure left| F2{{triage}}
    F2 -.->|contract question| G[🛑 human rules:<br/>the test over-asserts]
    G -.->|route=author-tests| H[author-tests repairs]
    H --> E3[[verify ✓ 148 tests]]
    E3 --> I[docs + 5 lenses] --> J[review-synthesis]
    J -.->|⚠️ blocker| K[🛑 human accepts the risk]
    K --> L[release-readiness]
    L -->|ready: false| M[🛑 human signs off anyway<br/>residuals recorded]

    classDef human fill:#fef3c7,stroke:#b45309,color:#111
    classDef mech fill:#e5e7eb,stroke:#374151,color:#111
    class G,K,M human
    class D,D2,E,E2,E3 mech
```

Full account: [`fixtures/runs/greenfield-3/README.md`](../orchestrator/fixtures/runs/greenfield-3/README.md).
