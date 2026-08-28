# CodeQL disposition execution v1.1

See [Owner acceptance](OWNER-ACCEPTANCE.md), [matrix](CODEQL-FALSE-POSITIVE-DISPOSITION-MATRIX-v1.1.json), and [machine result](summary.json).

## Verified result

Five exact false-positive dismissals; five matching threads resolved; zero unresolved threads; all 13 checks SUCCESS. No CodeQL rerun or source change occurred during disposition. PR remains OPEN / DRAFT / UNMERGED.

| Alert | Comment length | Before / after | Thread result |
| --- | ---: | --- | --- |
| #66 | 247 | [before](alert-66-before.json) / [after](alert-66-after.json) | [resolved](thread-66-resolve-response.json) |
| #73 | 253 | [before](alert-73-before.json) / [after](alert-73-after.json) | [resolved](thread-73-resolve-response.json) |
| #74 | 261 | [before](alert-74-before.json) / [after](alert-74-after.json) | [resolved](thread-74-resolve-response.json) |
| #75 | 254 | [before](alert-75-before.json) / [after](alert-75-after.json) | [resolved](thread-75-resolve-response.json) |
| #76 | 248 | [before](alert-76-before.json) / [after](alert-76-after.json) | [resolved](thread-76-resolve-response.json) |

## Comparison scope

Of 31 PR-scoped alert records, exactly the five authorized records changed. The other 26 are identical. All six unrelated review-thread records are identical. The separate default-branch inventory returned zero records both times; it is not an inventory of every historical ref. No other alert mutation was issued.

## Source inspection

Source bytes matched the accepted C3 tree; [source hashes and thread mapping](verified-before.json) preserve their identity. The following matrix bases were checked against the current source and supporting test contracts.

### #66 — Disabled Spring CSRF protection

`backend/marketops-server/src/main/java/com/mimococo/marketops/identityaccess/internal/web/IdentitySecurityConfig.java:83`

- Spring Security is STATELESS and the console accepts only an explicit validated Authorization bearer token.
- No form login, HTTP Basic, OAuth2 login, authentication cookie, servlet-session authentication, query-token or form-token path is active.
- SignedBearerIdentityIT proves cookie/query/form/session inputs cannot authenticate, no Set-Cookie/session is produced, and hostile Origin mutation is refused even with a valid bearer.

### #73 — Empty password in configuration file

`backend/marketops-server/src/main/resources/application-production.yaml:21`

- The empty value is an inactive override under spring.flyway.enabled=false.
- It clears the inherited migration-secret placeholder from the serving application process; it is not an application or migration credential.
- The serving process receives only the application-role credential, while the separate managed migration executable requires its own credential and attestation envelope.
- ApplicationConfigurationTest.rolesAreSeparated and packaged migration tests pin this separation.

### #74 — Cross-site scripting

`backend/marketops-server/src/main/java/com/mimococo/marketops/shared/internal/http/BoundedOutboundHttp.java:167`

- The flagged setEntity call is Apache HttpUriRequestBase for an outgoing provider request, not a servlet/browser response sink.
- HTML escaping would corrupt provider request bytes and their digest binding.
- The outbound layer enforces HTTPS, allowlisted destination/method/path/headers, pinned public DNS, no redirects/cookies/retries, and body/response bounds.
- BoundedOutboundHttpTest observes exact markup-containing bytes only on a local transport fixture while public preparation still rejects unsafe destinations.

### #75 — User-controlled bypass of sensitive method

`backend/marketops-server/src/main/java/com/mimococo/marketops/analyticsdecision/internal/application/DiagnosticExportService.java:72`

- The user-controlled part-number condition is a denying guard that throws and returns no data.
- Every successful path resolves stored requester/organization ownership and live store scope, then calls the DB authorization function before storage I/O.
- The service reauthorizes after I/O before returning bytes.
- DiagnosticExportIT covers invalid parts, foreign IDs, revoked access and expired jobs.

### #76 — User-controlled bypass of sensitive method

`backend/marketops-server/src/main/java/com/mimococo/marketops/analyticsdecision/internal/application/DiagnosticExportService.java:80`

- The integrity condition throws on length/hash mismatch and returns no bytes.
- A successful response requires authorization before I/O, exact custody length/hash verification, and database reauthorization after I/O.
- Revocation during object read returns forbidden and does not write a PART_VERIFIED audit event.
- Corrupt/expired content is refused.

## Execution notes

Each mutation used `gh api --method PATCH` with its exact `alert-N-request.json`, followed by a separate GET and exact persisted-comment verification. Only then was the matching GraphQL thread resolution submitted. Request/response files and full before/after instances are retained.

Alert #75 first returned a network EOF. Readback showed no dismissal. The same authorized request was retried with escalation and succeeded; no comment or reason was changed.

The detail endpoint returned a null top-level state for the initially open PR-only alerts. The PR-scoped inventory and current instances both reported OPEN. Detail responses also include a redundant message.markdown field absent from the list endpoint; comparison retained all identity fields and the exact message.text.

These are security-alert dispositions, not Controller approval of the Frozen Findings or any release authorization. The final canonical commit still requires complete verification and fresh CI on its exact Head.
