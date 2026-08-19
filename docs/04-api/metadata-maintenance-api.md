# Metadata maintenance API

The maintenance surface lives under `/api/v1/admin/metadata/` on the
loopback-bound backend. Commands are explicit `POST`/`PUT` requests carrying an
`X-Operator` attribution header; queries are `GET` and need no attribution.
`docs/06-runbooks/metadata-maintenance.md` describes the security boundary, the
refusal vocabulary and worked examples; this page lists the resources and their
query filters.

Request bodies are strict JSON: an unknown field is refused, never dropped.
Every state-changing command carries `expectedVersion` for optimistic locking,
and lifecycle, correction, grant, revocation and verification commands carry a
`reason`. Errors are RFC 9457 problem documents whose `title` is a stable code
from the shared error registry and which echo no request content.

## Operating entities

| Method and path | Purpose | Query filters |
| --- | --- | --- |
| `POST /organizations`, `PUT /organizations/{id}`, `POST /organizations/{id}/status` | Create, update, lifecycle | — |
| `GET /organizations`, `GET /organizations/{id}` | List, load | `afterCode`, `limit` |
| `POST /legal-entities`, `PUT /legal-entities/{id}`, `POST /legal-entities/{id}/status` | Create, update, lifecycle | — |
| `GET /legal-entities`, `GET /legal-entities/{id}` | List by owner, load | `organizationId`, `afterCode`, `limit` |
| `POST /marketplace-accounts`, `PUT /marketplace-accounts/{id}`, `POST /marketplace-accounts/{id}/status` | Create, update, lifecycle | — |
| `GET /marketplace-accounts`, `GET /marketplace-accounts/{id}` | List by owner, load | `organizationId`, `afterCode`, `limit` |
| `POST /stores`, `PUT /stores/{id}`, `POST /stores/{id}/status` | Create, update, lifecycle | — |
| `GET /stores`, `GET /stores/{id}` | List by owner, load | `organizationId`, `afterCode`, `limit` |
| `POST /warehouses`, `PUT /warehouses/{id}`, `POST /warehouses/{id}/status` | Create, update, lifecycle | — |
| `GET /warehouses`, `GET /warehouses/{id}` | List by owner, load | `organizationId`, `afterCode`, `limit` |

## Associations

| Method and path | Purpose | Query filters |
| --- | --- | --- |
| `POST /store-warehouse-links`, `PUT /store-warehouse-links/{id}`, `POST /store-warehouse-links/{id}/status` | Create, adjust interval, end or cancel | — |
| `GET /store-warehouse-links` | List a store's links | `storeId`, `limit` |
| `POST /store-fulfillment-declarations`, `POST /store-fulfillment-declarations/{id}/status` | Declare, end or cancel | — |
| `GET /store-fulfillment-declarations` | List a store's declarations | `storeId`, `limit` |

## Access metadata

| Method and path | Purpose | Query filters |
| --- | --- | --- |
| `POST /service-accounts`, `PUT /service-accounts/{id}`, `POST /service-accounts/{id}/status` | Create, update, lifecycle | — |
| `POST /service-accounts/{id}/allowed-sources`, `POST /service-accounts/{id}/allowed-sources/{sourceId}/status` | Declare and withdraw source ranges | — |
| `GET /service-accounts`, `GET /service-accounts/{id}` | List with evaluation, load | `organizationId`, `afterCode`, `limit` |
| `POST /scope-grants`, `POST /scope-grants/{id}/revoke` | Grant and revoke | — |
| `GET /scope-grants` | List a subject's grants | `serviceAccountId`, `limit` |

## Credentials

| Method and path | Purpose | Query filters |
| --- | --- | --- |
| `POST /credentials` | Register metadata; `scopeMode` is mandatory and `STORE_SET` carries its initial `storeIds` atomically | — |
| `PUT /credentials/{id}` | Update non-secret descriptive fields | — |
| `POST /credentials/{id}/status` | `ACTIVE ⇄ DISABLED → REVOKED` | — |
| `POST /credentials/{id}/scope-mode` | Explicit widen/narrow between `ACCOUNT` and `STORE_SET` | — |
| `POST /credentials/{id}/store-scopes`, `POST /credentials/{id}/store-scopes/{scopeId}/status` | Add and withdraw scope rows | — |
| `GET /credentials`, `GET /credentials/{id}` | Views carry derived `expired`, `scopeUsability` and `rotationStatus` | `marketplaceAccountId`, `afterCode`, `limit` |

## Capability and endpoint registry

| Method and path | Purpose | Query filters |
| --- | --- | --- |
| `POST /capabilities`, `PUT /capabilities/{id}`, `POST /capabilities/{id}/status` | Register, update, retire | — |
| `POST /capabilities/{id}/verification` | `UNKNOWN ↔ UNVERIFIED` only; `VERIFIED` is refused | — |
| `GET /capabilities`, `GET /capabilities/{id}`, `GET /capabilities/{id}/verification-events` | List, load, journal | `platformCode`, `afterCode`, `limit` |
| `POST /endpoints`, `PUT /endpoints/{id}`, `POST /endpoints/{id}/status`, `POST /endpoints/{id}/verification` | Same contract as capabilities | — |
| `GET /endpoints`, `GET /endpoints/{id}`, `GET /endpoints/{id}/verification-events` | List, load, journal | `platformCode`, `afterCode`, `afterVersion`, `limit` |
| `POST /capability-subject-statuses` | Declare one account or store subject; availability starts `UNKNOWN` | — |
| `GET /capability-subject-statuses` | Matrix view with fail-closed usability per row | `capabilityId`, `limit` |
| `POST /platform-permission-requirements` | Record the platform's own requirement evidence | — |
| `GET /platform-permission-requirements` | List per target | `capabilityId` or `endpointId`, `limit` |

## Feature flags and the audit journal

| Method and path | Purpose | Query filters |
| --- | --- | --- |
| `POST /feature-flags` | Register in the disabled state | — |
| `POST /feature-flags/{id}/state` | Switch; enabling a `WRITE_CAPABILITY` flag is refused while production writes are disabled | — |
| `POST /feature-flags/{id}/status` | Retire a disabled flag | — |
| `GET /feature-flags`, `GET /feature-flags/{id}` | List, load | `afterFlagCode`, `afterScopeKey`, `limit` |
| `GET /audit-events` | Journal, newest first | `actorId`, `sourceDomain`, `action`, `entityType`, `entityId`, `occurredFrom`, `occurredTo`, `beforeOccurredAt`, `beforeId`, `limit` |
