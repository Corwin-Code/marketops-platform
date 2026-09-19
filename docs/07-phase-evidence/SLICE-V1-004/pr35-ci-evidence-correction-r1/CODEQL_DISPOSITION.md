# CodeQL disposition — PR #35

No alert is dismissed, no query pack, rule, severity threshold or path filter is
changed, and test directories stay in scope. Dispositions follow Controller
decision Q5 and `controller-arbitration-57c5efc4-r1/04_CODEQL_TRIAGE.md`.

## Scan identity (baseline at `57c5efc4`)

| Item | Value |
| --- | --- |
| Analysed ref / commit | `refs/pull/35/merge` / `950ef64af8817682b4f731d9dbc35e0d732c892b` (tree equal to `57c5efc4`) |
| Java analysis | id `1791578981`, CodeQL 2.27.0, category `/language:java-kotlin`, 520 results, SARIF id `5c9689d0-b271-11f1-90db-05081f9c62d8` |
| TypeScript analysis | id `1791564133`, CodeQL 2.27.0, category `/language:javascript-typescript`, 1 result |
| Query source used for #641 | `github/codeql` tag `codeql-cli/v2.27.0` (commit `c6baf479093fafc81d4655dc2014dc583360308e`), see `ci-baseline-57c5efc4/codeql-query-source-v2.27.0.json` |
| Result check | `CodeQL` (github-advanced-security) check run `105130010045`: failure, "419 new alerts including 2 high severity security vulnerabilities" (2 high, 7 warnings, 410 notes) |
| Required checks | `codeql-java` and `codeql-typescript` analysis jobs: success. The result check is not in the Ruleset's 12 required contexts; that does not remove the duty to dispose of the alerts. |

The 419 "new in this pull request" count and the 516 open alerts listed for the
PR ref are different sets and are never added or mixed.

Retained sources (all in `ci-baseline-57c5efc4/`): the two analysis API
responses (`codeql-analysis-java-1791578981.json`,
`codeql-analysis-typescript-1791564133.json`); the Java SARIF retrieved through
the code-scanning analyses API on 2026-09-18 (`codeql-java-1791578981.sarif.gz`,
uncompressed SHA-256
`06019ee5fe58d216092af4cb2b9b5b42bb03f24c44e1d3928bb3a8b7a257901f`, 520
results); `codeql-sarif-alignment.json`, which matches each of the nine alerts
below in the Controller's projected inventory to exactly one SARIF result by
rule, file and line and keeps its message; and
`codeql-query-source-v2.27.0.json`, the blob identities and relevant lines of
the query and library used for #641. The individual-alert endpoint that was
closed to the Controller was not needed.

## The two high and seven warning alerts

| Alert | Rule and location at `57c5efc4` | Source fact | Action in commit `48298206…` | Expected on the new scan |
| --- | --- | --- | --- | --- |
| #233, #234 (high) | `java/concatenated-sql-query`, `ListingReworkAuthorizationIT.java:272/276` | A fixture UUID concatenated into `SELECT ops.lc_calibration_digest('…')` in test code | Both queries use `connection.prepareStatement("SELECT ops.lc_calibration_digest(?)")` with `setObject`, on the same connection, after the same `SET TIME ZONE` statements; the UTC = Asia/Taipei equality stays and each read now asserts a row and a non-null digest | Closed by code |
| #642 | `java/misleading-indentation`, `PromotionSimulator.java:197` | `if (value == null) { if (!nullable) invalid(); return; }` is well defined; readability only | Braces only; behaviour identical. Regression `PromotionSimulatorTest.requiredAmountsCannotBeNullWhileDeclaredUnknownsMayBe` covers the `nullable=false` slots and a `nullable=true` slot | Closed by code |
| #641 | `java/missing-case-in-switch`, `ListingDescriptionCommandWorker.java:137` ("no case for UNKNOWN_STATE") | The `pollStatus` switch has `case TIMEOUT, UNKNOWN_STATE -> deferObservation(…)`. At CodeQL 2.27.0 the query tests coverage with `ConstCase.getValue()`, which the library defines as "the `case` constant at index 0", so the second label of a multi-label case is reported missing. Pre-existing alert #186 (`AdBidCommandWorker.java:165`, on `main`) has the same shape | No production change. Regression `ListingDescriptionCommandWorkerTest.Pending.inconclusiveStatusEnquiryKeepsPollingTheSameTask` drives TIMEOUT, UNKNOWN_STATE and an ACCEPTED answer without evidence (rewritten to UNKNOWN_STATE) through `pollStatus`: three status enquiries of the same native task, three deferrals, no re-apply, no transition | Remains open; recorded analyzer false positive, not dismissed |
| #643 | `java/dereferenced-value-may-be-null`, `CanonicalScopeMetricService.java:143` (`denominator`) | A missing store value adds `EXPOSURE_STORE_VALUE_UNQUALIFIED`; the dereference runs only when `gaps.isEmpty()` | No production change. Regression `CanonicalScopeMetricServiceTest.missingStoreDenominatorIsUnknownAndReadsNoMemberWindow` | Remains open; path shown impossible |
| #644 | same rule, `CalibrationService.java:190` (`minimum`) | An absent or non-numeric minimum adds `PROFIT_REFERENCE_VALUE_UNQUALIFIED` and `PROFIT_REFERENCE_BELOW_ACCEPTED_MINIMUM`; the dereference runs only when `gaps.isEmpty()` | No production change. Regression `AcceptedDemandEvidenceTest.absentNonNumericOrNegativeAcceptedMinimumNeverBecomesABoundProfitReference` (absent, textual and negative minimum) | Remains open; path shown impossible |
| #645 | same rule, `ListingOutcomeSupplyEvidence.java:45` (`scenarios`) | A missing scenario set adds `FROZEN_SUPPLY_SCENARIOS_UNQUALIFIED`; the loop runs only when `gaps.isEmpty()`; uncovered products add `FROZEN_SUPPLY_SCENARIO_MISSING:<id>` and the verdict is UNDETERMINED | No production change. Regression `ListingOutcomeSupplyEvidenceTest.absentFrozenScenarioSetForAKnownProductIsUndeterminedWithoutProjection` | Remains open; path shown impossible |
| #646 | same rule, `FixedTrafficComparison.java:58` (`criticalValue`) | `boundQualified` already requires `criticalValue != null`, a positive sign and a finite double, and can only turn false afterwards | No production change. Regression `FixedTrafficComparisonTest.anyUnqualifiedCriticalValueLeavesBothBoundsAbsent` (null, 0, −2, 1E+400) | Remains open; path shown impossible |
| #647 | same rule, `GuardrailService.java:170` (`scope`) | A null scope always adds `LISTING_ACTION_BLOCKED`, so `passed` is false and neither ternary dereferences `scope` | No production change. `ListingGuardrailTest.missingScopeBlocks` now verifies the recorded row: not passed, no calibration package or version, the single blocking reason and `scope=UNAVAILABLE` | Remains open; path shown impossible |

The five null-path warnings are left in production code as they are: an
unreachable fail-closed branch added only to satisfy the analyzer would change
nothing observable, and could not be covered by a test.

## Notes: limited key-path check

Controller Q5 asks that notes touching authorization, ownership, gates or
protection are checked before they are treated as debt. All 36 open alerts of
the rules `java/unused-parameter` (25), `java/local-variable-is-never-read` (5),
`java/uncaught-number-format-exception` (4) and
`java/internal-representation-exposure` (2) on the PR ref were traced
read-only. Ten were introduced by this pull request (`created_at` 2026-09-17);
26 already existed on `main`. Each production-code group was re-checked by an
independent verifier asked to refute. Per-alert results are in
`CODEQL_NOTE_KEYPATH_CHECK.json`.

| Classification | Count | Examples |
| --- | ---: | --- |
| Not a control gap (the control is enforced elsewhere, cited) | 28 | #648/#649: client-supplied `conservativeBound` and protection inputs are ignored on purpose; the bound and vector come from server evidence and the V0122/V0079 triggers; #653: `approvalDecisionId` is ignored in `recordRejection`, while its only caller `ApprovalService.reject` enforces store-scope approval permission, version, state and freshness and records the deciding user with an audit change; #204–#210: declaring `AuthenticatedActor` still makes the argument resolver refuse unauthenticated requests, and the service mints a step-up, one-use grant from the security context that the database consumes |
| Maintenance debt | 8 | dead test locals and parameters; #652 `nodeVerdict`; #130 `startedAt` |
| Security-relevant defect in the alert itself | 0 | — |

**Related latent defect found during the check (`PR35-NOTE-KEYPATH-01`).**
While confirming that #238 cannot throw, the verifier found that the production
transport `BoundedOutboundHttp` keeps only allowlisted response headers
(`BoundedOutboundHttp.java:45-47`, applied at `:180`). The list contains
`retry-after` but not the platform-native `item-retry-after` (Ozon, minutes) or
`x-ratelimit-retry` (Wildberries, seconds) that `RetryAfterUnits.java:47-48`
and V0084 read. With only a native header present, the database records
`ABSENT` rather than `UNKNOWN` (V0084 line 60), `provider_not_before` is not
moved (line 76) and the MO092 provider-wait gates (lines 102, 118) do not hold;
the worker falls back to its 60-second default. The existing test uses a
`java.net.http` stub transport and never exercises `BoundedOutboundHttp`.
Exposure is latent: live description writes need verified capability rows and
an Owner-published Gate EV/E, and `production_write_enabled` is false. The file
is outside envelope 05, so it is **not changed here** and is reported to the
Owner and the Controller with the minimal difference (add the two names to the
allowlist and test through the real transport).

## Remaining alerts: register

The other 507 open alerts on the PR ref (all except the nine above) are
registered in `CODEQL_NOTE_REGISTER.json`, grouped into 26 entries by
severity, rule and module, each with its alert numbers, ref, how many were
introduced by this pull request, how many were key-path checked, an owner and a
review trigger. The entries include 456 `java/deprecated-call` notes and the
12 warnings that already existed on `main` (8 `java/dereferenced-value-may-be-null`,
3 `java/misleading-indentation`, 1 `java/missing-case-in-switch`). The
repository has no CODEOWNERS file, so the owner is recorded as the Human Owner
rather than a narrower maintainer. The register is non-blocking maintenance
debt: no alert is dismissed, no API replacement or dependency upgrade is part of
this correction, and the query pack is unchanged.
