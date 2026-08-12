# DR-0001 — Temporary Codex Git Execution Delegation

```yaml
decision: D-17
status: ACCEPTED
effective_date: 2026-08-12
owner_approval: EXPLICIT
controller_verification: REQUIRED_BEFORE_PR_MERGE
revocation_authority: Human Owner only
```

## Problem and trigger

The Human Owner is still learning the protected Git workflow and has explicitly
asked Codex to perform routine PR approval-state and merge execution on the
Owner's behalf. The existing D-12 / ADR-0004 wording says the Human Owner performs
the merge and does not define a controlled delegation path.

## Current rule/design

- the Human Owner holds final merge authority;
- the GPT Controller issues the independent PR verdict;
- Codex may perform bounded rework but may not approve its own changes;
- required GitHub Ruleset and CI gates may not be bypassed.

## Accepted change

The Human Owner retains the decision and revocation authority for final merge.
While Current State records an `ACTIVE` delegation to `CODEX`, Codex may:

1. mark a PR ready after an independent Controller verdict is recorded;
2. execute the configured Squash Merge after every repository/project gate passes;
3. synchronize local `main` and perform verified branch cleanup after merge.

Delegation does **not** allow Codex to:

- issue the approving Controller verdict for a PR it authored or repaired;
- reduce GitHub approving-review or status-check requirements;
- bypass a Ruleset, unresolved conversation, Draft state or failed/stale check;
- push directly to `main`;
- provision credentials, authorize production, change business scope or accept an
  architecture decision on behalf of the Human Owner;
- merge when required evidence or an independent Controller verdict is missing.

The active scope is exactly:

```text
PR_READY_AND_MERGE_AFTER_ALL_GATES
```

## Alternatives considered

1. Require the Human Owner to perform every GitHub click immediately — rejected
   temporarily because it impedes learning and routine progress.
2. Grant unrestricted repository administrator/bypass authority — rejected because
   it defeats the Ruleset, evidence and Maker–Checker controls.
3. Remove independent Controller review — rejected because Codex must not approve
   its own authored or repaired change.

## Affected decisions and artifacts

- D-12 and ADR-0004 are amended only for mechanical Ready/merge execution;
- D-16 guidance remains required;
- WP-P0-001, Current State, Project Charter, AI Operating Model, Handoff Protocol,
  GitHub Setup and agent contracts must reflect the bounded delegation;
- no product requirement, data model, API, deployment or Marketplace permission is
  changed.

## Migration and compatibility impact

No code or data migration. Existing PRs remain Draft until their normal gates pass.
GitHub's required approving-review count remains `0`; the independent Controller
verdict remains a project gate recorded in the PR rather than a GitHub approval.

## Security and privacy impact

The delegate receives no credential, production, Ruleset bypass or direct-main
authority. Before execution, Codex must add or update the PR handoff to disclose
that D-17 delegation is being used and identify the verified gates; GitHub account
attribution alone does not distinguish delegated Codex execution. Public repository
data restrictions remain unchanged.

## Testing and evidence plan

- governance validation checks the exact active delegate, scope and exit fields;
- unit tests cover active and inactive delegation states;
- the PR must have current required checks, no unresolved conversations and an
  independent Controller verdict before delegated merge execution;
- the executor reports the before/after PR, branch, commit and `main` state.

## Rollback plan

The Human Owner may explicitly revoke the delegation at any time. A governance PR
then sets:

```yaml
owner_git_execution_delegation: INACTIVE
owner_git_execution_delegate: NONE
owner_git_execution_delegation_scope: NONE
```

The reason, effective date and Owner confirmation must be recorded. Revocation
does not disable PR, CI, Ruleset or independent Controller gates.

## Cost and operational impact

No GitHub plan change and no infrastructure cost. The operational change is
limited to who performs the already-gated Ready/merge and local cleanup steps.

## Decision record

- Owner decision: `ACCEPTED` by explicit instruction on 2026-08-12.
- Controller recommendation: preserve the delegation only with the restrictions
  above; independent Controller verification of this PR remains required.
- Final status: `ACCEPTED`; effective in repository governance when this PR merges.
