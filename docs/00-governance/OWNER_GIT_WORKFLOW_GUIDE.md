# Owner Git Workflow Guidance Mode

```yaml
decision: D-16
audience: Human Owner and every Controller, Maker or Rework Agent
state_source: docs/00-governance/CURRENT_STATE.md#owner_git_workflow_guidance
supported_states: REQUIRED | DISABLED
activation: every task start while Current State is REQUIRED
exit_authority: Human Owner explicit confirmation only
```

## 1. Purpose

This mode teaches the Human Owner how repository work moves safely from a local
change into protected `main`. It is guidance, not an additional approval gate.
The repository remains a solo-owner workflow: GitHub requires a Pull Request and
CI evidence, but requires zero approving reviews.

Until the Human Owner explicitly confirms that this assistance is no longer
needed, every task must begin with a complete but concise Git workflow briefing
and identify the current task's exact position in that workflow.

## 2. Mandatory task-start briefing

Before changing files or Git state, the active agent must:

1. inspect and report the repository root, current branch, worktree status,
   upstream, divergence from `origin/main`, related PR and latest relevant CI;
2. classify the request as read-only, design-only, change/implementation,
   PR review/rework or merge/release;
3. show the complete lifecycle:

   ```text
   sync main → create/reuse task branch → edit → local checks → stage/review
   → commit → push task branch → Draft PR → CI/review/rework
   → Controller verdict → Owner-authorized merge execution → local sync/cleanup
   ```

4. state which lifecycle step the task is currently at and the next authorized
   Git action;
5. explain that local commits and feature-branch pushes do not update `main`,
   while merging the PR does;
6. remind the Owner that GitHub approval count is `0`; the required gates are the
   PR, `governance`, an up-to-date branch and resolved review conversations;
7. state that final merge authorization remains Human Owner authority, identify
   the execution delegate recorded in Current State, and distinguish that bounded
   delegation from credentials, production, business decisions and emergency
   bypass, which remain exclusively Human Owner authority.

This briefing must not block a read-only task or demand unnecessary confirmation.
It should accompany the normal work and use the actual repository state rather
than generic assumptions.

## 3. Canonical workflow and rationale

### Step 0 — Inspect before acting

Typical read-only checks:

```bash
git status --short --branch
git branch -vv
git remote -v
git log --oneline --decorate -5
```

Why: confirm the target repository, active branch, uncommitted Owner changes,
tracking branch and current baseline before any mutation.

### Step 1 — Start from current `main`

For a new change task:

```bash
git switch main
git pull --ff-only origin main
```

Why: a task should start from the latest accepted baseline. `--ff-only` prevents
an accidental merge commit on local `main`.

Read-only analysis does not require a new branch. A task that already has a valid
branch or PR should reuse it rather than creating another branch.

### Step 2 — Create one short-lived task branch

Examples:

```bash
git switch -c feat/WP-P0-001-repository-foundation
git switch -c docs/<short-topic>
git switch -c fix/<short-topic>
git switch -c codex/<short-topic>
```

Why: the task branch is the safe workbench. Changes on it cannot update protected
`main` until the Pull Request gates pass and the Owner-authorized executor merges.

### Step 3 — Edit only the authorized scope

Inspect progress with:

```bash
git status --short
git diff
```

Why: keep one Work Package or one bounded finding per branch and avoid mixing
unrelated changes.

### Step 4 — Run relevant local checks

Current minimum governance checks:

```bash
python3 scripts/validate_governance.py
git diff --check
```

Add backend, frontend, migration, architecture and security checks when those
components exist or the Work Package requires them.

Why: detect deterministic failures before creating remote review noise. Passing
checks are evidence, not proof of business correctness.

### Step 5 — Stage intentionally and review the exact commit

```bash
git add <explicit-files>
git diff --cached
```

Why: staging defines the next commit. Reviewing the staged diff prevents Owner
files, secrets or unrelated work from entering the commit accidentally.

### Step 6 — Create a local commit

```bash
git commit -m "type: concise outcome"
```

Why: a commit is a local, recoverable checkpoint on the task branch. It does not
change remote `main` and does not need a GitHub approval.

### Step 7 — Push only the task branch

```bash
git push -u origin HEAD
```

Why: `HEAD` means the current task branch and reduces the risk of accidentally
targeting `main`. The push publishes/backs up the branch but still does not update
`main`.

### Step 8 — Open a focused Draft Pull Request

```bash
gh pr create --draft --base main --head "$(git branch --show-current)"
```

Why: the PR is the auditable change record. It connects scope, diff, evidence,
security statements, review conversations and the eventual merge.

### Step 9 — CI, review and rework

```bash
gh pr checks --watch
```

The PR may merge only when:

- it is no longer Draft;
- required check `governance` passes;
- the branch is up to date with `main`;
- all review conversations are resolved;
- required Work Package evidence is present;
- the Controller verdict is `APPROVE_FOR_HUMAN_MERGE` when applicable.

If the branch is out of date, use GitHub's **Update branch** and let CI rerun. If
a check fails, repair the same branch, commit and push again; do not create a new
PR for each correction.

Why: the same proposed change is repeatedly evaluated against the latest accepted
baseline until evidence and review are complete.

### Step 10 — Owner-authorized merge execution

Preferred method:

```text
Squash and merge
```

Why: the Human Owner retains final decision and revocation authority, while squash
merge turns iterative branch commits into one clear outcome on `main`. GitHub
approving reviews remain at `0`; the Owner is not waiting for another GitHub user
to approve.

The Human Owner normally performs the merge. Under accepted D-17, Codex may
perform the mechanical Ready/merge operation only while Current State records:

```yaml
owner_git_execution_delegation: ACTIVE
owner_git_execution_delegate: CODEX
owner_git_execution_delegation_scope: PR_READY_AND_MERGE_AFTER_ALL_GATES
```

Before delegated execution, all of the following must be true:

- an independent GPT Controller has issued `APPROVE_FOR_HUMAN_MERGE` after
  inspecting the current PR head;
- the PR is or can now be marked Ready and is up to date with `main`;
- every required check passes and all review conversations are resolved;
- no unresolved BLOCKER/MAJOR finding or missing Work Package evidence remains;
- no Ruleset bypass, direct push to `main` or self-approval is involved.

Codex cannot supply the independent approving verdict for a PR it authored or
repaired. Delegation changes who performs the already-authorized GitHub action;
it does not transfer Owner business/production authority or weaken any gate.

### Step 11 — Synchronize and clean up locally

```bash
git switch main
git pull --ff-only origin main
git branch -d <task-branch>
```

Why: begin the next task from the merged baseline. If the repository setting
**Automatically delete head branches** is enabled, GitHub deletes the remote head
branch after merge. With merge/rebase history, `git branch -d` removes a local
branch only when Git considers it merged.

After a squash merge, `git branch -d` may correctly refuse because the original
branch commits are not ancestors of `main`. First verify in GitHub that the PR is
`MERGED`, synchronize `main`, and confirm the squashed change is present. Only
then, if cleanup is desired, use:

```bash
git branch -D <task-branch>
```

`-D` deletes only the named local branch; it must never be used merely to silence
an unexplained warning or while unmerged work is uncertain.

## 4. Permission and effect map

| Action | Effect | Default authority |
| --- | --- | --- |
| Read status/diff/log | No state change | Agent or Owner |
| Edit files | Local worktree only | Authorized task scope |
| `git add` | Local staging area | Authorized task scope |
| `git commit` | Local task branch | Authorized task scope |
| Push task branch | Remote task branch only | Authorized task workflow |
| Create/update Draft PR | Remote review record | Authorized task workflow |
| Push directly to `main` | Rejected by Ruleset | Prohibited |
| Authorize PR merge | Permits baseline change after all gates | Human Owner only |
| Execute gated PR merge | Changes accepted baseline | Human Owner or active D-17 delegate |
| Bypass Ruleset | Weakens audit/control | Human Owner emergency decision only |
| Production enablement/secrets | External high-risk state | Human Owner only |

## 5. Required guidance around Git writes

Before each branch creation, staging, commit, push or PR operation, the agent must
briefly state:

- current branch and target;
- what will change locally or remotely;
- why the action is the correct next lifecycle step;
- what the action does **not** change, especially whether `main` is untouched.

After the operation, report its actual result and the next Owner-visible step.
Never claim a commit, push, check, PR or merge succeeded without evidence.

## 6. Task completion briefing

Every change-task handoff must state:

- branch and worktree state;
- files changed;
- local checks and results;
- commit status and SHA, if any;
- push/upstream status, if any;
- PR URL/state and required checks, if any;
- whether Owner-authorized merge execution is now allowed or what remains blocking;
- exact commands or UI action for the Owner or active delegate's next step;
- cleanup remaining after merge.

## 7. Exit protocol

Guidance mode may be disabled only after the Human Owner explicitly states that
the workflow is understood and task-start assistance is no longer required.
Silence, successful PRs or inferred familiarity are not confirmation.

`CURRENT_STATE.md` is the only runtime state source. The guide and Agent
instructions remain as reference/conditional contracts and must not duplicate the
runtime value.

On explicit confirmation, update in one governance PR:

```yaml
owner_git_workflow_guidance: DISABLED
```

The same PR must:

1. record the effective date and Owner confirmation in `CURRENT_STATE.md`;
2. record the transition in the Decision Log or phase evidence;
3. run governance validation and its state-machine tests, including the explicit
   `DISABLED` path;
4. preserve the conditional references in every Agent instruction so the mode can
   be re-enabled without rebuilding the contract.

The validator accepts exactly `REQUIRED` or `DISABLED` and rejects other values.
Disabling the teaching mode does not disable the branch Ruleset, PR requirement,
CI checks, independent Controller gate or Human Owner merge authorization.

## 8. Temporary merge-execution delegation exit

D-17 delegation is independent from D-16 teaching-mode state. Only an explicit
Human Owner revocation may deactivate it. On revocation, update Current State to:

```yaml
owner_git_execution_delegation: INACTIVE
owner_git_execution_delegate: NONE
owner_git_execution_delegation_scope: NONE
```

Record the effective date and Owner confirmation, rerun governance validation and
leave all PR/CI/Controller gates in place.
