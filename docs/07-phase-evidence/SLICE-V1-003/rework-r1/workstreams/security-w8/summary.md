# W8 exact security evidence

Security run **33967874668, attempt 1** succeeded on Head `9b6e6195f779bd80b1e8ed9c78d6ad9daa1a68af`. The actual tested merge was `954ff3617402c3ff22fa8eb86d9aa8abaea76941`, with parents Base `08ad7da7d9e75b4ddd1c387a22ac0affba9e1430` and W8; its tree is `eb4bce1333c87e4de762f6f42bbd3bcd392fec38`.

Dependency job **101311078442**, Java job **101311078484** and TypeScript job **101311078347** succeeded. Java analysis **1729319849** and TypeScript analysis **1729315000** used CodeQL **2.26.4**, both on that exact merge. Independent aggregate CodeQL **101311235351** succeeded after Java finished, with **87 alerts: 11 warnings and 76 notes**. Its initial NEUTRAL response while Java was missing remains preserved.

The six principal-check HIGH, five fixture-SQL HIGH and PATH MEDIUM alerts **#118–129 remain fixed**, and are absent from the exact current SARIF. W7→W8 has no added or removed open alert. All 87 flagged expressions are byte-identical: 75 alerts are in unchanged whole files, and 12 are in files with narrowly reviewed time-precision/test changes. Nine existing deprecated-call locations moved with inserted lines. The individual delta records source hashes, remote Git blobs and exact expression equality. Prior bounded engineering triage remains applicable; no alert was dismissed here.

Raw SARIF still contains **92 results**, including five legacy HIGH false positives **#66, #73–76** dismissed in August. Their dismissal metadata and complete source files remain identical to Base. This evidence does not describe raw SARIF as having zero security-rule results.

The exact Base→W8 dependency comparison removes fast-uri 3.1.5 and its four HIGH advisories, and adds 3.1.6 with no vulnerability entries. The lock SHA-256 is still `6e2751b0187c16ac884ddc5584e2cd4f78fc3abd7a401a98388bc9334e0f8ea4`. Default main still has four open Dependabot HIGH alerts; branch remediation does not claim they closed.

Original API responses, SARIF and the complete job log are preserved. This workflow published no downloadable Actions artifacts. The repository governance patterns plus JWT scan found eight raw assignments in two TypeScript SARIF representations. Exact JSON-pointer review places every hit in static CodeQL session-fixation help; decoded examples also exactly match previously reviewed W7 help. The original scan `pass=false` is retained, accompanied by the narrow false-positive triage; no matching value is printed or copied into the triage.

No repository edit, workflow rerun, alert change, Provider access or live full-run XML read occurred. This Security result does not itself close full backend/browser verification, AC200 or independent Controller approval.
