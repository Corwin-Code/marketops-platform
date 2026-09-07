# Exact SARIF secret-pattern triage

The four original W6/W7 TypeScript SARIF members are safe to retain unchanged for these exact matches. All 16 raw matches occur inside bundled CodeQL `js/session-fixation` teaching examples in rule help (markdown/text); the two documents per run are pretty JSON and the original API response body. They do not occur in application findings or runtime credentials. No matched value is printed or retained in this triage.

Decoded rule-help strings also contain `js/jwt-missing-verification` teaching examples that raw JSON escape handling did not match. These are explicitly reviewed separately; the original raw scan is not rewritten or relabelled as a zero-hit scan.

`review.json` binds original archive/member hashes, exact JSON pointers and raw line/column positions, sanitized context and justification. The original scan remains `pass=false`. Patterns, original archives and repository files are untouched. This is a scoped false-positive disposition, not a CodeQL alert dismissal or blanket publication certificate.

The v2 scanner is not recursive through a ZIP/GZIP embedded inside a TAR. Any such members of these two security archives have therefore been separately decompressed and scanned with the unchanged pattern set plus JWT shape; precise counts and hits are recorded in the report. Unrelated archives are outside this limited review.
