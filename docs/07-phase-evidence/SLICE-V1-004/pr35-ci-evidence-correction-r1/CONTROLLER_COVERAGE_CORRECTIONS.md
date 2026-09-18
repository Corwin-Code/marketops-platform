# Controller review coverage corrections — PR #35

The Controller's arbitration record
(`controller-arbitration-57c5efc4-r1/01_ARBITRATION_RECORD.json`, SHA-256
`f62a8484d030392ad770dff26d7e366efd7ea7652a29ad9d273d1ecfd6cf734b`,
`controller_coverage_corrections`) registers three review coverage failures
of type `CONTROLLER_REVIEW_COVERAGE_FAILURE`. They are recorded here with the
closing evidence produced by this correction. They are not a 28th frozen
finding and do not reopen the 27 closed findings.

| ID | Subtype | Controller fact (from the record) | Closing evidence in this correction |
| --- | --- | --- | --- |
| CRCF-PR35-01 | `TRANSITIVE_MIGRATION_CLOSURE_VERIFICATION` | V0124 required scanning all latest-migration test consumers; prior limited inheritance did not establish the three managed/upgrade suites. | Every latest-migration consumer was swept at `57c5efc4`: eight stale pins in `AdvertisingProtectedBaseUpgradeIT`, `ManagedMigrationRunnerIT` and `ManagedProfileMigrationIT` were derived from their fixtures (89 = 124 − 35, 124, 114 = 124 − 10, `"0124"` ×5) and corrected; resume values 8 and `"0010"` and every intentional old pin stay. No SQL migration changed. Other consumers (`FlywayMigrationIT.APPROVED_MIGRATIONS`, the readiness validator and its test) already listed V0124. The complete backend `clean verify` of the correction commit is in `local-verification/`. |
| CRCF-PR35-02 | `COMMAND_TO_SOURCE_EVIDENCE_BINDING` | Historical lint header/log integrity was insufficient to prove a pass on the final test source; initial and final manifests differ for that file. | `HISTORICAL_LINT_ERRATUM.md` and `HISTORICAL_FRONTEND_COMMAND_AUDIT.json`. Every local run of this correction records the exact commit, tree, tool versions, real exit code and before/after fingerprints (`local-verification/*/steps.tsv`, `fingerprint-*.txt`). |
| CRCF-PR35-03 | `CI_FIXTURE_ENTRYPOINT_COMPATIBILITY` | The stricter fixture guard was not reconciled with unchanged CI default configuration. Local browser success did not establish CI startup compatibility. | `frontend-test` now starts the business-flow suite through `scripts/validation/business_browser_isolated.sh`, the same entry point used locally: a per-run Compose project and a dedicated non-5432 loopback port, with every `BrowserFixtureApplication` guard unchanged. The local run of that entry point passed 26/26, followed by the advertising suite 12/12. CI compatibility is established only by the PR #35 CI of the published Head. |

## Residual observed during the sweep, outside envelope 05

`scripts/fresh_clone_check.sh` (`make fresh-clone`, not a CI job) still uses
`make env-init` and `make up` on the developer default port and therefore meets
the same 5432 refusal as the unchanged CI did. The readiness validator requires
its `make env-init` and `npm run test:browser` tokens. It is not named in
envelope 05 and is left unchanged; it is recorded here for a separately
authorized follow-up.
