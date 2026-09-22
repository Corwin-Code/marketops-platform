# Controller coverage corrections — PR #35 residual supplement

Append-only register. Earlier entries are in
`../pr35-ci-evidence-correction-r1/CONTROLLER_COVERAGE_CORRECTIONS.md` and are not edited here.

## CRCF-PR35-04

| Field | Value |
|---|---|
| Registered by | Controller review `SLICE-V1-004-PR35-CORRECTION-REVIEW-45474B60-R1` (`controller_coverage_correction`) |
| Basis | Prior end-to-end retry evidence skipped the production shared response-header filter |
| What was missed | A test with a `java.net.http` stand-in transport was accepted as proof of the complete wait chain; the shared allow-list was not checked against its actual consumers |
| Discovery | Reported by the engineering side during the earlier bounded correction and stopped at its authority boundary |
| Reopens all findings | No |
| Effect | Current reuse of S4-DR-R1-022 and S4-DR-R1-027 qualified; see `HISTORICAL_CLOSURE_QUALIFICATION.md` |
| Engineering response | B1 proves retention through the production `BoundedOutboundHttp` filter, the adapter, PostgreSQL and the worker (`B1_WAIT_SIGNAL_TRANSPORT.md`); the remaining stand-ins are named there with what they do and do not prove |
