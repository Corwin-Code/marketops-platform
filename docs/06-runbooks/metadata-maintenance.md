# Metadata maintenance operations

This runbook describes how an operator maintains organization, access and
integration metadata through the local maintenance API.

## Security boundary

The maintenance surface is reachable only from the machine the backend runs on:
the server binds to loopback, no authentication layer exists yet, and the
process never accepts a connection from another host. Three further boundaries
apply to every request:

- **Environment write gate.** An environment accepts maintenance mutations only
  when its profile sets `marketops.metadata-maintenance.write-enabled: true`.
  The base configuration is `false`, so an environment that has not opted in
  refuses every mutation with `MAINTENANCE_WRITE_DISABLED` while queries stay
  available.
- **Operator attribution.** Every mutation must carry an `X-Operator` header
  with a short lower-case identifier (`ivan.petrov` style). The value is a
  recording obligation for the audit journal, not authentication. A mutation
  without valid attribution is refused with `OPERATOR_ATTRIBUTION_MISSING`.
- **Production writes.** `marketops.production-writes.enabled` is `false` for
  the whole product and the application refuses to start when it is configured
  `true`. No metadata flag can represent an enabled platform write; enabling a
  `WRITE_CAPABILITY` feature flag is refused with `PRODUCTION_WRITE_DISABLED`.

Never place secret material in any field. Credentials are registered by opaque
`secret-ref://` references only; free text that looks like key material is
refused with `SECRET_MATERIAL_SUSPECTED` and the refused value is not stored or
logged.

## Resources

All resources live under `/api/v1/admin/metadata/`:

| Resource | Purpose |
| --- | --- |
| `organizations`, `legal-entities`, `marketplace-accounts`, `stores`, `warehouses` | The operating-entity chain |
| `store-warehouse-links`, `store-fulfillment-declarations` | Effective-dated service and fulfillment associations |
| `service-accounts`, `scope-grants` | Non-human subjects and their explicit permission grants |
| `credentials` | Non-secret credential metadata with an explicit scope contract |
| `capabilities`, `endpoints`, `capability-subject-statuses`, `platform-permission-requirements` | The platform capability and endpoint registry |
| `feature-flags` | Feature-flag metadata |
| `audit-events` | Read-only view of the append-only audit journal |

Commands are explicit `POST`/`PUT` requests; queries are `GET` and need no
attribution. List queries accept `limit` (1–200) and a keyset cursor parameter.

## Common operations

Create an organization:

```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/admin/metadata/organizations \
  -H 'Content-Type: application/json' \
  -H 'X-Operator: ivan.petrov' \
  -d '{"code":"mimococo","displayName":"Mimococo",
       "defaultTimezone":"Europe/Moscow","defaultCurrencyCode":"RUB"}'
```

Suspend a store (state commands carry a reason and the expected version):

```bash
curl -sS -X POST \
  http://127.0.0.1:8080/api/v1/admin/metadata/stores/<store-id>/status \
  -H 'Content-Type: application/json' \
  -H 'X-Operator: ivan.petrov' \
  -d '{"target":"SUSPENDED","reason":"seasonal pause","expectedVersion":3}'
```

Register a store-scoped credential (metadata only; the reference names a secret
that lives in the secret manager):

```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/admin/metadata/credentials \
  -H 'Content-Type: application/json' \
  -H 'X-Operator: ivan.petrov' \
  -d '{"marketplaceAccountId":"<account-id>","code":"ozon-read",
       "displayName":"Ozon read","purposeCode":"READ",
       "scopeMode":"STORE_SET","storeIds":["<store-id>"],
       "secretReference":"secret-ref://vault/marketops/ozon-main/read",
       "effectiveFrom":"2026-08-01T00:00:00Z",
       "expiresAt":"2027-02-01T00:00:00Z","custodianLabel":"platform-team"}'
```

Rotate a credential: register the successor with `replacesCredentialId` and a
new secret reference, verify the consumer picture, then disable or revoke the
predecessor through `/{id}/status`. Both stay active during the overlap window
by design.

Read the audit journal:

```bash
curl -sS 'http://127.0.0.1:8080/api/v1/admin/metadata/audit-events?limit=20'
```

## Refusal vocabulary

Every refusal returns a stable code in the problem `title` together with the
request's correlation identifier, and every refused mutation is journaled as a
`DENIED` audit event. The most common codes:

| Code | Meaning | Operator action |
| --- | --- | --- |
| `MAINTENANCE_WRITE_DISABLED` | This environment does not accept writes | Use the maintenance seat, or opt the environment in deliberately |
| `OPERATOR_ATTRIBUTION_MISSING` | No valid `X-Operator` header | Repeat the request with attribution |
| `DUPLICATE_IDENTITY` | A live resource already holds this identity | Reuse the surviving resource named by `conflictingResourceId` |
| `VERSION_CONFLICT` | The resource changed since it was read | Re-read and repeat with the current version |
| `INVALID_STATE_TRANSITION` | The lifecycle machine refuses the move | Check the current state; terminal states never revert |
| `REFERENCED_ENTITY_ACTIVE` | Live grants, credentials, scopes or flags still reference the entity | Retire the references explicitly first |
| `EFFECTIVE_RANGE_OVERLAP` | An active association already covers the interval | End the existing interval or adjust the new one |
| `CROSS_ORGANIZATION_REJECTED` | The referenced resources belong to different owners | Correct the reference |
| `SECRET_REFERENCE_INVALID` / `SECRET_MATERIAL_SUSPECTED` | The value is not an opaque reference, or looks like secret material | Use a well-formed `secret-ref://` name |
| `CAPABILITY_VERIFICATION_NOT_SUPPORTED` | `VERIFIED` requires provenance that this maintenance API cannot supply | Leave the row `UNKNOWN`/`UNVERIFIED`; use the evidence-governed registry process for verified facts |
| `PRODUCTION_WRITE_DISABLED` | The transition would represent an enabled platform write | None; production writes are globally disabled |

## Fail-closed facts worth remembering

- A `STORE_SET` credential whose active scope rows are all withdrawn matches
  nothing. It never widens to the whole account; add scope rows back or issue an
  explicit scope-mode change.
- Registry verification moves only between `UNKNOWN` and `UNVERIFIED`.
  Capability usability evaluates to a refusal for every subject until verified
  platform evidence exists.
- Retirement is terminal and never cascades: retire children, grants,
  credentials and flags explicitly so the journal records each step.
- The audit journal is append-only at the database level and every mutation is
  journaled in the same transaction; a change that cannot be journaled does not
  happen.
