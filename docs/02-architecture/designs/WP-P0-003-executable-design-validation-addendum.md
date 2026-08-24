# WP-P0-003 — Executable Design Validation Addendum

```yaml
document_type: as_built_design_addendum
task: WP_P0_003_EXECUTABLE_DESIGN_VALIDATION
mode: BOUNDED_IMPLEMENTATION_FOR_DESIGN_VALIDATION
work_package: WP-P0-003
source_base: 9f7688204950c64b9f6bd8629daf90a115669864
source_tree: f56515a28f003c19d2acb9440a61656a409eb02c
frozen_design_candidate: WP_P0_003_Durable_Ingestion_Control_Plane_Immutable_Raw_Evidence_Design_v1.11.md
frozen_design_candidate_sha256: 71bd2ca7b338779ed9b382aed728b650a0ad32e019ccfe491e59b3610fc0b2a8
design_approved: false
implementation_authorized_scope: BC-01, BC-02, BC-03 closure evidence only
marketplace_outbound: NONE
secret_retrieval: NONE
production_write: DISABLED
next_gate: CONTROLLER_WP_P0_003_IMPLEMENTATION_BACKED_DESIGN_VALIDATION_REVIEW
```

This addendum records only what the executable validation built, the binding
invariants it proves, and the points where the executable truth corrects the
frozen Design candidate. The Design v1.11 architecture mainline is otherwise
unchanged; nothing here re-states it.

## 1. What was built

| Artifact | Content |
| --- | --- |
| `V0007__create_ingestion_control_plane_authority.sql` | `platform.control_scope` type, `platform.control_epoch` (4 scope kinds, no global row, monotonic), `platform.advance_control_epochs(control_scope[])`, `platform.control_epoch_membership_guard` (FK to `core.marketplace_platform(code)`, exactly-one-per-platform totality via deferred constraint trigger), `platform.acquire_platform_job_set_guard(text[])` with a mandatory row-count assertion, `platform.ingestion_job` (write-once `platform_code`, guard-acquiring insert trigger), `platform.control_route_inventory` (every table routed or explicitly `NO_ROUTE` with reason) |
| `V0008__attach_control_epoch_triggers.sql` | Epoch backfill for existing scopes; three event-specific `FOR EACH STATEMENT` transition-relation triggers per routed table (22 tables × 3 = 66), generated from one routing map; fan-out wrappers acquire the membership guard before reading the job set; migration-time self-check comparing installed triggers to the route inventory |
| `V0009__create_control_boundary_kinds_and_decision_evidence.sql` | Closed `platform.control_boundary_kind` set (6 kinds; `STORE_SCOPE_BOUNDARY` declared `NOT_APPLICABLE` against the live schema), `control_snapshot_boundaries()` resolving exactly one row per kind, `control_snapshot_temporal()` enforcing missing/duplicate/unexpected-kind fail-closed with a set digest, `ops.authorization_decision_evidence` (grant strictly before boundary, authority capped, epoch arrays paired, FK-pinned subject/account/scope-grant/Credential identities, `SELECT`-only for the application — rows are written solely by the grant primitive, zero secret material) |
| `V0010__create_ingestion_run_checkpoint_and_raw_evidence.sql` | `ops.ingestion_run` (lease + monotonic fence, one live run per job), `ops.ingestion_checkpoint` (CAS version), three-identity Raw evidence (`raw_content` / `raw_logical_unit` / `raw_acquisition_observation`, SELECT+INSERT only), and the two `SECURITY DEFINER` transition primitives that are the **only** write paths onto run and checkpoint (the application role holds `SELECT` and nothing else on both): `platform.grant_call_authority(run, fence, lease owner, scope grant, Credential, nominal, correlation)` — locks the run `FOR UPDATE`, derives the whole identity graph from the locked job row, validates the selected scope grant and Credential against database truth, locks the four epoch rows `FOR SHARE` in the writers' own ascending order and holds them to commit, recomputes the temporal relation under those locks, requires the grant instant strictly before the boundary, re-checks fence/owner/state/lease inside a one-row guarded `UPDATE … RETURNING` (zero rows raises `MO014` and rolls everything back), and records server-derived evidence only after that transition; `ops.acknowledge_checkpoint(run, fence, lease owner, observation, version, position)` — locks the run, requires `RUNNING` + matching fence/owner + a live lease, requires the observation to belong to this run and job, and moves the cursor through a version CAS while the run lock is still held |
| Ports | `marketplaceintegration.port.AcquisitionPort` / `AcquisitionRequest` / `AcquisitionResult` / `ObjectStoragePort` — identity-only request (no field can hold secret material), verbatim result with `UNKNOWN_STATE`, write-once content-verified storage; no provider named |
| Tests | `ControlEpochTriggerIT`, `ControlBoundaryCompletenessIT`, `MembershipGuardIT`, `IngestionAuthorityAndEvidenceIT`, `CallAuthorityExclusivityIT` (two-connection lock-wait, takeover and lock-timeout races), `IngestionSkeletonFlowIT` (all REAL_DATABASE on pinned `postgres:18.4`), `IngestionAuthorityArchitectureTest` (+ violation/conforming fixtures), `AcquisitionRequestTest`, updated `FlywayMigrationIT` as-built assertions |

## 2. Binding invariants proven executable

| BC | Invariant | Mechanism | Evidence |
| --- | --- | --- | --- |
| BC-01 | Every expected temporal boundary kind is structurally present in the snapshot; a missing, duplicate or undeclared kind fails closed; `valid_until` is the `MIN` of a counted relation, never a scalar `LEAST` over rememberable arguments; and the grant consumes that relation **inside its own locked transaction** — the caller supplies no identity, epoch or temporal value at all, so there is nothing left to forge or to let go stale | Independently authored handled-set vs declared-set comparison inside `control_snapshot_temporal()`; the grant primitive derives the identity graph from the locked run/job row, validates the selected scope grant and Credential from database truth, recomputes the relation under the held epoch locks, refuses at or after the boundary, and caps the authority | TC-CTRL-200…209, TC-CTRL-400/401/402/404/405/414/415/416, TC-CTRL-420…422 |
| BC-02 | Every platform that a job can reference has exactly one membership guard; a missing guard is a raised error, never a zero-row lock; job creation and platform fan-out are serialized through the same `FOR UPDATE`; the platform set itself is runtime-immutable | Guard FK + deferred totality trigger in both directions; `acquire_platform_job_set_guard` row-count assertion; guard-acquiring statement triggers on `platform.ingestion_job` and on every fan-out route; application role holds only `SELECT` on `core.marketplace_platform` | TC-CTRL-300…311 |
| BC-03 | The as-built contract is machine-comparable: the route inventory, the installed trigger set and the schema are compared inside the migration transaction and again by tests; a table added without routing cannot reach a deployed database | `platform.control_route_inventory` + V0008 self-check + TC-CTRL-102/103/104; `FlywayMigrationIT` pins the exact table and seed sets | TC-CTRL-102…104, TC-DB-110/113/118 |

## 3. Corrections the executable evidence forced

These are points where building and running the contract on PostgreSQL 18.4
changed the shape the frozen Design candidate had described.

1. **`LEAST(NULL, …)` semantics.** PostgreSQL ignores NULL arguments in
   `LEAST` and returns NULL only when every argument is NULL. Reproduced
   directly and asserted by TC-CTRL-200. Completeness is therefore carried by
   the counted boundary relation, not by any property of a scalar expression.
2. **Future platform + guard creation is one data-modifying CTE statement.**
   The installed `GLOBAL_FANOUT` epoch trigger on `core.marketplace_platform`
   enumerates every platform code and acquires every guard **at the end of the
   platform INSERT statement itself**, so a platform inserted without its guard
   fails at statement end (`MO002`, `locked N of M`), and a two-statement
   "platform first, guard second" migration is unexecutable. The only passing
   shape — proven by TC-CTRL-303 — is a single data-modifying CTE that inserts
   the platform row and its guard row together; the guard's FK is satisfied
   because both land in the same statement. The `DEFERRABLE INITIALLY
   DEFERRED` totality trigger remains the commit-time backstop: it is what
   rejects a committed guard deletion and any other end-state where the guard
   set and the platform set differ (TC-CTRL-302/304).
3. **The handled set must be authored independently of the declared set.** A
   first version of the boundary resolver derived its rows from
   `platform.control_boundary_kind` by outer join; a declared kind the resolver
   did not know then resolved to infinity and counted as covered — the same
   fail-open as the `LEAST` omission, relocated into a join. The resolver now
   carries its own literal kind list and the two sets are compared.
4. **`credential_store_scope` has no validity window.** Confirmed against the
   live V0006 DDL. `STORE_SCOPE_BOUNDARY` is declared `NOT_APPLICABLE` and
   resolves to a counted infinity row, so adding a window column later is a
   vocabulary change that the completeness check forces to be explicit.
5. **Row locks ride on the UPDATE privilege.** `SELECT … FOR SHARE` requires
   UPDATE on some column. The application role holds `UPDATE (updated_at)` only,
   on `control_epoch` and the guard; the monotonicity triggers reject an
   `updated_at`-only write, so the role can lock but cannot forge. Verified by
   grant/revoke mutation.
6. **Call-start semantics.** The grant bounds when a call may start
   (`granted_at` strictly before the boundary; authority capped by it). No claim
   is made that a remote call completes before the boundary; that would need a
   separate timeout proof.
7. **A comparison is not a serialization point.** The first executable grant
   read epoch values, compared them against caller-supplied copies and moved
   the run without locking anything — so a control mutation could commit
   between its read and its commit, and a caller could supply the identity
   graph and temporal composite outright. The reworked primitive holds the run
   `FOR UPDATE` and the four epoch rows `FOR SHARE` to commit, derives every
   identity from the locked rows, recomputes the temporal relation under those
   locks, and asserts its final one-row transition. The lock wait is observable:
   TC-CTRL-421 shows a metadata writer demonstrably blocked behind an open
   grant, and TC-CTRL-422 shows the mirror refusal under `lock_timeout` with
   zero residue.
8. **Guarded functions require a closed table.** As long as the application
   role held direct `UPDATE` on the run and the checkpoint, both transition
   functions were advisory — any SQL client could move state, fence, lease or
   cursor around them (the first test suite itself did). The application role
   now holds `SELECT` and nothing else on `ops.ingestion_run`,
   `ops.ingestion_checkpoint` and `ops.authorization_decision_evidence`; the
   two `SECURITY DEFINER` primitives (fixed `search_path`, `PUBLIC` revoked,
   explicit `EXECUTE`) are the only write paths, and TC-CTRL-417 asserts each
   direct write is a privilege refusal.

## 4. Deviations from the frozen candidate's letter

| Candidate said | As built | Why |
| --- | --- | --- |
| Guard `generation` advanced by callers per protocol description | `acquire_platform_job_set_guard` performs lock, row-count assertion and generation bump in one `SECURITY DEFINER` function | An arbitrary SQL client cannot skip the row-count assertion if the assertion and the lock are the same call |
| Job-creation trigger both acquires the guard and advances the JOB epoch | Guard acquisition and epoch advancement are separate triggers; the routed insert trigger is the only JOB-epoch writer | One writer per fact; a double advance is harmless but harder to reason about |
| Route inventory as a design table | `platform.control_route_inventory` is a real table with a `NO_ROUTE` vocabulary and per-row reasons, checked by migration and tests | "Not routed" and "forgotten" must be different machine states |

## 5. What remains prohibited and untouched

No real Marketplace credential, Secret retrieval, Marketplace outbound call,
production data, concrete Object Storage or Secret Manager choice, deployment,
production write, or public operational mutation surface. V0001–V0006 are
byte-identical to `main`. OQ-005 and OQ-006 remain OPEN and are not narrowed by
anything here. `production_write_enabled` remains `false`.
