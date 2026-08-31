# SLICE-V1-002 executable evidence

```yaml
document_type: executable_evidence_record
slice: SLICE-V1-002
contract_sha256: d89ea296d0ff854c7d57895b448f9467a22106881d26de4c62a0e8629600556e
owner_acceptance_evidence_sha256: 4e243c85412c549975ef70ee46bb09502a3157c0d4bb6a1b2679b7745b96538e
source_protected_main: 8a7076877374391cf851481c023dfb0e621ab712
source_protected_main_tree: b87ec67d0242eb86e15698ab95430c37f0fe4328
executed_at: 2026-08-31
executed_on: LOCAL_ISOLATED_CONTAINERS_AND_SYNTHETIC_FIXTURES
assessment: PARTIAL_IMPLEMENTATION_CHECKPOINT
engineering_closure: NOT_CLAIMED
controller_verdict: NOT_CLAIMED
owner_formal_closure: NOT_CLAIMED
remote_publication: NOT_CLAIMED
external_business_systems_contacted: NONE
deployment: NOT_EXECUTED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot: NOT_AUTHORIZED
production_write_enabled: false
```

## Evidence boundary

Everything below ran locally against isolated containers and synthetic
fixtures. No marketplace, model provider, identity provider or cloud account
was contacted, no credential was used, and nothing was deployed. The Slice has
no controlled write target, so there is no write path to evidence and none is
claimed.

This is a mid-implementation record. It reports what runs today, and the
[acceptance status](acceptance-status.md) records which criteria that leaves
unproven.

## Environment

The database evidence is real PostgreSQL, not an in-memory substitute.

| Component | Version |
| --- | --- |
| Backend runtime | Java 21.0.10, Spring Boot 4.1.0 |
| Database under test | PostgreSQL 18.4 via Testcontainers 2.0.5 |
| Migration inventory | V0001–V0030; V0001–V0029 unchanged |
| Frontend toolchain | Node 24.20.0, npm 11.19.0 |
| Governance tooling | Python 3.11.15 |

## Commands and results

| Command | Observed result | Scope |
| --- | --- | --- |
| `./mvnw -B -ntp -Dtest='DemandPolicyEngineTest' test` | 11 passed | deterministic D7/D14/D30 selection, censoring, carry-forward and its expiry |
| `./mvnw -B -ntp -Dtest='ChannelRiskCalculatorTest' test` | 8 passed | channel independence, fresh-zero and unsellable escalation, staleness, policy-derived lanes |
| `./mvnw -B -ntp -Dtest='CompanyRiskCalculatorTest' test` | 15 passed | fail-closed company answer, deduplication, inbound eligibility, conservative proof |
| `./mvnw -B -ntp -Dtest='PriorityPolicyTest' test` | 5 passed | lane band, visible factors, determinism |
| `./mvnw -B -ntp -Dtest='NoFalseSafetyAdversarialTest' test` | 13 passed | the values a removed gate would produce cannot be constructed |
| `./mvnw -B -ntp -Dtest='*ArchitectureTest' test` | 65 passed | module, boundary, acquisition-authority and rule-sensitivity suites with the new module registered |
| `./mvnw -B -ntp -Dit.test='FlywayMigrationIT' verify` | 11 passed | migration history, 132-table inventory, seeded role and action matrix |
| `./mvnw -B -ntp -Dit.test='ControlBoundaryCompletenessIT' verify` | 10 passed | every new table registered in the route inventory |
| `./mvnw -B -ntp -Dit.test='ControlEpochTriggerIT' verify` | 11 passed | no epoch trigger on a `NO_ROUTE` table |
| `./mvnw -B -ntp -Dit.test='DatabasePrivilegeIT' verify` | 11 passed | least-privilege grants unchanged |
| `./mvnw -B -ntp -Dit.test='AvailabilityRiskSchemaIT' verify` | 10 passed | ten refusals the schema must make, against PostgreSQL 18.4 |
| `./mvnw -B -ntp -Dit.test='AvailabilityRiskFlowIT' verify` | 6 passed | the loop end to end, including targeted-versus-sweep equality |
| `./mvnw -B -ntp -Dtest='com.mimococo.marketops.operatingfacts.**' test` | 148 passed | the extended fact authority, unchanged behaviour |
| `python3 scripts/validate_governance.py` | passed | canonical governance contract |
| `python3 scripts/validate_production_readiness.py` | passed over 1642 files | TC-GLOBAL-001 through TC-GLOBAL-004 |
| `python3 -m unittest discover -s tests -p 'test_*.py'` | 383 passed | governance tooling, including the V0030 migration registration |
| `npm run lint && npm run format:check && npm run typecheck` | clean | frontend static checks, unchanged |
| `npm run test:ci` | 196 passed | frontend suite, unchanged |
| `npm run build && npm run verify:bundle` | passed | bundle isolation |

## What the database refuses

`AvailabilityRiskSchemaIT` asserts refusals rather than happy paths, because a
constraint that has never rejected anything is a comment:

| Case | Refusal proven |
| --- | --- |
| `TC-AVAIL-DB-001` | two overlapping active lead-time versions of one scope |
| `TC-AVAIL-DB-002` | a policy scope naming identifiers it is not scoped by |
| `TC-AVAIL-DB-003` | a company child claiming health on blocked evidence |
| `TC-AVAIL-DB-004` | a provisional child with no proof terms |
| `TC-AVAIL-DB-005` | a second live case for one cause, and a verified success with no recorded action |
| `TC-AVAIL-DB-006` | an approval by the requester where separation is required |
| `TC-AVAIL-DB-007` | an active accepted exception with no expiry |
| `TC-AVAIL-DB-008` | a second pending recalculation for one variant |
| `TC-AVAIL-DB-009` | any `DELETE` of case, event, decision, SLO or attestation history |
| `TC-AVAIL-DB-010` | a free-text acknowledgement offered as a structured action |

## Targeted and sweep equivalence

`TC-AVAIL-FLOW-004` calculates the same variant twice at one as-of instant and
compares the whole returned value, not a summary of it. The two paths call one
calculator over one evidence snapshot, so the equality is structural rather
than coincidental. Both runs also produce the same policy-version digest.

## Preserved failures and known limits

- The full `./mvnw clean verify` observed one pre-existing integration failure,
  `PriceCommandWorkerIT.unknownWriteOutcome`, when the whole suite runs
  concurrently on this four-core host. The same class passes 18/18 in
  isolation. The cause is cross-test container contention in existing
  SLICE-V1-001 fixtures, not a change made here; it is recorded rather than
  removed.
- Docker Hub's blob CDN is denied by this session's egress policy. Images were
  obtained through the permitted Google pull-through mirror configured as a
  daemon-level registry mirror, so the digest-pinned image references in the
  existing tests resolve unchanged. No test source, image name or pinned digest
  was modified.
- No performance, SLO, fault-injection, browser or end-to-end operator evidence
  exists yet, because the workers and the operating surface those would exercise
  are not yet implemented.
