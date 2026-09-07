# Official failed checkpoint backend raw scan

Run `33994962312`, attempt `1`, source head `5fd53c0959ad50d949ee92c7477f1fef9954f2a2`, remains a **failed historical checkpoint**. This review does not admit any test, security, or closure PASS.

All 26 original files and 2,512 actual members of the two official backend artifacts and the run-log archive were scanned. ZIP CRCs, official artifact digest/size, and the existing source-file SHA manifest were verified. The scan includes raw log/XML/text content, entity decoding, and decoded JSON strings, including JSON inside the artifacts. Each encountered file/member was inspected for nested ZIP/GZIP/TAR containers; none were present inside these three top-level ZIP files. Member metadata and hashes are in `MEMBERS.jsonl`; original hashes and exact rule definitions are in `SCAN-INPUT.json`.

The seven existing governance secret-shape patterns plus a JWT shape pattern returned **0 matches**, so there are no per-hit classifications or unreviewed hits. No matched value or raw token was printed. All original bytes remain unchanged. This is a bounded pattern result, not a universal no-secret/no-PII certification or independent authorization to publish raw payloads. Any subsequently identified test token must remain private.

`scan_raw.py` is a local read-only scanner, not a repository runtime or product validation change. `PUBLICATION-SAFETY-REVIEW.json` is the review record; `SHA256-MANIFEST.json` binds all report/scanner files other than the manifest itself.
