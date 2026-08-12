# Owner Git Workflow Guidance Mode

```yaml
status: REQUIRED
decision: D-16
audience: Human Owner and every Controller, Maker or Rework Agent
activation: every task start
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
   → Controller verdict → Human Owner merge → local sync/cleanup
   ```

4. state which lifecycle step the task is currently at and the next authorized
   Git action;
5. explain that local commits and feature-branch pushes do not update `main`,
   while merging the PR does;
6. remind the Owner that GitHub approval count is `0`; the required gates are the
   PR, `governance`, an up-to-date branch and resolved review conversations;
7. state any action that remains exclusively Human Owner authority, especially
   final merge, credential provisioning, production enablement and any emergency
   bypass.

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
`main` until the Pull Request gates pass and the Human Owner merges.

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

### Step 10 — Human Owner merge

Preferred method:

```text
Squash and merge
```

Why: the Human Owner retains final authority, while squash merge turns iterative
branch commits into one clear outcome on `main`. GitHub approving reviews remain
at `0`; the Owner is not waiting for another GitHub user to approve.

Agents must not perform the final merge unless the Human Owner explicitly requests
that exact merge action and all repository/project gates are satisfied.

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
| Merge PR into `main` | Changes accepted baseline | Human Owner final authority |
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
- whether Human Owner merge is now allowed or what remains blocking;
- exact commands or UI action for the Owner's next step;
- cleanup remaining after merge.

## 7. Exit protocol

Guidance mode may be disabled only after the Human Owner explicitly states that
the workflow is understood and task-start assistance is no longer required.
Silence, successful PRs or inferred familiarity are not confirmation.

On explicit confirmation, update in one governance PR:

```yaml
owner_git_workflow_guidance: DISABLED
```

Record the effective date and Owner confirmation in `CURRENT_STATE.md` and the
Decision Log or phase evidence. Disabling the teaching mode does not disable the
branch Ruleset, PR requirement, CI checks or Human Owner merge authority.
