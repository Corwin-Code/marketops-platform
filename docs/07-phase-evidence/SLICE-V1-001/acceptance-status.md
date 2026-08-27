# SLICE-V1-001 acceptance status

```yaml
document_type: acceptance_criteria_status
slice: SLICE-V1-001
contract_sha256: 0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5
accepted_amendments: NONE
assessed_at: 2026-08-27
assessed_against: LOCAL_CHECKPOINT_ONLY
remote_publication: NONE
production_write_enabled: false
```

## How to read this

Three verdicts, and the difference between them is the reason this file exists.

**`MET`** — implemented and demonstrated by executable evidence that ran. The
evidence column names what ran.

**`IMPLEMENTED_UNPROVEN`** — the mechanism exists and is tested against the parts
that can be tested locally, but the criterion's own evidence class requires
something this authorization cannot produce: a real marketplace, a real model
provider, a real cloud account, or a Gate-EV envelope. What is missing is named.

**`NOT_MET`** — not delivered by this work.

Nothing here is rounded up. A criterion whose second half is open is not `MET`
because its first half is.

## A. Identity, security and scope

| ID | Verdict | Evidence and what is missing |
| --- | --- | --- |
| `S1-AC-001` | `IMPLEMENTED_UNPROVEN` | Authorization-code with proof key for code exchange, `acr_values=mfa` requested, identity-provider registry requiring a recorded MFA claim before a provider can be `ACTIVE` (V0011). Browser suite proves the console's own flow end to end against a routed provider (`TC-BROWSER-011`). **Missing:** no real identity provider was contacted, so the approved environment's MFA behaviour is unproven. |
| `S1-AC-002` | `MET` | Role plus action scope plus resource scope evaluated per request against a live profile; ten action scopes with a reviewed role matrix seeded and asserted (`TC-DB-118`); `anyRequest().denyAll()`; unauthenticated and forged-token requests refused by the running backend (`TC-BROWSER-010`). |
| `S1-AC-003` | `MET` | `credentials_valid_from` refuses a token issued before it; four actions require a recent authentication proven against the provider's recorded maximum age; every decision lands in the append-only `iam.identity_decision_event` with subject and session digests only. |
| `S1-AC-004` | `MET` | Bundle isolation check; browser assertion that the built bundle carries no secret reference, private key or client secret; repository secret scan in `validate_governance.py`; no credential in any business table, request, log record or audit row. |

## B. Infrastructure and recovery

| ID | Verdict | Evidence and what is missing |
| --- | --- | --- |
| `S1-AC-005` | `IMPLEMENTED_UNPROVEN` | Complete `ru-central1` topology in `infra/yandex`: network with no public route to the database, three workload identities with narrow role bindings, managed PostgreSQL, evidence bucket, observability. **Missing:** never applied, no state exists, and no `terraform validate` ran — no binary was available and no provider could be downloaded. Reviewed, not machine-checked, not built. |
| `S1-AC-006` | `IMPLEMENTED_UNPROVEN` | Backup window, retention with a floor of seven days enforced by a variable validation, performance diagnostics, object-lock compliance mode with a one-year floor, versioning, encryption. **Missing:** the restore drill has never run, because there is nothing to restore. `docs/06-runbooks/database-restore-drill.md` says so in its first lines. |
| `S1-AC-007` | `IMPLEMENTED_UNPROVEN` | Six alerts, each naming a runbook and a plain-language meaning; five new runbooks covering unknown write outcome, kill switch, backlog, evidence custody and restore. **Missing:** no failure injection has been performed against a real environment, so operator-visible degradation is designed rather than demonstrated. |

## C. Marketplace capability evidence

| ID | Verdict | Evidence and what is missing |
| --- | --- | --- |
| `S1-AC-008` | `NOT_MET` | No Ozon account was contacted. Every capability row is `UNVERIFIED`, which is why no call is reachable. Producing this evidence is an Owner-authorized act against a real account. |
| `S1-AC-009` | `NOT_MET` | No Wildberries account was contacted. The asynchronous write-result model is represented in the schema (`write_result_model`, task pointers, status vocabulary) so its semantics need not be assumed to match Ozon's — but nothing has been verified. |
| `S1-AC-010` | `IMPLEMENTED_UNPROVEN` | Every field the criterion names is a recorded column with a verification state and an evidence reference: rate limit, pagination model, freshness expectation, idempotency support, late-data behaviour, timeout and unknown-result strategy, readback path, credential scope. **Missing:** every row is `UNVERIFIED`. The register exists; the facts do not. |

## D. Ingestion and Raw

| ID | Verdict | Evidence and what is missing |
| --- | --- | --- |
| `S1-AC-011` | `IMPLEMENTED_UNPROVEN` | Run lifecycle, leasing and fencing on a real PostgreSQL path (`AuthorizedAcquisitionFlowIT`, `IngestionAuthorityAndEvidenceIT`); rate limiter refusing rather than exceeding a recorded limit; bounded retry budget. **Missing:** no real marketplace was called, so restartability against a real source is untested. |
| `S1-AC-012` | `IMPLEMENTED_UNPROVEN` | Hash, length and provenance recorded per object; read-back verification refusing a mismatch; filesystem and S3-compatible custody adapters; the bucket's compliance lock enforces immutability independently. **Missing:** no approved object store was contacted. |
| `S1-AC-013` | `MET` | The cursor advances only inside the transaction that committed the evidence (`AcquisitionPageWorker`), asserted against a real database in `IngestionAuthorityAndEvidenceIT`. |
| `S1-AC-014` | `MET` | Idempotent fact keys on `(organization_id, source_fact_key)`; metric values keyed by input digest; `ops.replay_run_makes_no_call()` refuses a marketplace call during replay (MO042), asserted against a real database. |
| `S1-AC-015` | `IMPLEMENTED_UNPROVEN` | `staging.schema_drift_observation` records a pointer no declaration names; unknown states are preserved verbatim; missing object paths surface as custody failures with their own alert. **Missing:** no real drift has been observed, so recovery from it is designed rather than exercised. |

## E. Product identity and internal intake

| ID | Verdict | Evidence and what is missing |
| --- | --- | --- |
| `S1-AC-016` | `MET` | Effective-dated mapping with a gist exclusion admitting one internal variant per listing variant at any instant; explicit conflict queue; the write gate refuses on `MAPPING_UNRESOLVED` and `MAPPING_CONFLICT_OPEN`, asserted against a real database (`TC-WRITE-101`). |
| `S1-AC-017` | `IMPLEMENTED_UNPROVEN` | CSV and XLSX intake with content hashing, preview, per-row validation, rejection, audit and replay; manual entry; versioned cost with a gist exclusion. **Missing:** no actual internal file sample from the business has been processed, so the registered schema profiles are provisional. |
| `S1-AC-018` | `MET` | Duplicate content refused on the content hash; malformed rows rejected per row with a reason; superseding rather than overwriting; asserted in the intake tests. |

## F. Facts, metrics and diagnosis

| ID | Verdict | Evidence and what is missing |
| --- | --- | --- |
| `S1-AC-019` | `MET` | Every fact carries a provenance row; `EvidenceQuery` resolves a canonical value back to the source it came from; source time, freshness and confidence travel with every value to the screen. |
| `S1-AC-020` | `MET` | Contribution profit and break-even minimum price computed by a pure function over declared inputs; twenty-eight guardrail cases including every way a missing input blocks rather than distorts (`TC-GUARD-001` through `TC-GUARD-007`); decimal money with explicit currency throughout. |
| `S1-AC-021` | `MET` | Completed, retained and settled sales are separate rows with a retention window; a late return writes a new fact rather than rewriting a historical one; metric values are keyed by input digest so a correction appears beside the figure somebody acted on. |
| `S1-AC-022` | `MET` | Nine rules in fixed ordinal order with `DATA_BLOCKED` first and guarding the rest; a rule that cannot answer records a decline with its reason rather than staying silent; rule order asserted as a seed fact (`TC-DB-118`). |

## G. AI

| ID | Verdict | Evidence and what is missing |
| --- | --- | --- |
| `S1-AC-023` | `MET` | Twenty-two allowlisted field paths enforced on the assembled projection; the projection is path-and-value pairs rather than an object graph, so an undeclared field has no way to travel; the request digest identifies what was sent without retaining it. |
| `S1-AC-024` | `MET` | Sixteen output-validation cases (`TC-AI-001` through `TC-AI-005`): fact, inference, recommendation and unknown are distinct kinds; a factual claim must cite something the model was shown; a recommendation may only name an action with a gate. |
| `S1-AC-025` | `MET` | Every path ends in a recorded invocation and none of them raises: no eligible provider records a refusal, a provider that does not answer records a failure, an answer that does not validate records the rejected claims. The deterministic layer is untouched in all three. |
| `S1-AC-026` | `NOT_MET` | No golden diagnostic cases have been approved by the Owner, and none could be produced without real operating data. The mechanism that would run them exists. |

## H. Workflow, policy and price execution

| ID | Verdict | Evidence and what is missing |
| --- | --- | --- |
| `S1-AC-027` | `MET` | Recommendation state machine asserted as whole-machine properties (`TC-WF-001` through `TC-WF-004`): nothing reaches an authorized state without review, a task-only proposal can never become one, and a terminal proposal cannot be revived. Decisions are append-only with one standing authorization per recommendation enforced by a partial unique index; every decision carries a bounded scope expiry. |
| `S1-AC-028` | `MET` | Most-specific-wins policy resolution in one statement; publishing refuses a version leaving a required limit unconfigured; an absent or expired policy produces `NO_POLICY_IN_FORCE` and blocks, asserted in `TC-GUARD-001`. |
| `S1-AC-029` | `MET` | The preview runs the same engine, over the same gathered values, as the gate; the recorded input digest makes the verdict re-derivable; a stale entity version blocks with `ENTITY_VERSION_CHANGED`, and the write gate independently refuses on `AUTHORIZATION_INVALID_OR_EXPIRED` when the digest has moved (`TC-WRITE-101`). |
| `S1-AC-030` | `MET` | Forty-five real-database cases (`TC-WRITE-101` through `TC-WRITE-108`) covering the gate as a conjunction, fence and lease refusal, success requiring a matching readback, no path from unknown back to executing, compensation safety, crash recovery, attempt immutability and every bound on a policy authorization. Derived idempotency key asserted in unit tests. |
| `S1-AC-031` | `NOT_MET` | Requires an exact unexpired Gate-EV envelope. Not authorized and not attempted. |
| `S1-AC-032` | `NOT_MET` | Requires its own Gate-EV envelope. Not authorized and not attempted. |
| `S1-AC-033` | `IMPLEMENTED_UNPROVEN` | The refusal that matters is proven against a real database: a restore is refused once anything else has moved the price, and a compensation is not complete until a readback observes the prior value (`TC-WRITE-105`). **Missing:** no real platform restore, which is Gate-EV work. |
| `S1-AC-034` | `MET` | Global, platform, account, store and capability scopes; a missing flag is off, so an unconfigured scope blocks; the gate is evaluated inside the transaction that claims a command, so a switch thrown while a worker is deciding is seen. Disable is ungated, enable requires a recent authentication. Asserted in `TC-WRITE-101`. |

## I. UI and operations

| ID | Verdict | Evidence and what is missing |
| --- | --- | --- |
| `S1-AC-035` | `IMPLEMENTED_UNPROVEN` | The browser suite proves sign-in through to subject diagnosis against the real backend and a routed identity provider, and proves that nothing operational reaches an unauthenticated visitor. Approval, command creation and the readback timeline are proven by component tests over the same code. **Missing:** the full chain in one browser run needs seeded operating data and a real provider; neither exists. |
| `S1-AC-036` | `MET` | One module decides presentation, asserted over the whole confidence vocabulary rather than case by case; an unavailable value is absent whatever its confidence says; an unresolved command says so in plain words and no readback means the screen says nothing about what the marketplace holds. Asserted in unit, component and browser tests. |
| `S1-AC-037` | `NOT_MET` | No representative data set and no environment to measure against. Indexes for the queue and subject queries exist but their targets are unmeasured. |
| `S1-AC-038` | `IMPLEMENTED_UNPROVEN` | Runbooks committed for unknown write outcome, readback mismatch, closed gate, kill switch, backlog, evidence custody, restore drill and environment bootstrap. **Missing:** none has been executed by support personnel, which is what this criterion asks for. |

## J. Release

| ID | Verdict | Evidence and what is missing |
| --- | --- | --- |
| `S1-AC-039` | `IMPLEMENTED_UNPROVEN` | Locally: 324 unit and architecture tests, 197 integration tests, 118 frontend tests, 8 browser tests, both repository validators, lint, type check, format check and bundle isolation — all passing on the final tree. **Missing:** no CI run exists, because nothing was pushed. |
| `S1-AC-040` | `NOT_MET` | The mechanisms are complete — pilot allowlist, policy limits, bounded authorizations, monitoring window, kill criteria — and every one of them is empty. Recording the actual cohort is an Owner decision. |
| `S1-AC-041` | `MET` | Production writes are disabled in every profile; the worker switch is off by default; enabling a write needs a verified capability, verified operations, both flag scopes on, an allowlisted entity, a live authorization and a passing guardrail — six separate decisions, none implied by a merge. |

## Summary

| Verdict | Count | Criteria |
| --- | --- | --- |
| `MET` | 21 | 002, 003, 004, 013, 014, 016, 018, 019, 020, 021, 022, 023, 024, 025, 027, 028, 029, 030, 034, 036, 041 |
| `IMPLEMENTED_UNPROVEN` | 13 | 001, 005, 006, 007, 010, 011, 012, 015, 017, 033, 035, 038, 039 |
| `NOT_MET` | 7 | 008, 009, 026, 031, 032, 037, 040 |

Every `NOT_MET` criterion needs something outside this authorization: a real
marketplace account, an Owner-approved golden case set, a Gate-EV envelope, a
running environment, or an Owner decision about who the pilot is.

No criterion is blocked by something this work could have done and did not.
