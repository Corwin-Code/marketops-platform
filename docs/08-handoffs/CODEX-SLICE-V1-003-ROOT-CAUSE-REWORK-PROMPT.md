# CODEX ACTIVE PROMPT — SLICE-V1-003 Root-Cause Rework / Fix / Verify

```yaml
prompt_status: ACTIVE_AFTER_DEDICATED_REMOTE_WRITE_AUTHORITY
repository: Corwin-Code/marketops-platform
base: 08ad7da7d9e75b4ddd1c387a22ac0affba9e1430
starting_head: a0711f1ae430e70ab7ec06917004e9dbfd1fb4eb
starting_tree: fb4d242d62febd87191da9dce353bdef99f5a77d
branch: feat/SLICE-V1-003-advertising-traffic-efficiency

accepted_contract:
  path: docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md
  sha256: 1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c
  git_blob_sha1: 669c38dc4d9429249e663da0e684dabf570c4a4a

frozen_finding_set:
  id: SLICE-V1-003-FROZEN-FINDING-SET-001
  path_in_handoff: SLICE-V1-003-FROZEN-FINDING-SET-001.md
  sha256: 15b3c076fc7f1d283a2c7359d9647d91d3ecfccd9b229be1f734f4e7d4ceefc1
  findings: 22
  blocker: 17
  major: 5

controller_deep_review_verdict: READY_FOR_CODEX_REWORK
production_write_enabled: false
```

## Mission

Read the immutable accepted Contract and the complete Frozen Finding Set once.
Perform one continuous, full-scope root-cause rework/fix/verify cycle. Close all
22 findings coherently across backend, frontend, candidate migrations, tests,
runbooks, traceability and executable evidence.

Do not limit work to the smallest patch. Scan the same defect class across the
entire transitive surface. Do not ask the Owner to choose ordinary engineering
mechanics. Stop only for an exact Contract stop condition or a required expansion
of authority.

## Required starting verification

Before mutation, verify and report:

- exact repository/branch/Base/starting Head/tree;
- accepted Contract bytes/hash/blob;
- Frozen Finding Set SHA-256 `15b3c076fc7f1d283a2c7359d9647d91d3ecfccd9b229be1f734f4e7d4ceefc1`;
- V0001–V0035 byte identity;
- current PR/CI/remote branch state;
- clean worktree;
- no real Credential, Provider or production authority.

If any identity differs, stop before mutation.

## Mandatory rework rules

1. Preserve the original Contract bytes and every accepted predecessor identity.
2. Preserve V0001–V0035. V0036–V0056 are candidate migrations and may be
   coherently corrected before protected merge; regenerate all checksums and
   recreate disposable databases.
3. Close every finding, including all same-class and transitive consequences.
4. Convert every engineering `PARTIAL`/`NOT_YET` criterion into executable
   evidence. Do not relabel genuine engineering work as S3-REL.
5. Keep real Ozon/Wildberries profiles, endpoints, credentials, Gate EV, Gate E,
   Pilot and production writes disabled and unreachable.
6. Use fixture/synthetic providers only. Never mark a real platform profile
   verified without separate evidence.
7. Do not weaken validators, thresholds, datasets, role checks, migration rules,
   coverage gates or security tests.
8. No automatic rollback, standing automation, Budget/Status/strategy write,
   `STOCK_CHANGE`, replenishment, Allocation or Transfer may be introduced.
9. Preserve Unknown/Mismatch, immutable history, late-data versions and exact
   Provider/native identities.
10. Update canonical documentation and evidence from the actual final Head, not
    from an intermediate measurement.

## Verification minimum

Run the complete repository verification applicable to the final tree:

- unit, property/generative and architecture;
- Flyway clean install and exact protected-Base upgrade on PostgreSQL;
- full integration and real application HTTP tests;
- browser E2E for all accepted lanes/workflows/states/roles;
- concurrency, restart, replay, missed-trigger and reconciliation;
- command/idempotency/NOT_APPLIED/readback/compensation;
- reservation/exposure/quarantine/Kill/reenablement races;
- Operational/Settled/late-data/confounder outcomes;
- role-minimal API/export/notification/attachment/AI disclosure;
- mutation or systematic fault-seeding evidence;
- advertising-specific targeted-SLO and declared-capacity hourly sweep;
- console lint/typecheck/format/test/bundle;
- governance, production-readiness, secret/dependency/SAST/CodeQL as available;
- full repository regression with no threshold weakening.

## Git and external authority

This prompt does not itself authorize remote mutation. Use it only after the
Human Owner supplies a dedicated Level-3 Codex rework/publication authority.
Never push directly to `main`, mark Ready, merge, deploy, use a shared
environment, resolve a real Credential, call a real Provider, activate Gate EV
or Gate E, enable a Pilot, or enable production write.

## Return

Return one exact new Head/tree, clean worktree, changed-file and migration
inventory, complete test/evidence receipts, 22/22 closure mapping,
S3-AC-001..199 executable status, S3-AC-200 candidate prerequisites, all
S3-REL-001..024 preserved as production-blocking, and no real external action.
