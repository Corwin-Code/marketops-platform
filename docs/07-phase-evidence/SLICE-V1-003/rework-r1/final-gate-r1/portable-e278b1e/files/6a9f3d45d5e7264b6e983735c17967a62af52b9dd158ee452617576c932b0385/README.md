This is a private, additive reconciliation of Backend CI run 34001582148, attempt 1, at source Head 7e66cf87afc15773d4f6e6e6717a86e7b8336ae5. The original downloads remain untouched in `/tmp/slice3-checkpoint-backend-ci-7e66cf8`. This successful checkpoint precedes the section 6.24 OutcomePolicy repair and is not final closure or Controller approval.

All three job checkout logs independently record tested merge f9a1d73f324323d13ea72db3890090c9b9d85450. The existing Security receipt at the same merge is referenced unchanged; its verified merge tree 4fe4ffd46ff408ff97cd59a2b676c575a73982ac equals the source tree, with parents 08ad7da7d9e75b4ddd1c387a22ac0affba9e1430 and the exact source Head. The saved 1,272-file execution inventory is the authority, not the now-dirty checkout. All four actual capacity source-input lists (1,055 entries each) match its corresponding files and exactly match the saved local source-input bytes. The earlier full-tree comparison separately records 1,270 direct matches and two declared CRLF checkout conversions.

Per-job actual results:

| Job | Job ID | Actual unit results | Actual integration results | Evidence boundary |
| --- | --- | --- | --- | --- |
| backend-build | 101401317918 | 1,588 passed | 1,056 passed | Raw XML has 2,644 unique class/name nodes; no failures, errors or skips. |
| backend-integration | 101401317798 | 1,588 passed | 1,056 passed | Raw XML uploads only the 1,056 integration nodes. Units are separately reconciled from its single actual Maven step log; no unit XML/nodes are fabricated. |
| architecture-boundary | 101401318022 | 72 passed | Not this job's scope | Actual single Maven step log; a repeated subset of the full tests. |

Maven suite display labels are preserved in full, including spaces, and are not treated as Java class names. Each job's per-suite sums reconcile to its two aggregate lines with no repeated complete labels. Duplicate combined copies of Actions logs are not counted. Results across jobs are repeated execution evidence, not a larger number of distinct tests.

| Job / measurement | Critical P95 ms | Maximum ms | Sweep ms |
| --- | ---: | ---: | ---: |
| Build / existing capacity | 9,775 | 150,657 | 26,269 |
| Build / mixed capacity | 153,569 | 185,855 | 42,014 |
| Integration / existing capacity | 15,914 | 169,740 | 43,746 |
| Integration / mixed capacity | 189,142 | 222,715 | 69,717 |

Each measurement binds its own dataset UUID/hash, source-input hash, run/job/attempt/artifact identity and resource receipt. Both JVMs report four processors and max heap 4,192,206,848 bytes. The outer runner/Docker resources differ slightly by job (Build 16,765,374,464 bytes; Integration 16,766,414,848 bytes), each with four processors. Mixed PostgreSQL container zero quotas mean no additional container cap; they do not erase outer runner/Docker limits. The full raw mixed state/control counts remain in the reconciliation; pre-sweep `INCIDENT` is not relabelled globally healthy. No real Provider access or production writes occurred in these measurements.

Artifacts are individually pinned to the official GitHub digest and size:

- 9980040138, backend-test-reports: aa828dc67af9e61ef1147b7ecc6ebc50aff0f2631be53bf1b608588c6bf2f361.
- 9980137796, backend-integration-reports: 57a08ab1bf8fc145e4e958365fe1facb31e0b3809c9d98a4e92a723d5d236fce.
- 9980039142, backend-supply-chain: bf37bf80be4ae394a21958a4124eb7ba316f0163ec37f412fba3e1b6c53ae2ac.

Run logs have their own download hash, not an invented artifact ID. All four downloads succeeded; stderr files and exact commands remain preserved. Every one of the 2,641 ZIP members was read, CRC-checked and indexed by exact name, byte size and SHA. The Build JaCoCo root LINE counter is 24,197 covered / 3,834 missed; class LINE sum is 24,199 / 3,834. Sourcefile line totals equal root. Integration did not upload a JaCoCo XML, so none is claimed for that artifact.

The publication scan read all 13 original files and actual nested members: 2,654 records / 100,164,372 bytes including archive containers and duplicate log copies. Seven governance patterns pinned from the 7e source plus a JWT shape found zero hits; original bytes remained unchanged. This bounded scan is not a universal PII/credential proof and does not independently grant publication authority. `FINAL-REVIEW-RECEIPT.json` links the completed review; earlier METADATA intake and intermediate reconciliation retain their original pending-scan boundary.
