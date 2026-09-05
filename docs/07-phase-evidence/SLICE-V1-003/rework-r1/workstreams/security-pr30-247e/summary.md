# Default-branch dependency alerts and PR #30 security identity

The four open HIGH Dependabot alerts all affect the same `fast-uri 3.1.5` transitive development/optional dependency in `frontend/marketops-console/package-lock.json`, via `ajv 8.20.0` (`^3.0.1`). Base `08ad7da7d9e75b4ddd1c387a22ac0affba9e1430` and PR Head `247ea5ced6cd0ac110314db9fa606d8995c85cac` contain exactly the same lock bytes and Git blob `96d6c912433b6ccb260160f2ac5d757ab204bcc0`; SHA-256 is `86b820f905240c477da4d620bb6c8cb30a6d2da6fbc4869ced083258556ce332`. These alerts were not introduced by this PR, and the published `247e` candidate did not remove them.

| Alert | Official advisory | Applicable 3.x range | First patched |
| --- | --- | --- | --- |
| #1 | GHSA-5jgf-p345-68v8 / CVE-2026-75931 | `>= 3.1.3, < 3.1.6` | 3.1.6 |
| #2 | GHSA-fph4-wmhf-6fwf / CVE-2026-75899 | `>= 3.1.2, < 3.1.6` | 3.1.6 |
| #3 | GHSA-f65p-4m7j-42xc / CVE-2026-75975 | `>= 3.0.0, < 3.1.6` | 3.1.6 |
| #4 | GHSA-jqff-g426-hqxp / CVE-2026-76172 | `>= 3.0.0, < 3.1.6` | 3.1.6 |

Each individual GitHub-reviewed primary advisory was retrieved and was not withdrawn. Official npm registry metadata confirms the 3.1.6 tarball and integrity. The authorized local fix changes only the fast-uri node's `version`, `resolved` and `integrity`; package.json and all other lock nodes remain unchanged. New lock SHA-256 is `6e2751b0187c16ac884ddc5584e2cd4f78fc3abd7a401a98388bc9334e0f8ea4`. `npm update fast-uri --package-lock-only` initially chose newer compatible 3.1.7; the explicit version check rejected that intermediate result, retained it, and the exact authorized 3.1.6 metadata was applied to the same three fields. No override or broad lock refresh was introduced.

Node v24.19.0 / npm 11.17.0 then ran `npm audit --package-lock-only --ignore-scripts --json --audit-level=moderate` against the exact current lock: exit 0, 398 dependencies and zero known vulnerabilities at every severity. It did not install node_modules or mutate the lock. This is current npm advisory evidence, not a claim that GitHub default-main alerts were closed or that every dependency is intrinsically safe. Fresh frontend quality/browser and new-Head CI remain pending.

## Exact CI result at the published 247e candidate

Security run `33960570489`, attempt 1, is bound to Head `247ea5ced6cd0ac110314db9fa606d8995c85cac` and completed SUCCESS. Jobs `101291630738` dependency-review, `101291630635` codeql-java and `101291630812` codeql-typescript completed SUCCESS. The dependency graph compare returned `[]`; dependency-review is a delta check and its success did not detect/remediate pre-existing fast-uri risk.

CodeQL analyses `1729021395` (Java) and `1729015234` (JavaScript/TypeScript) both analyzed `refs/pull/30/merge` at `4c1aaa695892f70fe6966d8076b2b05e338c32be`, tree `e43d50161faf5b861ee768fa49b66c8b7a407429`. Git API confirms its two parents are the exact Base and Head above. Neither analysis reported an execution error. However, aggregate CodeQL check `101291728824` completed FAILURE: 99 new alerts including 11 HIGH security alerts (six permission-check findings in AdvertisingOperationsConsoleController and five SQL construction findings in test fixtures). Raw per-alert locations are retained in `summary.json` and the original API responses. Root/Norm/UI own the separately authorized CodeQL remediation.

## AC200 boundary

At published Head 247e, AC200's passing-security-scan prerequisite cannot be claimed: pre-existing HIGH dependencies were still present and aggregate CodeQL actually failed. No new Frozen Finding or formal Controller classification is created by this review. The local lock fix addresses the four fast-uri advisories and has a zero-vulnerability npm audit, but the current source is newer than 247e and requires the planned checkpoint, fresh tests, exact CI/alert readback and Controller adjudication. Production enablement, Provider access and alert dismissal remain unchanged.

All HTTP responses, source content responses, exact commands, timestamps, intermediate lock selection, final narrow patch and npm reports are retained beside this document. `manifest.json` hashes evidence files; the disposable npm cache is deliberately excluded.
