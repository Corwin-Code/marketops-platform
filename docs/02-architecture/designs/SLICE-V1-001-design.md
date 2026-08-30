# SLICE-V1-001 as-built design

```yaml
document_type: as_built_slice_design
slice: SLICE-V1-001
slice_title: SKU Growth & Profit Diagnostic Loop
contract: docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md
contract_sha256: 0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5
accepted_amendments: SLICE-V1-001-AMENDMENT-001,SLICE-V1-001-AMENDMENT-002
accepted_amendment_001_sha256: 8a36bbe0f2cd1d8e40efb171d368d8c4058ecc913da2a76f43f7e0a14de6854d
accepted_amendment_002_sha256: 92fdd8d67b327fbd2288ba99290b5b59f2797106c4b691ce2bff22bb80198b93
implementation_state: ENGINEERING_IMPLEMENTATION_MERGED
candidate_scope: PROTECTED_MAIN
controller_final_gate: PASS_R2_ENGINEERING_FINAL_GATE
controller_comment_id: 5469390502
approved_engineering_head: f35327a584b980ec4acf7ace7c88e124d6d79709
approved_engineering_tree: 390ebe37bea778b7a4548381ad357fc99aa0da6b
approved_tested_merge: bcc3b37965003c3ea1af720ea847dc27fb473a9e
actual_squash_commit: d562b81f4f0271aa33a53b21ccaffc88b5610c0c
actual_squash_tree: 390ebe37bea778b7a4548381ad357fc99aa0da6b
actual_squash_sole_parent: db92cf2f8bd818f36dd8f5aa17b8589c4140b669
engineering_closure: ENGINEERING_IMPLEMENTATION_CLOSED
production_readiness: DEFERRED_TO_RELEASE_V1_001
owner_formal_closure: PENDING
base_commit: db92cf2f8bd818f36dd8f5aa17b8589c4140b669
base_tree: 221e5a009d4cf5820d36c0e1bccd5b64caa6135b
production_write_enabled: false
real_marketplace_call_made: false
real_model_provider_call_made: false
infrastructure_applied: false
```

## 1. Implementation and post-merge state

PR #20 and PR #22 are merged. PR #21 remains held and is not an R2
implementation or closure-sync base. Controller comment `5469390502` closed the
complete ten-item frozen Supplemental R2 set with zero unresolved BLOCKER or
MAJOR finding. PR #22 was then protected-SQUASH-merged as
`d562b81f4f0271aa33a53b21ccaffc88b5610c0c`; its tree is exactly the approved
engineering tree and its sole parent is the reviewed base.

The code covers acquisition, canonical facts, diagnosis, recommendations,
controlled commands and readback. V0011–V0028 are immutable applied history.
V0029 was the only unmerged migration when R2 corrected it in place; it is now
merged in the exact reviewed tree but remains undeployed and unapplied to any
production database. This is not an applied-history rewrite. The final
transitive-closure correction removes a split JVM/DB clock
from Guardrail evaluation and replaces the inherited one-key price parameter
filter with the exact executable parameter contract described below. Accepted
Amendment-002 defers only its enumerated real/Owner/Gate evidence
to `RELEASE-V1-001`; the Deferred Evidence Register preserves every future
obligation. The engineering implementation is closed, but Human Owner Formal
Closure and production readiness are not claimed. No real provider, Marketplace
or production write is authorized or proven.

## 2. The decisions everything else follows from

### 2.1 A marketplace fact is a row, never a line of code

Platform behavior is selected from verified registry data. The base URL, the HTTP
method, the path and query templates, the request body shape, the authentication
headers, the pagination model, the rate limit, the write result model, where an
asynchronous task handle lives inside a response, where an observed price sits,
and what the platform's own word for "finished" is — every one of them is a
recorded row carrying its own `verification_state` and its own evidence
reference.

The consequence is the property the Contract asks for: an unverified capability
has **no reachable specification**, so the fail-closed behaviour is the absence
of a call rather than a check somebody could forget to write. It also gives
schema drift a precise definition — a pointer that no declaration names — rather
than leaving it as a feeling that the data looks wrong.

The cost is real and worth stating: nothing works until somebody records the
facts. That is the intended cost.

### 2.2 The guarantees that matter live in the database

`ops.price_command` grants the application `SELECT` and `INSERT` and no `UPDATE`
at all. Every state change runs through `ops.lease_price_command`,
`ops.transition_price_command` or `ops.lease_price_compensation`. The allowed
transitions are rows in `ops.price_command_transition`, not a switch statement.

So the lease, the fence token, the reviewed transition set, the rule that
success requires a matching readback, and the rule that a restore requires the
platform to still hold what this command wrote are properties of the database.
An arbitrary SQL client connecting as the application role cannot bypass any of
them. That is a stronger guarantee than a well-written caller, and it is why the
integration suite asserts them through those functions as the application role
rather than reaching around them.

The same reasoning put `used_count` outside the application's column grant on
`ops.policy_authorization`: a bounded authorization is only bounded if the bound
cannot be edited by the thing it bounds.

### 2.3 Absence is a value

`ValueState` distinguishes available, not available and undefined.
`ConfidenceState` distinguishes seven degrees of certainty, exactly one of which
supports a platform write. A missing unit cost does not become zero anywhere:
not in the metric engine, not in the guardrail, not in the console, not in the
AI projection.

This is the single decision most responsible for the product being safe. An
automated pricing system sells below cost because somebody, somewhere, treated a
missing input as a zero and the arithmetic kept working.

### 2.4 An approval is about the facts it was made from

Every recommendation stores a digest of the canonical values its case rests on.
For a price change this includes the target price, fulfillment mode, exact
economics profile/version/effective range/evidence, fee-family contract,
component identities and source-watermark identities. An approval stores the
same digest. The write gate re-resolves current authority and compares the
stored digest against the proposal at approval, command creation and worker
claim time; it refuses when the profile, components, watermarks or their current
freshness no longer match.

The difference this makes: "somebody approved this price" becomes "somebody
approved this price when the cost was what it is now". Hours pass between a
review and an execution, and the world moves in them.

One database statement captures `evaluation_as_of` and the complete
`price_authority_snapshot` together. Metrics, diagnoses and price history are
then queried only through that exact inclusive upper boundary. The evaluated
metric-entity digest, fulfillment mode, economics profile and component
identities are compared with the captured snapshot before an evaluation can be
persisted. `evaluated_at` is therefore not a JVM approximation of a later DB
snapshot. Approval, command creation and worker lease still re-resolve current
authority at their own transaction instant, preserving expiration and mutation
closure after the preview.

`PRICE_CHANGE` recommendations have one executable JSON contract:
`targetPrice` is required, `fulfillmentModeCode` is optional, and additional
keys are forbidden. When exactly one fulfillment mode is active, omission
selects that unique mode; multiple active modes require an explicit active code.
`UNKNOWN`, inactive or syntactically invalid modes fail closed. The selected
mode is bound in the snapshot, evaluation, approval and command, is returned by
the API, and is read again by the worker rather than being inferred later.

### 2.5 The deterministic layer is the authority; the model explains it

`GuardrailEngine` and `DiagnosisEngine` are pure functions of gathered values.
They read no clock, no database and no configuration, so the same facts produce
the same verdict on any day in any environment and a refusal can be re-derived
when somebody disputes it a month later.

The AI layer sits beside that and authorises nothing. Every path through it ends
in a recorded invocation — no eligible provider records a refusal, a provider
that does not answer records a failure, an answer that does not validate records
the rejected claims — and none of them raises, so an unavailable model degrades
the explanation and leaves the diagnosis, the guardrails and the command path
untouched.

### 2.6 A metric assertion is the exact facts, window and state it names

`AnalyticsCalculationService` creates one `FactWindow` and passes that exact
object through every query, stored `calculation_run` and stored `metric_value`.
Window facts use `[period_start, period_end)`; point facts resolve strictly
before the as-of boundary. A recomputation may deduplicate only when value
state, normalized value, currency, confidence, source time, mapping identity,
input identities and explicit availability states all match.

Historical Contribution Profit and proposed-price economics are separate
authorities. Historical actuals consume the ledger and the effective
`FeeFamily` coverage contract. Every family is represented as exactly one of
`PRESENT_NONZERO`, `PRESENT_EXPLICIT_ZERO`, `VERIFIED_NOT_APPLICABLE` or
`MISSING_OR_INCOMPLETE`. Required families must be one of the first two states; an explicit
sourced zero is never equivalent to absence, and removal of one platform fee
category cannot be hidden by another category's aggregate.

Projected Break-even and Minimum Price never average historical fees. The
effective `PriceEconomicsProfile` is scoped by platform, account, store,
fulfillment mode, currency, effective range, verification state and evidence.
Its fixed, percentage and tiered components are evaluated against the proposed
target price by the shared `PriceEconomicsCalculator`. The bounded deterministic
solver finds:

```text
Break-even: price = unit cost + projected component total(price)
Minimum:    price = unit cost + sourced Required Profit per Unit
                     + sourced Safety Buffer per Unit
                     + projected component total(price)
```

The calculator rejects missing required families/components, ambiguous or
expired profiles, tier gaps/overlaps, non-monotone or out-of-range economics and
currency conflict. Metric calculation, Impact Preview and Guardrail use the
same component set and bind the selected profile/component identities.

Current decision freshness is independent of a persisted metric age. Eight
`source_feed_watermark` authorities—`PRICE`, `STOCK`, `SALES`, `RETURNS`,
`FINANCE_FEES`, `ADVERTISING`, `INTERNAL_COST` and `COMMERCIAL_INPUTS`—carry
source/effective, ingestion and reconciliation attribution. Effective source
time is authoritative when reconciled; otherwise the conservative ingestion
time applies. Freshness is reevaluated against the transaction instant at
preview, approval/command binding and worker claim. Missing/stale watermarks,
missing stock, missing economics or insufficient confidence block the
write-grade path without creating a new metric row.

## 3. Module shape

Ten Spring Modulith modules. The two added by this slice are `operationsworkflow`
and `aicopilot`; the rest existed and were extended.

| Module | Owns | Publishes |
| --- | --- | --- |
| `shared` | Money, digests, error vocabulary, correlation, secret resolution | `Money`, `Digest`, `ErrorCode`, `SecretResolverPort` |
| `organizationaccount` | Organization, legal entity, account, store, warehouse | Store and account identity |
| `identityaccess` | Human identity, roles, action scopes, resource grants, decision journal | `AuthenticatedActor`, `BusinessAuthorization`, `ActionScopeCode` |
| `marketplaceintegration` | Raw custody, acquisition, platform adapters, price command execution, kill switch | `PriceCommandGateway`, `PriceChangeHistory`, `RawEvidenceQuery`, `IngestionJobDirectory` |
| `productlisting` | Product, variant, listing, listing variant, mapping, conflicts | `ListingIdentityDirectory`, `ListingVariantContext` |
| `operatingfacts` | Cross-domain facts, file intake, manual entry, normalization, provenance | `OperatingFactQuery`, `EvidenceQuery` |
| `analyticsdecision` | Canonical metrics, deterministic diagnosis rules, priority, target-price economics | `MetricQuery`, `DiagnosisQuery`, `MetricCode`, `PriceEconomicsQuery`, `PriceEconomicsCalculator` |
| `aicopilot` | Projection, model gateway, output validation, claims | `AiCopilot`, `AiClaim` |
| `operationsworkflow` | Recommendations, tasks, approvals, policy, guardrails, allowlist | `RecommendationView`, `GuardrailVerdict`, `ImpactPreview` |
| `adminobservability` | Audit journal, metadata status | `MetadataAuditRecorder`, `MetadataAuditQueries` |

### Two boundary decisions worth recording

**Secret resolution moved to `shared`.** The model gateway became the second
adapter that presents a credential, which showed that secret delivery is a
property of the deployment rather than of marketplace integration. One resolver
serves every outbound adapter, so there is one answer to whether this
environment can reach its secret store.

**`marketplaceintegration` owns the price command tables outright.** The
workflow module asks through `PriceCommandGateway` and does not execute. That
keeps one writer per table and one place a marketplace fact can live, at the
cost of a dependency direction some readers will find surprising: workflow
depends on integration rather than the reverse.

## 4. Schema, V0011 through V0029

| Migration | What it establishes |
| --- | --- |
| V0011 | Human identity, business roles, ten action scopes, the reviewed role matrix, resource grants, decision journal |
| V0012 | Product and variant identity, platform listings, effective-dated mapping, conflicts |
| V0013 | Cross-domain operating facts: health, price, stock, traffic, sales by stage, returns, fees, advertising |
| V0014 | File intake, import batches and rows, versioned cost, internal stock, finance inputs |
| V0015 | Twenty-six canonical metric definitions, calculation runs, reproducible metric values |
| V0016 | Nine deterministic diagnosis rules in fixed order, findings and their inputs |
| V0017 | Model providers, models by secret reference, projection allowlist, invocations, claims, claim evidence |
| V0018 | Recommendations, evidence links, work tasks, append-only approval decisions |
| V0019 | Nine policy limit kinds, commercial policy versions, limits, bounded authorizations and their consuming function, guardrail evaluations, pilot allowlist |
| V0020 | Price commands, the transition set as data, attempts, readbacks, kill-switch journal, the write gate, leasing and transition functions |
| V0021 | Platform API profiles, authentication headers, endpoint request shape, write result model |
| V0022 | Ingestion run lifecycle, dataset kinds, the replay guard, run functions |
| V0023 | Declared normalization: canonical fields, mappings, per-field pointers, checkpoints, drift observations |
| V0024 | Capability write-operation shape: which endpoint performs each operation and where its answer lives |
| V0025 | Attempt completion permitted exactly once, expired-lease recovery, compensation leasing, the rule that a restore is not complete until the prior value is observed |
| V0026 | `capability_code` renamed to `action_kind` in the two operational tables; dependent functions rebuilt |
| V0027 | Account-bound verification submissions/reviews, audit and promotion/revocation functions |
| V0028 | Bounded export jobs, immutable snapshot rows/parts, fenced worker and live authorization |
| V0029 | V2 fee-family coverage, versioned target-price economics profiles/components, source-feed watermarks, Break-even/Minimum Price identity, single-DB-as-of snapshot authority, exact price parameter schema, durable command mode and transaction-time DB write gates |

Every table in the eight foundation schemas appears in
`platform.control_route_inventory` exactly once — a hundred and seven tables,
a hundred and seven rows, twenty-four routed with three epoch triggers each.
V0001–V0028 are untouched by R2. The four V0029 additions are
`core.economics_projection_profile`, `core.economics_projection_profile_family`,
`core.economics_projection_component` and `core.source_feed_watermark`.
V0029 was rechecked before the transitive correction: it remained unmerged,
undeployed and without a shared/persistent consumer. It is therefore corrected
in place. The correction adds no fifth authority table; it strengthens
`ops.price_authority_snapshot`, `ops.price_change_parameter_contract_is_valid`,
`ops.create_price_command`, `ops.price_command_authority_matches` and the
existing write-gate triggers/functions, and adds the durable
`ops.price_command.fulfillment_mode_code` column.

### Two defects the schema work found

**The write gate crashed the moment it had a reason to give.** V0020 built its
answer with `reasons || 'CODE'`, which PostgreSQL resolves against
array-concatenation for an untyped literal. Every permitted command passed and
every blocked one raised `malformed array literal`. A gate that only works while
everything is allowed is worse than no gate: it passes every happy-path check
and fails at the one moment an operator needs the explanation. V0026 replaces
the function with `array_append`.

**`capability_code` meant two different things in adjacent tables** — a
lowercase registry identifier in `platform`, an uppercase business action in
`ops`. Joining the allowlist to the capability registry on the shared name
returns nothing and reads as "not allowlisted". V0026 renames the operational
columns and refuses to let the collision return.

## 5. The write path, end to end

1. A calculation run produces canonical metric values, each keyed by a digest of
   its inputs so an identical recomputation writes nothing and a late correction
   writes a new row beside the old one.
2. The diagnosis engine runs nine rules in fixed order. `DATA_BLOCKED` runs
   first and guards the rest: a rule that cannot answer says so rather than
   staying silent.
3. A recommendation is proposed, carrying the digest of the values it rests on.
   An action with no write capability becomes a task instead — not a degraded
   price recommendation, a different thing.
4. An operator takes an impact preview. One DB capture returns the exact
   `evaluation_as_of` and authority snapshot. The application bounds every
   time-sensitive read by that instant, compares the evaluated identities with
   the snapshot, runs the same guardrail the gate will run, and records the
   verdict.
5. A person approves, or a bounded standing authorization is spent. Never both:
   a decision attributable to both a person and a standing rule leaves nobody
   accountable. Approval needs a recent authentication proven against the
   identity provider's recorded maximum age.
6. The guardrail runs again for execution specifically, and a command is
   created. Creating it makes no call.
7. A worker claims the command. The durable fulfillment mode is part of the
   command authority, and the write gate is evaluated **inside the
   transaction that takes the claim**, so a switch thrown while the worker is
   deciding cannot be missed. Ten conditions, each separately real.
8. The adapter performs the recorded operation. A synchronous platform's answer
   goes straight to readback; an asynchronous one yields a handle and a status
   enquiry.
9. A readback observes what the marketplace now holds. **Only this produces
   success** — platform acceptance is not success, and the transition function
   refuses without a matching readback in the same transaction.
10. A mismatch or an unclassifiable result goes to a person. There is no
    transition from unknown back to executing.

### What happens when things go wrong

| Situation | What the platform does |
| --- | --- |
| Rate limited (429) | Retriable. The request was not processed. |
| Timeout or server failure on a write | **Unknown**, never retriable. The call may have changed a real price. |
| Timeout or server failure on a read | Retriable. A read has no consequence. |
| Worker dies mid-call | The attempt row is already committed; the lease expires; recovery moves the command to unknown, which is an operator's problem rather than a stuck row. |
| Retry budget exhausted | A sweep closes the command as failed, because a waiting command with nothing left to spend would otherwise sit in the queue for ever. |
| Restore requested after something else moved the price | Refused. The latest readback no longer matches the target, and overwriting a change nobody here decided is not compensation. |

## 6. Security posture

- **Authentication** is external OIDC with a required second factor. The console
  uses authorization code with proof key for code exchange, holds the access
  token in memory for the life of the tab and nowhere else, and never sees a
  client secret. A token in local storage survives the tab and is readable by
  anything that can run script on the origin — the most useful thing an attacker
  could take from a console that changes real prices.
  The candidate servlet boundary requires an expiry and validates the exact
  configured audience and MFA claim. A non-local serving environment fails
  startup when issuer or audience is absent/blank; a wrong audience is denied.
  Missing `auth_time` cannot satisfy step-up; token renewal
  never substitutes `iat` for the original authentication time. Cookie, form,
  query and servlet-session authentication are not accepted.
- **Authorization** is role plus action scope plus resource scope, evaluated per
  request against a live profile. Four actions require a recent authentication
  as well as the grant: price approval, policy management, command resolution
  and kill-switch enablement.
- **Secrets** are opaque references resolved at the moment of use and cleared
  immediately after. No credential appears in a business table, a request, a log
  record, an audit row, the browser bundle or this repository.
  Candidate file resolution requires descriptor-based traversal without symlinks,
  strict UTF-8 and a 16,384-byte bound. Unsupported filesystems refuse resolution;
  the Linux runtime contract and macOS refusal are documented in the
  [identity/secret runbook](../../06-runbooks/identity-and-mounted-secrets.md).
- **The AI projection** is an allowlist of declared field paths enforced on the
  assembled projection. What leaves is a list of path-and-value pairs, not an
  object graph, so a field nobody declared has no way to travel.
- **Model output** is validated deterministically on every answer: a factual
  claim citing an identifier the model was not shown is rejected, a
  recommendation naming an action with no gate is rejected, and
  instruction-shaped text is rejected so a marketplace's own listing content
  cannot come back out as something a reader might act on.

## 7. Export, browser recovery and operational signals

Large diagnostic output uses an authorized asynchronous job, not an unbounded
HTTP body. V0028 materializes one MVCC snapshot with versioned metric/finding
references, bounds row/byte/part counts, and fences every worker transition.
Parts and the completion manifest use immutable Raw custody. Downloads recheck
live requester/store access before and after object I/O. The browser verifies
manifest and part hashes/lengths before creating a download; expiry denies
access but is not a claim of physical retention cleanup.

The subject view exposes typed metric inputs and metadata-only source
provenance. Recommendations are filtered by the authorized subject/store on
the server. An approved decision survives a failed command submission, and a
new session can read the existing command without another approval or write.
The timeline separates pending, unknown, mismatched and confirmed outcomes.

`GET /actuator/operations` is a bounded read-only aggregate snapshot available
only to an actual loopback peer. Forwarded headers are disabled and do not
create local authority. A two-second database read failure returns only the
unavailability signal; business metrics are absent rather than false zeros.
The host telemetry timer validates shape/freshness and sends six integer gauges
using an ephemeral instance token to the fixed Yandex endpoint. It runs under
a dynamic unprivileged user with no DB/Marketplace secret. The required alert
configuration treats No Data as an alarm.
Real alert provisioning, metric receipt and notification delivery remain
pending; see the operational monitoring runbook.

Every `/api/v1/admin/metadata/**` request also requires the actual socket peer
to be loopback before method-specific authorization or maintenance-write gates
run. The boundary applies to reads and writes; `Forwarded` and
`X-Forwarded-For` cannot manufacture local authority.

## 8. What is not proven

Stated plainly, because the difference between built and proven is the whole
point of the Assurance Matrix.

| Not proven | Why |
| --- | --- |
| Any Ozon or Wildberries capability | No marketplace was contacted. Every capability row is `UNVERIFIED`, which is why no call is reachable. |
| Any model provider | No provider was contacted. Every provider row is `UNVERIFIED`. |
| The Yandex topology | Never applied. No account contacted, no state file, no credential present. |
| Provider point-in-time recovery | Real Yandex PITR remains pending. Local PG17 dump/restore and exact object recovery have executed with validation; see the restore runbook. |
| Any real platform write | Requires a Gate-EV envelope. Not authorized, not attempted. |
| Complete deployable Terraform/runtime path | Local fmt/init-without-backend/provider-schema validate/mock-plan checks pass. Actual account/state/runtime operation is unproven. The Owner accepted Amendment-001: PG17, provider-managed extensions and a public Flyway external-attestation V0002 executor, preserving standard-profile SQL and V0001–V0010 bytes. |
| Performance under representative load | PG17/MockMvc measurements, five-second read-budget lock/recovery, a 488,000-record asynchronous export and local database/object restore pass at checkpoint 131. Actual Owner cohort and production capacity are unproven; final source-bound regression remains required. |
| Production/release quality | The exact final Head passed 877 unit, 391 PostgreSQL integration, 65 architecture, 377 Python, 196 frontend and 11 browser tests; 12/12 required contexts and aggregate CodeQL passed before merge. Real provider, release and production evidence remain deferred. |

## 9. Original implementation checkpoints

```text
65e5166  Forward schema V0011-V0020, human identity boundary and listing identity
36f2510  Raw custody, evidence-driven provider adapters, acquisition and normalization
a9b57a5  Internal fact intake: files, manual entry and versioned cost
9d38d1f  Deterministic metrics, diagnosis rules and the daily work list
6fe4eaf  AI projection, grounded claims and the model gateway
0d2fe05  Deterministic guardrails, approval and the controlled write path
18c9ab3  Prove the write path against a real database
0df670f  The operating console, from sign-in to the command timeline
02c3b73  Yandex topology and the runbooks an operator actually needs
e2bff73  As-built design, three ADRs and an honest acceptance record
1231a07  Exercise the product end to end, and fix what that found
34c89b2  Read the files a Russian finance team actually sends
```
