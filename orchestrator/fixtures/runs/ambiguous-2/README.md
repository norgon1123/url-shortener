# `ambiguous-2`

The second half of a differential pair. The write-up for both runs — what was
asked, how the two answer sets differed, and the defect the comparison exposed —
is in [`../ambiguous-1/README.md`](../ambiguous-1/README.md).

```bash
python -m sdlc.cli replay orchestrator/fixtures/runs/ambiguous-2
python -m sdlc.cli verify ambiguous-2      # 52 entries
```

`plan.json` here is the 21-task plan produced *after* `f4258e4`, when the
human's answers finally reached the nodes. The journal contains both attempts:
seq 28-33 is `decompose` working from the model's own proposal, seq 45-50 is the
same node with the same answers on file, after the fix.
