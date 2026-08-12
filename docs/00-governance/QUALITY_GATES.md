# Quality Gates

## G0 — Repository & Collaboration Foundation

All items must pass before product implementation begins:

- private repository created and source files committed;
- `main` protected by a Ruleset;
- Pull Request required for changes;
- required status check `governance` passes;
- force push and branch deletion disabled;
- issue/PR templates available;
- ChatGPT and Claude Project instructions installed;
- no secret or production PII in repository or AI knowledge base;
- WP-P0-001 design reviewed and verdict is `APPROVED_FOR_IMPLEMENTATION`.

## Design Gate

The Controller checks:

- Requirement IDs and non-goals are explicit;
- architecture respects accepted ADRs and Baseline hard rules;
- external technology/platform facts are verified with current primary sources;
- data model, state transitions and failure modes are explicit;
- idempotency, replay, freshness and unknown states are addressed;
- security, permissions, privacy and secret handling are addressed;
- test, observability, migration and rollback plans are concrete;
- open questions are not hidden behind implementation assumptions.

Verdicts:

```text
APPROVED_FOR_IMPLEMENTATION
CHANGES_REQUIRED
BLOCKED_BY_OWNER_DECISION
BLOCKED_BY_EXTERNAL_CAPABILITY
```

## Pull Request Gate

Required evidence is determined by the Work Package. Applicable checks include:

- backend build and unit tests;
- PostgreSQL integration and Flyway validation;
- architecture boundary tests;
- frontend lint, type check, tests and build;
- contract, replay and reconciliation tests;
- secret scanning, dependency review and SAST when configured;
- documentation and traceability update;
- migration/backfill/rollback notes;
- logs, metrics and runbook changes;
- no unresolved BLOCKER or MAJOR finding.

Finding severity:

```text
BLOCKER       unsafe to merge; scope/security/data correctness/irreversible risk
MAJOR         required behavior or evidence missing; must fix before merge
MINOR         should fix in current PR unless explicitly deferred and recorded
INFORMATIONAL optional improvement or observation
```

PR verdicts:

```text
APPROVE_FOR_HUMAN_MERGE
CHANGES_REQUIRED
REJECTED_SCOPE_VIOLATION
BLOCKED_EVIDENCE_INCOMPLETE
```

## Phase 0 Gate

Phase 0 acceptance follows the Baseline, including:

- all Marketplace Account, Store, Warehouse and Owner records registered;
- Secret absent from Git/log/frontend/plain configuration;
- Variant mapping at agreed threshold with exception queue;
- historical data manifest and hashes;
- Raw → Core traceability;
- replay without duplication;
- Daily Report by platform and SKU for order, stock, return and cost state;
- Data Quality visibility for freshness, failures and gaps;
- rate limit, retry, alert and manual recovery verified;
- business Key User review on real or approved redacted data.
