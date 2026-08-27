# PR #20 CodeQL disposition evidence

This record explains the seven threads from the reviewed Head. Remote alert
state, thread resolution and aggregate CodeQL success must be captured for the
published final Head; this document alone does not resolve or waive an alert.

| Alert | Correction / disposition basis | Verification |
| --- | --- | --- |
| 66 — disabled Spring CSRF | The console accepts only explicit Authorization bearer credentials. Servlet sessions, cookies, query strings and form fields cannot authenticate. No authentication cookie is issued. CSRF disablement is intentional for this stateless API; only this alert is eligible for a documented false-positive dismissal. | `SignedBearerIdentityIT`: signed mutation without CSRF/cookie; cookie/query/form/session-only refusal; hostile Origin refusal even with valid bearer; no session/Set-Cookie. Maintenance mutations require the local operator boundary and switch; loopback telemetry is read-only. |
| 67 — NumberFormatException | Import rows are revalidated before application; typed JSON numeric access replaces unchecked string reparsing. Integer range/type checks refuse unsupported values instead of truncating or throwing an uncaught parse error. | `FileIntakeFlowIT` TC-INTAKE-011 rejects NaN, fractional and overflowing quantities and applies the exact maximum integer. Cost/finance and parser boundary tests remain. |
| 68, 69 — UNKNOWN_STATE switch cases | APPLY and STATUS_ENQUIRY each have an explicit UNKNOWN_STATE arm, separate from TIMEOUT. Both preserve unknown outcome and require readback; neither resubmits an uncertain write. | `PriceCommandWorkerIT`, `PriceWriteClassificationTest`, real-DB price-path/fencing tests. |
| 70 — unused nativeStatus | Removed only the unused enquiry classifier argument and updated production/test call sites. Recorded platform task status still controls classification and raw HTTP evidence remains in immutable response custody. | Task success/refusal/pending/unrecognized-pointer classification and adapter tests. |
| 71 — unused operator | Profile registration now records a CREATE audit event using the supplied operator, profile ID/version, organization, dataset and owner in the same transaction. | `FileIntakeFlowIT` TC-INTAKE-010 proves attribution and rollback when audit attribution is refused. |
| 72 — unused field | Removed the superseded unaudited write-operation service and admin mutation route. V0027's account-bound verification/review path owns controlled operation promotion. | `RegistryVerificationFlowIT`, privilege/control-route inventory and adapter tests. |

The CSRF assessment is limited to the current bearer-only authentication model.
Adding cookie/session authentication requires revisiting CSRF protection before
that change ships. No CodeQL query, ruleset, workflow, security job or broad
source suppression was disabled to obtain a passing result.

Raw, SHA-256-bound command logs retain the tools' original whitespace inside
lossless `.log.gz` artifacts. `LOG-CUSTODY.json` records both compressed and
original-byte hashes. No whitespace exception or other check exemption was
added; decompressing an artifact recovers the exact recorded command output.
