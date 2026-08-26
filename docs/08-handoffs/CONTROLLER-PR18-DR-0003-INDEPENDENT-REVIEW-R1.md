# Controller Independent Review — PR #18 / DR-0003 V1 Baseline Reset

```yaml
review_id: CONTROLLER_PR18_DR0003_INDEPENDENT_REVIEW_R1
repository: Corwin-Code/marketops-platform
pull_request: 18
review_stage: DEVELOPMENT_BASELINE_RESET_PR_REVIEW
reviewed_at: 2026-08-26
reviewed_base: 52a657f7f6358f43246e03457ba2d48ef658986a
reviewed_head: d933bd91cd7396999776e157cb3cf9223d888c34
reviewed_head_tree: 83fd24cf57d75f1931e9c705f965552a8a2e6e60
tested_merge: f274882a9bb3d730a386ac16f88fe7de14ca18ef
tested_merge_tree: 83fd24cf57d75f1931e9c705f965552a8a2e6e60
tested_merge_parents:
  - 52a657f7f6358f43246e03457ba2d48ef658986a
  - d933bd91cd7396999776e157cb3cf9223d888c34
pr_state_reviewed: OPEN_DRAFT_CLEAN_UNMERGED
controller_verdict: CHANGES_REQUIRED
merge_authorization: NOT_GRANTED
production_enablement: NOT_AUTHORIZED
next_authorized_actor: CODEX
next_action: DR_0003_PR18_TARGETED_GOVERNANCE_REWORK_R1
```

## 1. Verdict

`CHANGES_REQUIRED`

PR #18 correctly implements the main direction of the V1 Development Baseline
Reset and preserves the existing repository, migrations, product source and
historical evidence. The change is strong enough to continue on the same Draft PR,
but it is **not yet safe to merge as the new governance source of truth**.

Four `MAJOR` findings remain. They are bounded governance defects rather than a
rejection of DR-0003 or the Vertical Slice strategy. They must be closed before an
`APPROVE_FOR_HUMAN_MERGE` verdict can be considered.

This review does not authorize Ready status, merge, deployment, Credential use,
real Marketplace write verification or production Capability enablement.

## 2. Exact Git and evidence identity

The review was performed against the actual PR rather than the Codex summary:

- Base: `52a657f7f6358f43246e03457ba2d48ef658986a`
- Head: `d933bd91cd7396999776e157cb3cf9223d888c34`
- Head tree: `83fd24cf57d75f1931e9c705f965552a8a2e6e60`
- GitHub tested merge:
  `f274882a9bb3d730a386ac16f88fe7de14ca18ef`
- Tested-merge tree equals the Head tree.
- Tested-merge parents are the exact Base and Head.
- PR state at review: `OPEN / DRAFT / CLEAN / UNMERGED`.
- One commit; 52 changed files; governance-only scope.
- Live `main` remained at the exact reviewed Base during the review.
- No review conversation or submitted approving review existed.

GitHub evidence on the exact Head:

| Workflow | Run | Jobs verified |
| --- | ---: | --- |
| Backend | `32922841032` | integration, build, architecture boundary — success |
| Governance | `32922840962` | governance/source/secret, production-readiness and validator tests — success |
| Frontend | `32922841092` | lint, tests/browser path, typecheck, build — success |
| Security | `32922841008` | dependency review, CodeQL Java, CodeQL TypeScript — success |

The four workflows contain 11 successful workflow jobs. GitHub/PR evidence reports
12 successful PR checks when the aggregate check is included.

The local `make verify` limitation caused by an unavailable Docker environment is
not a merge blocker for this governance-only PR because the exact Head passed the
GitHub backend integration job with a real PostgreSQL test environment. It remains
correctly disclosed rather than represented as a local pass.

## 3. What passed

### 3.1 Product-direction fidelity — PASS

The reset reflects the confirmed V1 direction:

- Production Vertical Slices are the primary delivery unit.
- Work Packages become implementation tranches rather than product phases.
- Claude receives continuous Detailed Design + Initial Full Implementation
  authority inside an approved Contract.
- GPT retains independent Deep Review and Final Gate authority.
- Codex performs in-scope rework without self-approval.
- AI is broad analysis/recommendation capability but not fact, approval, command
  or Credential authority.
- Ozon and Wildberries both remain in V1, with selective controlled execution.

### 3.2 Scope and preservation — PASS

The PR does not change backend, frontend, infrastructure runtime, fixtures,
Baseline v1.0, Naming Baseline, source checksums, legacy traceability,
V0001–V0010, or the prior WP designs/records/evidence.

The Phase 0 backlog is classified as historical provenance rather than deleted.
ADR-0003 preserves the full controlled-write chain while superseding only the old
version sequencing. ADR-0004 preserves Maker–Checker independence while making
the separate Design Gate conditional.

### 3.3 Validator and test direction — PASS WITH FINDINGS BELOW

The new validator introduces mutation-sensitive checks for the V1 state, Owner
decisions, Slice contract, write-disabled defaults, AI boundary, Capability
matrix, historical hashes and V1 traceability. Existing provenance is protected
by byte or deterministic tree hashes. The validator test inventory increases and
the exact Head passes CI.

Passing CI proves that the implemented validator matches the submitted contract.
It does not prove that every contract choice is internally consistent; the
findings below identify contradictions that the current validator presently
accepts or requires.

### 3.4 External-provider plausibility — INFORMATIONAL PASS

Current official Yandex documentation supports creating OIDC applications for
external web applications and configuring MFA policies. Therefore the default
Yandex Identity Hub direction is not an invented provider capability. Exact
tenant, user type, application, recovery-admin, access and MFA configuration
remain correctly assigned to external evidence and production acceptance; this
review does not claim that OQ-112 is complete.

## 4. Findings

### DR3-PR18-F01 — MAJOR  
### Two incompatible finding-severity vocabularies are active

**Evidence**

- `docs/00-governance/CONTROLLER_REVIEW_STANDARD.md` defines the exact finding
  vocabulary as:
  `BLOCKER / MAJOR / MINOR / INFORMATIONAL`.
- `docs/00-governance/QUALITY_GATES.md` uses `BLOCKER/MAJOR` as the merge-blocking
  boundary.
- `docs/05-testing/V1_PRODUCTION_ASSURANCE_MATRIX.md` instead declares findings
  as `CRITICAL / HIGH / MEDIUM / LOW / INFORMATIONAL`.
- The Assurance Matrix, Product Contract, Slice criterion `S1-AC-039`,
  Owner Git Workflow Guide and PR template use “no unresolved Critical/High”
  language.

**Why this changes the Gate**

The repository would have two canonical severity systems with no deterministic
mapping. A reviewer could label the same defect `HIGH` under one source and
`MAJOR` under another, while merge eligibility is expressed differently in
different files. That makes the exact blocking threshold ambiguous.

`delivery_risk: CRITICAL` may remain as a risk classification; the defect is the
use of `CRITICAL/HIGH/...` as a second finding taxonomy.

**Required observable correction**

Use one finding vocabulary everywhere:

```text
BLOCKER
MAJOR
MINOR
INFORMATIONAL
```

Replace merge/release conditions with “no unresolved BLOCKER/MAJOR finding”.
Update the Assurance Matrix, Product Contract, Slice Contract, Owner Git Workflow
Guide, PR template, validators and mutation tests. Add a test that rejects the
second finding taxonomy in canonical Gate language while still permitting a
separate `delivery_risk: CRITICAL` field.

---

### DR3-PR18-F02 — MAJOR  
### Canonical documents would remain permanently marked “pending merge” after the merge

**Evidence**

The proposed active baseline contains:

- DR-0003: `status: CONTROLLER_APPROVED_PENDING_REPOSITORY_EFFECT`
- Owner Decisions: `repository_effect: PENDING_DR_0003_MERGE`
- V1 Product Contract:
  `status: APPROVED_BY_DR_0003_PENDING_REPOSITORY_EFFECT`
- V1 Delivery Slices:
  `CONTRACT_APPROVED / PENDING_RESET_MERGE`

At the same time, the proposed `CURRENT_STATE.md` becomes
`EXECUTING_V1 / SLICE_CONTRACT_APPROVED / FULL_SCOPE_IMPLEMENTATION` immediately
when this same PR lands.

The validator explicitly requires some of the pending strings, so they would
remain stale by design after merge.

**Why this changes the Gate**

DR-0003 is intended to eliminate parallel and ambiguous authority. After merge,
the highest-precedence DR, Owner Decisions and Product Contract would still say
their repository effect is pending, while Current State would say they are
active. A future agent could reasonably interpret either side.

**Required observable correction**

Use durable state wording that is truthful both on the proposal branch and once
the file exists on protected `main`, for example:

```text
DR status:
ACCEPTED_EFFECTIVE_ON_PROTECTED_MAIN

Owner Decisions repository effect:
EFFECTIVE_WHEN_PRESENT_ON_PROTECTED_MAIN

V1 Product Contract status:
APPROVED_EFFECTIVE_ON_PROTECTED_MAIN

Slice roadmap state:
CONTRACT_APPROVED_EFFECTIVE_ON_PROTECTED_MAIN
```

Keep the explicit effective condition requiring independent Controller review and
Human Owner merge authorization. Remove active `PENDING_*_MERGE` wording from the
canonical V1 authority documents. Update validators and mutation tests so an
active `EXECUTING_V1` baseline rejects stale pending-merge metadata.

---

### DR3-PR18-F03 — MAJOR  
### The real-write proof required by Gate E has no prior bounded authorization path

**Evidence**

- `OD-V1-013` explicitly permits a strictly allowlisted real
  `Write → Readback → Restore/Compensate` verification during development and
  acceptance.
- Proposed `CURRENT_STATE.md` says Full-Scope Implementation does not authorize
  production platform writes.
- Slice criteria `S1-AC-031`, `S1-AC-032` and `S1-AC-033` require real Ozon and
  WB writes plus actual restore/compensate evidence.
- The Capability Matrix permits a write row to become `PASS` only after a real
  bounded write, Readback and restore/compensate.
- Gate E requires that same real proof before issuing
  `APPROVE_FOR_CONTROLLED_PRODUCTION_RELEASE`.
- The Assurance Matrix additionally says the Human Owner explicitly enables the
  exact scope as a Gate-E condition.

**Why this changes the Gate**

The current model creates an authorization cycle:

```text
real proof is required before Gate E approval
but
current implementation authority prohibits the real write needed to create proof
and
Gate E is the first documented write-authorization verdict
```

An implementation agent would eventually have to stop, infer that OD-V1-013 is a
standing operational authorization, or perform a real write before the exact
scope/time/risk envelope has been independently approved. None is acceptable.

**Required observable correction**

Add a separate pre-enablement Gate, recommended name:

```text
Gate EV — Bounded Real-Write Verification Authorization
```

Use exact verdicts such as:

```text
AUTHORIZE_BOUNDED_REAL_WRITE_VERIFICATION
CHANGES_REQUIRED
BLOCKED_BY_EXTERNAL_CAPABILITY
BLOCKED_EVIDENCE_INCOMPLETE
```

Gate EV must require, at minimum:

- explicit Human Owner authorization;
- exact Platform, opaque Account/Store reference, Capability and SKU allowlist;
- one-time/time-bounded verification window;
- maximum price delta and cumulative exposure;
- current official/account Capability evidence;
- current deterministic Guardrails and Dry Run;
- supervised operator and abort owner;
- global/scoped Kill Switch;
- captured pre-state;
- Readback and restore/compensate procedure;
- unknown-result/manual-resolution rule;
- complete audit and evidence-retention plan.

Gate EV authorizes only bounded evidence generation. It must not authorize
ongoing scheduling, general Pilot use or production release. Gate E then consumes
the resulting evidence and may approve controlled Pilot enablement.

Update Current State, Product Contract, Slice Contract, Quality Gates, Assurance
Matrix, AI Operating Model, Handoff Protocol, Capability Matrix, Open Questions,
traceability, validators and mutation tests so the two authorities cannot be
collapsed.

The default post-reset state should explicitly show:

```text
bounded_real_write_verification_authorization: NONE
production_write_enabled: false
```

---

### DR3-PR18-F04 — MAJOR  
### Full-Scope Implementation authority is not bound to the exact Slice Contract bytes

**Evidence**

- `AI_OPERATING_MODEL.md` requires major handoffs to carry the exact Contract
  path/hash.
- Proposed `CURRENT_STATE.md` records only:
  `active_slice_contract:
  docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md`
- It simultaneously grants:
  `authorization: FULL_SCOPE_IMPLEMENTATION`.
- No `active_slice_contract_sha256` or equivalent exact artifact identity is
  present.
- The validator checks the path and selected tokens, but it does not prove that
  the authorized Contract is byte-identical to the Controller-approved artifact.

**Why this changes the Gate**

The new process intentionally removes a routine Design Approval Gate and relies
instead on a strong front-loaded Contract. If implementation authority is attached
only to a mutable path, a later content change can alter scope, invariants or
acceptance without making the authorization visibly stale.

A protected PR is necessary but does not replace an explicit approval binding.
The handoff contract itself already promises a path/hash pair.

**Required observable correction**

After all R1 changes are complete:

1. compute the final SHA-256 of the active Slice Contract;
2. add it to Current State as
   `active_slice_contract_sha256: <final-sha256>`;
3. bind the Contract Gate/authorization text to that exact hash;
4. have the governance validator recompute the file SHA-256;
5. add mutation tests proving that:
   - changing the Contract without updating the hash fails;
   - changing only the hash fails;
   - a new Contract revision requires an independent Controller Contract
     re-review before `FULL_SCOPE_IMPLEMENTATION` is restored.

The final hash must be calculated after F01–F03 edits, not copied from the current
Head.

## 5. Live 11+1 review result

The currently effective `main` still requires the Controller 11+1 standard; the
candidate v2 dimensions were also applied.

| Review item | Result |
| --- | --- |
| Full repository/source/diff/test/CI cross-check | PASS |
| Stage target vs whole-product distinction | PASS |
| Full approved reset scope | CHANGES REQUIRED — F01–F04 |
| No hidden in-scope deferral | CHANGES REQUIRED — F03/F04 |
| No compromise implementation | PASS for runtime/product preservation |
| Owner decisions only for true authority choices | PASS |
| Functional/current documentation | CHANGES REQUIRED — F02 |
| Retire stale/parallel authority | CHANGES REQUIRED — F02 |
| Global compromise/comment/naming rules | PASS by exact-Head CI |
| Actionable design without micro-design | PASS |
| Standalone review and next-action artifacts | PASS with this R1 pair |
| +1 project-grade distinction | Recorded below |

## 6. Proposed v2 risk-dimension result

| Dimension | Result |
| --- | --- |
| Product outcome and scope | PASS |
| Source and authority | CHANGES REQUIRED — F03/F04 |
| Data and migration | PASS for governance-only reset |
| State and concurrency | CHANGES REQUIRED for write-authorization state — F03 |
| Security and privacy | PASS except unresolved real-write authority envelope — F03 |
| AI correctness | PASS at Contract level |
| Controlled execution | CHANGES REQUIRED — F01/F03 |
| User experience | PASS at Contract level; no runtime claim |
| Operations and recovery | PASS at Contract level; no runtime claim |
| Executable evidence | PASS for governance PR; semantic gaps are not yet tested |

## 7. Project-grade distinction

- **PR artifact quality:** strong, coherent and evidence-rich, but not merge-ready.
- **DR-0003 repository effect:** not yet accepted because four MAJOR findings
  remain.
- **SLICE-V1-001 Contract:** directionally approved by the earlier reset package,
  but its repository activation and Full-Scope Implementation authority remain
  pending this PR's successful re-review and merge.
- **SLICE-V1-001 completion:** not claimed.
- **V1 Product completion:** not claimed.
- **Production Capability enablement:** not authorized; all real writes remain
  disabled.

## 8. Required next action

Codex must rework the **same Draft PR #18 and the same branch**. It must not create
a replacement PR, mark Ready, merge, deploy, use Credentials or perform any real
Marketplace write.

The targeted rework must close F01–F04, add mutation-sensitive tests, update the PR
body to distinguish the original 44/44 package overlay from the later
Controller-authorized rework, rerun the full governance and CI evidence, and
return a new exact Base/Head/tree/tested-merge report.

```text
NEXT_AUTHORIZED_ACTOR: CODEX
NEXT_ACTION: DR_0003_PR18_TARGETED_GOVERNANCE_REWORK_R1
REQUESTED_NEXT_VERDICT: INDEPENDENT_DR_0003_RESET_PR_RE_REVIEW
MERGE_AUTHORIZATION: NOT_GRANTED
PRODUCTION_ENABLEMENT: NOT_AUTHORIZED
```
