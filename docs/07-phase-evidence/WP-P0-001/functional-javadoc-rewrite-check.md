# TC-GLOBAL-002 — functional comments

**Result: PASS**; the committed-head Fresh Clone validator inspected 200
non-generated files and Python discovery ran 122 tests.

The validator extracts source comments before matching. It rejects commit/WP
stage narration, Controller/rework/revision history, `historically`, `previously`,
former/old-path language, future cleanup instructions, provisional wording,
workarounds and legacy-compatibility claims in executable sources, workflows,
scripts, the canonical design and runbooks.

Governance decisions and evidence may retain history because history is their
purpose. Executable comments state only current purpose, inputs, outputs, safety
boundaries and failure handling. Mutation tests prove every new history phrase is
detected and code strings or URL text are not misclassified as comments.

```text
TC-GLOBAL-002 Functional Comment Check: PASS
```
