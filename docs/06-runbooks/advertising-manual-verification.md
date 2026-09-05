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

## Establish configuration, not confidence

| Evidence grade | Required proof |
| --- | --- |
| `OFFICIAL_API_READBACK` | Actual replayable Raw custody and matching account/object/native field/current observation. |
| `OFFICIAL_CONFIGURATION_EXPORT` | Actual replayable official export with the same exact binding. |
| `INDEPENDENT_MANUAL_VERIFICATION` | A different scope-authorized person observes the exact native field/value. |
| `EXECUTOR_SELF_REPORT` | Establishes only that execution was reported. |

A self-report moves to `ACTION_REPORTED_CONFIGURATION_UNVERIFIED`. It does not
start a favorable Outcome clock or release the reservation. A URL, arbitrary
UUID, observation for another account, wrong field or stale/superseded value
cannot become official proof. Independent verification by the executor is
refused. Independent proof establishes only the observed configuration; it does
not verify API idempotency or an exact provider application timestamp.

A conflicting or superseded configuration is `MANUAL_EXECUTION_UNCERTAIN`.
Keep the complete affected set reserved and investigate the actual current
configuration. Do not overwrite uncertainty with the intended target or reissue
another intervention against overlapping variants.

## Observe safety and business results separately

Controlled and Manual actions use one frozen baseline and Outcome plan.
Configuration proof anchors observation. Early Completed-Sales safety requires
company coverage and every action-time frozen critical unit; absent or unmatured
evidence remains unknown or `NOT_YET_OBSERVABLE`. Configuration alone is not
safety, efficiency or health.

Only canonical complete early safety plus valid current configuration may release
the reservation. The 30-day Retained Operational business result and mature
Settled confirmation use their own frozen windows and Owner thresholds. Late
adverse facts append a new observation and reopen/quarantine as necessary;
previous observations remain readable. Negative profit cannot close Protection.

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
