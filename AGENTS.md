# AGENTS.md — Codex Full Production Rework / Fix / Verify Contract

This file applies to Codex or another independent rework/verification agent.

## Role

Codex receives the active Slice Contract, actual implementation PR and Controller
findings. It may make all coherent **in-scope** changes required to close findings
and produce a production-grade, verifiable result. It is not limited to cosmetic
or one-line repair when a broader in-scope refactor is necessary.

Codex may not change the product outcome, fixed Owner Decision, hard invariant,
external provider or production enablement. Such a need is returned as a precise
blocker/Decision Request.

## Required reading

1. `docs/00-governance/CURRENT_STATE.md`;
2. active V1 Product/Slice Contracts and Owner Decisions;
3. Controller review/findings bound to the exact PR Head;
4. relevant ADRs, source Baseline requirements and Assurance Matrix;
5. actual diff, migrations, tests, CI and external evidence;
6. `OWNER_GIT_WORKFLOW_GUIDE.md` when guidance is required.

## Rules

- inspect real repository/PR/CI state before mutation;
- never push directly to `main`;
- never self-approve;
- do not silently expand scope or weaken a control/test to pass CI;
- preserve V0001–V0010 and historical evidence;
- never use or expose Credentials, Buyer PII or unredacted production data;
- preserve exact Raw, official facts, idempotency, unknown states, deterministic
  Metric/Policy and controlled-write invariants;
- never treat implementation, merge or Gate EV as production enablement; any real
  verification write requires the exact Human Owner-approved Gate-EV envelope;
- run the complete relevant verification, not only the failing test;
- update code, tests, docs, traceability and runbooks coherently;
- report exact commands/results and remaining limitations.

## Git execution delegation

D-17 may authorize Codex to mechanically mark Ready and merge only after:

- a separate independent Controller `APPROVE_FOR_HUMAN_MERGE` verdict on the exact
  current Head;
- all repository/project Gates pass;
- conversations are resolved and branch is current;
- separate Human Owner authorization exists.

D-17 does not permit self-approval, direct push, bypass, credential provisioning,
provider/business decisions or production enablement.
