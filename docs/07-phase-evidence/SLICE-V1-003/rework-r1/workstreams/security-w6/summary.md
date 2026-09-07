# W6 exact-Head security readback

Head `3ed3f4c87c336cb07188e470528f328358fb279f` was analyzed through merge `34fae1e0985865874f9ec44a3be93cd67d3c61a6`, whose exact parents are Base `08ad7da7d9e75b4ddd1c387a22ac0affba9e1430` and W6. Its tree equals W6 `4e73aa0e7c30fe470528ecd5287b55a9c55e5ff1`.

Security run **33962619117 attempt 1 succeeded**. Dependency-review 101297026494, Java 101297026388 and TypeScript 101297026530 all succeeded. Independently, aggregate CodeQL **101297138061 succeeded**, reporting 87 new alerts. Java analysis **1729112987** and TypeScript **1729109673** both ran CodeQL **2.26.4** on that exact merge. The Security workflow is byte-identical to the earlier 247e candidate; thresholds and query sets were not weakened.

The exact open-alert delta is **99 → 87**: #118–123 (six principal permission HIGH), #124–128 (five fixture SQL HIGH) and #129 (PATH MEDIUM) are all returned by GitHub as **fixed**, absent from both current SARIFs. No new alert appeared. `codeql-99-to-87-delta.json` accounts for every former alert, retains remaining alerts' current locations and source hashes, and preserves their individual bounded engineering triage. The remaining 87 have no security severity: 11 correctness warnings and 76 maintenance notes. No additional proven BLOCKER/MAJOR was identified by that bounded review; the notes and warnings have not been dismissed.

Raw SARIF contains 92 results (91 Java + 1 TypeScript), comprising those 87 open quality alerts plus five historical HIGH results #66 and #73–76, dismissed as false positives on 2026-08-28. Their current affected files are byte-identical to exact Base. Their historical reasons and raw findings are retained in `summary.json`; this agent did not create or alter a dismissal. Raw result counts must not be described as zero security-rule results.

GitHub's exact Base→W6 dependency graph comparison removes fast-uri **3.1.5** with four HIGH advisories and adds **3.1.6** with `vulnerabilities: []`. The published lock's SHA-256 is `6e2751b0187c16ac884ddc5584e2cd4f78fc3abd7a401a98388bc9334e0f8ea4`; the only dependency-node change is its three authorized version/tarball/integrity fields. Default main still has four open Dependabot alerts. This branch evidence does not claim they were automatically closed.

There are no downloadable Actions artifacts for this Security run; actual full job logs and both original SARIF API responses are saved directly. API request metadata includes timestamps and failures: eight individual historical alert requests returned EOF, then the dismissed-alert list succeeded and provided the records. No workflow was rerun, alert changed, Provider contacted or repository file modified.

This establishes the W6 security-scan portion of AC200. Final full regression, remaining exact-Head evidence and independent Controller adjudication belong to the overall handoff and are not implied by this security receipt.
