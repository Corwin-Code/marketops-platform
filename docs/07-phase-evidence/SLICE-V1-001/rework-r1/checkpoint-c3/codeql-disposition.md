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

## First published rework candidate: alerts 73–96

Candidate `6e44ed49de90e55b4558f1c0b76229d257729511` was analyzed on merge
`3026db2129870789095be0f1c4bd6fc69c500a1e`. GitHub reports original alerts
67–72 fixed. The following corrections preserve the original safety behavior;
C2 `cb799390f69f85982aa41a530a764c07dc7684f3`, analyzed on merge
`89ac1a82ee6bbe87f7e32f1360d038c83092b96e`, confirms all alerts 77–96 fixed.
[Remote C2 evidence](https://github.com/Corwin-Code/marketops-platform/blob/d4bc5fe51605501da4ebc18c89c5d47ec8dc5ed0/docs/07-phase-evidence/SLICE-V1-001/rework-r1/remote-ci-c2/alerts.json) contains 26 fixed alerts and only
the five open alerts assessed below. This is not aggregate CodeQL success.

| Alerts | Correction | Verification |
| --- | --- | --- |
| 77–81 — concatenated SQL | Capability provenance refusal uses a prepared statement. Managed fixture role creation binds synthetic passwords into PostgreSQL `format` with `%L` literal quoting; it no longer concatenates values into SQL. | `MetadataConstraintIT` keeps both application-role and owner-role refusal assertions; `ManagedProfileMigrationIT` exercises passwords containing an apostrophe, PG17 bootstrap/equivalence/recovery and PG18 refusal. |
| 82–83 — numeric parsing | Migration inventory compares the canonical padded version prefix; fixture fence reads use JDBC `getLong`. | `ManagedMigrationRunnerTest`, `ManagedProfileMigrationIT`, all real-DB `PriceWritePathIT` transitions. |
| 84 — unused local | Capability existence is an explicit refusal precondition without an unused UUID binding. | `OperatingFlowIT`, command authority tests. |
| 85 — constant loop condition | Exactly 64 upload iterations, followed by an explicit exhausted/non-exhausted snapshot check. No 65th upload is possible. | `DiagnosticExportIT.partCeilingNeverUploadsA65thPartAndOnlyCompletesWhenTheSnapshotIsExhausted` tests both outcomes; existing real-DB export/recovery tests remain. |
| 86 — grouped enum arm | UNKNOWN_RESULT, SCHEMA_DRIFT, UNREADABLE and CONFIG_INVALID each have an explicit arm; all retain BLOCKED and the exact reason. | `AcquisitionRunnerTest` and acquisition real-DB tests. |
| 87–89 — override annotations | Mark all three fixture object-storage port implementations with `@Override`. | `StoredRawReplayIT`. |
| 90–92 — deprecated calls | Use Jackson 3 `asString` for string fields. | `DiagnosticExportIT`, `RegistryVerificationFlowIT`. |
| 93–96 — unused parameters | Remove unused fixture attempt count/outcome and exception-handler parameters. Attempt opening, response completion and exception types remain separate and explicit. | `PriceWritePathIT`, `OperatingFlowIT`, exception/refusal tests. |

### Narrow false-positive assessments

These are individual alert dispositions, not query exclusions or risk waivers.
All five must be revisited if the described authentication, configuration or
data-flow model changes. Their remote state is recorded separately from this
source assessment.

| Alert | Exact reason the reported vulnerability does not occur | Executable basis |
| --- | --- | --- |
| 66 | No cookie or servlet session supplies authentication; only an explicit validated bearer token can authorize a console mutation. Maintenance requires the actual loopback peer, operator header and disabled-by-default switch. | `SignedBearerIdentityIT` negative cookie/query/form/session and hostile-Origin cases, plus the positive signed mutation. Full backend 134 and 136 pass. |
| 73 | `spring.flyway.enabled: false` disables application-owned migration. The empty inactive password override prevents inheritance of the required migration-secret placeholder; the application receives only its own database credential. No empty-password connection is made by this setting. The separate managed migration executable requires its own credential and evidence envelope. | `ApplicationConfigurationTest.rolesAreSeparated`; packaged migration refusal tests and managed-profile tests. Do not remove the override and accidentally require a migration secret in the application process. |
| 74 | The reported `setEntity` target is Apache `HttpUriRequestBase`, an outgoing HTTP request, not a browser/servlet response. It sends exact approved provider request bytes. HTML escaping would corrupt those bytes and their digest binding. | `BoundedOutboundHttpTest.outgoingRequestEntityPreservesExactBytesIncludingMarkup` uses a local transport fixture and observes exact bytes. Public destination preparation still rejects loopback/HTTP, private addresses, redirects and unapproved headers. |
| 75–76 | Invalid part numbers and corrupt content throw a refusal and return no bytes. Every returning path checks stored requester/organization/current scope and DB read authority before I/O, then repeats DB authorization after I/O. A guard that denies a request cannot bypass authorization to obtain data. | `DiagnosticExportIT`: invalid inputs, corrupt/expired content, foreign IDs, revoked store access and revocation during object read all refuse. Audited successful download remains after integrity verification; it is not moved ahead of a refusal merely to satisfy analysis. |

Focused run 141 passes 105 unit tests and 127 integration tests with zero
failures, errors or skips. [Checks 142](https://github.com/Corwin-Code/marketops-platform/blob/d4bc5fe51605501da4ebc18c89c5d47ec8dc5ed0/docs/07-phase-evidence/SLICE-V1-001/rework-r1/checks-142/summary.json) preserves its
exact command and compressed output, as well as failed preparatory attempts;
it is not final exact-commit verification. Full regression 143 additionally
passes 846 unit/architecture and 374 integration tests, with LINE 12186/14485
and BRANCH 3218/4461. Final exact-commit verification and remote CI remain
separately required. The five false-positive dismissals have not been executed;
the remote security-state mutation requires additional explicit authorization.

Raw, SHA-256-bound command logs retain the tools' original whitespace inside
lossless `.log.gz` artifacts. `LOG-CUSTODY.json` records both compressed and
original-byte hashes. No whitespace exception or other check exemption was
added; decompressing an artifact recovers the exact recorded command output.
