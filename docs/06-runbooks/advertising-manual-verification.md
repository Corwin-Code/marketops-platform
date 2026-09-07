# Governed Manual advertising configuration verification

Both Ozon and Wildberries can use the governed Manual workflow without claiming
verified API capability. Manual policies and packets cannot create API commands,
outbox attempts, credentials or provider authority. In this Slice all runtime
verification is isolated and fictional; production writes remain disabled.

## Establish a valid packet

Use the Case's Manual options. An exact Owner policy produces an eligible
proposal, Maker selects it, a distinct Operations Lead endorses it and Owner
approves the bounded packet. A proposal is not an instruction to execute.
Approval freezes the native field and intended target, current configuration,
complete affected set, people, versions, expiry, shared Outcome baseline and
reservation. Free-form target substitution is refused.

The API prefix is `/api/v1/console/advertising`:

| Operation | Endpoint |
| --- | --- |
| Read eligible options | `GET /cases/{caseId}/manual-options` |
| Select policy/candidate | `POST /cases/{caseId}/manual-selections` |
| Ops endorsement | `POST /manual-packets/{id}/endorsement` |
| Owner approval | `POST /manual-packets/{id}/approval` |
| Executor starts | `POST /manual-packets/{id}/start` |
| Executor reports | `POST /manual-packets/{id}/report` |
| Different person verifies | `POST /manual-packets/{id}/independent-verification` |
| Cite an official observation | `POST /manual-packets/{id}/official-verification` |
| Read shared Outcome history | `GET /manual-packets/{id}/outcomes` |
| Evaluate available early safety | `POST /manual-packets/{id}/early-observation` |

Use the server-returned expected version and allowed actions. Every state change
rechecks identity, scope and frozen authority. Do not insert packet, approval or
configuration-proof rows by SQL; that bypasses the human workflow and is not an
operating route. An expired or invalidated decision needs new evidence and a new
governed decision, never an edited expiry.

Read each option's `blockerCodes`, as well as the response's aggregate reasons.
`OUTCOME_POLICY_UNRESOLVED` and `OUTCOME_POLICY_CONFLICTED` keep that option visible
but disable its selection. Another valid option's global `SELECT_MANUAL_PROPOSAL` action
does not authorize a blocked option. The server repeats the exact current scoped
resolution and bound-policy check inside selection; refusal returns HTTP 409
with the exact code and creates no selected packet or frozen baseline. A missing
legacy option field is unresolved in the console, and unknown reasons remain
visible. Resolve the actual authority through its governed policy workflow;
do not choose a broader or later favorable version to bypass the binding.

## Establish configuration, not confidence

| Evidence grade | Required proof |
| --- | --- |
| `OFFICIAL_API_READBACK` | Actual replayable Raw custody and matching account/object/native field/current observation. |
| `OFFICIAL_CONFIGURATION_EXPORT` | Actual replayable official export with the same exact binding. |
| `INDEPENDENT_MANUAL_VERIFICATION` | A different scope-authorized person explicitly attests a complete direct official-console observation of the exact native field/value at the actual observation time. |
| `UNVERIFIED_MANUAL_EVIDENCE` | A screenshot, incomplete observation or direct observation without explicit attestation; it cannot prove configuration. |
| `EXECUTOR_SELF_REPORT` | Establishes only that execution was reported. |

A self-report moves to `ACTION_REPORTED_CONFIGURATION_UNVERIFIED`. It does not
start a favorable Outcome clock or release the reservation. A URL, arbitrary
UUID, observation for another account, wrong field or stale/superseded value
cannot become official proof. Independent verification by the executor is
refused. Independent proof establishes only the observed configuration; it does
not verify API idempotency or an exact provider application timestamp.

The independent-verification form requires `observedValue`, `observedAt`,
`evidenceSource`, `completeness`, `exactNativeObjectId`, `exactFieldPath`,
`semanticProfileId`, `evidenceReference` and `directObservationAttested`, together
with the packet's current `expectedVersion`. Record the time the field was
actually observed; the server never substitutes submission time. Copy the exact
object, field and profile from the packet. The immutable policy's freshness
limit applies when the observation is submitted. A screenshot remains unverified
even if its visible value matches the intended value. Only a complete direct
official-console observation with explicit attestation can qualify at this
manual evidence tier. Missing metadata, stale or future observations and a wrong
object, field or profile are refused.

History retains its original `provesConfiguration` value. Current authority also
requires `qualifiedForCurrentProof`: historical value-only records without the
observation envelope cannot establish a new release or Outcome. A valid proof's
actual observation time remains durable for later mature Outcome evaluation;
it is not replaced with the later evaluation time.

A conflicting or superseded current configuration is `MANUAL_EXECUTION_UNCERTAIN`.
An older observation stays in history without replacing a newer qualified proof.
A later unknown or mismatch cannot be cleared with an older favorable value.
If the original reservation has been released, later uncertainty reopens that
same reservation when no newer intervention overlaps it. If another intervention
already holds the overlapping scope, its reservation remains in place and an
Execution Integrity hold blocks the complete affected set. Both factual histories
remain readable. New instructions remain blocked by an issued or unresolved
packet; execution still requires the shared reservation.

Investigate the actual current configuration. Do not overwrite uncertainty with
the intended target or reissue another intervention against overlapping variants.

## Observe safety and business results separately

Controlled and Manual actions use one frozen baseline and Outcome plan.
Configuration proof anchors observation. Early Completed-Sales safety requires
company coverage and every action-time frozen critical unit; absent or unmatured
evidence remains unknown or `NOT_YET_OBSERVABLE`. Configuration alone is not
safety, efficiency or health. New observations carry the persisted
`OPERATIONAL_ASSOCIATION_NOT_CAUSAL_INCREMENTALITY` inference limit. Historical
absence or unsupported values remain UNKNOWN; no favorable stage verdict proves
causal incrementality.

Only canonical complete early safety plus valid current configuration may release
the reservation. The 30-day Retained Operational business result and mature
Settled confirmation use their own frozen windows and Owner thresholds. Late
adverse facts append a new observation and reopen/quarantine as necessary;
previous observations remain readable. Negative profit does not resolve an
economic-loss cause or establish efficiency health. Original physical-risk
clearance is separate and still requires the complete frozen safety window.
Reservation release itself grants no new ordinary same-object command authority;
see [reservation and reentry boundaries](advertising-reservation-or-exposure-block.md).

## Investigate a stuck workflow

Read the packet state, evidence grade, expiry and refusal code, then the Case
workflow, Task journal, reservation and shared Outcome history. Confirm the
relevant person's actual role and all affected store/variant grants. Compare the
packet's exact field/target with the actual observation and replayable custody.
Do not resolve a block by granting financial access to an otherwise restricted
reviewer or changing API verification status.

`AdvertisingManualWorkflowIT` covers real service/SQL human decisions and shared
Outcome; `AdvertisingManualShadowIT` covers structural no-command boundaries.
The isolated browser scenarios exercise both platform labels with actual role
sessions and verify stored semantic verification state. Measured results and
limitations are recorded in the R1 evidence package.
