# Architecture Records

Accepted architecture decisions live under `adr/`. The active V1 architecture is
also described by:

- `V1_SHARED_SPINE.md`;
- `V1_AI_DATA_AND_EXECUTION_BOUNDARY.md`;
- the active Delivery Slice Contract.

Architecture records do not create a parallel product source. They implement
DR-0003, the V1 Product Contract, unchanged Baseline requirements and hard rules.

ADR status vocabulary:

```text
PROPOSED
ACCEPTED
SUPERSEDED_IN_PART
SUPERSEDED
REJECTED
DEPRECATED
```

When a newer ADR supersedes only rollout or governance clauses, the old ADR
remains historical evidence and its explicitly preserved safety controls remain
binding.
