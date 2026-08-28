# Account-bound capability verification

## Authority and boundary

This procedure records reviewed evidence; it never tests a provider itself and
does not enable marketplace writes. During SLICE-V1-001 R1 rework, all real
provider calls, credentials, deployment, Gate EV and Gate E remain unauthorized.
Use the API only for evidence already obtained under the separate applicable
Owner authorization. Public documentation and protocol fixtures are not real
account evidence. A real marketplace write or restore requires its exact,
unexpired Human Owner-approved Gate-EV envelope before it occurs.

The API is `/api/v1/console/registry-verification`. It requires the actual peer
address to be loopback, a fresh authenticated bearer identity with OWNER and
`KILL_SWITCH_OPERATE` authority over the account, and the maintenance-write
switch for mutations. Attribution headers such as `X-Operator-Id` do not
authenticate this API. Do not forward this surface from a public proxy. Keep
tokens and credentials out of request examples, logs and evidence artifacts.

The old `/api/v1/admin/metadata/capability-operations/{id}/verification`
single-operator activation endpoint is removed. Registry verification is the
only application path that can promote profile/header/endpoint/capability/
operation facts together. Ordinary application DML cannot alter verified facts.

## Prepare the configuration

1. Register the structural capability and endpoints as UNVERIFIED through
   registry maintenance. Bind each endpoint to the same platform and capability.
2. Read `GET /accounts/{account}/capabilities/{capability}`. Retain the returned
   `digest` and `snapshot`; versions are part of that exact snapshot.
3. For an existing verified configuration, open a revision with
   `POST /accounts/{account}/capabilities/{capability}/revision` and body
   `{"expectedDigest":"<the returned SHA-256>"}`. This immediately invalidates
   previous snapshots. Shared profile edits can invalidate other capabilities
   on the platform as well; plan that interruption before editing.
4. Use `POST /accounts/{account}/capabilities/{capability}/draft` with `kind`,
   optional `id`, `expectedVersion`, and `definition`. New PROFILE, HEADER and
   OPERATION rows use version `-1`; updates use the returned current version.
   ENDPOINT and CAPABILITY drafts address existing structural rows. GET again
   after each change; never manufacture an expected version.

| Kind | Definition fields |
| --- | --- |
| PROFILE | `base_url`, `request_timeout_ms`, `max_response_bytes`, `owner_label` |
| HEADER | `header_name`, `value_source`, `value_template`, `credential_purpose`, `ordinal`, `owner_label` |
| ENDPOINT | `http_method`, `path_template`, `operation_function`, `query_template`, `body_template`, `response_content_type`, `continuation_pointer`, `pagination_model`, `rate_limit_per_minute` |
| CAPABILITY | `write_result_model` |
| OPERATION | `operation`, `endpoint_id`, `request_template`, `accepted_pointer`, `accepted_value`, `task_key_pointer`, `task_status_pointer`, `task_success_value`, `task_failure_value`, `task_pending_values`, `observed_price_pointer`, `observed_currency_pointer`, `conditional_write_header`, `version_token_header`, `owner_label` |

Definitions are complete replacements of these editable fields, not JSON merge
patches. Omit optional fields only when clearing them is intended. Unknown
fields, duplicate JSON keys, unbounded input and secret-like values are refused.
Credential values are never configuration fields; headers contain a shape such
as `Bearer {value}`, resolved only by the separately authorized runtime.

READ acquisition uses READ credential headers. Price command operations,
including their status and readback calls, use PRICE_WRITE headers. Their
availability does not authorize a command. Header names are unique without
regard to case within one platform and credential purpose.

## Submit and independently review

POST `/accounts/{account}/capabilities/{capability}/cases` with the exact
`expectedDigest`, `endpointIds`, `authHeaderIds`, and an `evidence` object:

| Field | Required meaning |
| --- | --- |
| `officialSourceUrl` | HTTPS official protocol source actually reviewed |
| `officialSourceSha256` | SHA-256 of the retained official-source artifact |
| `accountEvidenceRef` | Immutable, non-secret evidence reference for this account |
| `accountEvidenceSha256` | SHA-256 of that retained evidence artifact |
| `evidenceClass` | `REAL_ACCOUNT` or `PROTOCOL_FIXTURE`; fixtures cannot be approved |
| `testedAt` | Actual past verification timestamp |
| `validUntil` | Evidence expiry, at most 30 days after `testedAt` |

The submitter attests that the referenced evidence exists, its hashes match, and
it belongs to the exact account and configuration. The API does not retrieve
arbitrary URLs or dereference evidence references. The independent reviewer must
inspect those artifacts and any applicable Gate-EV envelope before approving.

A different currently authorized OWNER reviews using
`POST /cases/{id}/review` with `expectedVersion` and boolean `approve`.
Self-review, revoked authority, stale versions, changed configuration, expired
evidence or incomplete operation semantics are refused atomically. Rejection
records the decision without activating anything. Submission and review have
separate audit records naming the actual authenticated users and correlation IDs.
The original submitted snapshot remains alongside the promoted snapshot.

For price change, APPLY and READBACK must exist. An asynchronous model also
requires STATUS_ENQUIRY and exact task/pending/success/failure semantics. A
registered RESTORE must have conditional-write and readback-version evidence.
Mutation acceptance is an exact typed value at a recorded JSON Pointer, never
merely HTTP 2xx. Templates, functions and HTTP methods must match each operation.

## Current evidence, expiry and revocation

GET `/cases/{id}` reports the state, version, reviewer and current-evidence
indicator. An approval applies only to its account and captured configuration;
it is not a global grant to every account. Another account can attest the same
unchanged configuration without rewriting the first account's snapshot.

POST `/cases/{id}/revoke` with `expectedVersion` revokes that case and records an
audit event. Expiry, revocation, account deactivation or configuration changes
prevent dispatch. Runtime checks the snapshot again before resolving a secret
and again before HTTP exchange. In-flight provider responses retain the exact
operation snapshot that existed before I/O; later maintenance cannot reinterpret
their bytes or authorize a repeated mutation.

This workflow does not set availability, allowlists, feature flags, release
gates or production enablement. Those controls remain separately required.

## Local verification evidence

`RegistryVerificationFlowIT` runs only synthetic accounts and evidence in an
isolated PostgreSQL server initialized by the real workstation role scripts.
Its `REAL_ACCOUNT` labels simulate attestation transitions; they prove no real
account capability. `PlatformHttpAdaptersTest`, `PriceWritePathIT` and
`PriceCommandWorkerIT` verify dispatch refusal, immutable response interpretation
and compensation boundaries without contacting providers. Final test commands
and results belong in the R1 rework evidence, not in a production approval.
