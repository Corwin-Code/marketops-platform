# SLICE-V1-003 CI job wall-clock budget review

The initial review was read-only. Following Root's authorization and the completed W3 measurement, only the two backend job `timeout-minutes` values were changed to 60. No Maven, governance, business-SLO assertion, individual test timeout or matrix assessment was changed by this task. W3 remains a failed full run.

## Finding

The accepted Product Contract, source Baseline NFR/CI Gate sections, accepted Slice 3 Contract, Shared Spine and Assurance Matrix do not prescribe a numeric 25-minute or 30-minute CI job deadline. The former values were workflow implementation settings in `.github/workflows/backend.yml:42` and `:142`, now both set to 60.

Both `backend-build` and `backend-integration` execute the full `clean verify` lifecycle. Failsafe includes every `*IT.java` (`backend/marketops-server/pom.xml:325`); the integration job is not a small additional subset. `backend-build` also runs the coverage-threshold refusal, packaged migration/image checks, and artifact publication after Maven. Those steps need wall-clock budget too. Architecture's separate 20-minute job is outside this finding.

## Binding limits remain unchanged

- Baseline `docs/01-requirements/baseline-v1.0-cn.md:1350`: Command Center P95 ≤ 3 seconds, SKU 360 P95 ≤ 4 seconds, asynchronous large export and declared scaling profile. Its CI Gate at line 1565 requires full classes of verification, without a CI-duration cap.
- Accepted Slice Contract §11 (`docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md:2053`): Protection/Regression targeted P95 ≤ 5 minutes, hard ≤ 15 minutes; other targeted hard ≤ 15 minutes; full sweep at least hourly with sufficient cadence margin and visible breaches. AC070, AC071 and AC197 bind the executable evidence.
- `RepresentativePerformanceIT.java:99`: existing `@Timeout(1800)` bounds the full synthetic fixture/setup/performance/recovery test. Its query P95 assertions at lines 196–229, 5-second read timeout, export limits at 309/331, and existing availability latency/cadence assertions at 509–542 remain exact.
- `AdvertisingOrchestrationCapacityIT.java:51`: existing `@Timeout(900)` bounds its full large-capacity method. Targeted assertions at 95–97 remain 300000 ms P95 / 900000 ms hard / zero hard breaches; sweep at 118 remains below 1800000 ms, preserving half of the hourly cadence. Dataset cardinality 1000, production cadence 250 objects / 30 seconds, all three methods, all failure assertions and evidence outputs remain unchanged.

A CI job wall-clock deadline encloses tool setup, compilation, all test methods, post-verification checks and upload. It is not the same measurement as accepted-fact-to-card/Task latency or query P95, and not the timeout of either long-running test method.

## Completed measured basis

W3 exact source Head is `fd3e00e3ab033d823bf5577f8dbfca736f067d86`. Its preserved receipt records 2026-09-05T04:18:27.389559Z through 04:46:51.962903Z, elapsed **1704.575 seconds (28 minutes 24.575 seconds)**. The source and tree stayed clean/stable throughout that measurement.

The original receipt sums suite-declared counts to 2261. The separately preserved raw-node reconciliation and Maven console identify **2262 actual testcase nodes: 1546 unit + 716 integration, 11 failures, 6 errors, 0 skips**. All 17 nonpassing nodes remain failures/errors; W3 `completePass=false`. The one-count difference comes from the already identified nested-method suite declaration discrepancy and is not silently rewritten.

`RepresentativePerformanceIT` completed 1/1 with zero failures/errors/skips in **787.3 seconds**. `AdvertisingOrchestrationCapacityIT` completed all **3/3** with zero failures/errors/skips in **378.379 seconds**. The latter contains the complete 1000-object workload and all original assertions; it is not a reduced workload. These are local measured results, not a remote CI claim.

The original 25-minute build job is shorter than the actual full Maven runtime alone. The original 30-minute integration job leaves about 95 seconds beyond that Maven measurement for runner setup and artifacts, without meaningful variance headroom. `backend-build` additionally runs the coverage-refusal and packaged-runtime verification steps. Increasing the enclosing budget cannot resolve the 11 test failures or 6 errors; those remain separate repairs and rerun requirements.

## Applied minimal change

Only two workflow scalars changed: **60 minutes** for `backend-build` and `backend-integration`. Architecture's budget remains 20 minutes. The job commands, includes, dependencies, datasets, assertions, individual `@Timeout` values, artifact steps and isolation settings are byte-unchanged by this budget patch.

The 60-minute cap encloses the completed 28.4096-minute full Maven workload, post-checks and CI runner variance. It also permits the existing combined 45-minute long-method allowance (30 + 15 minutes) plus 15 minutes for other tests and tool/evidence steps. The combined timeout allowance is a bounded upper allowance, not expected runtime or a replacement for business SLO.

This is the minimum two-line workflow scope that makes both complete-suite jobs use the same explicit, bounded hour budget. Actual Ubuntu CI duration still has to be measured on the final exact Head; no remote pass or future capacity guarantee is inferred from local elapsed time. Any real product-SLO assertion failure remains a failing test at exactly its existing threshold.

Do not split away, skip, remove or narrow tests; do not extend `@Timeout(1800)` / `@Timeout(900)` or business-SLO thresholds; do not infer actual Provider capacity or production readiness. Increasing only the enclosing job budget does not create Provider, production, Ready or merge authority. No accepted Contract §15 incompatibility is established by the existing workflow budget being too short.
