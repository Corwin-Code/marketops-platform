# Current assessment preparation tools

This directory contains a self-contained Python standard-library assembler, its synthetic boundary checker, and a separate method-binding proposal tool with synthetic tests. No runtime library, private /tmp helper import, generated slots, or previous execution manifest is required. The assembler and checker locate the repository via `bootstrap-manifest.json`, preferring the current working directory and its parents, then the script's parents.

Both commands require an explicit dedicated `/tmp` output directory outside the repository. They do not write repository files. The assembler produces PENDING slots, node observations and pending candidates only. It never selects a proof automatically, generates a COMPLETE manifest, grants Controller approval, or changes production enablement. The current-source evidence and per-row review remain root-owned.

Before actual use, bind each executed tool's exact committed path/SHA to the same new checkpoint through `EXPECTED-SOURCE.json.additionalExecutionInputs` (scope `EVIDENCE_DERIVATION_ONLY`), unless that exact path is already included in the measured source inventory. The assembler checks its own bytes against the declared source at entry and exit. Additional inputs are independently checked against `git show <sourceHead>:<path>`. A source pin alone is not a successful execution receipt. Initially the assembler's own `executionReceipt` may be null so the tool can run; root then records its actual captured command/raw results and SHA, and separately reviews that receipt before using any proof attributed to the tool.

The `prepare` command reads the two existing final-gate draft JSONs, frozen Finding JSON, W9 individual criterion/finding assessments, prior named-method catalog, and repository finalizer. All seven authoring inputs must match the declared checkpoint's Git bytes; their exact references are included in the prepared slots. This preserves historical locator provenance without treating old PASS as new execution. These data files already exist in the repository; do not copy generated /tmp slots into the tool directory.

Run from the repository after root commits and supplies the actual expected-source document:

```sh
python3 docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/assessment_tools/check_structured_adapter.py --out /tmp/slice3-current-assessment-tool-checks
python3 docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/assessment_tools/assemble_assessment_with_structured.py prepare --source-identity /tmp/slice3-current-expected-source.json --out /tmp/slice3-current-assessment
```

Then root registers original successful layer receipts in `/tmp/slice3-current-assessment/EXECUTION-INPUTS.json` and uses the same explicit `--source-identity` and `--out` for `merge-reviews --reviews ...`, `catalog --config ...`, and `assemble-pending --config ...`. Absolute machine paths in the actual raw receipts/config are input provenance, not hardcoded script dependencies; use explicit `sourcePrefixToRepository` and `artifactDestinations` mappings without editing old receipt bytes. New checkpoints require new output directories and explicitly reviewed source pins.

Structured and named-record adapters require actual registered raw JSON bytes plus SHA. Receipt-relative raw artifact paths are normalized to their exact verified file before downstream publication mapping. Exact JSON pointer semantics support the root document, empty keys, escaped keys and nonnegative array indices; values are compared without Boolean/number coercion. Unregistered artifacts, absent pointers, changed bytes, unexecuted source, failed/running layers, value/type mismatches and generated manifest self-proof cannot admit a node. The sibling checker copies the exact tool into an isolated synthetic repository under `/tmp`; its fake source identity is only an adversarial tool fixture, never project verification. It also checks missing/changed measured assembler, forbidden repository/default output, stale reviewer pins, altered accepted text, changed authoring data, cross-checkpoint config/catalog, invalid pointers, renamed manifest self-proof and optimized Python that would disable assertions. Reviewer merge validates current candidate source hashes; final pending assembly validates the config, slots, catalog and selected-node source identity.

The final portable version passed 24 actual synthetic boundary checks, including a real isolated Git checkpoint and the complete prepare → merge-reviews → catalog → assemble-pending flow. The receipt is `/tmp/slice3-portable-assessment-tools-check-r4/STRUCTURED-ADAPTER-SAFETY-CHECKS.json`. These are tool checks, not project or closure evidence; actual new-checkpoint execution and additional-input receipts remain required. Earlier versions/checks retain their own hashes.

Other executed derivation helpers must be pinned separately: `../reconcile_measurements.py` for the CV-E recount; any additional review-generator script only if root actually uses it after the checkpoint. `collect_execution.py` already captures itself in its measured source inventory. The repository finalizer is already a scripts/ runtime-validator input. No old `/tmp/prepare_review_c.py` or `/tmp/assemble_assessment.py` is imported by this tool.

`propose_method_bindings.py` joins only explicitly reviewed source-method plans
to an admitted current execution catalog. It emits proposals into a new `/tmp`
directory; it cannot edit review slots, admit a layer, mark a criterion complete
or grant Controller approval. Pin both it and `test_propose_method_bindings.py`
as additional derivation inputs before use, and record their actual invocations.
Their synthetic checks remain separate from product test counts.

Each plan and blocker review must identify the exact current Head, tree and
source inventory. A new checkpoint needs actual source re-review; replacing old
hashes does not establish it. The proposal tool checks registered source bytes,
completed successful parent receipts and original raw reports. All registered
Java XML files are searched for failed, skipped or ambiguous expansions of an
explicitly selected method, including a wholly omitted report. Python, browser
and TypeScript names match exactly. It does not discover evidence absent from a
parent receipt or replace the root's review of complete layer scope. Structured
raw JSON assertions remain a separate explicit binding step.

Run `python3 -m unittest discover -s <this-directory> -p test_propose_method_bindings.py`
for the synthetic boundaries. Use `python3 <this-directory>/propose_method_bindings.py --help`
for explicit expected-source, catalog, plan, blocker and output arguments. A zero
exit code means a proposal report was generated; remaining gaps and blocked rows
must be reviewed individually and are never a closure PASS.


## Evidence derivation after an unchanged product checkpoint

A documentation-only descendant can use the explicit `derivationSourceIdentity`
mode. The original product Head, tree, source inventory and raw execution
receipts remain unchanged. The tools record their own actual committed Head,
tree, Git blobs and completed independent execution receipts. The validator
checks clean ancestry, every original runtime Git entry and worktree byte, and
rejects any changed or newly added runtime input. The only declared line-ending
transformations are the two existing Windows command files. An inventoried
document such as the collector remains a frozen execution input.

Additional helper source must be bound to its actual tool commit and a real,
completed execution. Root captures the command process, return code, timestamps,
source pins before and after, and original logs. Synthetic receipt fixtures in
the tool tests are refusal checks; they are never project execution evidence.
The later catalog and binding invocations receive their own actual capture.

Governance may refer to the original product verification and separate later
tool executions through an explicitly labelled composite record. That record
preserves each original run and makes no claim that they were one command.
An additional helper node must come from the exact evidence members of its
actual helper execution receipt. Product and tool execution identities remain
separate in the catalog, proposal and final proof provenance. Old successful
helper runs remain historical evidence of their own committed dependencies;
an unchanged test filename does not make an old run validate a changed binder.

The JUnit source resolver uses the original testcase FQCN or the original root
suite FQCN for display-named Java classes. It preserves each raw classname,
method name, ordinal and SHA. A repeated method across distinct nested classes
stays ambiguous. Browser JUnit uses its explicit original file mapping and
exact raw identity. No inferred class name, omitted parameter, missing report
or failed sibling can supply an admissible proof.
