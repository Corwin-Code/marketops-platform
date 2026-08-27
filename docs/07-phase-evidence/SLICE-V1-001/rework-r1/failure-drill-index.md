# Local failure and recovery evidence

Status: continuous rework in progress. These scenarios use isolated local
PostgreSQL, synthetic identities and synthetic external ports. They do not
prove real-account interoperability, production recovery or notification
delivery. Final exact-commit verification remains required.

| Scenario | Executable evidence | Observed behavior / remaining gap | Procedure |
| --- | --- | --- | --- |
| API outage | `business-journey.spec.ts`, TC-BROWSER-013 | Browser 129 passes aborted API read/recovery and `health-shell.spec.ts` real database stop/restart. A failed read must show an error and no operating rows; restoring transport must permit the real signed/SQL flow. | `browser-smoke.md`, `troubleshooting.md` |
| Backlog / retry / crash | `AuthorizedAcquisitionFlowIT.operationalBacklogTracksDurableRunAgeAndClearsAfterControlledCompletion`; `IngestionAuthorityAndEvidenceIT.repeatedWorkerCrashesExhaustThePersistedBudgetWithoutAnotherCall` | Real DB state, bounded claim budget, shared quota and aged-run signal; the new backlog case passes in full run 128. | `acquisition-backlog.md` |
| Stored Raw replay | `AcquisitionRunnerTest.replayRecordsItsLifecycleAndMakesZeroMarketplaceCalls`; `NormalizationRunnerTest` failure/cursor tests; `FactRecorderTest.everyDatasetRetainsSourceIdentityAndReplayUsesTheSameLogicalKey` | Unit boundary proofs pass; [Stored Raw replay 130](stored-raw-replay-130/ARTIFACT-HASHES.json) now passes on real PG17: missing bytes and parser faults retain progress; crash after committed facts followed by replay creates no duplicate logical fact or source call. Final full-suite integration remains pending. | `acquisition-backlog.md`, `evidence-custody.md` |
| AI failure / stopped worker | `OperatingFlowIT.providerFailureClosesThePreparedInvocationWithoutPersistingClaims`; `stoppedAiWorkerLeavesDurableIntentAndExpiredRecoveryNeverRedispatches` | Durable failed/expired invocation, no invented claims or redispatch, no command side effects; full 123 and focused 125 pass. | `capability-verification.md`, `troubleshooting.md` |
| Unknown write / compensation | `PriceCommandWorkerIT.unknownWriteOutcome`, `interruptedDispatchIsNeverReapplied`, `compensationDoesNotOverwriteOrGuess` | A possibly executed write remains unknown; replay does not apply again. Restore uses a fresh conditional observation and cannot claim success without final readback. Full 123/focused 125 pass. | `price-command-resolution.md` |
| Custody fault / export crash | `DiagnosticExportIT.storageFailureRetriesAndCorruptOrExpiredContentNeverDownloads`; `crashAfterUploadAndPartCommitResumesTheSameSnapshotWithNewFences` | Durable snapshot/parts resume under a new fence; corrupt/missing/expired bytes are refused. Full 123 passes. | `diagnostic-export.md`, `evidence-custody.md` |
| Database outage / recovery | `OperatingFlowIT.operationalSnapshotBecomesUnknownDuringDatabaseLockAndRecoversWithoutWrites` | Two-second lock timeout produces only database-unavailable; after rollback all six signals resume. Focused 125 passes. | `operational-monitoring.md` |
| Database + object restore | `RepresentativePerformanceIT` and `ManagedProfileMigrationIT` | Separate PG17 restore, history/privilege validation, zero new migrations on revalidation, missing-object refusal then exact-byte recovery. [Full 123 report](full-backend-123/representative-v1.json) and managed evidence. | `database-restore-drill.md` |
| Telemetry delivery failure | `tests/test_yandex_telemetry.py`; `SignedBearerIdentityIT.operationalSnapshotTrustsOnlyTheSocketPeerAndNeverBearerOrForwardedHeaders` | Local transport faults, response bounds, no proxy/redirect, stale snapshot refusal, no sensitive logs and private access are tested. Actual Yandex metric receipt/alert/channel/acknowledgement/recovery remains pending. | `operational-monitoring.md` |

All named procedures are under `docs/06-runbooks/`. A successful software fault
injection does not authorize deliberately causing a production incident.
