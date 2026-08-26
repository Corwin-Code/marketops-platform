# Codex Next-action Prompt — PR #18 DR-0003 Targeted Rework R2

```yaml
task_id: CODEX_DR_0003_PR18_WHITESPACE_ATTRIBUTE_PATH_TARGETED_REWORK_R2
repository: Corwin-Code/marketops-platform
pull_request: 18
required_branch: docs/DR-0003-v1-baseline-reset
required_base: 52a657f7f6358f43246e03457ba2d48ef658986a
controller_reviewed_starting_head: 37e04f7f02de8a52f0c8fd026724ec2dbaf99d60
controller_reviewed_starting_tree: 89b1caccf390f6abd9f1a30f8ff268f5091166da
controller_reviewed_tested_merge: b4b64e724c7be3ef691c1b53cfabfb7ac693a9ff
controller_verdict: CHANGES_REQUIRED
authorization: TARGETED_GOVERNANCE_REWORK_ONLY
finding: DR3-PR18-F05
merge_authorization: NOT_GRANTED
production_enablement: NOT_AUTHORIZED
requested_next_verdict: INDEPENDENT_DR_0003_RESET_PR_FINAL_RE_REVIEW
```

## 1. Role and scope

Act as Codex Rework/Fix/Verify Agent and authoritative repository writer for one
bounded correction on the existing PR #18.

The four earlier findings F01–F04 are accepted and must not be reopened or
weakened. Preserve the confirmed V1 Product Contract, Gate EV, one finding
taxonomy and exact Slice Contract SHA binding.

Keep the PR `OPEN / DRAFT / UNMERGED`.

## 2. Task-start checks

Before mutation, verify:

- `origin/main` is exactly
  `52a657f7f6358f43246e03457ba2d48ef658986a`;
- the task branch Head is exactly
  `37e04f7f02de8a52f0c8fd026724ec2dbaf99d60`;
- its tree is
  `89b1caccf390f6abd9f1a30f8ff268f5091166da`;
- PR #18 is open, Draft, clean and unmerged;
- all current work is owned and the worktree is clean.

If Base/Head moved or unowned work exists, stop and report the exact divergence.
Do not silently rebase or reinterpret the finding.

## 3. Finding DR3-PR18-F05

The actual artifact is:

```text
docs/08-handoffs/CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md
```

The current `.gitattributes` and validator incorrectly use:

```text
docs/08/handoffs/CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md
```

Close the finding as follows.

### 3.1 Correct `.gitattributes`

Use exactly:

```text
docs/08-handoffs/CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md -whitespace
```

Retain one exception only. Do not widen it to a directory or file pattern.

### 3.2 Correct and harden the validator

Update `scripts/validate_governance.py` so it checks the actual artifact path.

Prefer a single constant, for example:

```python
DR0003_R1_REVIEW_RELATIVE_PATH = (
    "docs/08-handoffs/"
    "CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md"
)
```

Reuse it in:

- the required-file set;
- the R1 hash map or corresponding lookup;
- the `.gitattributes` exception check;
- the review-path check.

Do not maintain two manually typed path spellings.

The validator must also prove that the exception target is an existing required
file and that no second `-whitespace` rule exists.

### 3.3 Add mutation-sensitive tests

At minimum test:

1. exact actual path is accepted;
2. `docs/08/handoffs/...` is rejected;
3. a missing target file is rejected;
4. a second or directory-wide `-whitespace` exception is rejected;
5. the three existing R1 Controller artifact SHA-256 values still pass.

Do not edit the SHA-256-pinned R1 Controller review, R1 prompt or R1 hash
manifest.

### 3.4 Prove the real Git behavior

Run:

```bash
git check-attr whitespace --   docs/08-handoffs/CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md

git diff --check origin/main...HEAD
```

Required result:

- `git check-attr` identifies the exact file and reports `whitespace: unset`
  (or the equivalent false/unset representation for `-whitespace`);
- the full Base-to-Head `git diff --check` exits zero with no output.

A scoped check that excludes the artifact is not sufficient after this fix.

### 3.5 Update the PR body

Replace the current `DISCLOSED EXCEPTION` evidence with:

- corrected attribute path;
- `git check-attr` exact output;
- full `git diff --check origin/main...HEAD` PASS;
- confirmation that the R1 artifact remained byte-identical and hash-valid.

Retain the distinction between the original package overlay at the initial Head
and Controller-authorized R1/R2 rework.

## 4. Controller R2 artifact recording

Add the exact Controller-supplied files accompanying this prompt to
`docs/08-handoffs/`:

```text
CONTROLLER-PR18-DR-0003-INDEPENDENT-RE-REVIEW-R2.md
CODEX-PR18-DR-0003-TARGETED-REWORK-R2.md
DR-0003-PR18-R2-ARTIFACT-HASHES.md
```

Add hash bindings without changing the previous initial-package or R1 artifact
files and hashes.

The R2 artifact files themselves contain no intentional trailing-space exception
and must pass normal whitespace checks.

## 5. Allowed changes

Expected changes are limited to:

```text
.gitattributes
scripts/validate_governance.py
tests/test_validate_governance.py
docs/08-handoffs/CONTROLLER-PR18-DR-0003-INDEPENDENT-RE-REVIEW-R2.md
docs/08-handoffs/CODEX-PR18-DR-0003-TARGETED-REWORK-R2.md
docs/08-handoffs/DR-0003-PR18-R2-ARTIFACT-HASHES.md
PR #18 body
```

A narrowly necessary governance-test helper change may be included, but report
it explicitly. Do not change product contracts, Current State, the Slice Contract
or its SHA unless the Controller finding proves it necessary; this task does not
require such a change.

## 6. Protected paths

Do not change:

```text
backend/**
frontend/**
infra/**
fixtures/**
docs/01-requirements/baseline-v1.0-cn.md
docs/01-requirements/naming-baseline-cn.md
docs/01-requirements/SHA256SUMS.txt
docs/01-requirements/traceability.csv
backend/marketops-server/src/main/resources/db/migration/V0001...V0010
docs/02-architecture/designs/**
docs/03-work-items/WP-P0-001*
docs/03-work-items/WP-P0-002*
docs/03-work-items/WP-P0-003*
docs/07-phase-evidence/WP-P0-001/**
docs/07-phase-evidence/WP-P0-002/**
docs/07-phase-evidence/WP-P0-003/**
docs/00-governance/DR-0001*
docs/00-governance/DR-0002*
the existing initial-package and R1 Controller artifacts
```

Do not use a Secret, Credential, Buyer PII, production payload, provider call or
real Marketplace write.

## 7. Verification

Run and report:

```bash
python3 scripts/validate_governance.py
python3 scripts/validate_production_readiness.py
python3 -m unittest discover -s tests -p 'test_*.py'
make governance
make frontend-check
git check-attr whitespace --   docs/08-handoffs/CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md
git diff --check origin/main...HEAD
```

Also prove:

- Base remains unchanged;
- Slice Contract SHA remains
  `0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5`;
- V0001–V0010 and protected paths remain byte-identical;
- no V0011 is added;
- all new-Head GitHub checks pass;
- no review conversation remains unresolved.

## 8. Git execution

Reuse:

```text
branch: docs/DR-0003-v1-baseline-reset
PR: #18
```

Commit and push only this branch. Do not mark Ready, self-approve or merge.

## 9. Required return

Return:

```text
Repository / Base
Starting Head/tree
New Head/tree
GitHub tested merge/tree/parents
R2 commits
R2 changed files
F05 closure evidence
git check-attr exact output
full git diff --check exact result
R1 artifact hashes unchanged
R2 artifact hashes
final Slice Contract SHA-256
local command/test results
GitHub check results
protected-path proof
PR state and review conversations
checks not run and why
MERGE_AUTHORIZATION: NOT_GRANTED_BY_CODEX
PRODUCTION_ENABLEMENT: NOT_AUTHORIZED
NEXT_AUTHORIZED_ACTOR: GPT-5.6 Sol Pro Controller
NEXT_ACTION: INDEPENDENT_DR_0003_RESET_PR_FINAL_RE_REVIEW
```
