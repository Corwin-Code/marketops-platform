# Final gate finalization design — integration draft

This is an implementation plan and source-impact index. It makes no new PASS,
engineering closure, Controller approval or production claim. The current
diagnostic runs and the following full regression/CI remain separate evidence.
The accepted Contract and Frozen Finding Set are read-only inputs.

## Exact impact and historical dispositions

`FINALIZATION-INPUT-MAP-DRAFT.json` contains all 22 original Findings, every
original required-rework and verification clause, all 200 exact accepted AC
texts, the supplied Controller disposition at W10, and source/test entry points
for CV-A through CV-E. `prepare_finalization_map.py` regenerates only this draft.
It pins current source bytes and separately records the reviewed W10 identity;
its method inventory is not an execution result. Parameterized methods are
listed once, so method counts must never replace expanded JUnit node counts.

The generated draft currently identifies 16 ACs directly named by the Controller
and 127 ACs requiring reassessment through the direct reference, original
residual Finding criteria, or a changed file in the prior source/proof mapping.
The 127 count is a conservative impact index, not 127 failed ACs and not an
assertion that the remaining 73 are verified at the new Head. Full relevant
regression and criterion-specific interpretation still govern the final result.
Regenerate the draft after coordinated source changes because its source pins
and conservative changed-reference inventory can change.

| Check | Original Findings | Direct Controller ACs | Main proof boundary |
| --- | --- | --- | --- |
| CV-A | 004, 015 | 036, 043–045, 163 | Per-kind/scope/purpose frozen authority, source and accepted ages, coverage/correction/incident, exact version invalidation, legal mature cohorts |
| CV-B | 004, 011 | 091, 092, 099, 100 | Fresh proved economic harm plus exact cause Policy; shared Java/SQL dependency rules; actual Planner, selection, approval, SQL and fixture protocol; unknown economics remain unavailable |
| CV-C | 015 | 160–162 | Frozen original physical cause and window; distinct risk/exposure/efficiency states; source/control/profile revisions once; original responsibility and history preserved |
| CV-D | 020 | 070, 197 | Declared 1,000 objects/200 critical objects, representative mature/revised Outcomes and held/expired/invalidated controls; targeted/hourly paths and unchanged time bounds |
| CV-E | 022 | 199, 200 | Executed source-bound evidence join, historical measurement reconciliation, all 22/200 indices, independent Controller boundary |

The Controller accepted the reviewed-scope evidence for DR-001, 002, 003, 005,
006, 007, 008, 009, 010, 012, 013, 014, 016, 017, 018, 019 and 021. Preserve
those 17 exact historical dispositions. DR-004, 011, 015, 020 and 022 have the
five residual/incomplete dispositions recorded verbatim in the draft. They are
existing Findings, not a new Frozen Finding Set. The supplied Controller verdict
is `NOT_PASS_EXISTING_FINDINGS_NOT_FULLY_CLOSED` at
`3ff042df66d5d6924b587cac96fc652b93bf5e7a`; AC-200 is
`NOT_PASSED_CONTROLLER_INDEPENDENT_CONCLUSION` for that reviewed candidate.

## Why the current finalizer cannot be reused unchanged

`scripts/validation/finalize_slice3_rework_assessment.py` currently:

1. Uses fixed W8/W9 inputs in `build_outputs()` and assigns every ordinary AC
   `VERIFIED`, every Finding `CLOSED_WITH_EVIDENCE`, all 115 frozen clauses
   verified, and engineering closure true without consuming CV-A through CV-E.
2. Calls `current_source()` while joining those historical proof records. That
   hashes today's edited source but leaves the W8/W9 execution result attached;
   a changed implementation/test can therefore appear newly source-bound
   without having executed at that digest.
3. Hardcodes the W8-local capacity values 30789/109169 under `capacityBoundary`
   without complete per-measurement run/job/artifact identity. They are valid W8
   local numbers, not either W10 CI artifact measurement.
4. Rewrites the three W9 detailed assessments plus the three active central
   files in `finalize()`. Re-running it now can overwrite later bookkeeping and
   restore a stale blanket closure claim.
5. Has tests that require precisely those blanket 22/115/199 dispositions and
   byte equality to a derivation that consults the current worktree.

The new finalizer must treat `current_source()` only as a digest comparison
against measured source identity. It must not relabel current bytes as the
source of a prior passing execution.

## Recommended additive integration

Keep the three detailed `workstreams/engineering-assessment-w9/*.json` files
unchanged as historical artifacts. Preserve the W10 versions of the three
central files before updating their active contents: `ENGINEERING_VERIFICATION`,
`FINDING-CLOSURE-MATRIX`, and `S3-AC-REWORK-STATUS`. The draft contains exact
W10 Git paths, sizes and SHA-256 values for all six old outputs. All six total
5,071,373 bytes; only the three central files need a new byte-for-byte archive
if their active paths are replaced (1,397,786 bytes). Do not duplicate historical
ZIPs, rewrite old receipts, or turn the Controller report into a mutable index.

Refactor the old finalizer into an explicit historical validation mode, or a
small dispatcher that validates the six pinned historical outputs and delegates
current final gate generation to a new module. Its default command must not
silently recreate the old active matrices. The three existing W9 detailed
outputs must be excluded from the new writer's allowed output paths.

The new generator should take a reviewed residual assessment document and a
measured evidence index, then produce additive current detailed assessments
under `final-gate-r1/` and the three active central views only after full pass.
Each current row needs:

- Exact original id/text/clause order; a historical W10 assessment reference
  and the exact Controller historical disposition.
- Current source/test references and reviewed positive, adverse and unknown
  assertions; the named expanded execution records that prove each boundary.
- Source HEAD/tree or explicit dirty-worktree manifest identity for local runs;
  compilation/test source digests; command, result, artifact path/hash; and a
  clear scope/limitation. Source mappings alone leave status pending.
- For residual Findings, explicit CV dependencies. DR-004 requires A and B;
  DR-011 requires B; DR-015 requires A and C; DR-020 requires D; DR-022 depends
  on A–E plus complete verification and final publication identity.
- Separate `historicalControllerVerdict` and `currentControllerVerdict` fields.
  Current engineering verification can pass after evidence closure; current
  independent Controller closure remains pending. AC-200 must not be promoted
  to independent PASS by Codex.

During current rework the rows remain pending and engineering closure false.
After the complete applicable local verification passes, the new candidate can
record an engineering assessment with exact-Head remote readback pending.
After append-only publication, attach an external immutable receipt for the
actual containing commit and all CI jobs. A file inside a commit cannot hash
its own future containing commit; do not fabricate that identity or endlessly
recommit self-referential receipts. An additive receipt commit must disclose
its own separate CI identity and the measured product-source relationship.

Every new CI measurement must include sourceHead, testedMerge, runId/attempt,
jobId/name, artifactId/name/digest and dataset digest. Local evidence uses a
local run id and null GitHub ids. Preserve W8-local, W8-build, W8-integration,
W10-build and W10-integration as five independent historical measurements.
Join the new mixed receipt, source-input manifest and canonical input dataset
per job; do not substitute the old unverified-object workload for mixed active
states. Use report-root JaCoCo counters for the report gate; keep CSV/class sums
as a separate aggregation. Do not sum duplicate build/integration nodes as
unique tests. Twelve required contexts plus aggregate CodeQL must be resolved
to the exact final candidate.

## Validator and test changes required at integration

| File / location | Required coherent change |
| --- | --- |
| `scripts/validate_governance.py`, current-state expectations around lines 617–631 | Replace the stale active Slice 3 phase tuple; add exact Controller report id/hash/reviewed Head and distinguish historical NOT_PASS from current rework/pending review. Preserve authority/Contract hashes, Draft, production false and next-actor rules. |
| `scripts/validate_production_readiness.py`, `COMPLETION_STATE_TOKENS` around lines 558–564 | Stop requiring Controller-next while Codex residual work is active; validate the same phase and historical-verdict fields. Keep all production/Gate/Pilot and formal/merge authority denials. |
| `tests/test_validate_governance.py`, Slice 3 state mutation cases around lines 3185–3240 | Update expected legal tuple and add mutations for deleting/altering the historical NOT_PASS, moving the Controller verdict to another Head, premature engineering completion, false current Controller approval, wrong next actor, Ready/merge and production claims. |
| `tests/test_validate_production_readiness.py`, Slice 3 mutation test around line 353 | Test the same legal phase tuple; do not retain a test that treats `next_authorized_actor: CODEX` as universally forbidden after the Owner-authorized resumption. |
| `tests/test_finalize_slice3_rework_assessment.py` | Replace blanket current closure assertions with historical byte/hash preservation and state-dependent current evidence checks. Preserve exact 22/115/200/24 cardinalities and immutable text. |
| New final gate generator tests | Reject missing A–E proof, failing/skipped required cases, stale source/test digests, mixed source/merge identities, missing negative assertions, duplicate parameterized nodes, missing/incorrect artifact/dataset hashes, W8 numbers labeled W10, class-sum JaCoCo used as root, old/new Controller identity confusion and altered Contract/Frozen text. Deterministic `--check` must not write any file. |
| `docs/00-governance/CURRENT_STATE.md` | Synchronize YAML and the “Next authorized action” prose together. Record the supplied W10 NOT_PASS, unchanged R1 authorization and the resumed current phase. Once the new candidate passes, record a new engineering result with independent Controller re-verification pending. |
| R1 README/final handoff/exact results/design/migration inventory | Link the immutable Controller package, new current assessments and exact per-job evidence; leave historical records accessible and scope-qualified. Add V0066 onward without rewriting applied migrations. |

A minimal legal active tuple while work is ongoing is `rework_status:
CODEX_RESIDUAL_REWORK_AND_VERIFICATION_IN_PROGRESS`, `implementation_state:
RESIDUAL_REWORK_IMPLEMENTED_VERIFICATION_IN_PROGRESS`, engineering closure
`NOT_CLAIMED_RESIDUAL_VERIFICATION_PENDING`, historical Controller verdict
`NOT_PASS_EXISTING_FINDINGS_NOT_FULLY_CLOSED`, current Controller verdict
`NOT_REVIEWED_NEW_CANDIDATE`, and next actor `CODEX`. After all required evidence
passes, use an explicit engineering-complete/current-Controller-pending tuple
while retaining the historical verdict and its exact reviewed Head. These are
proposed bookkeeping values, not edits to the accepted Contract.

All active states must retain `production_write_enabled=false`, the same named
branch and Draft PR, no Ready/merge/force-push, no actual Provider/shared or
production access, and all 24 unchanged deferred production obligations.
