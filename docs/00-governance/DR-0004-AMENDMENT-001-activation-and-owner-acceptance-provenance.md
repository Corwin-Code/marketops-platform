# DR-0004-AMENDMENT-001 — Activation Semantics and Owner-Acceptance Provenance

```yaml
document_type: contract_amendment
amendment_id: DR-0004-AMENDMENT-001
amends_contract: DR-0004
original_contract_sha256: dcc073bb8f6593bd24b4a74a96f06d0c45ece2f1c192615deb7301cbb850da9a
execution_envelope_sha256: 0dd73e8ed3e29a9903c991d5e723f40eb6a42d63841e6e952bf8f1292194f203
closure_snapshot_standard_sha256: 487379bc00badc37cd81bd82dec31621c25fbad2d56a7acd6f40cf2244d7ece1
product_scope_change: NONE
slice_scope_change: NONE
execution_envelope_expansion: NONE
effective_condition: EXACT_HUMAN_OWNER_ACCEPTANCE_EVIDENCE_AND_PROTECTED_MAIN
```

## 1. Defect being amended

The exact Human Owner accepted DR-0004 and its two normative policy artifacts were
created while still proposal artifacts. Their immutable bytes therefore contain:

```text
DR-0004:
status: PROPOSED_PENDING_EXACT_OWNER_ACCEPTANCE

Execution Envelope Policy:
status: PROPOSED_BY_DR_0004

Closure Snapshot Standard:
status: PROPOSED_BY_DR_0004
```

After exact Human Owner acceptance, these fields no longer describe current
acceptance/effect state. DR-0004 simultaneously requires accepted original
Contracts to remain byte-frozen, so editing those accepted bytes in place would
violate D4-02 and D4-AC-001.

The repository implementation also lacks a durable canonical evidence artifact
for the Human Owner acceptance event, while DR-0004's effective condition requires
that acceptance and chat is not the final source of truth.

## 2. Non-destructive amendment

The original DR-0004, Execution Envelope Policy and Closure Snapshot Standard
remain byte-identical at their accepted SHA-256 values.

Their proposal-state `status` fields are henceforth classified as
**proposal-time provenance only**, not the live repository-effect state.

The live acceptance/effect state is determined by this additive Amendment,
`CURRENT_STATE.md`, `SOURCE_MANIFEST.md` and the immutable Human Owner acceptance
evidence record required below.

## 3. Effective-state semantics

When this Amendment and the required Human Owner acceptance evidence are present
on protected `main` after independent Controller approval and Human Owner merge
authorization:

```text
DR-0004 acceptance:
HUMAN_OWNER_ACCEPTED

DR-0004 repository effect:
ACTIVE_ON_PROTECTED_MAIN

EXECUTION_ENVELOPE_V1:
ACTIVE_UNDER_DR_0004

CLOSURE_SNAPSHOT_V1:
ACTIVE_UNDER_DR_0004
```

No proposal-time `status` token in the frozen original artifacts may be used to
downgrade or contradict that effective state.

Before protected merge, the proposal branch is not active repository authority.

## 4. Required Human Owner acceptance evidence

Repository effect requires an immutable evidence artifact:

```text
docs/08-handoffs/OWNER-DR-0004-ACCEPTANCE-EVIDENCE.md
```

It must record, without Secrets/PII:

- repository and protected-main Base at acceptance;
- date/time of acceptance;
- Human Owner acceptance statement;
- exact accepted DR-0004 SHA-256;
- exact Execution Envelope Policy SHA-256;
- exact Closure Snapshot Standard SHA-256;
- exact accepted Amendment SHA-256;
- explicit statement that product scope, SLICE-V1-001, production, Gate EV and
  Gate E were not authorized;
- evidence provenance identifying the Human Owner interaction without treating
  chat history itself as ongoing normative authority.

The evidence file is factual acceptance provenance, not a new Product Contract.

## 5. Canonical repository reconciliation

The DR-0004 implementation must additionally:

1. reference this Amendment and the Owner acceptance evidence from
   `CURRENT_STATE.md` and `SOURCE_MANIFEST.md`;
2. update `DECISION_LOG.md` to index DR-0004 engineering execution/closure policy
   as an accepted decision/effective protocol once merged;
3. make the validator require:
   - the original three artifact hashes unchanged;
   - this Amendment exact hash;
   - the acceptance evidence file;
   - effective-state semantics that reject proposal-state metadata as live state;
4. preserve the active SLICE-V1-001 bytes/SHA and all DR-0004 non-goals.

## 6. No expansion

This Amendment does not change:

- V1 Product Contract;
- SLICE-V1-001 Contract or Acceptance;
- Claude/Codex/GPT responsibilities already accepted in DR-0004;
- Level-1/2/3 Execution Envelope permissions;
- Gate EV/Gate E;
- provider, deployment or production authority;
- runtime source or migrations.

It exists only to make activation and acceptance provenance internally consistent
without violating the original Contract-freeze rule.
