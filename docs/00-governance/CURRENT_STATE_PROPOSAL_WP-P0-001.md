# Proposed Current State after WP-P0-001

This is a merge candidate, not the current record. The canonical
`CURRENT_STATE.md` changes only after the Pull Request Gate passes and the Human
Owner merges the approved candidate.

## Candidate facts

| Area | Candidate state |
| --- | --- |
| Local configuration and prerequisite reporting | IMPLEMENTED_AND_LOCALLY_VERIFIED |
| Three production-readiness rules and mutation tests | IMPLEMENTED_AND_LOCALLY_VERIFIED |
| Official Maven Wrapper, backend and module foundation | IMPLEMENTED_AND_LOCALLY_VERIFIED |
| Metadata, correlation, failure and finite CORS contracts | IMPLEMENTED_AND_LOCALLY_VERIFIED |
| PostgreSQL roles, migration and privilege proofs | IMPLEMENTED_AND_LOCALLY_VERIFIED |
| Seven architecture rules and sensitivity fixtures | IMPLEMENTED_AND_LOCALLY_VERIFIED |
| Operations console and validated configuration boundary | IMPLEMENTED_AND_LOCALLY_VERIFIED |
| Real-browser ready and database-outage transitions | IMPLEMENTED_AND_LOCALLY_VERIFIED |
| Eleven pinned CI/security job definitions | READY_FOR_PR_EXECUTION |
| Backend/frontend CycloneDX and licence inventories | IMPLEMENTED_AND_LOCALLY_VERIFIED |
| Special-character full Fresh Clone | IMPLEMENTED_AND_LOCALLY_VERIFIED |

## Deliberately absent from WP-P0-001

- marketplace clients, credentials and production PII;
- business/domain tables and external-platform writes;
- authentication, deployment artifacts, a broker or a service split;
- any claim that the whole project is production-ready outside this foundation.

## Merge conditions

1. the committed publication Head passes the full local Gate and Fresh Clone;
2. all eleven Draft PR checks pass on the current Head;
3. High/Critical security alerts are inspected and disposed;
4. Controller review issues the merge verdict;
5. the Human Owner performs or separately delegates the merge.

Until those conditions hold, `docs/00-governance/CURRENT_STATE.md` remains
unchanged and `production_write_enabled` remains `false`.
