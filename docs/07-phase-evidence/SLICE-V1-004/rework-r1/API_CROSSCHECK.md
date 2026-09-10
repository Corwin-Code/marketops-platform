# Official API document cross-check — work in progress

No real provider or account was called. These are the user-supplied snapshots captured on 2026-09-09, identified by `API_DOCUMENT_MANIFEST.json`. They do not establish live capability, credentials, category applicability, or production enablement. Read-only extraction covers the documents; relevant page inspection below is distinct from full source validation.

| Source | Pages | Observation | Required implementation consequence |
| --- | --- | --- | --- |
| Ozon Seller API 文件.pdf | 60–61 | `/v1/product/attributes/update` changes supplied attributes and returns a task ID; `Item-Retry-After` is in minutes. Inner items schema is collapsed in this export. | Bind the verified exact operation/attribute schema and native target; do not infer hidden members or describe this as complete wire-schema proof. Preserve native wait without shortening. |
| Ozon Seller API 文件.pdf | 55; 93–94 | Task status uses `/v1/product/import/info`; description readback includes result ID, offer ID and description. | Bind status to the submitted task and readback to the frozen native object; a description substring is insufficient. |
| Item management — WB API.pdf | 66–68 | Update overwrites the listing and requires unchanged parameters; 200 may require checking failure details; synchronization can take up to 30 minutes. Description limit depends on category. `needKiz` determines labeling requirement. | Prove unchanged fields and exact category rules, separate acknowledgement/status/readback/display, and never impose the WB labeling declaration on Ozon. Page 68 was rendered and visually checked. |
| General — WB API.pdf | 9 | `X-Ratelimit-Retry` is seconds; retries before it continue to receive 429. | Read the native header and preserve the earliest permitted time. |

These checks inform frozen findings 009, 021 and 022. None is closed by this document. Native timing now has targeted loopback-HTTP, worker and isolated-database evidence in INTERIM_VERIFICATION.md. Exact wire schema, task/object correlation and the combined scheduler trace remain unverified.

Standard HTTP Retry-After semantics are cross-checked separately against [RFC 9110 §10.2.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3); its standard seconds/date form is not reinterpreted as the Ozon native minute header.
