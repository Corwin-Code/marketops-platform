# SLICE-V1-003 — Codex R1 evidence

This is the active evidence index for `OWNER_CODEX_SLICE_V1_003_ROOT_CAUSE_REWORK_R1`.
Codex has completed the scoped engineering rework and verification assessment:
all 22 Frozen findings are `CLOSED_WITH_EVIDENCE` by Codex, S3-AC-001 through
S3-AC-199 are `VERIFIED`, and S3-AC-200 is
`CANDIDATE_PREREQUISITES_PASS_CONTROLLER_PENDING`. These are engineering
dispositions, not an independent Controller or Human Owner closure verdict.
All 24 release obligations remain production-blocking and
`production_write_enabled=false`.

The accepted Contract SHA-256 is
`1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c`.
The Frozen Finding Set SHA-256 is
`15b3c076fc7f1d283a2c7359d9647d91d3ecfccd9b229be1f734f4e7d4ceefc1`.
The reviewed starting Head is `a0711f1ae430e70ab7ec06917004e9dbfd1fb4eb`.

| Record | Purpose and current boundary |
| --- | --- |
| [Takeover receipt](TAKEOVER_RECEIPT.md) | Completed read-only authority, package and repository checks. |
| [Finding matrix](FINDING-CLOSURE-MATRIX.json) | All exact 22 Frozen findings with individual root cause, same-class scan, transitive impact, tests and Codex engineering closure evidence; Controller remains pending. |
| [Acceptance status](S3-AC-REWORK-STATUS.json) | Exact 200 Contract criteria: 199 engineering-verified and AC200 Controller-pending. No Maker status is inherited. |
| [Release obligations](S3-REL-DEFERRED-REGISTER.json) | All 24 exact obligations remain production-blocking. |
| [Engineering verification](ENGINEERING_VERIFICATION.json) | Consolidated counts, measured source identities, execution layers, capacity/security limits and authority boundary. |
| [Migration inventory](MIGRATION-INVENTORY.json) | All 65 inspected migrations and exact preserved V0001–V0035 comparisons; committed identity and execution are separate receipts. |
| [Facts and priority](workstreams/facts-priority.md) | Canonical facts, economics, purpose freshness and deterministic rank. |
| [Human decisions](workstreams/human-decisions.md) | Responsibility, clocks, Accepted Exception, finite targets and materiality. |
| [Command controls](workstreams/command-controls.md) | Identity, immutable leases, exposure, compensation and containment. |
| [Outcome](workstreams/outcome.md) | Trusted pre-execution baseline, stage-distinct evaluation and revisions. |
| [Console and orchestration](workstreams/console-disclosure.md) | Scoped disclosure, Manual workflows, triggers, capacity and browser evidence. |
| [CI gate review](workstreams/ci-gates-review.md) | Required contexts and exact workflow/job/artifact collection requirements. |
| [Facts/Outcome fault assertions](workstreams/facts-outcome-fault-seeding.json) | Canonical facts, freshness, actual concurrent calculation and distinct Outcome/late-revision boundaries. |
| [Human fault assertions](workstreams/human-decisions-fault-seeding.md) | SLO, risk, amount/unit, conservative ceiling and each independent Materiality fault. |
| [Command fault assertions](workstreams/command-controls-fault-seeding.md) | Positive controls and schema, privilege, creator, transmission, retry and containment boundaries. |
| [Disclosure and Manual fault assertions](workstreams/disclosure-manual-fault-seeding.md) | Exact role/scope, actor, evidence grade and actual HTTP boundaries. |
| [Final targeted rerun](workstreams/final-targeted-r34.json) | All 97 selected backend tests pass, including real vertical/concurrent paths, Human16, Disclosure7 and Sealed15; clean full verification remains separate. |
| [Semantic mapping review](workstreams/human-governance-mapping-review-supplement.md) | Each of 41 human/governance criteria is mapped to actual assertions with explicit domain, PostgreSQL, HTTP and remaining verification limits. |
| [W2 exact full attempt](workstreams/full-clean-w2/run-receipt.json) | Clean commit b871d637: 1,545 actual unit test cases with three failures; one nested-suite aggregate declares 1,544. The [separate count reconciliation](workstreams/full-clean-w2/testcase-count-reconciliation.json) preserves that discrepancy. Integration/package/capacity phases were not reached. |
| [Post-full repairs](workstreams/post-full-repair-r35.json) | All 39 relevant tests pass after Clock injection and exact snapshot/sealed-expiry fixture corrections; full clean rerun is still required. |
| [W3 exact full attempt](workstreams/full-clean-w3/run-receipt.json) | Clean commit fd3e00e3: 2,262 actual cases (1,546 unit, 716 integration), 11 failures, 6 errors, zero skipped; all three advertising capacity cases pass. Full verification failed. Original XML, log, coverage and artifact hashes are preserved with a separate count reconciliation. |
| [CI execution budget](workstreams/ci-job-budget-w3/receipt.json) | The complete W3 run took 1,704.575 seconds. Both full-backend CI jobs receive 60 minutes; business SLOs, performance assertions, individual test timeouts and coverage thresholds are unchanged. |
| [W3 evidence scan](workstreams/full-clean-w3-publishability-scan.json) | All 2,075 text files and archive members were scanned for repository secret patterns and JWT shapes without exposing matched values; zero matches. |
| [W3 committed identity](workstreams/slice3-r1-w3-identity.json) | Exact clean source measured by W3; subsequent repairs require a new checkpoint and a complete clean rerun. |
| [Post-W3 regression results](workstreams/post-w3-targeted-r38/receipt.json) | R38 passes all 24 complete advertising flow tests. R37's other seven complete classes pass 74 cases; its four failing queue cases and R36's earlier failures remain preserved. The [source delta](workstreams/post-w3-targeted-r38/r37-r38-source-delta.json) proves only the flow test changed between those runs. A clean full rerun is still required. |
| [Unknown-set boundary review](workstreams/unknown-affected-set-review.json) | Bounded independent source review verifies explicit unknown state, advertising Task scope, historical disclosure and strict controlled-write eligibility; review is distinct from runtime evidence. |
| [Shared Task read regression](workstreams/post-w3-root-repairs.md) | Independent diagnostic read grants, revocation reason and no journal append on refusal are exercised by all seven passing journal tests in R37. |
| [Unknown-set UI and disclosure](workstreams/post-w3-ui/receipt.json) | All 19 relevant frontend tests pass; unknown impact is explicit. The actual historical unknown Case HTTP disclosure test joins the seven prior passing disclosure tests in R37. |
| [Post-W4 final control runs](workstreams/final-controls/index.json) | R21 executes 289 actual cases: all nine actual worker, twelve authority-version, twenty-five Gate E and twenty-one cross-domain cases pass; the containing run has one missing-Bundle diagnostic failure. Its one-line correction passes all 83 related cases in R22. R15 separately proves 17 real minimum-expiry boundaries. Original failures, exact source deltas, manifests and raw reports remain preserved. |
| [Post-W4 control repairs](workstreams/post-w4-control-rework.md) | Causal changes, exact R15–R22 measured boundaries and remaining full-source requirements; selected tests do not substitute for final clean regression or CI. |
| [Six-axis frontend quality](workstreams/post-w4-ui/exposure-quality-r3/receipt.json) | All 308 frontend tests and complete format/lint/type/build/coverage/bundle/SBOM checks pass at an exact stable source fingerprint. The legacy fixture now matches the six-axis contract; its four real HTTP refusal tests remain byte-identical. Browser runtime verification is still pending. |
| [W5 committed identity](workstreams/slice3-r1-w5-identity.json) | Clean published Head 247ea5c binds the W5 runtime repair and original-log supplement. Later principal, synthetic SQL, dependency and fixture corrections need a new checkpoint. |
| [Draft PR publication](workstreams/publication-w5/receipt.json) | Ordinary append-only publication and unique Draft PR #30, exact source/tree and unmerged/no-auto-merge readback; implementation verification remains in progress. |
| [Initial W5 CI](workstreams/ci-w5-initial/receipt.json) | Exact tested merge and 12 required contexts at the first snapshot. This is an unfinished historical snapshot, not final CI evidence. |
| [Actual W5 security review](workstreams/security-pr30-247e/artifact-index.json) | Four existing dependency advisories and all 99 analyzed CodeQL alerts; the aggregate CodeQL check failed despite successful scanner jobs. Original API/source/log evidence, individual triage and narrow repairs remain distinct from final rescan. |
| [Principal boundary verification](workstreams/post-w5-security-principal/review.json) | Real HTTP tests cover all seven reads with authenticated, anonymous, spoofed and revoked identity/scope inputs. R23 passes 175 cases in all 15 related full classes. |
| [Synthetic issuer SQL verification](workstreams/post-w5-security-issuer/repair.json) | Bound PostgreSQL quoting replaces five test-only SQL concatenations; actual hostile-shape synthetic password and application-role negatives pass. Latest CodeQL remains required. |
| [Repaired-lock frontend verification](workstreams/post-w4-ui/exposure-quality-r5/receipt.json) | Fresh dependency installation, all 308 tests and actual SBOM schema validation pass. R4's skipped validator is preserved as incomplete. |
| [W4 complete clean verification](workstreams/full-clean-w4/run-receipt.json) | Clean commit 4e101ca4: all 2,264 actual cases pass (1,546 unit, 718 integration), zero failures/errors/skips, JaCoCo passes, representative capacity 810 seconds and all three advertising capacity cases 356.931 seconds. Full run 1,724.270 seconds; 175 original XML reports, resources, actual PostgreSQL image identities and embedded JAR identity are preserved. |
| [W4 committed identity](workstreams/slice3-r1-w4-identity.json) | Exact measured clean Head/tree, unchanged authorities/history and all 65 migration identities. Later F014/F005 runtime and F003/F021 test supplements require a new complete source measurement. |
| [W4 named backend results](workstreams/slice3-r1-named-junit-w4.json) | 236 Java test references match actual passing cases in the archived W4 reports. Python and frontend/browser references remain separately measured; this index does not automatically close criteria or findings. |
| [W4 evidence scan](workstreams/full-clean-w4-publishability-scan.json) | All 2,078 text files/archive members, 43,902,245 bytes, scanned against repository secret patterns and JWT shapes; zero matches. |
| [Pre-W6 named governance](workstreams/pre-w6-governance/receipt.json) | All 410 actual methods pass, zero failures/errors/skips, with the principal, dependency and synthetic SQL repairs included in a stable measured worktree. |
| [Pre-W5 named governance](workstreams/pre-w5-governance/receipt.json) | Complete governance passes all 410 actual methods, zero failures/errors/skips, on the frozen post-repair worktree; source boundary remains stable. This is preparatory verification for the next clean checkpoint. |
| [Pre-W4 named governance](workstreams/pre-w4-governance/receipt.json) | Complete governance passes 410 methods with no failures, errors or skips, on the explicitly frozen pre-W4 worktree. Named Python references are indexed to actual results. |
| [Named governance proof review](workstreams/f001-f022-governance-proof-review.json) | F001/F022 map to actual named positive and negative assertions with their limits; static tests cannot substitute for real Git, PR or CI observations. |
| [W1 committed identity](workstreams/slice3-r1-w1-identity.json) | Clean implementation checkpoint `60638b1fc1a227b50f4b3ede1ba0bb983407bfdc`, protected authorities/history and all 65 migration identities. |
| [W1 independent clone](workstreams/fresh-clone-w1.json) | Exact committed source, independent Git objects, no ignored local configuration/build state, and 410 passing governance tests; offline scope only. |
| [W1 infrastructure](workstreams/infrastructure-w1/receipt.json) | Seven Terraform mock cases and 29 Python cases passed on an exact source copy; local plans only, with raw/compressed hashes. |
| [W6 complete clean verification](workstreams/full-clean-w6/run-receipt.json) | Exact clean Head 3ed3f4c: all 2,472 actual testcase nodes pass (1,552 unit, 920 integration), zero failures/errors/skips; JaCoCo and the packaged JAR identity pass. The 188 original XML reports and [count reconciliation](workstreams/full-clean-w6/testcase-count-reconciliation.json) preserve the original suite-declared 2,471 count separately. All three advertising capacity cases pass in 408.736 seconds; the declared 1,000-object workload records critical P95 37.148 seconds, maximum 265.047 seconds and a 136.393-second sweep. These are W6 observations, not results for later repairs. |
| [W6 packaged artifact check](workstreams/validation-w6/packaged-migration/receipt.json) | The exact full-verified W6 JAR passes the packaged resolver and minimal synthetic container check. This check connects to no database or Provider; the full-run PostgreSQL migration evidence remains separate. |
| [W6 coverage refusal proof](workstreams/validation-w6/negative-coverage/receipt.json) | Deliberately forced 100% line/branch thresholds fail with the expected coverage reason; the repository enforcement script passes. Original accepted thresholds, complete execution data and the verified JAR remain byte-identical. |
| [W6 actual browser and frontend verification](workstreams/browser-w6/verification-summary.json) | Exact W6 source passes 12 advertising and 25 original browser journeys, plus fresh dependency installation, all 308 frontend tests and actual SBOM validation. Twenty-six advertising screenshots and the [visual review](workstreams/browser-w6/visual-review/review.json) retain the synthetic read-oracle and UNVERIFIED-platform boundaries. |
| [W7 actual frontend CI](workstreams/frontend-ci-w7/receipt.json) | Run 33963350083 attempt 1 on W7 Head 3e403925 / tested merge 1d48739f passes all four frontend jobs, 308 unit cases and all 25 original plus 12 advertising browser journeys. Exact [named browser results](workstreams/frontend-ci-w7/named-browser-results.json), artifact digests and 26 screenshots are retained. W7 changes only the original business-journey queue-alert assertion; W6 local results are not relabeled as W7 executions. |
| [W6 exact security readback](workstreams/security-w6/summary.md) | Security and aggregate CodeQL pass; twelve repaired alerts are fixed and the remaining 87 open alerts are quality warnings/notes without security severity. Exact 99-to-87 reconciliation and individual triage remain preserved; raw SARIF also retains five historically dismissed HIGH findings. |
| [W7 exact security readback](workstreams/security-w7/summary.md) | Security run 33963350077 attempt 1 and aggregate CodeQL pass on the exact W7 tested merge. The same twelve alerts remain fixed and all 87 quality alerts remain unchanged. The branch lock removes four fast-uri HIGH advisories; the four default-main Dependabot alerts are not claimed closed. |
| [W7 failed backend CI](workstreams/ci-w7-failed/receipt.json) | Backend run 33963350093 attempt 1 fails: build job 101299023501 has 2 failures among 2,472 actual cases; integration job 101299023481 has 3 failures among 920 cases, both with zero errors/skips. The [original jobs and artifact identities](workstreams/ci-w7-failed/run-job-artifact-index.json) preserve the Case-age/replay precision and shared-test-queue Price worker failures. Ten of twelve required contexts pass; the two backend contexts fail. R24/R25 follow-up results are indexed below; a new clean checkpoint/full run/latest CI remain PENDING. |
| [W7 CI root cause and repair](workstreams/backend-ci-w7-repair/analysis.json) | Original job logs and downloaded XML bind the two W7 backend failures to their actual tested merge. PostgreSQL timestamp precision and an unscoped shared test queue explain the failures; repaired source identity remains distinct. |
| [R24 original repair attempt](workstreams/final-controls/r24/receipt.json) | All 18 Price worker, 34 calculation, 24 advertising flow and 10 priority tests pass; the containing 130-case run fails with one newly added projection-origin oracle failure and two Raw fixture setup errors. Both remaining capacity methods pass. The [individual review](workstreams/r24-individual-suite-review/review.json) preserves the outer-only resource binding limitation and the exact failures. |
| [R25 complete affected verification](workstreams/final-controls/r25/receipt.json) | All 72 cases pass: Reservation 41, Human workflow 18, advertising capacity 3, exact-duration reconciliation worker 4 and staffed-clock 6. The run takes 454.739 seconds with unchanged source and exact inner/outer resource SHA. The [six-file source delta](workstreams/input-delta-r24-r25/receipt.json) identifies the Raw fixture, correct projection-origin oracle, Task SLO and reconciliation changes. The 1,000-object workload and missed-correction recovery remain intact; this selected run does not replace the new clean full checkpoint. |
| [Time precision same-class review](workstreams/nanosecond-same-class-review/review.json) | Case and Task age distinguish the same persisted microsecond from a genuinely future origin. The [reconciliation repair](workstreams/reconciliation-duration-repair/receipt.json) checks exact negative/15-minute Duration boundaries before integer conversion; all original SLO thresholds remain unchanged. |
| [Frozen-clause review](workstreams/frozen-22-clause-review/review.json) | All 115 original required-rework/verification clauses across 22 findings are reviewed against specific proof. The identified dedicated F013 scope refusal now passes in R25; final source/CI and the central assessment remain pending. Capacity is the declared UNVERIFIED zero-command topology, including brief publication, Outcome due scanning and blocked gate reads; it does not measure mature Outcome or admissible APPLY throughput. |
| [Individual acceptance arguments](workstreams/prepared-assessment-union-w8/per-criterion-reviewed-contributions.json) | All 200 exact criteria retain 260 individual contributions, with 54 overlapping criteria and explicit proof scopes. The union preserves the original measured-source identities; it performs no automatic status promotion or Controller judgment. |
| [Pre-W8 named governance](workstreams/pre-w8-governance/receipt.json) | All 410 actual methods pass with zero failures/errors/skips on the frozen repaired worktree. Exact accepted/Frozen authority and 200/22 mapping checks pass; central rows remain verification-pending. |
| [W8 complete clean backend](workstreams/full-clean-w8/run-receipt.json) | Exact clean Head `9b6e6195`: 2,484 actual test nodes pass (1,560 unit and 924 integration), zero failures/errors/skips; 189 original XML reports, unchanged 80/70 coverage thresholds, packaged JAR, migration and capacity evidence are retained. |
| [Current named backend catalog](workstreams/current-named-backend/current-named-backend-evidence.json) | 390 exact methods expand to 522 passing nodes; current source bytes and W8/W9 raw report identities are rechecked. Test totals alone do not issue acceptance. |
| [W8 exact required CI](workstreams/ci-w8/receipt.json) | Twelve required contexts and aggregate CodeQL pass on the exact W8 PR Head/tested merge; separate backend, frontend, governance/infrastructure and security artifacts retain raw evidence. |
| [W9 SLO display quality](workstreams/ui-slo-repair-w9/quality-r4/receipt.json) | All 327 frontend tests plus lint, format, type, coverage, build, bundle-isolation and SBOM generation pass on a stable source fingerprint; 19 named cases cover the repaired response/clock boundary. |
| [W9 actual browser](workstreams/browser-w9/receipt.json) | Twelve isolated advertising HTTP/JWT/IAM/SQL/rendering journeys pass on W9 Head `52a34f36`; 27 synthetic data identities and 26 screenshots are retained with visual review. |
| [W9 exact CI assessment](workstreams/ci-w9/summary.json) | Twelve required jobs and aggregate CodeQL pass on W9 Head/tested merge. Raw logs/artifacts prove 2,484 backend nodes, 327 frontend tests, 25 legacy plus 12 advertising browser journeys, 410 governance tests and security disposition. |
| [W9 distribution defect](workstreams/ci-w9/frontend-distribution-defect.json) | The W9 uploaded console lacks the authored Head and retains a canary `published-*` value; the original failing artifact is preserved rather than restamped. |
| [Distribution-order repair](workstreams/frontend-distribution-order-repair/receipt.json) | The canary runs first and a second official build replaces it before upload. Local final bytes carry the authored Head, `ci` and loopback API with no canary UUID or unknown build-commit initializer; containing-Head CI must repeat the proof. |
| Frontend SBOM validation repair | The JSON validator packages are direct exact lockfile dependencies and `generate-validated-sbom.mjs` fails if CycloneDX reports that validation was skipped; local and containing-Head CI must print the explicit schema-validation PASS. |
| [Final local pre-publication run](workstreams/pre-publication-local-w10/receipt.json) | Node 24.19.0/npm 11.17.0: 418 governance tests, lint, format, typecheck, 327 frontend tests with coverage, negative coverage gate, bundle isolation, official distribution replacement, CycloneDX 1.6 schema validation, dependency inventory and zero-vulnerability npm audit all pass; raw logs are hash-preserved. |
| [Individual 200-criterion assessment](workstreams/engineering-assessment-w9/criterion-engineering-assessment.json) | Exact accepted text, 260 specific contributions, current implementation hashes, named positive/adverse proof and measured execution bindings for each criterion. |
| [Current UI81 assessment](workstreams/engineering-assessment-w9/ui81-current.json) | All 81 UI-related criteria, the 19 new SLO assertions and exact local/remote browser-title bindings with their source/unit boundaries. |
| [Individual 22-finding assessment](workstreams/engineering-assessment-w9/finding-engineering-assessment.json) | Every Frozen finding and all 115 unmodified rework/verification clauses receive an individual engineering reason and evidence disposition. |

The ZIP/TAR-aware [raw publication scan](workstreams/security-w6-w7-raw-scan.json)
preserves 16 matches in the CodeQL rule-help metadata. The
[exact-member triage](workstreams/security-w6-w7-secret-triage/review.json)
identifies those matches as static teaching examples, with archive/member hashes
and JSON pointers. The original scanner failure and raw SARIF are unchanged;
this scoped review does not dismiss actual CodeQL findings.

Workstream logs keep failing, partial and passing runs under their original
identities. W4, W6, W7 and all targeted attempts remain historical evidence.
The final backend implementation bytes were measured by the clean W8 run and
rechecked in W8 and W9 remote CI. W9 changes only the frontend SLO presentation
and its tests; complete local quality, actual browser execution and exact remote
CI bind those bytes to W9. The post-W9 workflow-only repair corrects the order
of canary and official distribution builds. Its containing commit requires a
fresh remote CI artifact readback, which is recorded externally after append-only
publication because this committed index cannot refer to its own future commit.

The declared capacity result covers 1,000 `UNVERIFIED` native objects, 200
critical objects and 1,200 Tasks in one synthetic organization/store/product
topology. Critical P95 is 30,789 ms, maximum is 239,115 ms, targeted execution
is 237,495 ms, the full sweep is 109,169 ms, hard-bound margin is 3,490,831 ms,
and one dropped correction is recovered. The population admitted zero commands
and contains no mature Outcomes. It provides no APPLY, mature Outcome or
multi-store throughput claim.

`scripts/validation/assemble_slice3_rework_evidence.py` remains the conservative
mapping-stage generator and always clears any later engineering closure claim.
`scripts/validation/finalize_slice3_rework_assessment.py` is the separate final
derivation: it verifies immutable authorities, exact 41/87/51/81 contribution
counts, the 260-to-200 union, W8/W9 measured evidence, 22 findings, 115 clauses,
the AC200 boundary and unchanged 24-item release register before writing the
central matrices. Its `--check` mode refuses stale checked-in output.

Earlier Slice files outside this directory, including `r2-implementation-handoff.md`,
`r3-implementation-handoff.md`, `S3-AC-STATUS.json`, `acceptance-status.md` and
`executable-evidence.md`, retain the Maker's historical reports. Their stale
identity or incomplete verification statements are not the current R1 result.
They are preserved for review, without overwriting prior evidence.

The unique PR remains Draft. No real Provider, shared/production environment,
Ready, merge, force-push, deployment, Gate EV/E activation or credential
provisioning is authorized by this record. Independent Controller Final Closure
Verification remains the next gate.
