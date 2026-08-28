# Codex Continuation Prompt — S1-F010 Managed PostgreSQL Bootstrap

```yaml
task_id: CODEX_SLICE_V1_001_REWORK_R1_CONTINUE_AFTER_F010_DECISION
repository: Corwin-Code/marketops-platform
pull_request: 20
branch: feat/SLICE-V1-001-sku-growth-profit-loop
protected_base: 89fc29be45327b592a9bcbeffbfec54c96fb66ed
reviewed_starting_head: 30d16e5d7db2d2190635a06fececd5883093a876
reviewed_starting_tree: 13b1b789cd4cff292d0d6ab24daca976afbba6da
immutable_contract_sha256: 0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5
frozen_finding_set_sha256: 8e5bd4ee3f5727bff9e9d1a7fc58739c635e6fd75483f28a4f302fcb222ae3a8
required_amendment_sha256: 8a36bbe0f2cd1d8e40efb171d368d8c4058ecc913da2a76f43f7e0a14de6854d
authorization: CONDITIONAL_ON_EXACT_HUMAN_OWNER_AMENDMENT_ACCEPTANCE
deployment: NOT_AUTHORIZED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
production_write_enabled: false
```

## 1. Preconditions

Do not implement the managed bootstrap exception until the Human Owner supplies
the exact acceptance text and Amendment SHA above.

Preserve the current uncommitted rework worktree. Do not reset, clean or
reconstruct it.

Recheck:

```text
origin/main == 89fc29be45327b592a9bcbeffbfec54c96fb66ed
PR #20 remains OPEN / DRAFT / UNMERGED
immutable Slice Contract SHA == 0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5
Frozen Finding Set SHA == 8e5bd4ee3f5727bff9e9d1a7fc58739c635e6fd75483f28a4f302fcb222ae3a8
V0001–V0010 bytes unchanged
production/Gate-EV/Gate-E authority disabled
```

## 2. Additive authority

Add the exact accepted Amendment as a canonical additive Slice artifact and bind
it in the relevant Current State, Source Manifest, Acceptance/evidence and
rework records. Do not edit the immutable original Slice Contract.

## 3. Required S1-F010 implementation

Implement the Amendment completely:

1. pin Yandex staging/production PostgreSQL major to 17;
2. manage required extensions through the Yandex database resource;
3. preserve standard-profile exact V0002 SQL execution;
4. add an explicit `YANDEX_MANAGED` migration profile;
5. use supported Flyway extension APIs to supply one externally satisfied V0002
   executor with canonical version/description/checksum;
6. ensure the canonical SQL V0002 is not simultaneously resolved in that profile;
7. perform all required extension/service/version/role/history/evidence
   preflight checks;
8. emit hash-verifiable, Secret-free bootstrap evidence;
9. retain normal V0003+ execution and Flyway validation;
10. prohibit manual schema-history SQL, baseline, repair, generic skip, edited
    V0002, paid Flyway features or silent fallback.

If the pinned Flyway public API cannot implement the exact identity/history
contract safely, stop and report a new Contract Defect. Do not fall back to
manual history mutation.

## 4. Required tests

Add and run all Amendment A-010 tests, including:

```text
standard PG17 clean path
managed-profile PG17 emulation with provider-preinstalled extensions
schema/constraint/index/function/privilege equivalence
identical V0002 version/description/checksum
extension absent/wrong version/wrong schema/wrong owner
PG18 managed-profile rejection
role privilege drift
missing/mismatched evidence
dual resolver exposure
clean rerun/upgrade/restore evidence integrity
```

Update the production alignment test matrix to use PostgreSQL 17 as the managed
target. PostgreSQL 18 may remain a compatibility/fail-closed test target.

## 5. Continue the complete Frozen Finding Set rework

This decision unblocks only the S1-F010 compatibility seam. Continue all remaining
authorized work, including:

```text
performance/representative dataset/EXPLAIN
asynchronous export and browser evidence
ephemeral DB/object restore and failure drills
container bootstrap/restart/secret/health tests
final same-class/transitive scan
canonical evidence synchronization
CodeQL/review-thread closure
fresh exact-commit full local verification
commit/push to the same Draft PR
exact-Head GitHub CI
```

Do not return for Final Closure Verification until the rework has one final
commit/tree and all applicable required checks have completed.

## 6. Boundaries

Do not:

```text
edit original Slice Contract
edit V0001–V0010
switch cloud/database provider
self-host PostgreSQL
introduce Flyway Teams/Enterprise
deploy or apply production Terraform
use real Credentials or Secrets
call Ozon/WB/Yandex/AI business APIs
execute Gate EV or Gate E
enable production writes
mark Ready or merge
```

## 7. Return

Return the complete existing rework report contract, plus:

```text
Owner Amendment acceptance identity
Amendment path/SHA
managed-profile implementation identity
PG17 and extension evidence
V0002 canonical SHA/Flyway checksum equivalence
standard-versus-managed schema equivalence result
negative test results
bootstrap evidence artifact/hash
final commit/tree/tested merge
all local and GitHub CI
S1-F001..F013 closure matrix
remaining external evidence, without relabeling it as complete

NEXT_AUTHORIZED_ACTOR: GPT-5.6 Sol Pro Controller
NEXT_ACTION: CONTROLLER_SLICE_V1_001_FINAL_CLOSURE_VERIFICATION
```
