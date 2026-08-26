# Codex Master Prompt — DR-0003 Content-Preserving Governance Git Execution

## Role and authority

You are the authoritative repository writer for the bounded governance task
`CODEX_DR_0003_V1_BASELINE_RESET_EXECUTION`.

You do **not** redesign the package, change its product decisions, implement
MarketOps product code, select different providers, expose credentials or enable
production writes. You import the Controller-authored canonical overlay exactly,
apply only the specified mechanical patches, update the governance validators and
tests to make the new authority executable, verify the result, and open a Draft
Pull Request for independent Controller review.

## Immutable task identity

```yaml
repository: Corwin-Code/marketops-platform
required_base_branch: main
required_base_sha: 52a657f7f6358f43246e03457ba2d48ef658986a
suggested_branch: docs/DR-0003-v1-baseline-reset
change_class: GOVERNANCE_ONLY
controller_verdict: APPROVE_RESET_PACKAGE_FOR_CODEX_GOVERNANCE_EXECUTION
merge_authorization: NONE
production_enablement: PROHIBITED
```

If remote `main` is not exactly the required Base SHA, stop before any write and
return `BLOCKED_BASE_MOVED` with the observed SHA. Do not rebase the package onto
a different Base on your own.

## Mandatory package inputs

Use the complete Controller package directory as the sole change specification:

```text
MarketOps_V1_Baseline_Reset_Controller_Package_v1.0/
├── repo_overlay/
├── execution_specs/
├── controller/
├── MANIFEST.json
└── SHA256SUMS.txt
```

Before Git writes:

1. read `README_PACKAGE.md`;
2. verify every package file against `SHA256SUMS.txt`;
3. read `controller/CONTROLLER_REVIEW_DR-0003_V1_BASELINE_RESET.md`;
4. read all files under `execution_specs/`;
5. inspect real branch/worktree/PR/CI state and give the complete D-16 task-start
   Git briefing;
6. verify that no unrelated local changes would be overwritten.

## Required execution sequence

1. Synchronize local `main` by fast-forward only and verify the exact Base.
2. Create `docs/DR-0003-v1-baseline-reset` from that Base.
3. Copy every file under `repo_overlay/` to the identical repository-relative
   path, byte-for-byte, UTF-8/LF. Do not reformat or paraphrase canonical files.
4. Apply `execution_specs/TARGETED_PATCH_SPEC.md` exactly to the long legacy files
   that are intentionally not fully replaced.
5. Implement `execution_specs/GOVERNANCE_VALIDATOR_CHANGE_SPEC.md` in:
   - `scripts/validate_governance.py`;
   - `tests/test_validate_governance.py`;
   - `scripts/validate_production_readiness.py` and its tests only where the spec
     requires compatibility with the new active model.
6. Apply `execution_specs/TRACEABILITY_RESET_SPEC.md`: preserve the historic
   `docs/01-requirements/traceability.csv` byte-for-byte and install/validate the
   supplied `docs/01-requirements/v1-traceability.csv`.
7. Compare the actual changed-file list with
   `execution_specs/EXPECTED_CHANGED_FILES.md`. Any additional file requires a
   stop and report; do not silently widen scope.
8. Run all commands in `execution_specs/CODEX_EXECUTION_CHECKLIST.md`.
9. Stage only explicit files, inspect the staged diff and verify again that:
   - backend/frontend product source is unchanged;
   - V0001–V0010 are byte-identical;
   - Baseline v1.0 and Naming Baseline are byte-identical;
   - existing WP evidence files are byte-identical;
   - no Secret, Credential, Buyer PII or production payload is present;
   - production writes remain disabled.
10. Commit with:

    ```text
    docs: reset MarketOps V1 product and delivery baseline
    ```

11. Push only the task branch.
12. Open a Draft PR using `execution_specs/PR_BODY_TEMPLATE.md`.
13. Wait for CI; repair only deterministic implementation defects in the same
    branch and within this package. Do not merge or mark Ready.
14. Return the exact report defined in
    `execution_specs/POST_EXECUTION_REPORT_TEMPLATE.md`.

## Hard prohibitions

- No product implementation, new migration, schema change or runtime provider
  connection.
- No edit to V0001–V0010.
- No edit to existing WP-P0-001/002/003 evidence content.
- No edit to `baseline-v1.0-cn.md`, `naming-baseline-cn.md` or their checksum lines.
- No Credential, Secret, production data or Buyer PII.
- No production write, real Marketplace call or Yandex provisioning.
- No direct push to `main`.
- No Ready transition, merge, self-approval or Ruleset bypass.
- No replacement of Controller decisions with Codex preferences.
- No broad refactor of governance validators beyond the exact new state model.

## Required result

The Draft PR must make the repository internally consistent with DR-0003 and must
leave the post-merge next action as:

```text
NEXT_AUTHORIZED_ACTOR: CLAUDE_FABLE_5
NEXT_ACTION: SLICE_V1_001_DETAILED_DESIGN_AND_INITIAL_FULL_IMPLEMENTATION
```

That future authorization becomes effective only after the governance PR receives
an independent Controller merge verdict, Human Owner authorization and a protected
merge to `main`.
