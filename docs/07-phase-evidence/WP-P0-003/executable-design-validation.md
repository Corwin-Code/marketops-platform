# WP-P0-003 executable design validation evidence

```yaml
task: CODEX_WP_P0_003_CALL_AUTHORITY_ENVELOPE_PROVENANCE_FINAL_TARGETED_REWORK_PR16
mode: BOUNDED_IMPLEMENTATION_FOR_DESIGN_VALIDATION
source_base: 9f7688204950c64b9f6bd8629daf90a115669864
reviewed_input_head: 85e901e6d7086b9fb1620d7a7d2c1257f9d0a25c
reviewed_input_tree: 70429664de61e86a305d7923da23e9e303839d86
verified_implementation_head: fc47e018d86902566a1219a6c4cbf84429c4d035
verified_implementation_tree: e2e5888cf4783bd97e2ce0388bd9b46c77a9fc92
final_package_identity: LIVE_PR_16_METADATA_AND_BODY
prior_controller_verdict: TARGETED_REWORK
targeted_findings: WP3-EDV-F02-R2A, WP3-EDV-F02-R2B, WP3-EDV-F02-R2C
preserved_closed_findings: WP3-EDV-F01, WP3-EDV-F02-R1A, WP3-EDV-F02-R1B, WP3-EDV-F02-R1C, WP3-EDV-F03, WP3-EDV-R01, WP3-EDV-RR02
environment: local workstation, Docker, Testcontainers, postgres:18.4
design_approved: false
targeted_rework_status: IMPLEMENTED_AWAITING_CONTROLLER
bounded_scope_quality: PRODUCTION_GRADE
project_production_complete: false
marketplace_outbound: NONE
production_write: DISABLED
```

## Package identity and manifest

| Stage | Commit | Tree | Authority |
| --- | --- | --- | --- |
| Base | `9f7688204950c64b9f6bd8629daf90a115669864` | resolved by GitHub repository | PR base |
| Controller-reviewed starting Head | `85e901e6d7086b9fb1620d7a7d2c1257f9d0a25c` | `70429664de61e86a305d7923da23e9e303839d86` | Controller ruling and live PR state |
| Verified implementation | `fc47e018d86902566a1219a6c4cbf84429c4d035` | `e2e5888cf4783bd97e2ce0388bd9b46c77a9fc92` | local Git object and complete verification |
| Final evidence package | live PR #16 Head | live PR #16 tree | PR metadata/body after evidence-only commit |
| Tested merge | live PR #16 test merge | live merge tree and parents | GitHub API after final CI |

The starting-to-final delta is exactly one implementation/test commit followed
by one evidence-only commit. The evidence commit cannot self-record its own SHA;
the immutable final identities and workflow/job counts are bound in the live PR
body after push and CI.

## Finding-to-test matrix

| Finding | Required proof | Result |
| --- | --- | --- |
| `F02-R2A` | 30-second server maximum, exact lease cap, immutable evidence checks, invalid/overflow requests fail with no residue | PASS — `TC-CTRL-439…442` |
| `F02-R2B` | exact production gateway owns query/map/execute; no controller path; single sequential/concurrent use; real gateway in semantic flow; synthetic ResultSet denied | PASS — `TC-ARCH-026…029`, `F-ARCH-026…028`, `TC-PORT-005/006`, `TC-CTRL-500/501` |
| `F02-R2C` | cross-account replacement refused by FK; same-account historical lineage and current-leaf selection remain valid | PASS — `TC-CTRL-443/444` |
| Preserved `R1A` | one fixed database instant and two 201-point temporal samples | PASS — `TC-CTRL-434/435` |
| Preserved `R1B` | attached/disconnected cycles, linear success, multiple leaves and `STORE_SET` refusal | PASS — `TC-CTRL-430/432/436…438` |
| Preserved `R1C` | exact inside/outside direct-call, grant-constructor and request-factory fixtures | PASS — `TC-ARCH-021/023/024` and sensitivity fixtures |
| Preserved `F01` | writer-first/grant-first serialization and zero-residue denial | PASS — `TC-CTRL-F01-A/B/C1/C2`, `TC-CTRL-420…422` |
| Preserved `F03` | final checkpoint CAS after blocking and lease expiry | PASS — `TC-CTRL-F03-A/B`, `TC-CTRL-406…409/427/428` |
| Preserved `R01` | functional migration contract and source-comment scan | PASS — `TC-CTRL-302…304`, `TC-GLOBAL-002` |
| Preserved `RR02` | separate starting, implementation, final-package and tested-merge identities | PASS — evidence package plus live PR #16 metadata/body |

## Complete backend verification

`./mvnw -B -ntp verify` completed successfully on the verified implementation
against PostgreSQL 18.4:

- 146 production Java source files compiled;
- 109 test source files compiled;
- 10 Flyway migrations validated and applied from an empty schema;
- the interrupted-migration recovery path also applied all 10 migrations;
- 207 unit and architecture tests passed;
- 150 integration tests passed;
- 357 total tests passed with zero failures, errors or skips;
- `IngestionAuthorityArchitectureTest`: 20/20 passed;
- `CallAuthoritySingleUseTest`: 6/6 passed;
- `JdbcAuthorizedAcquisitionGatewayTest`: 1/1 passed;
- `CallAuthorityExclusivityIT`: 15/15 passed;
- `IngestionAuthorityAndEvidenceIT`: 34/34 passed;
- `AuthorizedAcquisitionFlowIT`: 1/1 passed; and
- all JaCoCo coverage checks passed.

The run completed in 57.253 seconds at
`2026-08-25T13:24:00+08:00`. The complete-command summary is recorded in
`backend-verify-run.txt`.

## Focused R2 and preserved-race verification

The focused command selected 27 unit/architecture tests and 60 integration
tests. All 87 passed with no failure, error or skip:

- architecture rules: 20;
- sequential/concurrent grant semantics: 6;
- JDBC gateway semantic unit test: 1;
- Flyway installation/recovery: 10;
- real gateway/database flow: 1;
- authority and evidence database cases: 34; and
- preserved F01/F03 concurrency cases: 15.

The focused run completed in 30.624 seconds at
`2026-08-25T13:25:33+08:00`. Exact command and observations are recorded in
`f02-final-targeted-tests-run.txt`.

## R2A authority-envelope observations

The server-owned formula is:

```sql
authority_at := LEAST(
    grant_at + p_requested_authority,
    grant_at + interval '30 seconds',
    run_row.lease_expires_at,
    evaluation.valid_until
);
```

| Scenario | Result |
| --- | --- |
| request 1 day, lease 5 minutes, distant control boundary | exact expiry equals server-policy deadline at grant + 30 seconds |
| request 30 seconds, lease deadline 5 seconds away | exact expiry equals recorded run lease deadline |
| direct forged evidence with authority beyond recorded lease | database CHECK violation |
| `NULL`, zero or negative interval | `MO016`; run remains `LEASED`, sequence unchanged, evidence 0 |
| interval causing timestamp overflow | `MO016`; run remains `LEASED`, sequence unchanged, evidence 0 |

The grant result and immutable evidence record `run_lease_expires_at` and
`server_policy_deadline`. Database constraints prove authority is not later
than either. The guarded run update also requires the locked lease deadline to
remain unchanged.

## R2B gateway, provenance and one-shot observations

The only production query text is owned by
`JdbcAuthorizedAcquisitionGateway`:

```sql
SELECT * FROM platform.grant_call_authority(
    ?, ?, ?, ?, CAST(? AS interval), ?)
```

The gateway requires exactly one row, maps every named result column, closes all
JDBC resources, immediately invokes the sole executor and returns only
`AcquisitionResult`. It does not expose the grant, mapper or executor and adds
no network client.

| Protected seam | Enforced owner / negative proof |
| --- | --- |
| mapper call | exact gateway only; synthetic/literal ResultSet caller rejected |
| executor call | exact gateway only; second internal caller and controller-via-executor rejected |
| grant construction | exact mapper only; inside/outside constructor mutation fixtures rejected |
| request factory | exact executor only; second request-factory caller rejected |
| acquisition port invocation | exact executor only; direct interface and concrete-adapter calls rejected |
| web authority reachability | no `RestController` dependency on gateway/executor/mapper/grant/request/port |

`TC-PORT-005` executes the same valid grant twice sequentially and observes one
fake-port call. `TC-PORT-006` races two threads on the same grant and observes
exactly one fake-port call. The loser is rejected by atomic consumption before
the port. `TC-CTRL-500` uses the real gateway and database function; it does
not manually assemble a grant. `TC-CTRL-501` proves the gateway refuses an
expired mapped envelope before port invocation and closes JDBC resources.

## R2C credential-lineage observations

V0010 installs:

```sql
FOREIGN KEY (replaces_credential_id, marketplace_account_id)
REFERENCES platform.credential_metadata (id, marketplace_account_id)
```

| Scenario | Result |
| --- | --- |
| Account A credential names Account B predecessor | composite FK violation before grant; run remains `LEASED`, sequence 0, evidence 0 |
| same-account historical predecessor | allowed; current active leaf selected by the preserved R1B resolver |

`git diff origin/main -- V0001…V0006` is empty. The V0010 SHA-256 is
`a3b8ca08b796c1d211f17a042a8ec546cd0009d4457e2d68dcd930dfc36a13d9`.

## Exact function and ACL surface

| Object | `PUBLIC` | `marketops_app` | Authority posture |
| --- | --- | --- | --- |
| `platform.grant_call_authority(uuid,bigint,text,uuid,interval,text)` | all revoked | `EXECUTE` | only call-authority transition/evidence writer; `SECURITY DEFINER`, fixed `search_path` |
| `platform.evaluate_call_control_facts(uuid,uuid,timestamptz)` | all revoked | none | private resolver; no run transition, evidence or authority return |
| `ops.acknowledge_checkpoint(uuid,bigint,text,uuid,bigint,text)` | all revoked | `EXECUTE` | only checkpoint transition writer; `SECURITY DEFINER`, fixed `search_path` |
| run/checkpoint/decision evidence tables | none | `SELECT` | no direct app `INSERT`, `UPDATE` or `DELETE` |
| three Raw evidence tables | none | `SELECT`, `INSERT` | no app `UPDATE` or `DELETE` |

## Repository cross-checks

- `git diff --check`: pass.
- `python3 scripts/validate_governance.py`: pass.
- validator unit suite: 243/243 pass.
- `TC-GLOBAL-001` Compromise Retirement Check: pass.
- `TC-GLOBAL-002` Functional JavaDoc Rewrite Check: pass.
- `TC-GLOBAL-003` Production Naming Check: pass.
- V0001–V0006 are byte-identical to `origin/main`.
- no secret/PII, credential retrieval, real Marketplace outbound or Provider
  simulation was added.
- no Dependabot change is mixed into PR #16.

## Controller principles audit

| # | Assessment |
| --- | --- |
| 1 | PASS — governance, active WP, ADRs/DR, Controller ruling, V0010, Java boundary, tests and live PR/CI facts were cross-checked. |
| 2 | PASS FOR TARGETED SCOPE — R2A/R2B production blockers and R2C major lineage defect have deterministic executable proof; independent Controller acceptance is still required. |
| 3 | PASS FOR TARGETED SCOPE — database enforcement, immutable evidence, exact gateway ownership, concurrency semantics and composite identity are production implementations, not a minimum slice. |
| 4 | PASS — remaining work-package/project Deferred Items are enumerated in the addendum and are not represented as delivered. |
| 5 | PASS — no caller-controlled widening, mapper-only provenance claim, replayable grant, cross-account FK gap or fallback path remains in the repaired boundary. |
| 6 | PASS — production decisions follow the ruling and accepted ADRs; no Owner decision was required. |
| 7 | PASS — changed production comments describe current functional capability only; `TC-GLOBAL-002` passes. |
| 8 | PASS — superseded public grant/executor types and manual test grant assembly were removed; no parallel old authority path remains. |
| 9 | PASS — Compromise Retirement, Functional JavaDoc Rewrite and Production Naming checks all pass. |
| 10 | BOUNDED PRODUCTION-GRADE / PROJECT INCOMPLETE — the repaired authority boundary is production-grade; WP-P0-003 and MarketOps are not project-level production-complete. |
| 11 | YES — project-level deferred/readiness work exists and is explicitly allocated in addendum section 6. |

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
```

`CI_EXECUTION` is bound only after the final package is pushed and the exact
GitHub workflow/job set completes.

Not executed and not claimed:

```text
REAL_MARKETPLACE_CREDENTIAL: NONE
SECRET_RETRIEVAL: NONE
REAL_HTTP_MARKETPLACE: NOT_RUN
REAL_PROVIDER_OR_EXTERNAL_SYSTEM: NOT_RUN
SOCKET_START_UNDER_DATABASE_AUTHORITY: NOT_RUN
PERFORMANCE_OR_LOAD: NOT_RUN
OWNER_VERIFIED_RESULT: NOT_RUN
DEPLOYMENT: NOT_RUN
```

The fake-port flow proves local identity-bound request construction, local
single consumption and expiry refusal. It does not claim remote exactly-once,
unknown-commit closure or Provider idempotency.

## Branch convergence record

The stale local `docs/WP-P0-003-canonicalization` branch had an empty tree diff
against `main` and was removed in the prior rework. The three Dependabot
branches remain attached to separate dependency-update PRs and are isolated
from PR #16. The sole WP-P0-003 implementation line is
`feat/WP-P0-003-executable-design-validation` and its remote counterpart.

## Boundary statement

PR #16 must remain open, draft and unmerged for
`CONTROLLER_WP_P0_003_IMPLEMENTATION_BACKED_DESIGN_VALIDATION_RE_REVIEW`.
This package is not Design approval, merge authorization, deployment
authorization or production-write authorization. `OQ-005`, `OQ-006` and
the project-level readiness work in the addendum remain open at their allocated
Gates.
