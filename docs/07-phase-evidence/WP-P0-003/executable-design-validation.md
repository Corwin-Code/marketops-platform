# WP-P0-003 executable design validation evidence

```yaml
task: WP_P0_003_EXECUTABLE_DESIGN_VALIDATION_FINAL_TARGETED_REWORK
mode: BOUNDED_IMPLEMENTATION_FOR_DESIGN_VALIDATION
source_base: 9f7688204950c64b9f6bd8629daf90a115669864
reviewed_input_head: 6715b4d48ebbea7b3135455d0d5a587fed1e00d0
reviewed_input_tree: 5bf299242c3c35b905f378fbfd3b2012537afea3
verified_implementation_head: d620a8b9d951ded22698448244f73d82cbd899d3
verified_implementation_tree: c743415fb4967a990e1d7aa6311e9484fcb5655f
final_package_identity: LIVE_PR_16_METADATA_AND_BODY
prior_controller_verdict: TARGETED_REWORK
current_findings: WP3-EDV-F01, WP3-EDV-F02, WP3-EDV-F03, WP3-EDV-R01, WP3-EDV-RR02
environment: local workstation, Docker, Testcontainers, postgres:18.4
design_approved: false
implementation_complete: false
bounded_scope_quality: PRODUCTION_GRADE
project_production_complete: false
marketplace_outbound: NONE
production_write: DISABLED
```

## Finding-to-test matrix

| Finding | Required proof | Result |
| --- | --- | --- |
| `F01` | Revocation and Credential disable commit while grant waits; Job writer-first and grant-first orders; zero residue on deny | PASS — `TC-CTRL-F01-A/B/C1/C2` |
| `F02` | Job/endpoint validity; mandatory platform-pinned endpoint; unique Credential rotation leaf; caller Credential removed; `STORE_SET` denied; nonpositive nominal denied; exact evidence identities; request non-rebinding | PASS — `TC-CTRL-400/418/419/430…433`, `TC-PORT-001…004`, `TC-ARCH-023`, `F-ARCH-023/024`, `TC-CTRL-500/501` |
| `F03` | Checkpoint blocker crosses lease expiry; wrong owner and expired lease deny; no checkpoint mutation | PASS — `TC-CTRL-F03-A/B` |
| `R01` | One-statement platform/guard contract and source-comment functional scan | PASS — `TC-CTRL-302…304`, `TC-GLOBAL-002` |
| `RR02` | Separate reviewed input, tested implementation and final PR package identities | PASS — this package plus live PR #16 body |

## Backend verification

`./mvnw -B -ntp verify` completed successfully against PostgreSQL 18.4:

- 10 Flyway migrations validated and applied from an empty schema;
- 193 unit and architecture tests passed;
- 141 integration tests passed;
- `CallAuthorityExclusivityIT`: 15/15 passed;
- `IngestionAuthorityAndEvidenceIT`: 24/24 passed;
- `AuthorizedAcquisitionFlowIT`: 2/2 passed; and
- all JaCoCo coverage checks passed.

The command transcript is recorded in `backend-verify-run.txt`.

## Exact function and ACL surface

| Object | `PUBLIC` | `marketops_app` | Direct mutation posture |
| --- | --- | --- | --- |
| `platform.grant_call_authority(uuid,bigint,text,uuid,interval,text) RETURNS platform.call_authority_grant` | all revoked | `EXECUTE` | only grant transition/evidence writer; `SECURITY DEFINER`, fixed `search_path` |
| `ops.acknowledge_checkpoint(uuid,bigint,text,uuid,bigint,text) RETURNS bigint` | all revoked | `EXECUTE` | only checkpoint transition writer; `SECURITY DEFINER`, fixed `search_path` |
| `ops.ingestion_run` / `ops.ingestion_checkpoint` / `ops.authorization_decision_evidence` | none | `SELECT` | no direct application `INSERT`, `UPDATE` or `DELETE` |
| three Raw evidence tables | none | `SELECT`, `INSERT` | no application `UPDATE` or `DELETE` |

## Deterministic race records

| Test | Ordered events | Observed result |
| --- | --- | --- |
| `TC-CTRL-F01-A` | scope revocation reaches epoch write → grant starts and waits → writer commits → grant resumes | `MO012`; zero run transition/evidence |
| `TC-CTRL-F01-B` | Credential disable reaches epoch write → grant starts and waits → writer commits → grant resumes | `MO013`; zero run transition/evidence |
| `TC-CTRL-F01-C1` | Job mutation owns the Job row and commits → grant reads the committed row | `MO015`; zero run transition/evidence |
| `TC-CTRL-F01-C2` | grant locks Job → Job writer starts and blocks → grant completes/commits → writer lands | grant identities match pre-writer Job; writer is strictly later |
| `TC-CTRL-F03-A` | T1 locks checkpoint → T2 starts while lease is live, locks run and blocks → wall clock passes lease → T1 releases | `MO008`; position/version unchanged |
| `TC-CTRL-F03-B` | wrong owner, then already-expired lease | both `MO008`; position/version unchanged |

## Cross-checks

- `git diff origin/main -- V0001…V0006`: empty.
- `git diff --check`: pass.
- global compromise-retirement, functional-comment and production-naming rules:
  pass.
- governance validator and validator unit tests: pass.
- no secret/PII or real Marketplace outbound path was added.
- the application role has no direct write on run/checkpoint/decision evidence;
  Raw remains immutable to that role.

## Controller principles audit

| # | Assessment |
| --- | --- |
| 1 | PASS — governance, active Work Package, ADRs, migrations, Java boundaries, executable tests and live PR/CI facts were cross-checked. |
| 2 | PASS FOR BOUNDED SCOPE — every targeted finding has executable proof and no known release-blocking defect remains inside this authorization boundary. |
| 3 | PASS FOR BOUNDED SCOPE — authority and acknowledgement paths fail closed and are production-grade; the package does not claim the unimplemented whole-product runtime. |
| 4 | PASS — remaining Work Package/project allocations are listed in the addendum; none is silently represented as complete. |
| 5 | PASS — no fallback, validation-only substitute or weakened control remains in the repaired path. |
| 6 | PASS — the rework used fail-closed production decisions without requiring an Owner choice; no `BLOCKED_BY_OWNER_DECISION` item arose. |
| 7 | PASS — changed production comments describe functional behavior; `TC-GLOBAL-002` passes. |
| 8 | PASS — the bare-expiry grant, caller Credential selection, independently constructible request path and stage-named flow test were retired; no parallel authority path remains. |
| 9 | PASS — `TC-GLOBAL-001`, `TC-GLOBAL-002` and `TC-GLOBAL-003` all pass. |
| 10 | BOUNDED PRODUCTION-GRADE / PROJECT INCOMPLETE — this is executable design-validation authority, not the complete WP-P0-003 or MarketOps product. |
| 11 | YES — project-level production-readiness work exists and is enumerated in addendum section 5 with its allocated Work Package, open question or Gate. |

## Evidence limits

Executed evidence classes:

```text
STATIC_SOURCE_PROOF
UNIT_TEST
ARCHITECTURE_TEST
INTEGRATION_TEST
REAL_DATABASE
FAKE_CREDENTIAL_ZERO_OUTBOUND
PACKAGE_OR_PROVENANCE
CI_EXECUTION (final package Head is bound by live GitHub CI and PR body)
```

Not executed and not claimed:

```text
REAL_MARKETPLACE_CREDENTIAL
SECRET_RETRIEVAL
REAL_HTTP_MARKETPLACE
REAL_PROVIDER_OR_EXTERNAL_SYSTEM
PERFORMANCE_OR_LOAD
OWNER_VERIFIED_RESULT
DEPLOYMENT
```

## Branch convergence record

The removed local `docs/WP-P0-003-canonicalization` branch pointed to
`862bab71ee65753234fedfbddc727d0092569ff5`. Its tree was exactly
`f56515a28f003c19d2acb9440a61656a409eb02c`, the same as `main`, and its direct
tree diff against `main` was empty. The remote branch was already absent, so the
local stale reference was deleted without losing content.

The three Dependabot branches each back a separate open dependency-update PR.
They remain isolated from PR #16; this package neither merges nor deletes them.

## Boundary statement

PR #16 remains draft and unmerged. This evidence requests the independent
`CONTROLLER_WP_P0_003_IMPLEMENTATION_BACKED_DESIGN_VALIDATION_RE_REVIEW`; it is
not a Design approval, full implementation authorization, merge authorization,
deployment authorization or production-write authorization. `OQ-005`, `OQ-006`
and project-level production-readiness work remain open at their allocated Gates.
