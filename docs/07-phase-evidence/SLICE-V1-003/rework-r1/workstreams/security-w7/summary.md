# W7 security readback

W7 Head `3e4039259c0a56d0f10319cdcae79cab66f81983` was analyzed through merge `1d48739f4fe71d394e12e0623cf533e89770bece`, with exact parents Base `08ad7da7d9e75b4ddd1c387a22ac0affba9e1430` and W7. The tested tree is `3fbe57fc39f0d4d969e571ea895354e81c4fe1b8`, equal to W7.

Security run **33963350077 attempt 1 succeeded**. Dependency-review **101298991105**, Java **101298991340** and TypeScript **101298991284** succeeded. Separately, aggregate CodeQL **101299099534 succeeded**, reporting 87 new alerts. Java analysis **1729143817** and TypeScript **1729139824** used CodeQL **2.26.4** on the exact merge.

The original twelve repaired alerts #118–129 remain **fixed**: six permission HIGH, five fixture SQL HIGH and one PATH MEDIUM. W6→W7 has **no added or removed open alert**: the same 87 quality alerts, identical locations/rules/severities, remain open; none has a security severity. `w6-w7-alert-delta.json` records each match. The entire backend source tree, security workflow and dependency lock are exact Git-object matches to W6, so the earlier bounded individual triage is retained without a redundant general audit. The sole W7 changed file is the existing frontend business-journey test.

Raw SARIF still contains 92 results: the 87 open quality alerts plus five historical HIGH false positives #66 and #73–76, dismissed on 2026-08-28. Their recorded reasons and dismissal metadata are unchanged. No alert was dismissed by this run or this agent.

GitHub's exact Base→W7 dependency comparison removes fast-uri3.1.5 with four HIGH advisories and adds3.1.6 with no vulnerability entries. The published lock SHA-256 remains `6e2751b0187c16ac884ddc5584e2cd4f78fc3abd7a401a98388bc9334e0f8ea4`. Default main still has four open Dependabot alerts; the branch repair does not claim they were closed.

The raw API snapshots, original SARIF responses and complete Security job log are saved here. This workflow publishes no downloadable Actions artifact; the artifact-list API records zero. `manifest.json` binds each original evidence file and `publishability-scan.json` records its credentials/JWT-pattern scan. Disposable gh caches are excluded. The local W6 full-run checkout was not fetched, checked out, rebuilt or otherwise modified.

This is W7's security result only; full regression/browser evidence and independent Controller acceptance remain separate obligations.
