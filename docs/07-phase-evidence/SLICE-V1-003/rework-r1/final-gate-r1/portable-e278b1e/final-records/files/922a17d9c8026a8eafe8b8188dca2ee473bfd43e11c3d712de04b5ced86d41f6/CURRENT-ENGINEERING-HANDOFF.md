# SLICE-V1-003 R1 engineering handoff

Codex has completed the authorized root-cause rework and the engineering
assessment of the unchanged 22 Findings, 115 Frozen rework/verification clauses,
200 accepted criteria and CV-A through CV-E. The repository finalizer actually
validated the published raw proof inputs and generated the three current
engineering views. Independent Controller acceptance remains pending;
S3-AC-200 records candidate prerequisites, rather than a Controller verdict.

The measured product Head is
`e278b1e3d8541aeb806e41d6cbef4deac8d16d06`, tree
`178132dd32a92e59e320eb100774b5bb9f6fb248`. Its 1,280-file runtime/test/validator
inventory has SHA-256
`73cc1dc47aebd86cf93de3afbdfe4d905b6c5b4f8b1335488f28ca63fe963eda`.
Eight additional evidence-tool source files were independently Git-bound and
actually exercised on that same Head. Earlier product/tool identities remain
historical; their execution results were not assigned to this source.

The evidence-only commit containing this document must retain its own exact
Head, tree, tested merge and CI readback in the separate final handoff bundle.
Its runtime inventory must equal the measured product inventory above. That
external readback avoids inventing a commit's own hash inside the commit.

## Current verification

| Scope | Actual completed result |
| --- | --- |
| Full backend | 2,769 passing testcase nodes: 1,630 unit and 1,139 integration; zero failures, errors or skipped nodes. Full clean Maven verify and unchanged positive/negative coverage gates. |
| Frontend quality | 358 tests in 22 files; lint, formatting, types, coverage gates, bundle checks and build pass. |
| Application browser | All 37 pass: 25 existing journeys and 12 advertising journeys, including both platforms' partial and complete manual-observation flows. |
| Governance | All 421 governance tests pass. The separately executed 106 evidence-tool tests and 24 structured checks retain their own receipts and counts. |
| Infrastructure | Mock-only validation groups of 9, 13 and 7 pass. |
| Migration and supply chain | All 73 migrations checked against Git, source inventory, the actual completed backend JAR and isolated disposable databases. The JAR, SBOM, licenses and build metadata remained byte-identical through verification; npm audit reports zero vulnerabilities. |
| Mixed capacity | The representative workload runs inside the completed full backend parent. Its testcase is already included in 2,769 and is not counted again. |
| Security | Exact Security run, three jobs and aggregate CodeQL pass. All 100 raw SARIF results were reconciled: 95 open quality findings and five historical dismissed high findings, with no new high/critical security finding. No alert was modified or dismissed. |

The current assembly binds all 2,297 source method selectors, including every
selected parameter expansion. It also binds 621 explicitly reviewed structured
nodes and seven separately collected publication observations. The resulting
1,375 distinct selected nodes have 8,616 scoped uses across 342 rows; these are
evidence nodes and uses, not additional product tests. An independent mechanical
audit checked all 342 rows, including the 115 exact Frozen clauses, with zero
issues. The root scope review preserves each proof's applicability and limits.

The executable manifest combines same-source, same-raw, same-run and same-role
JSON atoms conjunctively: 5,118 original uses in its 227 criterion/Finding/CV
rows become 2,340 execution groups. All 2,142 JUnit uses are unchanged. Each
combined record preserves the exact original names, scopes and assertions;
independent re-expansion reproduces every original atom. The unmodified
validator passes and rejects a deliberately incorrect typed assertion. This
reduces repeated JSON parsing without dropping a validation condition. The
original atomic manifest, all 342 row bindings and the unchanged 115-clause
audit remain available. The interrupted pre-conjunction document test run is
preserved as incomplete; it does not supply a complete-suite result.

## Residual and same-class repair

CV-A and CV-C now use each Outcome input's source, evidence purpose, scope and
frozen observation authority. Cause-specific Protection completion remains
separate from economic efficiency and inventory/listing repair. CV-B applies
the same exact Policy and cause dependencies in Java, SQL, Planner, Preview and
Outcome, including the bounded one-way economic-harm path. Positive and adverse
paths execute through the actual application and isolated PostgreSQL with the
fixture Provider.

The same-class review also completed the existing DR007 manual-evidence
requirement. V0073 requires explicit observed value/time, source, completeness,
native object, field, profile, evidence reference and direct attestation.
Older bare observations cannot gain authority from a later favourable record;
new partial records cannot borrow previous complete verification. Reopened
uncertainty preserves newer holders and execution facts. Both platforms have
real browser evidence for the completed flow. See the
[repair and retained failure history](MANUAL-EVIDENCE-COMPLETENESS-REWORK.md).

CV-D preserves the original capacity proof and adds the representative mixed
states. CV-E reconciles every local and remote measurement with its exact source,
parent, original raw artifacts and measured resource scope. Historical failures,
interrupted runs and superseded observations retain their original status.

## Capacity measurements

| Actual measurement | Critical P95 ms | Maximum ms | Complete sweep ms |
| --- | ---: | ---: | ---: |
| Product H local original workload | 23,365 | 199,533 | 72,784 |
| Product H local representative mixed workload | 226,605 | 263,105 | 116,776 |
| Product H CI backend-build original workload | 13,326 | 161,724 | 35,719 |
| Product H CI backend-build mixed workload | 172,568 | 205,460 | 56,728 |
| Product H CI backend-integration original workload | 16,302 | 170,145 | 44,069 |
| Product H CI backend-integration mixed workload | 190,664 | 224,280 | 70,439 |

The thresholds remain P95 ≤ 300,000 ms, maximum ≤ 900,000 ms and complete sweep
< 1,800,000 ms. Each row has its own dataset, receipt and resource identity.
Original and mixed workloads retain the observed hourly-reconciliation incident.
The original 40 dropped corrections completed; 40 distinct post-cutoff successor
requests remained pending. No final empty-queue or eventual-latency claim is made.
The mixed workload contains mature and revised retained Outcomes and 210 actual
appends; its settled state stays indeterminate. Separate vertical tests exercise
settled outcomes. These measurements do not establish multi-store, real Provider
or APPLY throughput. JVM and outer Docker/host resources remain distinct.

## Published product checkpoint CI

The exact product PR readback is open Draft PR
[30](https://github.com/Corwin-Code/marketops-platform/pull/30), branch
`feat/SLICE-V1-003-advertising-traffic-efficiency`, base
`08ad7da7d9e75b4ddd1c387a22ac0affba9e1430`. Its tested merge is
`196afde14f0d51f88c4b27ac2e7cff7d645b5a1a`, with parents base then product H
and tree equal to product H. All 12 required contexts and aggregate CodeQL pass.

| Workflow | Actual run, attempt 1 |
| --- | --- |
| Backend | [34025462661](https://github.com/Corwin-Code/marketops-platform/actions/runs/34025462661) |
| Frontend | [34025462660](https://github.com/Corwin-Code/marketops-platform/actions/runs/34025462660) |
| Governance | [34025462604](https://github.com/Corwin-Code/marketops-platform/actions/runs/34025462604) |
| Infrastructure | [34025462662](https://github.com/Corwin-Code/marketops-platform/actions/runs/34025462662) |
| Security | [34025462624](https://github.com/Corwin-Code/marketops-platform/actions/runs/34025462624) |

The complete original API capture includes 38 GET attempts, preserving failed
transport attempts and the later exact successful response. It binds job IDs,
checkout identities and seven official artifacts by digest, byte count and ZIP
member CRC/hash. Backend-build contains 2,769 nodes; the separate integration job
repeats 1,139 and is not added to that count. CI did not upload an executable JAR
or the fourth mixed diagnostic artifact; the local same-JAR evidence is separate.

## Evidence entry points and boundary

- [Execution manifest](EXECUTION-MANIFEST.json) binds current source, nine admitted
  scopes and exact raw proof references.
- [All 115 Frozen clauses](FROZEN-CLAUSE-ASSESSMENT.json) retain exact accepted
  text, individual reasons, proof scopes and the independent mechanical audit.
- [Portable publication plan](portable-e278b1e/PUBLICATION-PLAN.json) and
  [relocation map](portable-e278b1e/RELOCATION-MAPPING.json) preserve original JSON
  bytes. Resolve an original path together with its SHA to the direct file or
  exact archive/member location; embedded historical absolute paths are not
  rewritten as current execution paths.
- [Engineering verification](../ENGINEERING_VERIFICATION.json),
  [Finding matrix](../FINDING-CLOSURE-MATRIX.json) and
  [accepted-criterion matrix](../S3-AC-REWORK-STATUS.json) are generated from the
  current validated manifest. Historical W10 assessments remain immutable.

The original accepted Contract, Frozen Finding Set, 47 Owner Decisions and all
24 production-blocking release obligations are unchanged.
`production_write_enabled=false`. The PR remains Draft. Independent Controller
review and Human Owner closure have not been supplied by Codex.
