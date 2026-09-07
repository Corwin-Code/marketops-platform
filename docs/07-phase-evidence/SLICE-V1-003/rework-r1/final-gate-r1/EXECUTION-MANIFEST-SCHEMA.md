# Current execution manifest

`EXECUTION-MANIFEST.json` is currently `PENDING`; the finalizer consequently
emits no current engineering verification or closure PASS. The archived W10
views and exact historical Controller NOT_PASS are separate read-only inputs.

Run `python3 scripts/validation/finalize_slice3_rework_assessment.py --check`
to validate the current derivation without writing. Without `--check` it writes
only the three active central JSON views. It does not edit historical W9
assessments, Contract/Frozen bytes, CURRENT_STATE, Git or CI. Both governance and readiness validators already support the two exact phases.
They validate the full manifest, immutable authorities and byte-identical central
views before admitting the corresponding CURRENT_STATE tuple. Changing a
status label alone cannot admit engineering completion. After measured final
integration, update only the generated CURRENT_STATE metadata/prose and views;
no validator-source change is needed. `CURRENT_PHASE_STATES` in the finalizer
lists the exact pending and engineering-complete/Controller-pending values.

To admit `COMPLETE`, populate these fields from the actual execution records;
do not manufacture a manifest to satisfy the schema:

- `productionWriteEnabled: false`, `controllerApprovalClaimMade: false`.
- `source`: exact `sourceHead`, `sourceTree`, `identityScope` and `inventory`.
  `identityScope` is `CLEAN_COMMIT_TREE` or
  `WORKTREE_WITH_EXACT_SOURCE_MANIFEST`. For the latter, HEAD/tree identify the
  checked-out origin while the inventory identifies the executed amended
  source; they must not be described as a clean committed measurement.
- `source.inventory`: a repository-relative `{path, sha256}` reference to
  immutable JSON containing `files: [{path, sha256}, ...]`. These hashes must
  have been captured for the execution, then compared with current bytes. The
  generator requires complete backend/src, frontend/src, scripts, tests and
  build dependency inputs, excluding Python bytecode. Include other relevant
  workflow/configuration/infrastructure inputs in the inventory as well.
- `layers`: exactly `backend_full`, `frontend_quality`, `browser`,
  `governance`, `infrastructure`, `migration`, `security`, `supply_chain`, and
  `mixed_capacity`. Each has `id`, actual `runId`, `command`, `result: PASS`,
  `failures: 0`, `errors: 0`, `skipped: 0`, the same recorded `sourceHead`,
  `sourceTree`, `sourceInventorySha256`, and a nonempty `evidence` list of
  `{path, sha256}` references. `backend_full` also has
  `scope: FULL_RELEVANT_VERIFICATION` and the actual raw JUnit XML reports.
  Every supplied XML report is checked for failure/error/skipped testcase
  nodes; selecting only a passed testcase cannot hide another failure in its
  supplied raw report. Preserve full report inventories and execution scope.
- `verificationChecks`: exactly CV-A through CV-E, each with `id`, `result:
  PASS`, individually reviewed `engineeringReason`, nonempty `proofLimits`,
  and named `proofs` containing both `role: positive` and `role: adverse`.
- `criteria`: exactly all 200 AC ids; `findings`: exactly all 22 original ids.
  Each row has an individually reviewed `engineeringReason`, a nonempty
  `layers` list and nonempty current named `proofs`. Keep each proof's actual
  boundary; a file or aggregate passing count is not evidence for all clauses.
  The generator copies original AC text and frozen clause text itself rather
  than allowing a manifest to replace them.

Every proof contains `kind`, `layer`, meaningful assertion `scope`,
`source: {path, sha256}` and `evidence: {path, sha256}`. Its evidence must be in
that execution layer's artifact inventory. The two supported forms are:

```json
{
  "kind": "junit",
  "layer": "backend_full",
  "role": "adverse",
  "scope": "The exact frozen profile revocation creates one downgrade and no repeated revision.",
  "source": {"path": "repository-relative Java test path", "sha256": "actual digest"},
  "evidence": {"path": "repository-relative preserved JUnit XML path", "sha256": "actual digest"},
  "class": "exact testcase classname",
  "name": "exact expanded testcase name"
}
```

The named JUnit testcase must occur exactly once and contain no failure, error
or skipped element. Parameterized cases use their exact expanded node names.

```json
{
  "kind": "json",
  "layer": "governance",
  "role": "adverse",
  "scope": "The named mutation test rejects a changed historical Controller verdict.",
  "source": {"path": "repository-relative Python test path", "sha256": "actual digest"},
  "evidence": {"path": "repository-relative actual structured run result", "sha256": "actual digest"},
  "assertions": [{"pointer": "/exact/result/path/status", "expected": "PASSED"}]
}
```

Structured assertions resolve JSON pointers and compare actual recorded values.
Use a preserved execution artifact, not a source map or a newly asserted
assessment. The numerical source/job/dataset reconciliation and mixed workload
receipt can be bound this way with their actual measurement fields and limits.

The finalizer can record Codex engineering completion only after all these
inputs validate. Independent Controller closure, AC-200's independent
no-unresolved-finding conclusion, and production enablement remain false or
pending. Exact containing-commit/PR/CI readback remains a separate external
receipt; never assign a later publication SHA to an earlier local measurement.
That receipt must resolve all twelve required contexts and aggregate CodeQL,
including source HEAD, tested merge, run/attempt, job, artifact and dataset
identities for each measured run. Historical capacities and root/class JaCoCo
aggregations remain separately labeled in their immutable reconciliation.
