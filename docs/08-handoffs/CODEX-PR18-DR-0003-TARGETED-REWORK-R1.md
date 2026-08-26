# Codex Next-action Prompt — PR #18 / DR-0003 Targeted Governance Rework R1

```yaml
task_id: CODEX_DR_0003_PR18_TARGETED_GOVERNANCE_REWORK_R1
repository: Corwin-Code/marketops-platform
pull_request: 18
required_branch: docs/DR-0003-v1-baseline-reset
controller_reviewed_base: 52a657f7f6358f43246e03457ba2d48ef658986a
controller_reviewed_starting_head: d933bd91cd7396999776e157cb3cf9223d888c34
controller_reviewed_starting_tree: 83fd24cf57d75f1931e9c705f965552a8a2e6e60
controller_verdict: CHANGES_REQUIRED
authorization: TARGETED_GOVERNANCE_REWORK_ONLY
merge_authorization: NOT_GRANTED
production_enablement: NOT_AUTHORIZED
requested_next_verdict: INDEPENDENT_DR_0003_RESET_PR_RE_REVIEW
```

## 1. Role and non-negotiable boundary

Act as Codex Rework/Fix/Verify Agent and authoritative repository writer for this
bounded task. You may change every governance, validator and validator-test file
needed to close the four findings below coherently.

You may not:

- redefine the confirmed V1 product outcome;
- change backend, frontend product source, runtime infrastructure or fixtures;
- add/edit any migration or rewrite V0001–V0010;
- modify Baseline v1.0, Naming Baseline, source checksums, legacy traceability,
  prior WP records/designs/evidence or DR-0001/DR-0002;
- use a Secret, Credential, Buyer PII or unredacted production payload;
- call Ozon, Wildberries, Yandex or an AI provider;
- perform a real write-verification operation;
- mark the PR Ready, self-approve, merge, deploy or enable a Capability.

Keep PR #18 `OPEN / DRAFT / UNMERGED`.

## 2. Task-start verification

Before mutation:

1. read `CURRENT_STATE.md` from live `origin/main`;
2. read the full Controller R1 review and this prompt;
3. inspect the current PR, CI, conversations and branch;
4. verify:
   - `origin/main` is still
     `52a657f7f6358f43246e03457ba2d48ef658986a`;
   - the task branch starts at
     `d933bd91cd7396999776e157cb3cf9223d888c34`;
   - the starting tree is
     `83fd24cf57d75f1931e9c705f965552a8a2e6e60`;
   - no unowned local change exists.

If Base/Head moved, preserve all work and stop with the exact divergence. Do not
silently rebase or reinterpret the Controller findings.

## 3. Finding closure contract

### F01 — one finding-severity vocabulary

Make the following the only canonical Controller finding vocabulary:

```text
BLOCKER
MAJOR
MINOR
INFORMATIONAL
```

Apply it consistently to:

- Controller Review Standard;
- Quality Gates and Gate R/F/E/EV conditions;
- Production Assurance Matrix;
- V1 Product Contract;
- SLICE-V1-001, including `S1-AC-039`;
- Owner Git Workflow Guide;
- PR template;
- any other canonical current document;
- governance validators and mutation tests.

`delivery_risk: CRITICAL` may remain because it classifies Slice risk rather than
a Controller finding.

Add a mutation-sensitive test that fails when canonical finding/merge/release
language reintroduces `CRITICAL/HIGH/MEDIUM/LOW` as a second finding taxonomy.

### F02 — remove permanently pending post-merge authority

Replace active pending-merge metadata with durable conditional semantics. Use
one coherent vocabulary across the repository. Recommended exact values:

```text
DR-0003:
status: ACCEPTED_EFFECTIVE_ON_PROTECTED_MAIN

OWNER_DECISIONS_V1:
repository_effect: EFFECTIVE_WHEN_PRESENT_ON_PROTECTED_MAIN

V1_PRODUCT_CONTRACT:
status: APPROVED_EFFECTIVE_ON_PROTECTED_MAIN

V1_DELIVERY_SLICES Slice 1 state:
CONTRACT_APPROVED_EFFECTIVE_ON_PROTECTED_MAIN
```

Keep the effective condition that independent Controller review and Human Owner
merge authorization are required. Do not claim that this Draft PR is already live
on `main`.

Update validators/tests to:

- accept the durable values;
- reject active `PENDING_DR_0003_MERGE`,
  `PENDING_REPOSITORY_EFFECT` and `PENDING_RESET_MERGE` metadata;
- preserve those terms only where they are quoted as historical evidence, if any;
- prove that `EXECUTING_V1` cannot coexist with a canonical pending-merge state.

### F03 — add a bounded real-write verification Gate before Gate E

Create an explicit authority distinct from implementation, merge and controlled
production enablement:

```text
Gate EV — Bounded Real-Write Verification Authorization
```

Required verdict vocabulary:

```text
AUTHORIZE_BOUNDED_REAL_WRITE_VERIFICATION
CHANGES_REQUIRED
BLOCKED_BY_EXTERNAL_CAPABILITY
BLOCKED_EVIDENCE_INCOMPLETE
```

Gate EV must require:

- explicit Human Owner authorization;
- exact Platform, opaque Account/Store reference, Capability and SKU allowlist;
- one-time or time-bounded verification window;
- maximum price delta and cumulative exposure;
- current official and real-account capability evidence;
- current deterministic Guardrails and Dry Run;
- supervised operator, abort owner and manual stop;
- global/scoped Kill Switch;
- captured prior state;
- Readback and Restore/Compensate procedure;
- unknown-result/manual-resolution behavior;
- complete Audit and durable redacted evidence plan.

Gate EV authorizes only bounded evidence generation. It does not authorize:

- general Pilot enablement;
- unattended recurring execution;
- a broad Policy;
- production release;
- any scope not named in the authorization.

Gate E must consume Gate-EV evidence and remain the only Gate that can approve
ongoing controlled Pilot production release.

Update coherently, as applicable:

- `CURRENT_STATE.md`;
- `QUALITY_GATES.md`;
- `AI_OPERATING_MODEL.md`;
- `HANDOFF_PROTOCOL.md`;
- `OWNER_GIT_WORKFLOW_GUIDE.md`;
- `V1_PRODUCT_CONTRACT.md`;
- `SLICE-V1-001...md`;
- `V1_CAPABILITY_MATRIX.md`;
- `V1_PRODUCTION_ASSURANCE_MATRIX.md`;
- `OPEN_QUESTIONS.md`;
- `v1-traceability.csv`;
- agent instructions/templates where they state write authority;
- governance validators and tests.

The default post-reset state must include exact fail-closed fields such as:

```yaml
bounded_real_write_verification_authorization: NONE
bounded_real_write_verification_gate: REQUIRED_BEFORE_FIRST_REAL_WRITE
production_write_enabled: false
ozon_price_write: DISABLED_PENDING_VERIFIED_CAPABILITY_AND_RELEASE_GATE
wildberries_price_write: DISABLED_PENDING_VERIFIED_CAPABILITY_AND_RELEASE_GATE
```

Add tests proving that Full-Scope Implementation, merge and Gate EV cannot be
mistaken for controlled production enablement.

### F04 — bind implementation authority to the exact Slice Contract hash

After all content changes to the Slice Contract are complete:

1. calculate its final SHA-256;
2. add to Current State:

   ```yaml
   active_slice_contract_sha256: <final-sha256>
   ```

3. state that `SLICE_CONTRACT_APPROVED` and `FULL_SCOPE_IMPLEMENTATION` are bound
   to that exact path/hash;
4. make `scripts/validate_governance.py` recompute the SHA-256;
5. add mutation tests proving:
   - Contract bytes changed while hash remains old → fail;
   - hash changed while Contract bytes remain old → fail;
   - authorization cannot remain Full-Scope Implementation when the exact
     approved Contract identity is missing or mismatched.

Do not use the starting-Head hash. F01–F03 change the Contract, so calculate the
final value only after those edits.

## 4. Controller artifact recording

Add these Controller-supplied files byte-for-byte to `docs/08-handoffs/` using the
exact files delivered with this prompt:

```text
CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md
CODEX-PR18-DR-0003-TARGETED-REWORK-R1.md
DR-0003-PR18-R1-ARTIFACT-HASHES.md
```

Preserve the original DR-0003 package Controller artifacts; they remain historical
authority for the initial execution and must not be rewritten.

Extend the governance validator only as needed to bind the new R1 artifact hashes
without weakening the old bindings.

## 5. PR body truthfulness after rework

The current PR body may retain this historical fact:

```text
The original Controller package overlay matched 44/44 files at starting Head
d933bd91cd7396999776e157cb3cf9223d888c34.
```

After R1 rework, it must not claim that the new Head remains byte-identical to all
44 original overlay files. Add a separate Controller-authorized rework section
with:

- R1 review artifact/hash;
- F01–F04 closure matrix;
- starting Head and new Head/tree;
- exact changed-file delta for R1;
- updated test count;
- local and GitHub evidence;
- checks not run;
- confirmation that no product source/migration/provider/production behavior
  changed;
- requested next verdict.

## 6. File scope

Reuse the same branch and PR. Change only files necessary to close F01–F04,
validator/tests, PR body and the three new handoff artifacts.

Protected content remains byte-identical:

```text
backend/**
frontend/**
infra/**
fixtures/**
docs/01-requirements/baseline-v1.0-cn.md
docs/01-requirements/naming-baseline-cn.md
docs/01-requirements/SHA256SUMS.txt
docs/01-requirements/traceability.csv
backend/marketops-server/src/main/resources/db/migration/V0001...V0010
docs/02-architecture/designs/**
docs/03-work-items/WP-P0-001*
docs/03-work-items/WP-P0-002*
docs/03-work-items/WP-P0-003*
docs/07-phase-evidence/WP-P0-001/**
docs/07-phase-evidence/WP-P0-002/**
docs/07-phase-evidence/WP-P0-003/**
docs/00-governance/DR-0001*
docs/00-governance/DR-0002*
```

Do not delete or weaken an existing validator/test merely because the R1 contract
requires a different current-state value. Preserve old provenance through exact
fixtures/hashes and add the new behavior beside it.

## 7. Required verification

Run and report exact results:

```bash
python3 scripts/validate_governance.py
python3 scripts/validate_production_readiness.py
python3 -m unittest discover -s tests -p 'test_*.py'
make governance
make frontend-check
git diff --check
```

Also prove:

- exact Base remains unchanged;
- V0001–V0010 inventory and bytes remain unchanged; no V0011 is added;
- backend/frontend/infra/fixtures and protected docs remain unchanged;
- source Baseline and Naming checksums pass;
- no Secret/PII/production payload appears;
- the final Slice Contract SHA-256 equals Current State;
- all required GitHub CI/checks pass on the new Head;
- review conversations remain resolved.

A missing local Docker environment may be honestly reported. Do not weaken tests
or provision production services to mask it; GitHub integration CI remains the
required external evidence for this governance-only rework.

## 8. Git execution

Use the existing branch and Draft PR:

```text
branch: docs/DR-0003-v1-baseline-reset
PR: #18
```

Create one or more focused R1 commits, push only the task branch and wait for CI.
Do not mark Ready and do not merge.

## 9. Required return

Return a standalone execution report containing:

```text
Repository / Base
Starting Head/tree
New Head/tree
GitHub tested merge/tree/parents
Commits
Changed files
F01–F04 closure matrix
Final Slice Contract SHA-256
Validator/test count before and after
Exact local commands/results
Exact GitHub checks/results
Protected-path proof
Secret/PII/provider/write proof
PR state and unresolved conversations
Checks not run and why
MERGE_AUTHORIZATION: NOT_GRANTED_BY_CODEX
PRODUCTION_ENABLEMENT: NOT_AUTHORIZED
NEXT_AUTHORIZED_ACTOR: GPT-5.6 Sol Pro Controller
NEXT_ACTION: INDEPENDENT_DR_0003_RESET_PR_RE_REVIEW
```
