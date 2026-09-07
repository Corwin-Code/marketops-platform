# SLICE-V1-003 R3 implementation handoff

```yaml
document_type: claude_r3_implementation_handoff
slice: SLICE-V1-003
recorded_at: 2026-09-05
contract: docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md
contract_sha256: 1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c
contract_git_blob_sha1: 669c38dc4d9429249e663da0e684dabf570c4a4a
contract_bytes: 129400
contract_lines: 2687
accepted_amendments: NONE
source_protected_main: 08ad7da7d9e75b4ddd1c387a22ac0affba9e1430
branch: feat/SLICE-V1-003-advertising-traffic-efficiency
implementation_state: FULL_SCOPE_IMPLEMENTATION_IN_PROGRESS
engineering_closure_claim: NOT_MADE
acceptance_criteria: 55_VERIFIED_132_PARTIAL_13_NOT_YET_OF_200
deferred_release_obligations: S3_REL_001_THROUGH_024_ALL_DEFERRED
migration_inventory: V0036_THROUGH_V0056_21_MIGRATIONS
controlled_write_target: AD_BID_CHANGE
controlled_write_provider_paths: STRUCTURALLY_UNREACHABLE
real_provider_calls: NONE
real_credentials_used: NONE
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot: NOT_AUTHORIZED
production_write_enabled: false
deployment: NOT_EXECUTED
merge: NOT_EXECUTED
push: NOT_EXECUTED
pull_request: NOT_CREATED
next_actor: Human Owner or Controller
measured_commit: ae099e1913af2c99c2a0909dfca0842d5d15b1bd
measured_tree: 18bca0325a8fb19fe32d1ef3c413577a872d911e
local_checkpoint_commit: THE_BRANCH_TIP_WHICH_ADDS_ONLY_THESE_FOUR_LINES
worktree_clean: true
immutable_prefix_v0001_v0035_modified: 0
next_action: REVIEW_LOCAL_CHECKPOINT
```

## What this is

A local checkpoint. Nothing has been pushed, no remote branch or tag exists, no
pull request was created, no deployment happened, no shared environment was
touched, no real credential was read and no provider was called. The advertising
controlled-write path to a real marketplace is still structurally unreachable,
which `AdBidWriteGateAdversarialIT#TC-AD-GATE-ADV-008` and
`AdvertisingVerticalPathIT#TC-AD-VERTICAL-018` assert rather than assume.

## The two corrective rulings

### Capacity

Discharged. `RepresentativePerformanceIT` passes, on a dataset larger than the
run that failed, with the threshold untouched, the test not excluded and no
failure converted to a warning.

| Head | CRITICAL p95 | Budget | `mart.metric_value` rows | Result |
| --- | --- | --- | --- | --- |
| `08ad7da7` (Slice base) | 326,120 ms | 300,000 ms | 1,047,420 | fail |
| `384e34e` (R2) | 388,962 ms | 300,000 ms | 983,940 | fail |
| this checkpoint | **226,229 ms** | 300,000 ms | **1,047,420** | **pass** |

The run was profiled rather than guessed at, the failed measurement is preserved
beside the passing one, and the four behaviour-preserving changes and the one
index are described in `executable-evidence.md`. The same report carries the
reconciliation half: 50 dropped targeted triggers all repaired, the hourly sweep
completing in 169,843 ms with 3,430,157 ms of margin, and zero breaches in any
of the five lanes.

`S3-REL-023` was restored to its exact Contract wording and is not offered as a
substitute for this gate.

### Candidate migrations

Discharged. The three narration violations were corrected in `V0040` and
`V0047` themselves, their checksums regenerated, the disposable databases
recreated and clean-install, replay and exact-base-upgrade validation rerun. No
validator exception was added and no Owner decision was requested;
`validate_production_readiness.py` now passes all four checks.

The same principle then applied twice more. Two latent defects found by the
first end-to-end run were corrected in `V0037` and `V0043` rather than patched
forward, for the reason the ruling gives: both are unpushed candidate migrations
of this branch, and a forward patch would leave every clean install applying a
statement that cannot succeed.

## The gates that must pass

| Gate | Result | Where |
| --- | --- | --- |
| unit, architecture and property | 1,480 passed | `mvn verify`, surefire |
| integration | 604 passed | `mvn verify`, failsafe |
| merged coverage | LINE 0.8589 / BRANCH 0.7141 against 0.80 / 0.70 | `measurements/jacoco-merged-r3.json` |
| whole build | `BUILD SUCCESS` in 21:51 min | `mvn verify` |
| console (vitest) | 269 passed | `npm run test:ci` |
| console lint, typecheck, prettier | pass | `npm run lint`, `typecheck`, `format:check` |
| console bundle isolation | pass | `npm run verify:bundle` |
| browser (Playwright, Chromium) | 25 passed | `npm run test:browser` |
| `validate_governance.py` | pass | `make governance` |
| `validate_production_readiness.py` | 4 of 4 checks pass | `make governance` |
| validator unit suite | 397 passed | `make governance` |

Nothing was weakened to reach any of them.

## What was built

| Workstream | Where it is |
| --- | --- |
| Append-only Case/Task/action/assignment history | `V0055`, `ops.work_task_event`, `WorkTaskService`, `WorkTaskJournalIT` (7 cases) |
| One complete real local vertical advertising path | `AdvertisingVerticalPathIT` (18 ordered cases), `AdvertisingWriteEnabledFixture` |
| Property-based invariant tests | `AdvertisingDomainPropertyTest` (18), `WorkTaskEventPropertyTest` (4) |
| Versioned Daily Brief and Weekly Evidence Review | `V0056`, `AdvertisingBriefService`, `AdvertisingBriefPublicationIT` (10 cases), `AdvertisingBriefView.tsx` |
| Acceptance status | `S3-AC-STATUS.json`, `acceptance-status.md` |
| Runbooks | `docs/06-runbooks/advertising-task-history.md`, `advertising-brief-and-review.md` |

The journal is written through the existing `operationsworkflow` authority
rather than as a second one, and it keeps four things apart that a service level
can otherwise be reported as met by blurring: a page open is not an
acknowledgement, an acknowledgement is not an action, an action is not an
outcome, and a task that changes hands keeps the instant it was raised. Each is
enforced by the schema and proved twice — once through the service and once
against the constraint.

The vertical path carries one decision from accepted facts to a quarantined
lineage: case, lane and priority, an accountable task, a recommendation, a
deterministic candidate, a preview, an approval, a lease, a reservation, a
command, the write gate returning an empty array, the outbox, a fixture
provider, a readback, the early completed-sales guard, an operational outcome, a
settled outcome, a late-data revision and a same-lineage quarantine that then
refuses the next write.

## Two defects the first end-to-end run found

Neither was found by reading. Both survived because nothing had ever executed
the code.

`ops.complete_ad_bid_command_attempt` re-hydrated the frozen operation shape
with `SELECT jsonb_populate_record(...) INTO operation`. PL/pgSQL assigns a
select list to a row variable field by field, so the composite landed in the
first field — a `uuid` — and raised `22P02` for every response carrying bytes.
Every acceptance and every readback carries bytes, so **no advertising attempt
could ever be classified and no readback could ever be recorded**. Its five
sibling sites in `V0025` and `V0027` already used the working form.

`core.ad_qualification_tier_is_monotonic` asserted `count(*) = 4` over a
self-join on adjacent rank. Four tiers make three adjacent pairs, so the check
was unsatisfiable with one row per tier and **every bundle activation failed**
with `QUALIFICATION_TIER_MONOTONICITY_VIOLATED`.

Neither test was shaped around its defect. A fixture padded with a fifth policy
row to satisfy the arithmetic mistake was written first and then deleted, because
a fixture shaped around a defect proves the defect.

## What the reader should question

The vertical path runs against `FIXTURE_ADS`, a platform whose write protocol
this repository specifies, with an `OFFICIAL_VERIFIED` semantic profile and a
verified capability. That is honest only because the protocol is one the fixture
itself writes; marking an Ozon or Wildberries profile verified would have been
inventing a marketplace fact, and
`ad_semantic_profile_fixture_ck` makes promoting the synthetic profile
impossible for every role. `TC-AD-VERTICAL-001` and `TC-AD-VERTICAL-018` assert
that no real marketplace gained a capability, a command, an allowlist entry or
an active bundle, and that `production_write_enabled` is still false.

`AdvertisingCaseCalculationService.profitOf` passes the promotion-cost component
as unconditionally absent, so no calculated case can have an empty blocker list
and the calculator cannot reach an approval on its own today. `TC-AD-VERTICAL-002`
asserts what the calculator really produces — a blocked `DATA_REPAIR` case with
no recommendation — and the stages from `TC-AD-VERTICAL-003` onward hang off a
seeded protection case. That is a real gap, named rather than hidden.

Endorsement is not reachable per command and is not a defect: `ops.ad_decision_policy_bundle`
carries the only enforced endorsement, one level above the command, under the
separation constraint `TC-AD-VERTICAL-005` asserts. There is no per-command
endorse service, controller or column.

## Acceptance status

| Status | R2 | This checkpoint |
| --- | --- | --- |
| VERIFIED | 46 | 55 |
| PARTIAL | 134 | 132 |
| NOT_YET | 20 | 13 |

Nine criteria moved to VERIFIED and one from NOT_YET to PARTIAL; nine more
gained evidence without gaining a status, because in each case a named part is
still missing and the row says which. No engineering closure claim is made:
`CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS` requires all two hundred
criteria executably verified.

## What is still engineering work

Named row by row in `S3-AC-STATUS.json`, and joined to the deferred obligations
in `S3-AC-REL-MAPPING.json`: of the 145 criteria not yet verified, 62 wait on at
least one deferred obligation and 83 are engineering work with no external
dependency. The largest remaining blocks:

- **External by prohibition.** No real Provider, Credential, Gate EV, Gate E or
  Pilot exists, and the register in `deferred-release-register.json` carries
  those obligations rather than this document.
- **Advertising's own capacity measurement.** `ops.ad_slo_observation` records
  latencies and no advertising load has been applied to it; the declared-capacity
  gate that now passes measures the availability portfolio.
- **Accepted Exceptions.** No advertising exception table, service or endpoint
  exists; only the `ADVERTISING_EXCEPTION_REQUEST` action scope code.
- **Freshness at runtime.** `AdvertisingPolicyRepository.resolveFreshness` has no
  callers, so purpose-scoped blocking cannot be observed.
- **Coverage windows and out-of-coverage exposure.** Stored and read, never
  consulted.
- **Dual-axis efficiency success.** `DualAxisVerdict` and `SalesPreservation` are
  unreachable from the running outcome path, which uses the single-axis
  `OutcomeEvaluation`.
- **Mutation testing.** No tool is configured and none was added.

## Where the evidence is

| Artefact | Path |
| --- | --- |
| as-built design | `docs/02-architecture/designs/SLICE-V1-003-design.md` |
| executable evidence | `docs/07-phase-evidence/SLICE-V1-003/executable-evidence.md` |
| acceptance status, prose | `docs/07-phase-evidence/SLICE-V1-003/acceptance-status.md` |
| acceptance status, machine-readable | `docs/07-phase-evidence/SLICE-V1-003/S3-AC-STATUS.json` |
| deferred release register | `docs/07-phase-evidence/SLICE-V1-003/deferred-release-register.json` |
| migration inventory | `docs/07-phase-evidence/SLICE-V1-003/MIGRATION-INVENTORY.json` |
| S3-AC / S3-REL mapping | `docs/07-phase-evidence/SLICE-V1-003/S3-AC-REL-MAPPING.json` |
| measurements | `docs/07-phase-evidence/SLICE-V1-003/measurements/` |
| runbooks | `docs/06-runbooks/advertising-*.md` |
| the R2 checkpoint this one continues | `docs/07-phase-evidence/SLICE-V1-003/r2-implementation-handoff.md` |
