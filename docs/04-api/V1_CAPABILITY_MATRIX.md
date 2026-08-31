# V1 Platform API Capability Matrix

```yaml
document_type: capability_evidence_register
scope: MARKETOPS_V1
platforms: OZON_AND_WILDBERRIES
initial_verification_state: UNVERIFIED_FAIL_CLOSED
owner_accounts_available: true
secret_material_allowed_in_document: false
```

## 1. Rule

This file records current platform facts; it does not guess endpoints, roles,
quotas or behavior from the Baseline. Before implementation of a concrete Adapter,
Claude/Codex must verify current official primary documentation and, where
required, the Owner-controlled real account. Every row is fail-closed until its
required evidence is attached.

No Token, API key, Cookie, account-secret value, signed URL, Buyer PII or
unredacted production payload may appear here, in a PR, Issue or test fixture.

## 2. Required fields per verified capability

| Field | Requirement |
| --- | --- |
| Platform | `OZON` or `WILDBERRIES` |
| Account/Store scope | opaque internal reference only |
| Internal capability | stable business capability code |
| Native method/endpoint | current official method identifier |
| API/version | current version or dated contract |
| Read/write | exact class |
| Credential category/scope | metadata only |
| Subscription/role requirement | verified account fact |
| Rate limit/quota | value, response headers/semantics and evidence date |
| Pagination/cursor | exact native model |
| Source freshness/latency | source behavior, not desired polling alone |
| Business/idempotency key | native or MarketOps strategy |
| Native states/errors | preserved vocabulary and unknown handling |
| Timeout/unknown-result strategy | readback/status/idempotency evidence |
| Readback method | exact independent verification path |
| Restore/compensate | supported/unsupported and safe strategy |
| Schema/contract fixture | approved redacted/synthetic reference |
| Deprecation/replacement | current official status |
| Last verified | UTC date/time |
| Verifier and evidence | source URL/reference + test/evidence ID |
| Contract-test status | `UNVERIFIED`, `PASS`, `FAIL`, `BLOCKED` |

## 2a. Implementation state after SLICE-V1-001

Every row in this register is still `UNVERIFIED`, and the implementation is
built so that this is a working state rather than a blocked one.

The fields this document requires are now recorded columns, each carrying its
own verification state and evidence reference:

| Required field | Where it lives |
| --- | --- |
| Native method/endpoint, path, query, body | `platform.platform_endpoint` |
| API base and timeouts | `platform.platform_api_profile` |
| Credential presentation | `platform.platform_auth_header` |
| Read/write class, subscription requirement | `platform.platform_capability` |
| Write result model | `platform.platform_capability.write_result_model` |
| Apply, status enquiry, readback and restore shape | `platform.capability_operation` |
| Rate limit, pagination, freshness, idempotency, late-data behaviour | `platform.platform_endpoint` |
| Payload field locations | `staging.normalization_mapping`, `staging.normalization_field` |
| Store-level availability | `platform.capability_subject_status` |
| Verification provenance | `verification_state`, `last_verified_at`, `evidence_ref`, `verified_source_title` on each |

A row is reachable only when it is `VERIFIED` **and** `ACTIVE`, and the
relational contract refuses `ACTIVE` without `VERIFIED`. An unverified
capability therefore has no reachable specification, and the fail-closed
behaviour is the absence of a call rather than a check somebody could forget.

Verifying a row is a separate act from recording it: recording a shape is a
claim about documentation, verifying it is a claim that somebody exercised it
against a real account and watched what happened. Only the second makes an
operation reachable, and only an Owner-authorized session against a real account
can produce it.

## 3. Slice 1 required read capabilities

All rows begin `UNVERIFIED`. Implementation may refine internal codes, but it may
not weaken the business coverage or claim an endpoint before verification.

| Platform | Internal capability | Purpose | Minimum evidence | Initial state |
| --- | --- | --- | --- | --- |
| Ozon | `PRODUCT_LISTING_READ` | Product/listing/variant identity and sellability | official docs + real read + Raw/contract fixture | UNVERIFIED |
| WB | `PRODUCT_LISTING_READ` | Product card/variant identity and sellability | official docs + real read + Raw/contract fixture | UNVERIFIED |
| Ozon | `PRICE_READ` | current price/discount/promotion observation and write readback | official docs + real read | UNVERIFIED |
| WB | `PRICE_READ` | current price/discount and write-task outcome/readback | official docs + real read | UNVERIFIED |
| Ozon | `STOCK_READ` | FBO/FBS stock and availability diagnosis | official docs + real read | UNVERIFIED |
| WB | `STOCK_READ` | FBO/FBS/seller-warehouse stock diagnosis as supported | official docs + real read | UNVERIFIED |
| Ozon | `ORDER_FULFILLMENT_READ` | order/posting/completion/cancel facts by FBO/FBS | official docs + real read | UNVERIFIED |
| WB | `ORDER_FULFILLMENT_READ` | order/supply/sales facts by FBO/FBS | official docs + real read | UNVERIFIED |
| Ozon | `RETURN_REFUND_READ` | refusal/return/refund facts | official docs + real read | UNVERIFIED |
| WB | `RETURN_REFUND_READ` | return/refusal/refund facts | official docs + real read | UNVERIFIED |
| Ozon | `FINANCE_SETTLEMENT_READ` | fees/transactions/settlement facts | official docs + real read/report | UNVERIFIED |
| WB | `FINANCE_SETTLEMENT_READ` | sales/realization/financial report facts | official docs + real read/report | UNVERIFIED |
| Ozon | `FUNNEL_ANALYTICS_READ` | impressions/visits/conversion where account permits | official docs + account permission + real read | UNVERIFIED |
| WB | `FUNNEL_ANALYTICS_READ` | funnel/analytics where account permits | official docs + account permission + real read | UNVERIFIED |
| Ozon | `ADVERTISING_PERFORMANCE_READ` | campaign/spend/performance diagnosis | official docs + account permission + real read | UNVERIFIED |
| WB | `ADVERTISING_PERFORMANCE_READ` | campaign/spend/performance diagnosis | official docs + account permission + real read | UNVERIFIED |
| Ozon | `PROMOTION_READ` | promotion/discount context for diagnosis and guardrail | official docs + real read | UNVERIFIED |
| WB | `PROMOTION_READ` | promotion participation/discount context | official docs + real read | UNVERIFIED |

When a desired field is genuinely unavailable, the Matrix records the gap and the
product exposes `NOT_AVAILABLE` with reduced Confidence; it must not synthesize a
zero or invent a substitute.

## 4. Slice 1 controlled-write capability

| Platform | Internal capability | Required chain | Initial state |
| --- | --- | --- | --- |
| Ozon | `PRICE_CHANGE` | preview → deterministic guardrail → approval/policy → idempotent command → official write → status/readback → audit → outcome → restore/compensate | UNVERIFIED |
| WB | `PRICE_CHANGE` | preview → deterministic guardrail → approval/policy → idempotent command → official write/task → item/task status → readback/quarantine handling → audit → outcome → restore/compensate | UNVERIFIED |

`PRICE_CHANGE` is one business intent, not an assertion that native endpoint,
payload, state or error semantics are symmetric.

## 5. Later V1 candidates

These capabilities are diagnosis/task-only in Slice 1 unless separately promoted.

`STOCK_CHANGE` moved out of SLICE-V1-002 when that Contract was accepted. The
Slice raises accountable work about availability and verifies its outcome; it
writes no stock anywhere, has no Preview, Approval, Command, Outbox, Adapter or
Readback for stock, and its absence is checked by test rather than asserted.

| Capability | Intended Slice | Current release state |
| --- | --- | --- |
| `STOCK_CHANGE` | a later Slice; explicitly out of SLICE-V1-002 | DISABLED / UNVERIFIED |
| `AD_BUDGET_CHANGE` | SLICE-V1-003 | DISABLED / UNVERIFIED |
| `AD_BID_CHANGE` | SLICE-V1-003 | DISABLED / UNVERIFIED |
| `AD_CAMPAIGN_STATE_CHANGE` | SLICE-V1-003 | DISABLED / UNVERIFIED |
| `PROMOTION_PARTICIPATION_CHANGE` | SLICE-V1-004 | DISABLED / UNVERIFIED |
| `LISTING_CONTENT_CHANGE` | SLICE-V1-004 | DISABLED / UNVERIFIED |
| `ORDER_FULFILLMENT_ACTION` | SLICE-V1-005 | DISABLED / UNVERIFIED |

## 6. Gate-EV authority before write evidence

No write row may perform the real call required for `PASS` until Gate EV issues
`AUTHORIZE_BOUNDED_REAL_WRITE_VERIFICATION` for the exact Platform, opaque
Account/Store, Capability, SKU allowlist, verification window, price-delta and
cumulative-exposure limits. The envelope also binds current Capability evidence,
Guardrails/Dry Run, supervised operator/abort owner, Kill Switch, captured
pre-state, Readback/Restore/Compensate, unknown-result/manual-resolution and
durable redacted Audit evidence.

Gate EV authorizes only evidence generation. It does not turn the row into an
enabled Pilot Capability, authorize recurring execution or replace Gate E.

## 7. Verification and change protocol

A Matrix row may become `PASS` only with:

1. current primary-source review;
2. safe request/response contract test;
3. real-account capability/permission proof when required;
4. Raw evidence and safe redaction;
5. rate-limit/failure/unknown-state behavior;
6. write rows: real bounded write, Readback and restore/compensate evidence
   generated under a valid exact Gate-EV authorization;
7. reviewer, date and evidence reference.

Platform changes do not require an Owner decision when they can be absorbed by the
existing Adapter and Capability Contract. They do require a Conditional Design or
Owner Gate when they remove the safe Readback/restore path, materially change
commercial behavior, add a new trust boundary or make a V1 outcome impossible.
