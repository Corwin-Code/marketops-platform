# SLICE-V1-001 executable evidence

```yaml
document_type: executable_evidence_record
slice: SLICE-V1-001
executed_at: 2026-08-27
executed_on: WORKSTATION
external_systems_contacted: NONE
```

## What ran, and what it produced

Every command below was executed against the final tree. The counts are the
ones the tools printed.

### Backend

```bash
cd backend/marketops-server && ./mvnw -B -ntp verify
```

| Suite | Result |
| --- | --- |
| Unit and architecture (surefire) | 324 tests, 0 failures, 0 errors |
| Integration (failsafe, real PostgreSQL 18.4 in Testcontainers) | 197 tests, 0 failures, 0 errors |
| Build | `BUILD SUCCESS` |

The architecture suite includes 65 boundary tests: module internals private, no
cycles, `shared` a leaf, domain and port never depending outward, vendor SDK
types only inside platform adapters, time only from an injected clock, no field
injection, controllers unable to reach the acquisition authority chain, and
`ObjectStoragePort` reachable by exactly one caller. Each rule has a
deliberately invalid fixture proving the rule would catch a violation.

### Migrations

```bash
./mvnw -B -ntp -Dit.test=FlywayMigrationIT verify
```

Twenty-six migrations applied in order against a real PostgreSQL 18.4. The
applied set is pinned to an approved list, the table set is pinned to an exact
103-table list, and the reference seeds are asserted individually — business
roles, action scopes, the read-only role matrix, the profit metric set, the
diagnosis rule order, the absence of a transition from
`UNKNOWN_REQUIRES_READBACK` back to `EXECUTING`, and the AI projection field
classifications.

Control-plane totality holds: 103 tables in the eight foundation schemas, 103
route-inventory rows, 24 routed tables, 72 epoch triggers.

### The write path against a real database

```bash
./mvnw -B -ntp -Dit.test=PriceWritePathIT verify
```

Forty-five cases, connected as the application role, exercising the same
functions the application calls:

| Group | Cases | What it proves |
| --- | --- | --- |
| `TC-WRITE-101` | 13 | The gate is a conjunction and each part is separately real: both switch scopes, a scoped switch, the allowlist, authorization expiry, a moved entity digest, mapping resolution, an open conflict, capability verification, an execution-purpose guardrail pass. A closed gate refuses the lease rather than the call. |
| `TC-WRITE-102` | 6 | A stale fence writes nothing; another worker holding the right fence writes nothing; an expired lease writes nothing; a second lease on a claimed command is refused. |
| `TC-WRITE-103` | 4 | Success is refused without a readback, with a non-matching readback, and with another command's readback. A matching readback completes it. |
| `TC-WRITE-104` | 3 | There is no transition from unknown back to executing; the only ways out are a readback or a person; the lease is released so no worker sits on it. |
| `TC-WRITE-105` | 5 | A restore is refused once something else moved the price, authorised while the platform still holds what this command wrote, refused as complete until the prior value is observed, and refused entirely once the gate has closed. |
| `TC-WRITE-106` | 3 | A claimed-but-uncalled command returns to the queue; one that may have written becomes unknown rather than retried; a live lease is left alone. |
| `TC-WRITE-107` | 3 | An attempt completes exactly once; the application cannot change a command row directly; the application cannot delete a readback. |
| `TC-WRITE-108` | 8 | A bounded authorization is bounded in magnitude, scope, uses and status; the application cannot move the counter. |

### Frontend

```bash
cd frontend/marketops-console
npm run lint && npm run typecheck && npm run format:check
npm run test:ci && npm run build && npm run verify:bundle
```

| Check | Result |
| --- | --- |
| ESLint (`--max-warnings 0`) | clean |
| TypeScript (`--noEmit`) | clean |
| Prettier | all files formatted |
| Vitest with coverage | 124 tests passing; statements 83.46%, branches 75.0%, functions 92.02%, lines 83.66% — all above the configured thresholds |
| Build | 32 modules, `BUILD SUCCESS` |
| Bundle isolation | `only prefixed values reached the bundle` |

### Browser

```bash
npm run test:browser
```

Eight tests passing against the real backend and the built console, in Chromium.

| Test | What it proves |
| --- | --- |
| Health shell across a real database outage | The console reports the outage rather than showing stale figures, and recovers. |
| `TC-BROWSER-010` (4 cases) | An unauthenticated visitor sees the platform panel and nothing operational; the **running backend** refuses an operating request with no token and with a forged token; the built bundle carries no secret reference, private key or client secret. |
| `TC-BROWSER-011` (3 cases) | Sign-in through to subject diagnosis in a real browser; an unavailable figure renders as an absence with tone `absent` and nothing on the screen claims a confirmed value; signing out leaves no operating data; a refused sign-in leaves the visitor told rather than blank. |

The identity provider and the console API responses in `TC-BROWSER-011` are
answered by the test through the browser's own network layer. That is stated in
the spec's opening comment. **None of it is evidence about any marketplace,
identity provider or model provider, and it is not offered as such.** What
`TC-BROWSER-010` proves about refusal comes from the running backend.

### Repository validators

```bash
python3 scripts/validate_governance.py
python3 scripts/validate_production_readiness.py
```

Both pass. The governance validator's secret scan caught a placeholder bearer
value written as a single literal in the browser spec during this work; it was
restructured rather than the rule weakened.

### Working tree

```bash
git diff --check
git status --short
```

Clean at every checkpoint.

## What did not run, and why

| Not run | Reason |
| --- | --- |
| `terraform validate` / `plan` | No `terraform` or `tofu` binary is present and no provider could be downloaded. The configuration is reviewed, not machine-checked. |
| Any Ozon or Wildberries call | No marketplace was contacted. Every capability row is `UNVERIFIED`, which is why no call is reachable. |
| Any model provider call | No provider was contacted. Every provider row is `UNVERIFIED`. |
| Restore drill | No environment exists to restore. |
| Failure injection against a deployed environment | No environment exists. |
| Performance measurement | No representative data set and no environment. |
| CI | Nothing was pushed. No pull request exists. |
| JaCoCo coverage gate | Skipped during iteration with `-Djacoco.skip=true`; the final `verify` ran without it and passed. |

## Defects this evidence found

Three, each fixed and each recorded in the checkpoint that fixed it.

1. **The write gate crashed whenever it had a reason to give.** `reasons ||
   'CODE'` resolves against array-concatenation for an untyped literal, so every
   permitted command passed and every blocked one raised `malformed array
   literal`. Found by the first integration case that blocked. Fixed in V0026.
2. **The analytics thresholds were never bound.** Nothing registered their
   configuration properties, so the application context failed to start wherever
   the whole graph was loaded. The unit tests had not noticed because they
   construct the engine directly. Found by `MetadataMaintenanceApiIT`.
3. **The console's content policy hardcoded the workstation's backend origin.**
   A console pointed anywhere else would have had every request blocked, and one
   with an identity provider could never have completed a sign-in. Found by the
   browser suite, which reported the blocked connection in the page console.

A fourth was found by review rather than by a test: `capability_code` meant a
lowercase registry identifier in `platform` and an uppercase business action in
`ops`, so joining the two on the shared name returns nothing and reads as "not
allowlisted". Renamed in V0026, with a check that refuses to let it come back.
