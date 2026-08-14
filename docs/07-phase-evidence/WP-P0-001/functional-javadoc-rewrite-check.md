# TC-GLOBAL-002 — functional comments

**Result: PASS**; the validator inspected 188 non-generated workspace files.

The validator extracts source comments before matching. It rejects work-package
or commit-stage narration, review-version history, future cleanup instructions,
provisional wording, workaround narration and legacy-compatibility claims in
production sources, workflows, scripts, canonical design and runbooks.

Governance, decisions and evidence may record history because history is their
purpose. Executable comments instead explain current purpose, inputs, outputs,
failure handling and security consequences. Tests prove code strings and URL
text are not misclassified as comments.

```text
TC-GLOBAL-002 Functional Comment Check: PASS
```
