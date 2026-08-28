# Frozen Finding Set R1 — candidate rework mapping

The immutable set is `FROZEN-FINDING-SET-SLICE-V1-001-PR20-R1.json`, SHA-256
`8e5bd4ee3f5727bff9e9d1a7fc58739c635e6fd75483f28a4f302fcb222ae3a8`, bound to
reviewed Head `30d16e5d7db2d2190635a06fececd5883093a876`.
The original Contract plus exact Owner-accepted Amendment-001 remain normative.

This is a rework map, not a Controller closure verdict. All thirteen findings
remain open for independent closure. [C3 full verification](checkpoint-c3/REPORT.md)
and [v1.1 disposition](codeql-v1.1/EXECUTION-RECORD.md) have completed. The
[final handoff](final-handoff.md) requires fresh exact-final-Head verification
and CI; no checkpoint substitutes for that final delivery packet.

| Finding | Root correction and same-class scope | Main verification sources | Remaining closure evidence |
| --- | --- | --- | --- |
| S1-F001 | V0020/V0025 bind command target, prior value, current entity, exact approval and wire identity at the DB boundary; app-role direct writers cannot replace controlled functions. Both apply and restore paths share the gate. | `PriceWritePathIT`, `RegistryVerificationFlowIT`, `OperatingFlowIT`, database privilege/control-boundary suites | Final Head/CI and independent closure |
| S1-F002 | Attempt preparation precedes I/O; raw response and headers have immutable custody and causal completion. Unknown writes are never blindly reapplied. Restore has a distinct stable identity, fresh preflight, conditional version and final readback. | `PriceCommandWorkerIT`, `PriceWritePathIT`, `RawCustodyLocatorTest`, storage adapter tests | Final Head/CI; real platform restore remains Gate EV |
| S1-F003 | Acquisition, price, AI and S3 use the shared bounded outbound client. Destination validation precedes secret use; DNS addresses are pinned, private/special addresses and redirects refused, proxies disabled and request/response sizes bounded. Mounted-secret traversal refuses symlinks. | `BoundedOutboundHttpTest`, `PlatformHttpAdaptersTest`, `HttpModelGatewayTest`, `S3CompatibleObjectStorageTest`, mounted-secret tests | Final scan/CodeQL; actual provider interoperability remains external |
| S1-F004 | UUID routes resolve stored organization/store ownership; subject filters apply before pagination. Signed-token expiry/MFA and live user, role, grant and credential-boundary revocation are enforced. Export/status/download and metric input/source paths retain the same scope. | `SignedBearerIdentityIT`, `OperatingFlowIT`, `DiagnosticExportIT`, business browser | Final Head/CI; real IdP setup remains external |
| S1-F005 | Acquisition, price, AI, custody and normalization I/O reject inherited business transactions. Short prepare/complete transactions leave durable intent; stopped workers and expired leases cannot redispatch uncertain writes. | Worker/AI flow tests, `AuthorizedAcquisitionFlowIT`, `StoredRawReplayIT`, `DiagnosticExportIT` | Final full-suite/Head evidence |
| S1-F006 | Explicit END/NEXT and drift outcomes replace empty-page guesses; quota/backpressure/retry authority is shared in PostgreSQL. Bodies/parsers/pages are bounded, unknown states stop, custody precedes cursor movement and exhausted crashes stop calls. | `AcquisitionPageWorkerTest`, `AcquisitionRunnerTest`, `IngestionAuthorityAndEvidenceIT`, `AuthorizedAcquisitionFlowIT`, normalization/replay tests | Final full-suite/Head evidence; real endpoint quotas remain verified-account facts |
| S1-F007 | V0027 provides account/credential-bound verification submissions, independent review, expiry/revocation and audit. Operation semantics require exact recorded paths/pointers/conditional writes; direct promotion and stale review are refused. | `RegistryVerificationFlowIT`, `PlatformHttpAdaptersTest`, DB privilege tests | Final Head/CI; no production capability is promoted by a fixture |
| S1-F008 | Typed CSV/XLSX import processes complete bounded input beyond 5,000 rows, validates money/quantity/time/header/formula semantics, records row outcomes and applies under atomic status/audit rules. | `FileIntakeFlowIT`, `SpreadsheetReaderTest`, `ImportRowValidatorTest`, fact/normalization tests | Final Head/CI; real redacted customer file schemas remain external |
| S1-F009 | Versioned per-kind AI JSON schemas constrain structure, fields and reference kinds. Fact/inference/recommendation/unknown are distinct; provider failure and expired invocation recover without invented claims or command authority. | `OutputValidatorTest`, `HttpModelGatewayTest`, `OperatingFlowIT`, frontend AI tests | Final Head/CI; useful real-model golden cases require Owner/provider evidence |
| S1-F010 | Full private Yandex workload/ALB/DNS/NAT/SG/state/identity topology; ephemeral write-only DB passwords; PG17/provider extensions; strict standard V0002 and explicit managed external attestation. Hash-bound migration→runtime sequencing, packaged artifact guards and private telemetry. | Terraform schema/mock plans and mutation tests; managed migration/restore suites; runtime transport tests; packaged JAR/images | Exact-Head infrastructure CI; real apply/state/PITR/alert delivery remains external |
| S1-F011 | Risk-bearing parser, outbound, authority, repository, signed servlet, crash and browser tests increased coverage above unchanged gates. Migration inventory/privilege/governance checks cover the complete candidate schema. No blanket exclusions or skip flags were introduced. | C3 full backend 150/151, 372 Python, 196 frontend, 11 browser; 13 checks SUCCESS; 26 source alerts fixed and five exact v1.1 dispositions verified; zero unresolved threads | Final exact Head and fresh full CI/CodeQL; independent Controller closure |
| S1-F012 | Reproducible PG17 representative dataset/query plans; bounded asynchronous snapshot exports with live authorization and immutable download manifest; real local DB/object restore and fault drills including stored-Raw replay. | `RepresentativePerformanceIT`, `DiagnosticExportIT`, `StoredRawReplayIT`, [failure index](failure-drill-index.md), browser 152 | Final full-suite/Head binding; production capacity and provider recovery are not claimed |
| S1-F013 | Current State, as-built design, 41 criteria, assurance implementation-facts, traceability and runbooks distinguish local evidence from external/Owner/Gate conditions. Frozen inputs and original history are preserved. | Governance/readiness mutation tests; [41-source map](criterion-evidence-map.json); protected byte inventory | Final publication identities/CI and Controller handoff synchronization |

## Scan boundaries and limitations

The source scan covers all application HTTP factories, object-store callers,
external-I/O transaction entry points, UUID console routes, typed intake,
normalization, AI schemas, write/readback/restore paths, staging/production IaC,
read/export endpoints and critical failure runbooks. It is a bounded rework
scan against the frozen classes, not a new open-ended Controller discovery pass.

The [same-class source inventory](same-class-source-inventory-134.json) records
HTTP factories, outbound port users, I/O transaction boundaries, controller
routes and bounded read paths. The [CodeQL disposition](codeql-disposition.md)
preserves the original alert analysis. The executed five-alert disposition
and reassessment triggers are in the Owner-accepted v1.1 package; no other
alert dismissal is authorized.
Primary source logs/reports remain under the recorded checkpoint artifacts.
The final report must name which exact commit/tree and merge parents each CI
run tested. Source-level corrections, local tests and a green PR do not grant
Ready, merge, deploy, Gate EV, Gate E, real credentials or production writes.
