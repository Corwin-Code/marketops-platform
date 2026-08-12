# GitHub Setup — Initial Private Repository

## 1. Repository

```text
Name: marketops-platform
Visibility: Private
Default branch: main
```

Do not add a separate README, .gitignore or License during remote creation; this bootstrap already contains them.

## 2. Repository Ruleset for `main`

Create a branch Ruleset targeting the default branch and enable:

- Require a pull request before merging;
- Required approvals: `0` during solo development;
- Require conversation resolution before merging;
- Require status checks to pass before merging;
- Required status check: `governance` after its first successful run;
- Block force pushes;
- Block branch deletion;
- Require linear history: recommended after the first PR workflow is proven;
- Require signed commits: optional at G0, evaluate before production.

Do not rely on a permanent administrator bypass for normal work. Any emergency bypass must be recorded in the Decision Log and reviewed afterward.

## 3. Merge policy

Recommended:

- allow Squash Merge;
- disable Merge Commit after confirming squash workflow;
- optionally allow Rebase Merge for carefully controlled maintenance work;
- automatically delete head branches after merge;
- Human Owner performs merge only after Controller verdict `APPROVE_FOR_HUMAN_MERGE`.

## 4. Actions permissions

Start with least privilege:

```yaml
permissions:
  contents: read
```

Grant write permissions only to a specific workflow that demonstrably requires them. AI coding integrations must work on feature branches or PRs, not direct pushes to `main`.

## 5. Security settings

Enable where available for the repository plan:

- Secret scanning and push protection;
- Dependabot alerts and security updates;
- Dependency review as a required PR check after Maven/npm manifests exist;
- Code scanning / CodeQL after the language scaffold is merged.

## 6. Templates

This bootstrap provides:

- `.github/ISSUE_TEMPLATE/work_package.yml`;
- `.github/ISSUE_TEMPLATE/decision_request.yml`;
- `.github/ISSUE_TEMPLATE/bug.yml`;
- `.github/pull_request_template.md`.

They must be present on the default branch before collaborators receive them automatically.

## 7. Required check naming

Keep workflow job names unique. The initial required check is exactly:

```text
governance
```

Future names should remain stable, for example:

```text
backend-build-test
backend-integration
migration-validation
architecture-boundary
frontend-quality
frontend-e2e
security-scan
traceability
```
