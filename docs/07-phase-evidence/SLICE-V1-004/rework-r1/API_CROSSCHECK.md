# Official API document cross-check and implementation boundary

No real provider or account was called. These are the user-supplied snapshots captured on 2026-09-09 and identified by `API_DOCUMENT_MANIFEST.json` (manifest SHA-256 `cf7d7b3b1bcd7ffb7afb80afc64f7264396d80959737dc1a7e12563bc669191d`). They do not establish live capability, credentials, category applicability, or production enablement. Read-only extraction covers the documents; relevant page inspection below is distinct from live source qualification.

| Source | Pages | Observation | Required implementation consequence |
| --- | --- | --- | --- |
| Ozon Seller API 文件.pdf | 60–61 | `/v1/product/attributes/update` changes supplied attributes and returns a task ID; `Item-Retry-After` is in minutes. Inner items schema is collapsed in this export. | Bind the verified exact operation/attribute schema and native target; do not infer hidden members or describe this as complete wire-schema proof. Preserve native wait without shortening. |
| Ozon Seller API 文件.pdf | 55; 93–94 | Task status uses `/v1/product/import/info`; description readback includes result ID, offer ID and description. | Bind status to the submitted task and readback to the frozen native object; a description substring is insufficient. |
| Item management — WB API.pdf | 66–68 | Update overwrites the listing and requires unchanged parameters; 200 may require checking failure details; synchronization can take up to 30 minutes. Description limit depends on category. `needKiz` determines labeling requirement. | Prove unchanged fields and exact category rules, separate acknowledgement/status/readback/display, and never impose the WB labeling declaration on Ozon. Page 68 was rendered and visually checked. |
| General — WB API.pdf | 9 | `X-Ratelimit-Retry` is seconds; retries before it continue to receive 429. | Read the native header and preserve the earliest permitted time. |

These checks constrain frozen findings 009, 021 and 022; source interpretation alone does not settle any finding. The 113-test convergence pass exercises exact long-text intake and the loopback worker/adapter path. The specialized request-schema, task/object, retry-timing and restart classes subsequently passed in the clean backend verification; that local result does not qualify a live Provider capability.

Standard HTTP Retry-After semantics are cross-checked separately against [RFC 9110 §10.2.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3); its standard seconds/date form is not reinterpreted as the Ozon native minute header.

## Promotion economics source boundary — local text inspection

The two source PDF hashes were rechecked against the existing manifest. Targeted text extraction inspected Ozon pages 146–153 and WB Marketing and Promotion pages 132, 135–141; this was not a live API call or visual/wire-schema qualification. The extraction process finished; no provider/test process was started.

| Source | Observed fields / scope | Implementation consequence |
| --- | --- | --- |
| Ozon pp. 146–148 | `/v1/actions`: native activity ID, type/description, start/end, `auto_add_dates`, `freeze_date`, participation and discount descriptors. The continuation explains restrictions on raising price, changing members and reducing promotion quantities when frozen; lowering price/increasing quantities is treated separately. | Preserve native dates and exact action-specific restrictions. A single timeless freeze flag or participation flag does not establish all exit rights, residual release or economic conditions. |
| Ozon pp. 149–153 | Candidate/participating product pages name `id`, `price`, `action_price`, stock/minimum stock and other parameters. Product examples include pagination; parts are clipped and inner schemas are not fully expanded. | These are object/participation/price evidence candidates. Do not reinterpret `action_price` as final seller revenue after all benefits, `order_amount` as a fixed activity fee, or a named boost field as a known expense without its verified definition. |
| WB pp. 135–141 | Selected promotion IDs and a participation selector are documented. Detailed promotion/nomenclature response arrays remain collapsed in the supplied export. The preceding description excludes auto promos from the nomenclature method. | Do not invent hidden monetary fields, infer complete scope for auto promos, or use partial/unsupported membership as a complete economic basis. |

No inspected page establishes a complete activity-level fixed-fee, compensation,
stacking-order or final seller-revenue contract. This is a limitation of these
inspected snapshots, not a claim that the provider has no such facts. The source
implementation therefore does not infer those values from provider fields.

The promotion calculation reads the required fixed fee, buyer payment, seller
revenue and explicit platform compensation through the existing governed internal
finance intake/version/provenance authority, scoped to the exact Store, Listing,
native promotion identity, terms digest, currency and finite period. Existing
price-economics profiles explicitly name their fee base, and the calculation
rechecks the same qualified versions at approval/use. Missing, inconsistent,
uncovered, late-known or revoked inputs remain unqualified; an explicit sourced
zero is distinct from absence. This reuse adds no provider fact acquisition,
provider write, generic financial platform or production value.

This cross-check is source interpretation only. The bounded convergence command
recorded in `executable-evidence.md` passed 113 tests, and the terminal local
matrix receipts are recorded in `FINAL_LEVEL1_LOCAL_VERIFICATION.json`. No real
Provider/account call occurred, and no API snapshot is treated as proof of final
economic qualification, exit, residual release, verified live capability or
production enablement.
