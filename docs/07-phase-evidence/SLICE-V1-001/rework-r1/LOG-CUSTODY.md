# Raw command log custody

Command output is stored as lossless `.log.gz` files so original trailing
whitespace and terminal bytes remain intact without changing repository checks.
`LOG-CUSTODY.json` binds each artifact to both its compressed hash and the
original log hash. Decompress with `gzip -dc PATH.log.gz` and verify that output
against `original_sha256`. Older checkpoint JSON/log references use the logical
uncompressed name; the bytes are recovered from the corresponding gzip file.

`ARTIFACT-HASHES.json` lists actual repository artifacts. Where packaging changed,
`ARTIFACT-HASHES.before-log-packaging.json` preserves the earlier index bytes and
original uncompressed hashes. These historical indexes describe the uncompressed
view; they do not represent another test run. No raw output was trimmed,
redacted after hashing, regenerated or replaced by an illustrative result.

The first packaging attempt added a scoped whitespace exception. The existing
governance check refused it. That exception was removed before publication;
`.gitattributes` remains byte-identical to the reviewed Head. The failed
governance log is retained in `checks-134/governance-staged-134-failed.log.gz`
and is not counted as a passing verification.
