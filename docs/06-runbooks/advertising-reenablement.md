# Advertising reenablement

Reenablement requires evidence, an independent Operations Lead and a distinct Owner. Time passing or restoring old flag/policy values does not revive old approvals, packets or compensation authority.

1. Open the scoped containment and reconcile pending, unknown, mismatched and reported manual work. Preserve original reservations and event history.
2. Record evidence through `POST /api/v1/console/advertising/containments/{containmentId}/attestations` for `ROOT_CAUSE_CLASSIFIED`, `UNKNOWNS_RESOLVED`, `AUTHORITIES_REPLACED`, `RESULTS_RECONCILED` and `CAPABILITY_EVIDENCE_CURRENT`. These are attributed immutable attestation rows, not editable booleans.
3. For credential/security, Provider/readback or execution-integrity causes, a person holding explicit `ADVERTISING_TECHNICAL_ATTEST` responsibility records `SECURITY_ATTESTATION_PRESENT`.
4. An Operations Lead other than the stopper records `OPERATIONS_ENDORSEMENT`. The database rechecks the actor's live scope.
5. Publish and endorse a new complete Bundle with the exact current Gate authority. Bundle content is immutable; activation atomically retires the prior version and invalidates its old assets.
6. A scoped Owner, distinct from stopper and endorser, submits `POST /api/v1/console/advertising/containments/{containmentId}/reenablement` with the exact new Bundle. The database independently checks all evidence and unresolved execution conditions before `REENABLED`.

State proceeds through `ACTIVE`, `REENABLEMENT_REVIEW` and `REENABLED`. Inspection/attestation does not itself lift the stop. A missing authority, unresolved result or broader requested scope remains blocked.

After lifting, create a new case decision/preview and full human approval chain. The old action's append-only invalidation remains effective. `ops.evaluate_ad_bid_write_gate(command_id)` continues to report any other unmet Gate, credential, evidence, permission, reservation or exposure condition.

No direct SQL mutation is a supported operating procedure. During R1 keep `production_write_enabled=false`; do not activate a real Provider or shared/production environment. Future real verification needs its separate exact Owner-approved Gate envelope.
