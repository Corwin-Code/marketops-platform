# Advertising quarantine

Containment is an explicit scope and cause, not a severity number.

| Kind | Meaning |
| --- | --- |
| `EMERGENCY_ENTITY_HOLD` | Human hold on a native object or its canonical affected set. |
| `ACTION_OUTCOME_QUARANTINE` | Canonical action-bound regression, including early company/critical sales regression or a later correction. |
| `AUTHORITY_VERSION_QUARANTINE` | Hold on decisions referencing the exact quarantined authority version. |
| `CAPABILITY_QUARANTINED` | Hold on the identified platform/Store/account capability. |
| `KILL_SWITCH_ACTIVE` | Stop at its explicit scope; the name alone does not imply global scope. |

Use an available console Stop action and provide evidence plus an eligible review owner; see [kill-switch authority](advertising-kill-switch.md). The application role cannot create a containment row directly. System outcome quarantine is derived from a specific immutable `REGRESSED` observation, not a caller verdict or an inferred AI action.

Affected-set quarantine tests intersecting canonical Product variants, including a later generation with a different digest. Authority-version quarantine follows the referenced Bundle components. A late correction retains the old observation and task age; it appends review responsibility and blocks overlapping execution even if another current reservation prevents reacquisition.

A shared authority defect uses `POST /api/v1/console/advertising/containments/authority-versions/{authorityId}/stop`, with the review owner, reason and evidence reference. The caller must have an active Owner or Operations Lead role and an exact organization-wide `ADVERTISING_POLICY_MANAGE` grant; the named review owner needs the organization-wide Operations Lead grant. A Store-only grant cannot stop all consumers across the organization. The reference must actually be consumed inside the caller's organization. Scope follows the exact object/Bundle or Manual authority references, including current and frozen purpose-freshness versions; another Profile/Bundle in the same Store is not automatically a consumer.

Stops are checked before leasing and at transmission. They cannot retract a request already sent, so pending/unknown/native readback work must still converge. Cached booleans, passage of time and reverting a policy row cannot revive invalidated actions.

`CREDENTIAL_OR_SECURITY`, `PROVIDER_OR_READBACK_DEFECT` and `EXECUTION_INTEGRITY` require the corresponding technical/security attestation before reenablement. Choose the actual cause; use [reenablement](advertising-reenablement.md) for the independent review and fresh Bundle.

R1 changes are engineering and isolated-fixture evidence only. Production write remains disabled and no real Provider or shared environment is authorized here.
