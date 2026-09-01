# SLICE-V1-002 R1 final handoff

```yaml
document_type: codex_r1_final_handoff
slice: SLICE-V1-002
recorded_at: 2026-09-01
reviewed_source_branch: claude/slice-v1-002-stockout-u48w8w
reviewed_source_head: c5d896a4ca01ecdc6d4add85fb4fd2e33ba8e4c6
reviewed_source_tree: c94341232b5fa67b5c40a1e6be121a7696e748c4
frozen_finding_set_sha256: 60589cfa9303d17e71910e085fd18f1d68b87dd9e3b56a99bf6f799879ebcf94
rework_branch: fix/SLICE-V1-002-root-cause-rework-r1
draft_pr: 26
draft_pr_url: https://github.com/Corwin-Code/marketops-platform/pull/26
implementation_findings: 18_OF_18_ENGINEERING_CLOSED_PENDING_CONTROLLER
acceptance_criteria: 99_EXECUTABLY_VERIFIED_1_RESERVED_FOR_CONTROLLER
migration_inventory: V0001_THROUGH_V0035
local_gate: PASS_COMPLETE_PREPUBLICATION_CANDIDATE
remote_gate: AUTHORITATIVE_EXTERNAL_FINAL_HEAD_READBACK_REQUIRED
controller_verdict: NOT_CLAIMED
owner_formal_closure: NOT_CLAIMED
merge: NOT_EXECUTED
deployment: NOT_EXECUTED
real_provider_calls: NONE
production_write_enabled: false
next_actor: GPT-5.6 Pro Controller
next_action: CONTROLLER_FINAL_CLOSURE_VERIFICATION_ON_UPDATED_DRAFT_PR_26
```

## Identity binding

The immutable review source is Head
`c5d896a4ca01ecdc6d4add85fb4fd2e33ba8e4c6`, tree
`c94341232b5fa67b5c40a1e6be121a7696e748c4`. The rework starts from that exact
Head; the Claude branch remains unchanged as review evidence.

The final document-containing commit cannot embed its own Git object identity
without changing that identity. Therefore the authoritative rework Head/tree,
origin ref, PR Head, merge-base, tested merge commit/tree/parents and clean
worktree readback are the exact values emitted by the final commands and the
external handoff packet after this file is committed. No reconstructed or
approximate identity is accepted.

## Engineering disposition

All 18 frozen findings have one continuous root-cause disposition in
[r1-finding-closure.json](r1-finding-closure.json), bound to frozen-set SHA-256
`60589cfa9303d17e71910e085fd18f1d68b87dd9e3b56a99bf6f799879ebcf94`.
[V0034-root-cause-rework-evidence.md](V0034-root-cause-rework-evidence.md)
provides the human-readable traceability, while
[acceptance-status.md](acceptance-status.md) records 99 criteria as
`EXECUTABLY_VERIFIED` and reserves only `S2-AC-100` for the independent
Controller.

V0001–V0033 remain immutable. V0034 is the frozen-finding forward repair and
V0035 is the targeted audit/SLA/quality/delegation/trace/capacity forward
repair. Fresh install, standard profile, managed profile and accepted-base
upgrade all terminate at V0035.

## Verification packet

The final candidate must have all of the following green in aggregate before
handoff; exact observed counts, timings and coverage are synchronized in
[executable-evidence.md](executable-evidence.md):

- exact Contract blob/SHA-256, frozen JSON SHA-256 and migration inventory;
- `git diff --check`;
- complete backend `./mvnw -B -ntp clean verify`, including PostgreSQL,
  actual-path 5,000-Variant SLO/sweep, fault injection, export and restore;
- backend coverage mutation guard and packaged-migration verification;
- governance, production-readiness and complete validator unit-test aggregate;
- infrastructure static validation and refusal-control tests;
- frontend lint, format, typecheck, unit/coverage, build and bundle isolation;
- real-backend Chromium browser E2E;
- dependency convergence, licence inventory, CycloneDX SBOM and secret/PII/no-
  write scans;
- Draft PR required checks, dependency review and aggregate CodeQL;
- exact remote identity, Draft state and zero unresolved review-thread readback.

## Declared-capacity authority

The authoritative profile is version `S2_DECLARED_CAPACITY_V1` in
`backend/marketops-server/src/test/resources/application-availability-declared-capacity-v1.yaml`,
SHA-256
`01523457ab9aa19ffbd7f363a5e0f2946c0f6c483954818984f4b0ce42751215`.
It binds `worker-enabled=true`, `facts-per-scan=5000`,
`variants-per-pass=5000`, `scan-interval=PT30S` and
`sweep-interval=PT1H`. Test-only initial delays are `PT24H`, preventing
background overlap while the declared scheduler entry points are invoked
explicitly and exactly once.

`RepresentativePerformanceIT` is the capacity authority. It seeds no targeted
`ops.availability_recalculation_request`; instead, it accepts 5,000 attributable
canonical facts with one exact `fact_accepted_at`, asserts the queue is empty,
and drives `AvailabilityRecalculationScheduler.recalculateWhatChanged()` through
`AvailabilityTriggerIngestionService`, durable cursor, Variant resolution/dedup,
targeted worker, evidence, calculation, projection, Case, automatic verification
and SLO. It then executes the real 5,000-Variant sweep and 50/50 dropped-trigger
recovery. `TC-TARGET-CAP-001` is mocked worker-accounting support and
`TC-RECON-003` is mocked worker-paging support; neither is runtime authority.

The report is self-stamped by the final GitHub Actions run with source Head,
tested merge, workflow run/attempt/job and artifact name. GitHub assigns the
artifact archive digest after upload, so the authoritative final binding is
recorded in the Draft PR #26 external handoff packet after the final Head passes:
source Head/tree, tested merge/tree/parents, workflow run/job, artifact
name/digest, downloaded `representative-v1.json` SHA-256 and exact capacity
values. Recording this packet outside the commit avoids changing the identity
it proves.

## Authority boundary

This packet is implementation evidence, not self-approval. Draft PR #26 remains
Draft and unmerged. No deployment, Credential, Buyer PII, real provider,
shared/production database, Gate EV, Gate E, Pilot or production write entered
the work. The exact next actor is `GPT-5.6 Pro Controller`; the exact next action
is `CONTROLLER_FINAL_CLOSURE_VERIFICATION_ON_UPDATED_DRAFT_PR_26`.
