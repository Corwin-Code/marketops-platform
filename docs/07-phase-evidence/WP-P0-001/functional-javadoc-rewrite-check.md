# TC-GLOBAL-002 — functional comments

**Result: PASS**; the clean committed-head validator inspected 191
non-generated files and the Python discovery ran 104 tests.

The validator extracts source comments before matching. It rejects work-package
or commit-stage narration, review-version history, future cleanup instructions,
provisional wording, workaround narration and legacy-compatibility claims in
production sources, workflows, scripts, canonical design and runbooks.

Governance, decisions and evidence may record history because history is their
purpose. Executable comments explain current purpose, inputs, outputs, safety
boundaries and failure handling. Tests prove code strings and URL text are not
misclassified as comments.

```text
TC-GLOBAL-002 Functional Comment Check: PASS
```
