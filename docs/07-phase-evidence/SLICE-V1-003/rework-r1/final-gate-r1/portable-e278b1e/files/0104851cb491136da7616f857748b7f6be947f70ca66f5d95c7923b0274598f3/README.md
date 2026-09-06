Nine independent immutable archives of exact previously scanned local 02e6172 layer files. Each ZIP has a separate complete member index with original path, portable member path, SHA-256, byte size, compressed size, CRC32, Unix mode and fixed timestamp.

| Layer | ZIP bytes | Members | Original outcome |
|---|---:|---:|---|
| frontend-quality | 725359 | 80 | COMMAND_SUCCEEDED_REVIEW_REQUIRED |
| governance-r2 | 249823 | 19 | COMMAND_SUCCEEDED_REVIEW_REQUIRED |
| infrastructure | 2481358 | 23 | COMMAND_SUCCEEDED_REVIEW_REQUIRED |
| migration | 172198 | 25 | COMMAND_SUCCEEDED_REVIEW_REQUIRED |
| supply-chain | 269994 | 17 | COMMAND_SUCCEEDED_REVIEW_REQUIRED |
| security-npm-audit-r2 | 252638 | 32 | COMMAND_SUCCEEDED_REVIEW_REQUIRED |
| browser | 203533 | 23 | COMMAND_FAILED |
| browser-r2 | 1535867 | 62 | COMMAND_FAILED |
| browser-r3 | 715367 | 38 | COMMAND_FAILED |

ZIP creation uses lexicographic member names, 1980-01-01 00:00:00 timestamps, mode 100644 and DEFLATE level 6. Every member was read back and verified for SHA, size and CRC; order/mode/timestamps were checked. Each original file and complete file set was compared against its scanned inventory before and after.

publication-scan/ contains byte-identical copies of original scan receipts, complete original file SHA inventories, decoded archive member indices, findings and scanner provenance. PUBLICATION-SCAN-COPY-INDEX.json maps original paths to portable package-relative paths. Original absolute provenance fields remain unchanged.

For offline inspection, locate the ZIP using archive.packageRelativePath in each *-ZIP-MEMBER-INDEX.json; resolve each files[].member inside that ZIP and compare bytes, SHA-256 and CRC32. Original source files are not required for this archive-member check. The companion copied scan receipt is given by portablePublicationScanReceipt.

Browser r1/r2/r3 remain whole-layer failures. Browser r3 has 25 legacy passing nodes and zero advertising nodes; it is not an all-browser PASS. Completed other commands are preserved under their original identities without asserting final engineering closure. Backend/current CI and a later browser r4 are outside this package. Archive creation is not reexecution, publication or Controller approval. Scanning is bounded pattern/byte inspection; no OCR or pixel review was performed.

PREPARATION-DISPOSITION.json preserves the initial harmless path-order assertion; two already-verified ZIPs stayed unchanged and were revalidated by the resumed command. No original evidence was rewritten.
