# WP-P0-003 executable design validation evidence

```yaml
task: WP_P0_003_EXECUTABLE_DESIGN_VALIDATION
mode: BOUNDED_IMPLEMENTATION_FOR_DESIGN_VALIDATION
source_base: 9f7688204950c64b9f6bd8629daf90a115669864
environment: local workstation, Docker, Testcontainers, postgres:18.4 (pinned, asserted by TC-DB-100)
design_approved: false
implementation_complete: false
marketplace_outbound: NONE
production_write: DISABLED
```

This tranche exists to close three binding corrections with executable
evidence, not to implement the ingestion product. The Design candidate remains
unapproved, and this evidence does not change any Gate by itself.

## BC closure matrix

| BC | Obligation | Carried by | Result source |
| --- | --- | --- | --- |
| BC-01 | Temporal boundary completeness: closed kind set, missing/duplicate/unexpected kind fail closed, `valid_until` from a counted relation, no `LEAST(NULL)` assumption, grant strictly before the boundary, authority capped — and the whole relation recomputed and consumed **inside the locked grant transaction**, with no caller-supplied identity, epoch or temporal value anywhere in the EXECUTE surface | `V0009`, `V0010`, `ControlBoundaryCompletenessIT` (TC-CTRL-200…209), `IngestionAuthorityAndEvidenceIT` (TC-CTRL-400…405, 414…416), `CallAuthorityExclusivityIT` (TC-CTRL-420…422) | `backend-verify-run.txt` |
| BC-02 | Membership guard totality: guard FK to the platform set, exactly one guard per platform in both directions at commit, zero-row lock impossible (`row count = 1` asserted inside the acquire function), job-create vs fan-out phantom-free in both orders, runtime-immutable platform set, grant never touches the guard | `V0007`, `V0008`, `MembershipGuardIT` (TC-CTRL-300…311) | `backend-verify-run.txt` |
| BC-03 | As-built cross-section truth: route inventory vs installed triggers vs schema compared by the migration itself and re-checked by tests; exact table/seed/migration sets pinned; architecture rules mutation-tested | `V0008` self-check, `ControlEpochTriggerIT` (TC-CTRL-102…104), `FlywayMigrationIT` (TC-DB-110/111/113/118), `IngestionAuthorityArchitectureTest` (TC-ARCH-020…022, F-ARCH-020…023) | `backend-verify-run.txt` |

## Authority exclusivity evidence (WP3-EDV-F01/F02/F03)

`CallAuthorityExclusivityIT` proves the serialization with two live
connections and real lock waits:

- a metadata writer's epoch advance demonstrably blocks behind an open grant's
  `FOR SHARE` and lands strictly after the already-bounded authority
  (TC-CTRL-421); the mirror case refuses under `lock_timeout` with zero
  residue (TC-CTRL-422);
- a revocation committed first refuses the grant from database truth
  (TC-CTRL-420);
- a takeover either waits behind the run lock (TC-CTRL-423/427) or, committed
  first, turns the superseded worker's grant and acknowledgement into raised
  refusals with zero effect (TC-CTRL-424/428);
- an expired lease and a wrong owner refuse at grant time (TC-CTRL-425/426).

The forgery surface is closed by construction and by privilege:
`grant_call_authority` accepts only run/fence/owner plus the selected scope
grant and Credential row ids, derives everything else from locked rows
(TC-CTRL-401/415/416), and `marketops_app` holds `SELECT` and nothing else on
`ops.ingestion_run`, `ops.ingestion_checkpoint` and
`ops.authorization_decision_evidence` (TC-CTRL-417), so both transition
functions are the only write paths rather than the polite ones.

## Zero-outbound and zero-secret evidence

`IngestionSkeletonFlowIT` (TC-CTRL-500/501) drives one complete flow — grant →
acquisition through `RecordedAcquisitionPort` → custody in
`InMemoryObjectStoragePort` with hash-verified read-back → three-identity Raw
rows → cursor acknowledgement — and asserts:

- the only acquisition doorway in the run owns no network client;
- the recorded request carries identities only (`AcquisitionRequest` has no
  field able to hold secret material);
- an expired call authority stops the call at the doorway with zero recorded
  invocations.

No real credential exists anywhere in the repository or the test run; the one
credential row is metadata with a `secret-ref://` reference that nothing
resolves.

## Engine facts established on the pinned server

| Fact | Assertion |
| --- | --- |
| `LEAST` ignores NULL arguments; NULL only when all arguments are NULL | TC-CTRL-200 |
| A trigger requesting transition relations cannot cover more than one event | TC-CTRL-100 |
| `OLD TABLE` is illegal on INSERT triggers; `NEW TABLE` on DELETE triggers | TC-CTRL-101 |
| A statement matching zero rows still fires its statement trigger with an empty transition relation, advancing nothing | TC-CTRL-105 |
| `SELECT … FOR UPDATE` locks only returned rows; a missing row yields a zero-row no-op unless the caller counts | TC-CTRL-305 |
| A `FOR SHARE` held to commit blocks a concurrent writer's `UPDATE` of the same row until the holder commits | TC-CTRL-421 |
| Row locks require the UPDATE privilege; a single-column grant (`updated_at`) confers exactly the lock and nothing else | TC-CTRL-110 |

## Command transcripts

| File | Command |
| --- | --- |
| `backend-verify-run.txt` | `./mvnw -B -ntp verify` (unit + architecture + REAL_DATABASE integration, coverage gate) |
| `validate-governance-run.txt` | `python3 scripts/validate_governance.py` |
| `validate-production-readiness-run.txt` | `python3 scripts/validate_production_readiness.py` |
| `validator-unit-tests-run.txt` | `python3 -m unittest tests.test_validate_governance tests.test_validate_production_readiness` |

## Boundary statement

Draft PR only, UNMERGED; independent Controller
IMPLEMENTATION_BACKED_DESIGN_VALIDATION_REVIEW follows. This evidence is not a
Design PASS, not a full Implementation authorization and not a merge
authorization. OQ-005 and OQ-006 remain OPEN. No secret or PII was added.
