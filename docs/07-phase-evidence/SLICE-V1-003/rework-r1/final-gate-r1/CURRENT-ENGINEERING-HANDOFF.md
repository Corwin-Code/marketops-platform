# SLICE-V1-003 — current engineering handoff

Codex engineering rework is complete on measured source
`32b307c648d16f2ecb7f0074e615e78e95ed4121`. All 22 original Frozen Findings and
CV-A through CV-E have current engineering evidence. The 200-row acceptance
matrix records 199 engineering-verified criteria and `S3-AC-200` candidate
prerequisites passed with independent Controller review pending. All 115 Frozen
remedy/verification clauses are individually bound. These organizational counts
do not introduce new requirements or constitute independent approval.

The [execution manifest](EXECUTION-MANIFEST.json),
[Frozen clause assessment](FROZEN-CLAUSE-ASSESSMENT.json),
[AC matrix](../S3-AC-REWORK-STATUS.json),
[Finding matrix](../FINDING-CLOSURE-MATRIX.json) and
[root admission](current-32b307c/ROOT-ADMISSION.json) identify the actual named
source assertions and untouched raw reports. The existing repository finalizer
derives the three central views from this manifest.

## Source and publication identity

- Measured source: `32b307c648d16f2ecb7f0074e615e78e95ed4121`.
- Measured tree: `ca2e7f0a3b0f6b8361ffed3f2181a6bd8d000ca2`.
- Complete source inventory: 1,282 files; SHA-256
  `246182d691995ec50c39bc05db67510d30cd3186bba1efa42e3f19a06ef342cb`.
- Original tested merge: `f9fe2f58f23327b5ffa0ff755a2f392fc7a3215e`;
  its tree equals the measured tree.
- Original base: `08ad7da7d9e75b4ddd1c387a22ac0affba9e1430`.
- Branch: `feat/SLICE-V1-003-advertising-traffic-efficiency`;
  [PR #30](https://github.com/Corwin-Code/marketops-platform/pull/30) remains Draft.

The final documentation-containing commit is supplied by the external handoff's
`FINAL-READBACK.json`, together with its actual tree, parent, remote PR Head,
tested merge, full unchanged-runtime Git comparison and final 13 CI contexts.
This document deliberately does not invent its own future commit identity.
Publication and CI completion must be read from that external receipt; the
original runs below retain their measured identity. No further commit is needed
to place a containing commit's own readback inside itself.

## Completed verification

| Layer | Actual execution and result |
| --- | --- |
| Backend | Original GitHub `backend-build` full clean Maven verify: 1,634 unit + 1,145 integration = 2,779 passed; zero failure/error/skip. The independent integration job repeats 1,145 tests and is not added to that total. |
| Frontend | Original local install, lint, formatting, typecheck, 358 tests in 22 files, coverage/negative guard, bundle guard and build passed. |
| Browser | Original local real application plus owned isolated PostgreSQL/native fixtures: 37 passed (25 existing + 12 advertising); owned-resource cleanup passed. |
| Governance | Original local full governance: 421 tests. Separately 106 evidence-tool tests and 24 structured checks passed; these are not product tests. Final document-phase governance is separately captured in the external handoff. |
| Infrastructure | Provider-mocked Terraform validation/plans/refusals and runtime/telemetry checks passed. No apply or shared environment access. |
| Migration | Original CI packaged resolver/build and missing-envelope/wrong-artifact refusals passed; all 73 packaged SQL digests match source. Packaging makes no DB connection. Clean/install/upgrade/role behavior is covered separately by backend PostgreSQL integration tests. |
| Supply chain | Original CI backend SBOM/licenses plus separately completed local frontend supply collection. The first frontend npm-config startup failure is preserved; only that scope was repeated. |
| Security | Original Security workflow, dependency review, exact SARIF/source review and current local npm audit passed the stated scope. No new High/Critical; four earlier High are fixed without dismissal. |
| Mixed capacity | Original full-backend mixed run passed all unchanged latency and recovery assertions; no additional test count or local diagnostic run is claimed. |

The backend JaCoCo report root contains LINE 24,437 covered / 3,751 missed and
BRANCH 8,031 covered / 3,028 missed, above unchanged 80%/70% thresholds. A raw
`AdvertisingBidDecisionShapeTest` suite declares 12 while containing 13 actual
testcase nodes; the original discrepancy is retained and counts use actual nodes.

Backend command: `./mvnw -B -ntp clean -Dmarketops.build.gitCommit="${SOURCE_HEAD_SHA}" verify`,
followed by the original CI coverage guard step. Exact local commands, timestamps,
return codes, inventories and all full JUnit reports are in the layer receipts.
There was no new full local backend run on this source after the successful CI
was available. The CI runner's packaged JAR SHA is
`61b54a2b0e16724c83cdbfc14ad4dedfd10cd308a38d6b7546d623aba167e55b`;
the JAR itself was not downloaded or claimed as a locally executed artifact.

| Original backend-build workload | Critical P95 ms | Maximum ms | Sweep ms |
| --- | ---: | ---: | ---: |
| 1,000 native objects / 200 critical fixtures | 8,954 | 148,363 | 22,472 |
| Representative mixed workload | 151,923 | 183,846 | 35,713 |

The mixed workload retains 40 historical actions, actual mature/revised Outcomes,
held/released reservations, expired/invalid controls, 1,040 critical samples and
40 dropped corrections repaired. Three original mixed files, named JUnit and
the raw command log establish this run; no fourth local diagnostic file was
uploaded or is implied. Limits remain P95 <= 300,000 ms, maximum <= 900,000 ms
and sweep < 1,800,000 ms. These fixtures do not prove real Provider, multi-store
or APPLY throughput.

## Residual root causes and proof limits

- CV-A: each Outcome input consumes its exact frozen kind, purpose, scope and
  source/accepted-age authority; fresh ingestion cannot refresh stale facts.
- CV-B: proven advertising loss requires fresh complete canonical negative
  economics and exact cause-accepting Policy, with every independent safety and
  human approval dependency retained. Unknown profit never becomes proven loss.
- CV-C: Protection evaluates the original cause and complete frozen window;
  favorable newer Policy cannot rebind old actions. Physical protection remains
  separate from profit efficiency, with unresolved historical association kept UNKNOWN.
- CV-D: the representative mixed workload exercises actual Outcome revisions,
  control expiry, reservations and dropped-correction recovery within unchanged SLOs.
- CV-E: original W8/W10 measurements, later failures and current results retain
  separate identities; current raw named evidence supports each original clause.

Current source review and the same-class scan cover the affected authority,
Policy, persistence, application, worker, read-model, frontend, and test paths.
The final two changes make log-redaction fixture IDs deterministic while retaining
every leak assertion. No production logging behavior or acceptance threshold changed.

The Security record retains 95 pre-existing open quality observations (83 notes,
12 warnings), five historical dismissed High, and four default-main dependency
High as separate states. No alert was dismissed for closure. The current local
npm audit reports zero vulnerabilities. Raw SARIF help examples match a common
secret-shaped text rule; their exact public rule-help context was reviewed, and
their unchanged original bytes are supplied externally without weakening the
repository scanner.

## Original CI evidence on measured source

All 13 PR contexts succeeded. The full official capture checks attempt/job/source,
artifact ID, official SHA-256 digest, downloaded bytes, member SHA and CRC. These
are the measured-source workflows; final containing-commit workflows are separate.

- [Backend / 34048388804 / attempt 1](https://github.com/Corwin-Code/marketops-platform/actions/runs/34048388804)
- [Frontend / 34048388797 / attempt 1](https://github.com/Corwin-Code/marketops-platform/actions/runs/34048388797)
- [Governance / 34048388784 / attempt 1](https://github.com/Corwin-Code/marketops-platform/actions/runs/34048388784)
- [Infrastructure / 34048388800 / attempt 1](https://github.com/Corwin-Code/marketops-platform/actions/runs/34048388800)
- [Security / 34048388769 / attempt 1](https://github.com/Corwin-Code/marketops-platform/actions/runs/34048388769)

| Artifact | ID | Job | SHA-256 |
| --- | --- | --- | --- |
| backend-test-reports | 9994298701 | 101527455682 | `a334e022d07adaec96c951e58195d3fb01c1e3d41126eb27cdc27a957a293e0f` |
| backend-integration-reports | 9994244693 | 101527455617 | `d44d540c38d8394a8a887068f5bfb1b951d1a16479d6c59ec7c59294141bf804` |
| backend-supply-chain | 9994297603 | 101527455682 | `c0856699c63d1b28f4abf03331a9725de8812e99fd6d1f5304f313ef0fc60718` |
| frontend-coverage | 9993865760 | 101527455522 | `9286151bd83b39a9e1c6289ba2b6bb2facfc5ddbddd5351fefaab3465cece526` |
| advertising-browser-screenshots | 9993865400 | 101527455522 | `5e29911b21584b29419ece8a863fe9358ad4dd808c2f7a15e7558282e7a9bff6` |
| frontend-distribution | 9993802333 | 101527455629 | `9cba93eb44e2899d9b5c1a43baa893147fa1ce553fe94d8b949766d3ee2d1e28` |
| infrastructure-verification | 9993800697 | 101527455228 | `3dbb9c9acbda70ff8888da4f06d62a37ad6785720762062563647c5d82e55c49` |

## Preserved history and handoff boundary

The [original W10 record](historical-w10-central/README.md),
[Controller intake](CONTROLLER-INTAKE.json),
[measurement reconciliation](CV-E-MEASUREMENT-RECONCILIATION.json),
[phase-transition correction](GOVERNANCE-PHASE-TRANSITION-REWORK.md),
[priority-page repair](PRIORITY-PAGE-READ-REWORK.md),
[344f597 failures and repairs](CHECKPOINT-344F597-REWORK.md), and
[a5a308c verification history](CHECKPOINT-A5A308C-VERIFICATION.md) remain available.
In particular, a5a308c R7 has 2,779 actual passing tests but an INVALID_REPORTS
collector disposition; R8 did not execute Maven; R9 failed a random ID canary.
None is relabeled as this source's successful local execution.

[Original locator mapping](current-32b307c/ORIGINAL-LOCATOR-MAP.json) resolves
untouched capture paths to exact repository bytes or the supplied local handoff.
Full machine-readable reports are in Git; redundant rendered coverage, browser
images and replay-source copies remain in the handoff. Existing historical
corpora are preserved once, without recursive duplication. The external
`SLICE-V1-003-Final-Closure-20260907-R1` directory also contains the row review,
source review, public-data triage, final document verification and final readback.

The accepted Contract, Frozen Finding Set, 47 Owner Decisions and protected
historical migrations are unchanged. All 24 `S3-REL` entries remain
`DEFERRED_PRODUCTION_BLOCKING`. The preserved independent W10 verdict is
`NOT_PASS_EXISTING_FINDINGS_NOT_FULLY_CLOSED`; only the independent Controller
can issue a new verdict on the final exact Head. No new Finding Set or Contract
Amendment is created. `production_write_enabled=false`.

The next actor is the independent Controller after final publication/readback.
PR #30 remains Draft. No Ready, merge, force-push, direct-main push, real
Provider access, credential provisioning, shared/production access, deployment,
Gate EV/E, Pilot or production enablement was performed or authorized here.
