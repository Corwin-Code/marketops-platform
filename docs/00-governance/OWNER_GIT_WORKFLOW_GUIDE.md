# Owner Git Workflow Guidance Mode

```yaml
decision: D-16
audience: Human Owner and every Controller, Maker or Rework Agent
state_source: docs/00-governance/CURRENT_STATE.md#owner_git_workflow_guidance
supported_states: REQUIRED | DISABLED
exit_authority: Human Owner explicit confirmation only
```

## 1. Purpose

This mode explains how a local/agent change reaches protected `main`. It is
teaching and status transparency, not another product, Design or GitHub approval
Gate. GitHub may require zero approving reviews while project policy still
requires independent Controller review and Human Owner merge authorization.

## 2. Mandatory task-start briefing

While state is `REQUIRED`, the active agent reports:

1. repository root, current branch/worktree, upstream and divergence from
   `origin/main`;
2. related PR, Head/Base and latest relevant CI;
3. request class: read-only, Contract/governance change, implementation, review,
   rework, merge or release/enablement;
4. current position in the lifecycle and next authorized Git action;
5. the complete lifecycle:

   ```text
   sync main → create/reuse Slice/task branch → local edit/checkpoint
   → stage/review → commit → Codex exact remote publication
   → Draft PR → CI → one GPT Deep Review + Frozen Finding Set
   → one Codex root-cause rework cycle → GPT Final closure verification
   → Human Owner-authorized merge execution → local sync/cleanup
   → Gate EV for any bounded real-write evidence when applicable
   → separate Gate-E production/capability enablement when applicable
   → Controller Slice Closure → Owner Formal Closure
   → Closure Snapshot → next Slice
   ```

6. that branch commits/pushes do not change `main`, and merge does not itself
   enable production behavior;
7. the D-17 delegate currently recorded in Current State and its limited effect.

Read-only analysis proceeds without a needless confirmation.

## 3. Canonical Git flow

### Inspect

```bash
git status --short --branch
git branch -vv
git remote -v
git log --oneline --decorate -5
```

### Start from current main

```bash
git switch main
git pull --ff-only origin main
```

A task with a valid existing branch/PR reuses it.

### Create a short-lived branch

```bash
git switch -c feat/SLICE-V1-001-sku-diagnostic
git switch -c docs/DR-0003-v1-baseline-reset
git switch -c fix/SLICE-V1-001-price-readback
git switch -c codex/SLICE-V1-001-final-rework
```

### Edit only authorized scope

```bash
git status --short
git diff
```

A Delivery Slice may use more than one bounded PR tranche when necessary for
reviewability, but each PR cites the same Slice Contract and Acceptance IDs.

### Run relevant checks

At minimum for governance-only changes:

```bash
python3 scripts/validate_governance.py
python3 scripts/validate_production_readiness.py
python3 -m unittest discover -s tests -p 'test_*.py'
git diff --check
```

Add backend/frontend/database/browser/security/provider/recovery checks required by
the active Slice and changed surface.

### Stage and inspect

```bash
git add <explicit-files>
git diff --cached --check
git diff --cached
```

### Create the exact local checkpoint

```bash
git commit -m "type: concise outcome"
```

Claude ordinary authority ends at this exact local commit/tree. It does not
include push, remote branch/tag mutation or PR create/update.

### Exact remote publication by the named delegate

Under a dedicated Level-3 Remote Publication authority, Codex or the named Owner
delegate verifies the exact checkpoint/tree, immutable original Contract and
accepted Amendment hashes, target repository/base/branch and prohibitions before:

```bash
git push -u origin HEAD
```

Publication is transport, not redesign. The delegate may not reconstruct or
improve the checkpoint. If exact transport cannot be proven, request a
hash-verifiable shared worktree, Git bundle, patch series or equivalent. Local
commit and task-branch push do not update `main`.

### Open/update one Draft PR

```bash
gh pr create --draft --base main --head "$(git branch --show-current)"
gh pr checks --watch
```

Use the existing PR for rework. Do not create a new PR for every finding.

Formal Deep Review inspects the complete transitive surface once and freezes one
SHA-256-bound Finding Set. Codex receives the original Contract, accepted
Amendments and Frozen Finding Set once. Final Gate is closure verification, not a
second open-ended discovery pass; reopening requires materially new, previously
unavailable severe evidence.

## 4. Review and merge eligibility

A PR is eligible for Owner-authorized merge execution only when:

- the PR is Ready, current with `main` and all required checks pass;
- all review conversations are resolved;
- the active Contract's required evidence for the merged tranche is present;
- no unresolved BLOCKER/MAJOR finding remains;
- an independent GPT Controller reviewed the exact current Head and issued
  `APPROVE_FOR_HUMAN_MERGE`;
- the Human Owner authorizes the merge;
- no bypass or direct push is involved.

A Slice may remain incomplete after a valid tranche merge. A merged controlled
write remains disabled. A bounded real-write verification first requires an
exact Gate-EV authorization; ongoing Pilot use separately requires Gate E and
Human Owner production authorization.

After applicable release identities are known, Controller Slice Closure and
Human Owner Formal Closure must complete. Owner Formal Closure is an identity and
Owner-condition confirmation, not a third engineering review. An exact
Owner-accepted Closure Snapshot is required before the next Slice starts.

## 5. D-17 mechanical delegation

When Current State records:

```yaml
owner_git_execution_delegation: ACTIVE
owner_git_execution_delegate: CODEX
owner_git_execution_delegation_scope: PR_READY_AND_MERGE_AFTER_ALL_GATES
```

Codex may perform the already-authorized Ready/squash-merge operation. It cannot
supply the independent verdict for its own work or decide product, credential,
provider, legal, deployment or production-enable matters.

## 6. Post-merge synchronization

```bash
git switch main
git pull --ff-only origin main
git branch -d <task-branch>
```

After squash merge, `-d` may refuse because branch commits are not ancestors of
main. Confirm the PR is merged and the squash content is present before optional
`git branch -D <task-branch>`.

## 7. Required reporting around Git writes

Before branch/stage/commit/push/PR/merge, state what will change and what will not.
Afterward, report actual branch, status, commit SHA, upstream, PR URL/state, checks,
remaining Gate and next authorized action. Never claim a Git/CI result without
evidence.

## 8. Exit and delegation revocation

Only explicit Human Owner confirmation changes guidance to `DISABLED`. Only
explicit Human Owner revocation deactivates D-17. Both changes use a governance PR
and preserve all branch/CI/Controller/Owner controls.
