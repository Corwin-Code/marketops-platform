Each original layer has its own publication pattern scan receipt, complete original file SHA index, archive member SHA index and empty triage. All 9 scans completed with zero pattern hits and unchanged original file bytes and file sets.

| Layer | Original files | Archive members | Original outcome |
|---|---:|---:|---|
| frontend-quality | 80 | 0 | COMMAND_SUCCEEDED_REVIEW_REQUIRED |
| governance-r2 | 19 | 0 | COMMAND_SUCCEEDED_REVIEW_REQUIRED |
| infrastructure | 23 | 0 | COMMAND_SUCCEEDED_REVIEW_REQUIRED |
| migration | 25 | 0 | COMMAND_SUCCEEDED_REVIEW_REQUIRED |
| supply-chain | 17 | 0 | COMMAND_SUCCEEDED_REVIEW_REQUIRED |
| security-npm-audit-r2 | 32 | 0 | COMMAND_SUCCEEDED_REVIEW_REQUIRED |
| browser | 23 | 0 | COMMAND_FAILED |
| browser-r2 | 62 | 28 | COMMAND_FAILED |
| browser-r3 | 38 | 0 | COMMAND_FAILED |

Browser r3 records 25 passing legacy nodes and zero advertising nodes; its entire layer remains failed. Browser r1/r2 are also preserved as failed. A later r4 is outside this scan.

Seven exact 02e governance patterns plus JWT and signed/token URL shape searches were used. Archive members were decoded recursively with ZIP CRC checks. Images were scanned as bytes only, without OCR or pixel inspection. This bounded scan does not prove the absence of all PII/secrets or authorize publication. Backend and all-CI scans remain separate. No product tests or source changes occurred.
