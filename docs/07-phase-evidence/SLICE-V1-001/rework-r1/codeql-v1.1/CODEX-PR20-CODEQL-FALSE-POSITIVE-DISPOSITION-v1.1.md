# Codex Continuation — PR #20 CodeQL Disposition Matrix v1.1

```yaml
task_id: CODEX_PR20_CODEQL_FALSE_POSITIVE_DISPOSITION_V1_1
repository: Corwin-Code/marketops-platform
pull_request: 20
required_checkpoint_head: d4bc5fe51605501da4ebc18c89c5d47ec8dc5ed0
required_checkpoint_tree: db3b2c4df0b46a94575e42989904e4fe80e41444
replacement_matrix_sha256: b0a09962ebb37d257cb9f79a6e3d8f5543b0d3a7a69bc5bc99f578dc37bf4e8a
superseded_matrix_sha256: b966e4b475e1399cfff2ffcdf031abc2d9f3962c2c73514a44281c908a000981
comment_length_validation_sha256: 9eae2a6d8548ae9291de7f856de63b7fe4bd0588d75faa07e4c32c2826fc5310
authorized_alerts: [66, 73, 74, 75, 76]
dismissed_reason: false positive
authorization: CONDITIONAL_ON_EXACT_HUMAN_OWNER_V1_1_ACCEPTANCE
source_mutation_during_disposition: PROHIBITED
merge_authorization: NOT_GRANTED
deployment: NOT_AUTHORIZED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
production_write_enabled: false
```

## 1. Preconditions

Do not mutate alert/thread state until the Human Owner accepts the exact v1.1
Matrix SHA above.

Verify:

```text
PR #20 remains OPEN / DRAFT / UNMERGED
remote Head/tree equal the required checkpoint
old Matrix is not used
v1.1 Matrix and length-validation hashes match
each dismissed_comment is ASCII and <=280 characters
only alerts 66,73,74,75,76 remain candidates
no source/worktree mutation occurs during disposition
```

## 2. Per-alert operation

For each alert, independently:

1. fetch current alert and instances;
2. verify number/rule/path/line/data-flow against v1.1 Matrix;
3. PATCH only that alert with:
   ```json
   {
     "state": "dismissed",
     "dismissed_reason": "false positive",
     "dismissed_comment": "<exact v1.1 comment>"
   }
   ```
4. fetch it again and verify exact persisted comment/reason/actor/time;
5. resolve only its matching review thread.

Do not truncate, rewrite or append text. Do not bulk-dismiss.

## 3. After the five operations

Capture and prove:

```text
five exact alert before/after records
five exact thread before/after records
no other alert changed
zero unresolved review threads
aggregate CodeQL success
all required checks still successful
PR remains OPEN / DRAFT / UNMERGED
```

One existing CodeQL rerun on the unchanged checkpoint is authorized only when
needed to refresh aggregate state. No source/workflow change is authorized.

## 4. Resume existing final handoff

After successful disposition, continue the already authorized final canonical
handoff lifecycle exactly as previously specified. A new alert on the final Head
is evidence and is not covered by this authorization.

Return the full existing report contract, plus Matrix v1.1 SHA, exact comment
lengths and alert/thread before-after evidence.

```text
MERGE_AUTHORIZATION: NOT_GRANTED_BY_CODEX
DEPLOYMENT: NOT_AUTHORIZED
PRODUCTION_ENABLEMENT: NOT_AUTHORIZED
NEXT_AUTHORIZED_ACTOR: GPT-5.6 Sol Pro Controller
NEXT_ACTION: CONTROLLER_SLICE_V1_001_FINAL_CLOSURE_VERIFICATION
```
