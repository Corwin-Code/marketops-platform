# SLICE-V1-003 — Codex R1 evidence

This is the active evidence index for `OWNER_CODEX_SLICE_V1_003_ROOT_CAUSE_REWORK_R1`.
Rework and verification are in progress. It does not assert engineering closure,
Controller approval or production enablement. `production_write_enabled=false`.

The accepted Contract SHA-256 is
`1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c`.
The Frozen Finding Set SHA-256 is
`15b3c076fc7f1d283a2c7359d9647d91d3ecfccd9b229be1f734f4e7d4ceefc1`.
The reviewed starting Head is `a0711f1ae430e70ab7ec06917004e9dbfd1fb4eb`.

| Record | Purpose and current boundary |
| --- | --- |
| [Takeover receipt](TAKEOVER_RECEIPT.md) | Completed read-only authority, package and repository checks. |
| [Finding matrix](FINDING-CLOSURE-MATRIX.json) | The exact 22 frozen findings; current-source verification remains explicit. |
| [Acceptance status](S3-AC-REWORK-STATUS.json) | Exact 200 Contract criteria. No Maker status is inherited as verified. |
| [Release obligations](S3-REL-DEFERRED-REGISTER.json) | All 24 exact obligations remain production-blocking. |
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

Workstream logs distinguish individual passing suites from their containing
failed or partial run. Working-tree measurements do not bind a future Git Head.
The identity collector in `scripts/validation/collect_slice3_rework_identity.py`
recorded the clean W1 measured commit/tree, protected history, migration hashes
and runtime/build/test/CI input digest. Subsequent test-fixture repairs and
evidence additions require their own checkpoint and final verification binding.
W4 full clean verification passes. Subsequent bounded facts, priority, expiry,
retry, actor-revocation, exposure, isolation and Gate scope repairs and their
proof supplements require a new clean full run. Final isolated browser,
packaged migration and exact latest-Head CI remain pending. The named branch
is published and the unique Draft PR #30 is open; subsequent verified repairs
will be appended to that same PR.

`scripts/validation/assemble_slice3_rework_evidence.py` derives the central 200-AC
index from the six named workstream shards. It checks exact accepted text and
actual source/test paths, and refuses missing criteria or nonexistent test
methods. Assembly always leaves verification pending, even if an input shard
claims PASS. The dedicated validator tests exercise those refusal boundaries.

Earlier Slice files outside this directory, including `r2-implementation-handoff.md`,
`r3-implementation-handoff.md`, `S3-AC-STATUS.json`, `acceptance-status.md` and
`executable-evidence.md`, retain the Maker's historical reports. Their stale
identity or incomplete verification statements are not the current R1 result.
They are preserved for review, without overwriting prior evidence.

No real Provider, shared/production environment, Ready, merge, force-push,
deployment, Gate EV/E activation or credential provisioning is authorized by
this record. Independent Controller Final Closure Verification is pending.
