# WP-P0-002 completion evidence

WP-P0-002 is complete to the exact FULL/PARTIAL boundary approved in Design
v1.2. The technical implementation was independently accepted by the Controller
on Head `28d50134bbd272dc4cc9335315841a526bb819c5`, tree
`30de068598341e545782b0bd833da94838ea22c6`, and GitHub tested merge
`0efda272211f91aecdc7cf614744e9ca4a576677`. The final governance/traceability
closure was independently approved, Human Owner-authorized and squash-merged
through PR #10. This completion does not authorize another Work Package,
production writes, real Marketplace connectivity, Secret retrieval or
production data.

The deterministic Requirement → Test → Evidence table and one-for-one 16-row
Work Package acceptance matrix are committed in
`docs/07-phase-evidence/WP-P0-002/acceptance-criteria.md`.

## Post-merge provenance

The completed protected-merge chain is:

| Evidence | Exact value |
| --- | --- |
| Pull Request | `#10 — MERGED / CLOSED / NOT_DRAFT` |
| Controller merge verdict | `PASS — APPROVE_FOR_HUMAN_MERGE` |
| Controller approval artifact SHA-256 | `d477bb77846d1c9f3f50de58a6795450327b445853794fc38192ee96d4cd3c9f` |
| Owner authorization | approved D-17 Ready and squash merge of PR #10 on the exact accepted identity |
| Approved Base | `3c4f6a6210db377b5471d6014da6afd5bfef6127` |
| Approved Head | `ce8eb44f2f750d73d7329fb78a17640ef3fc80c1` |
| Approved Head tree | `6a2db6f565b29847bed6065d2b04d1df800b516b` |
| Approved tested merge | `fdcbf2bc69a0a80d1b6fb98455e91bf7e6373fef` |
| Squash merge SHA | `203b509e765959560fdfbd0edbde428ba9c6d763` |
| Merged main tree | `6a2db6f565b29847bed6065d2b04d1df800b516b` |
| Squash parent | `3c4f6a6210db377b5471d6014da6afd5bfef6127` |
| Commit signature | `VERIFIED` |
| Merge time | `2026-08-19T17:44:16Z` / `2026-08-20 01:44:16 UTC+8` |
| Tree equality | merged main tree equals approved Head tree |
| Remote task branch | deleted after merge |
| Post-merge Controller verdict | `PASS — MERGE_EXECUTION_VERIFIED` |
| Post-merge Controller artifact SHA-256 | `4e65f0a7fb1c997096c5fd98fb56f42211c546cca323fae5b12d39eaa0c1c8ab` |

All four push workflows ran on squash commit
`203b509e765959560fdfbd0edbde428ba9c6d763`, attempt 1:

| Workflow | Run | Conclusion |
| --- | ---: | --- |
| Backend | [32283328311](https://github.com/Corwin-Code/marketops-platform/actions/runs/32283328311) | SUCCESS |
| Frontend | [32283328372](https://github.com/Corwin-Code/marketops-platform/actions/runs/32283328372) | SUCCESS |
| Governance | [32283328293](https://github.com/Corwin-Code/marketops-platform/actions/runs/32283328293) | SUCCESS |
| Security | [32283328308](https://github.com/Corwin-Code/marketops-platform/actions/runs/32283328308) | SUCCESS |

The push-event `dependency-review` job was `SKIPPED` by design because it
compares Pull Request commits. The required PR `dependency-review` Gate passed
on the exact approved Head/tested merge before merge. Java and TypeScript CodeQL
both passed again on the merged commit.

The merged invariant remains:

```text
active_work_package: NONE
active_gate: CONTROLLER_PHASE_0_PLANNING
authorization: PLANNING_ONLY
production_write_enabled: false
WP-P0-003 remains DRAFT
```

Controller Phase 0 planning is the next lifecycle state. Neither the PR #10
merge nor this provenance record activates another Work Package.

## Independently accepted technical snapshot

### Local unit-only and full verification

| Boundary | Command | Exact result |
| --- | --- | --- |
| Governance before closure | `python3 scripts/validate_governance.py` | PASS |
| Production readiness before closure | `python3 scripts/validate_production_readiness.py` | PASS over 388 files; all three official Global Hard Rules PASS |
| Validator sensitivity suites | `python3 -m unittest discover -s tests -p 'test_*.py'` | PASS, 178 tests |
| Unit-only backend Gate | `cd backend/marketops-server && ./mvnw -B -ntp -DskipITs verify` | PASS, 180 tests; JaCoCo lines 3204/3968 = 80.7460%; branches 608/861 = 70.6156% |
| Full backend Gate | `cd backend/marketops-server && ./mvnw -B -ntp verify` | PASS, 180 unit + 67 integration tests; lines 3807/3968 = 95.9425%; branches 705/861 = 81.8815% |
| Architecture/Modulith | `./mvnw -B -ntp -Dtest='*ArchitectureTest' -DfailIfNoTests=true test` | PASS, 31 tests |
| Coverage sensitivity | `bash scripts/verify_coverage_thresholds.sh all` | PASS; backend and frontend deliberately unmet 100% thresholds were rejected |
| Committed-head Fresh Clone | `bash scripts/fresh_clone_check.sh` | PASS for Head `28d50134…`: governance, PostgreSQL, backend, frontend, SBOM/config, both coverage sensitivities and real Chromium outage recovery 1/1 |

The Testcontainers image is exactly `postgres:18.4`; the running server asserted
`server_version_num=180004`, and Flyway validated/applied V0001–V0006. The
integration suite proves the clean path, constraints, object-level least
privilege, append-only journals, maintenance API, DENIED auditing,
Credential/Service Account lifecycle, Capability fail-closed behavior and
production-write refusal. V0001 remains byte-pinned by
`scripts/validate_production_readiness.py`.

### Deterministic test-identity inventory

The completed test tree contains 96 canonical Java `TC-*` definitions and 96
unique identities. A bounded structural scanner records each identity with its
file, top-level class, exact test method or nested test group, display text and
global occurrence count. The final collision repair preserves the maintenance
write-gate and WP-P0-001 privilege identities:

| Exact test | Previous ID | Final ID |
| --- | --- | --- |
| `MetadataMaintenanceApiIT#coreMetadataSupportsCompleteMaintenancePaths` | `TC-API-080` | `TC-API-086` |
| `MetadataMaintenanceApiIT#associationsSupportCompleteMaintenancePaths` | `TC-API-081` | `TC-API-087` |
| `FlywayMigrationIT#referenceSeedsAreExact` | `TC-DB-115` | `TC-DB-118` |

`TC-API-080/081` remain bound only to `MaintenanceWriteGateApiIT`; `TC-DB-115`
remains bound only to
`DatabasePrivilegeIT#applicationRoleSearchPathExcludesPublic`. The governance
validator rejects duplicate definitions, missing or ambiguous traceability IDs,
acceptance IDs that are unpaired or bound to another method, unbound test
display identities, and an API inventory not represented exactly by
`TEST_STRATEGY.md`. Independent mutations cover both known API collisions,
traceability missing and duplicate resolution, unpaired acceptance IDs,
ID-to-method mismatch, Test Strategy omission and the valid unique case.

### Pull-request CI on the accepted technical snapshot

| Workflow/job | Conclusion | Immutable job |
| --- | --- | --- |
| Backend / `backend-build` | SUCCESS | [95843211470](https://github.com/Corwin-Code/marketops-platform/actions/runs/32177670563/job/95843211470) |
| Backend / `backend-integration` | SUCCESS | [95843211437](https://github.com/Corwin-Code/marketops-platform/actions/runs/32177670563/job/95843211437) |
| Backend / `architecture-boundary` | SUCCESS | [95843211819](https://github.com/Corwin-Code/marketops-platform/actions/runs/32177670563/job/95843211819) |
| Frontend / `frontend-test` | SUCCESS | [95843211285](https://github.com/Corwin-Code/marketops-platform/actions/runs/32177670565/job/95843211285) |
| Frontend / `frontend-lint` | SUCCESS | [95843211372](https://github.com/Corwin-Code/marketops-platform/actions/runs/32177670565/job/95843211372) |
| Frontend / `frontend-build` | SUCCESS | [95843211379](https://github.com/Corwin-Code/marketops-platform/actions/runs/32177670565/job/95843211379) |
| Frontend / `frontend-typecheck` | SUCCESS | [95843211459](https://github.com/Corwin-Code/marketops-platform/actions/runs/32177670565/job/95843211459) |
| Security / `dependency-review` | SUCCESS | [95843211713](https://github.com/Corwin-Code/marketops-platform/actions/runs/32177670673/job/95843211713) |
| Security / `codeql-java` | SUCCESS | [95843211771](https://github.com/Corwin-Code/marketops-platform/actions/runs/32177670673/job/95843211771) |
| Security / `codeql-typescript` | SUCCESS | [95843211748](https://github.com/Corwin-Code/marketops-platform/actions/runs/32177670673/job/95843211748) |
| Security / aggregate `CodeQL` | SUCCESS | [95843506744](https://github.com/Corwin-Code/marketops-platform/runs/95843506744) |
| Governance / `governance` | SUCCESS | [95843211788](https://github.com/Corwin-Code/marketops-platform/actions/runs/32177670566/job/95843211788) |

The governance log names the official rules exactly:

```text
TC-GLOBAL-001 — Compromise Retirement Check
TC-GLOBAL-002 — Functional JavaDoc Rewrite Check
TC-GLOBAL-003 — Production Naming Check
```

Secret-pattern validation, architecture/runtime-boundary checks and least
privilege are separate controls; they are not relabelled as the three Global
Hard Rules.

## CodeQL and security enumeration

Native GitHub API enumeration was available after the accepted technical CI:

```text
GET /repos/Corwin-Code/marketops-platform/code-scanning/alerts?pr=10&state=fixed&per_page=100 -> 63
GET /repos/Corwin-Code/marketops-platform/code-scanning/alerts?pr=10&state=open&per_page=100 -> 0
GET /repos/Corwin-Code/marketops-platform/code-scanning/alerts?state=open&per_page=100 -> 0
GET /repos/Corwin-Code/marketops-platform/secret-scanning/alerts?state=open&per_page=100 -> 0
GET /repos/Corwin-Code/marketops-platform/dependabot/alerts?state=open&per_page=100 -> 0
```

All 63 PR CodeQL alerts were introduced by the PR and fixed without dismissal or
suppression: four `java/concatenated-sql-query`, 56 `java/deprecated-call`, and
three `java/unused-parameter`. Native GitHub API enumeration after the squash
merge again returned PR CodeQL open `0`, repository Code Scanning open `0`,
Secret Scanning open `0` and Dependabot open `0`. Those counts are native API
evidence from Codex's execution environment; the Controller connector does not
expose the alert-listing endpoints, but independently verified the post-merge
Security workflow and source state.

CycloneDX may emit upstream JSON-schema-library notices for `meta:enum` and
`deprecated`. SBOM generation, validation and upload succeed; these notices are
classified upstream tool output, not the resolved Jackson/serialization warning
debt, and are not suppressed.

## Closure evidence boundary

The merged governance closure changed canonical state, Work Package/backlog
status, traceability, this evidence, the acceptance matrix, Test Strategy, test
display identity metadata, and governance validators and sensitivity tests only.
It did not change Java test behavior or any production source. The immutable
Head, tested merge, squash commit, merged tree and post-merge workflow identities
are recorded above without claiming the provenance-sync PR's own future merge
identity.

## Historical non-gate probe and artifact provenance

`local-pg16-validation.sql` and its transcript remain historical candidate
provenance only. PostgreSQL 16 is not Gate or substitution evidence.

| Source artifact | SHA-256 |
| --- | --- |
| Approved Design v1.2 | `3e524c666e56b3d5fdecd6e2098a22d1bd9fd88711dd9c524858ca0cdd3859b2` |
| Original Claude candidate `WP_P0_002_Implementation_Candidate_v1.tar.gz` | `c43928e55c36ee74c45151201f9dcd03d1a068c634809b0dcf511f9c087c853e` |
| Controller implementation deep review | `5629fbeb7afde7dade2ef5e04bd726c10cd84d604e54a130b52d9b0caf08cc9e` |
| Codex new-window handoff pack | `6d32589e24bb72f0fe12d86fdf674e85176cff8fd84966e3fde325e02bb9e0c7` |
| Controller technical re-review of Head `28d50134…` | `483584d177344db3a14ad7093ec3cd89061b2cf1c93a0a538d6cfc6279d0c25f` |
| Controller merge approval of Head `ce8eb44…` | `d477bb77846d1c9f3f50de58a6795450327b445853794fc38192ee96d4cd3c9f` |
| Controller post-merge verification | `4e65f0a7fb1c997096c5fd98fb56f42211c546cca323fae5b12d39eaa0c1c8ab` |

No derived repository ZIP or documented deterministic transformation exists in
the bound inputs, so no derived-package identity is asserted.

## Project boundary

- `ADM-001`: FULL / `VERIFIED` to its approved local/internal/admin-only scope.
- IAM-001/004/006/007, INT-002/003 and ADM-002: PARTIAL / `ACTIVE_CONTROL`; each
  WP-P0-002 subset is verified while the whole source requirement stays open.
- OQ-101, OQ-005, OQ-006 and OQ-102 remain open as allocated.
- WP-P0-003 remains DRAFT; this closure does not activate it.
- WP-P0-002 technical implementation: project-grade / PASS.
- WP-P0-002 governance closure: merged through PR #10 and post-merge verified.
- Next lifecycle state: Controller Phase 0 planning.
- Phase 0: not complete.
- Whole product production readiness: NO.
- Secret/PII/production inventory/real Marketplace connectivity: none.
- Production writes: disabled.
