# GitHub Setup — Public Pre-production Repository

## 1. Repository

```text
Name: marketops-platform
Owner: Corwin-Code
Visibility: Public during pre-production
Default branch: main
```

Do not add a separate README, .gitignore or License during remote creation; this bootstrap already contains them.

Public visibility is a temporary Human Owner decision recorded as D-15. When real
production go-live is reached, or earlier before any confidential business material
is committed, upgrade the GitHub plan as needed, change the repository to Private
and revalidate the Ruleset, required checks and security features.

## 2. Public repository boundary

Treat every commit, branch, Issue, Pull Request, review comment, Actions log and
artifact as publicly accessible. Never add:

- credentials, tokens, cookies, private keys or environment files with values;
- buyer PII, production payloads or unredacted operational exports;
- confidential commercial terms, supplier contracts, internal cost details or
  legal documents that the Human Owner has not approved for publication.

Changing the repository to Private later does not make prior public disclosure an
acceptable secret-handling strategy. Any accidental disclosure must be treated as
an incident, with credential rotation and history remediation where applicable.

## 3. Repository Ruleset for `main`

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

## 4. Merge policy

Recommended:

- allow Squash Merge;
- disable Merge Commit after confirming squash workflow;
- optionally allow Rebase Merge for carefully controlled maintenance work;
- automatically delete head branches after merge;
- Human Owner performs merge only after Controller verdict `APPROVE_FOR_HUMAN_MERGE`.

## 5. Actions permissions

Start with least privilege:

```yaml
permissions:
  contents: read
```

Grant write permissions only to a specific workflow that demonstrably requires them. AI coding integrations must work on feature branches or PRs, not direct pushes to `main`.

## 6. Security settings

While the repository is Public, enable and verify:

- Secret scanning and push protection;
- Dependabot alerts and security updates;
- Dependency review as a required PR check after Maven/npm manifests exist;
- Code scanning / CodeQL after the language scaffold is merged.

Repository visibility changes do not replace explicit verification. When the
repository returns to Private, re-check feature availability and required-check
behavior under the selected GitHub plan during the production go-live transition.

## 7. Templates

This bootstrap provides:

- `.github/ISSUE_TEMPLATE/work_package.yml`;
- `.github/ISSUE_TEMPLATE/decision_request.yml`;
- `.github/ISSUE_TEMPLATE/bug.yml`;
- `.github/pull_request_template.md`.

They must be present on the default branch before collaborators receive them automatically.

## 8. Required check naming

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

## 9. Production go-live transition

When real production go-live is reached, or earlier before confidential business
material is committed:

1. upgrade the GitHub plan or move ownership to an approved account/organization
   that supports the required controls for a Private repository;
2. change repository visibility to Private;
3. verify `main` remains protected and all required checks still apply;
4. verify Secret Scanning, Push Protection, Dependabot and CodeQL/dependency
   controls remain enabled or record an approved replacement control;
5. review public history for material that must never enter production context;
6. record the transition, evidence and Human Owner approval in Current State,
   Decision Log and `docs/07-phase-evidence/Production-Go-Live/`.
