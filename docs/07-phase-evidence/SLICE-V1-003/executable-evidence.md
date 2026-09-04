# SLICE-V1-003 — executable evidence

Every claim below is a command anybody can run against this tree. Nothing here
is a summary of a summary: the counts are what the runner printed.

Head `384e34e`, branch `feat/SLICE-V1-003-advertising-traffic-efficiency`.
All runs require `JAVA_HOME=~/.sdkman/candidates/java/21.0.10-zulu` and are
invoked from the repository root.

## How to run it

Unit and architecture suites:

```bash
JAVA_HOME=~/.sdkman/candidates/java/21.0.10-zulu ./backend/marketops-server/mvnw -B -ntp -f backend/marketops-server/pom.xml -DskipITs test
```

Integration suites (Testcontainers; PostgreSQL 18.4 for the raw-JDBC tests and
17.6 for the Spring ones). Delete `target/classes/db/migration` first — a stale
copy of the migrations there will silently run instead of the source:

```bash
rm -rf backend/marketops-server/target/classes/db/migration
JAVA_HOME=~/.sdkman/candidates/java/21.0.10-zulu ./backend/marketops-server/mvnw -B -ntp -f backend/marketops-server/pom.xml test-compile failsafe:integration-test failsafe:verify
```

## What passes today

| Suite | Cases | What it establishes |
| --- | --- | --- |
| unit | 1406 | domain arithmetic, value states, refusal vocabularies, port contracts |
| architecture | 71 | module boundaries, no second writer, schema vocabulary agreement |
| console (vitest) | 262 | evidence vocabulary, the four advertising surfaces, every parser refusal |
| console (playwright) | 19 | the built console in Chromium against the real backend |
| `PriceWritePathIT` | 89 | the price controlled-write path, unchanged by this Slice |
| `AdBidWritePathIT` | 21 | advertising write-path structure: privileges, transition graph, registry shape |
| `AdvertisingPrivilegeBoundaryIT` | 9 | the application role's actual privileges, asserted from outside |
| `AdvertisingTransmissionBoundaryIT` | 7 | nothing leaves after the authority that permitted it stops holding |
| `AdvertisingAuthorityBindingIT` | 6 | an advertising decision binds to the advertising authority |
| `AdvertisingReservationIT` | 9 | overlap refusal, containment, multi-party reenablement |
| `AdvertisingManualShadowIT` | 8 | the Manual Shadow records and never routes |
| `AdvertisingEfficiencyFlowIT` | 23 | the calculation loop, both schedules, and the queue under concurrency, restart and replay |
| `AdvertisingDecisionQueryIT` | 4 | the decision-resolution queries against the real schema |
| `AdBidDispatchAuthorityIT` | 1 | the dispatch-authority query type-checks for all four operations |
| `AdBidParameterContractParityIT` | 1 | the Java and SQL parameter contracts accept the same shapes |
| `FlywayMigrationIT` | 11 | migration inventory, approved tables, seeds, role matrix |
| `ManagedMigrationRunnerIT` | 1 | clean install of 53 migrations, replay applies 0 |
| `ManagedProfileMigrationIT` | 4 | managed and standard profiles, upgrade from a prior release |
| `AdvertisingOperationsReadIT` | 7 | the console reads: scope in SQL, envelope axes kept apart, both outcome stages |
| `AdBidWriteGateAdversarialIT` | 8 | the gate attacked one fact at a time; every reason earned separately |

## The safety properties, and where each is proved

| Property | Test |
| --- | --- |
| No advertising command can be created while no capability is verified | `AdvertisingTransmissionBoundaryIT#TC-AD-BOUNDARY-002` |
| A kill switch thrown mid-flight is caught at transmission, not only at lease | `AdvertisingTransmissionBoundaryIT#TC-AD-BOUNDARY-003` |
| A second APPLY after an unknown result is refused twice over | `AdvertisingTransmissionBoundaryIT#TC-AD-BOUNDARY-006` |
| No edge exists from `UNKNOWN_REQUIRES_READBACK` back to `EXECUTING` | `AdvertisingTransmissionBoundaryIT#TC-AD-BOUNDARY-006` |
| Two interventions cannot hold the same product variants | `AdvertisingReservationIT#TC-AD-RESERVE-002` |
| One person cannot lift their own containment | `AdvertisingReservationIT#TC-AD-CONTAIN-002` |
| A security cause needs an attestation before anything restarts | `AdvertisingReservationIT#TC-AD-CONTAIN-003` |
| Budget and status changes have no command table, and no FK into one | `AdvertisingManualShadowIT#TC-AD-MANUAL-001,002` |
| An executor's own report cannot prove a configuration | `AdvertisingManualShadowIT#TC-AD-MANUAL-003,004` |
| The targeted path and the sweep leave the same case | `AdvertisingEfficiencyFlowIT#TC-AD-FLOW-012,018` |
| A trigger no targeted pass reached is repaired by the sweep that covered it | `AdvertisingEfficiencyFlowIT#TC-AD-FLOW-017` |
| A sweep abandoned mid-portfolio is failed, not left holding the mutex | `AdvertisingEfficiencyFlowIT#TC-AD-FLOW-019` |
| Every drained request leaves a latency observation behind | `AdvertisingEfficiencyFlowIT#TC-AD-FLOW-015` |
| Two workers cannot hold the same unit of work | `AdvertisingEfficiencyFlowIT#TC-AD-FLOW-020` |
| A lease that outlived its worker is reclaimed and the attempt counted | `AdvertisingEfficiencyFlowIT#TC-AD-FLOW-021` |
| A replayed fact coalesces, and one older than the answer given is suppressed | `AdvertisingEfficiencyFlowIT#TC-AD-FLOW-022,023` |
| A settled claim cannot outrun the Completed-Sales Guard | `OutcomeEvaluationTest#TC-AD-OUTCOME-007,011` |
| A fall in spend is the improvement a decrease wanted | `OutcomeEvaluationTest#TC-AD-OUTCOME-003b` |
| A bid never lands above the intent or off the platform's grid | `BidCandidateTest` (24 cases) |
| A unit whose sales matter is never cut automatically | `BidDirectionForCauseTest#TC-AD-DIR-002` |
| The advertising module writes no table another module owns | `SoleAuthorityArchitectureTest#TC-AUTHORITY-001` |
| Exactly one migration inserts into `ops.ad_bid_command` | `SoleAuthorityArchitectureTest#TC-AUTHORITY-002` |
| Java's vocabulary and the schema's are the same words | `SchemaVocabularyAgreementTest` (5 cases) |
| Each gate reason moves on its own fact, and none is emitted with another | `AdBidWriteGateAdversarialIT#TC-AD-GATE-ADV-003,004,006` |
| A reservation cannot be released while its conditions are unmet | `AdBidWriteGateAdversarialIT#TC-AD-GATE-ADV-005` |
| A command that does not exist is refused by name, never by an empty list | `AdBidWriteGateAdversarialIT#TC-AD-GATE-ADV-002` |
| The application role holds DELETE on no advertising table | `AdvertisingPrivilegeBoundaryIT#TC-AD-PRIV-101` |
| Every state-moving function is SECURITY DEFINER with a pinned search_path | `AdvertisingPrivilegeBoundaryIT#TC-AD-PRIV-103` |

## The whole-repository run

One `mvn verify` at this head, on a machine whose Docker is limited to 4 CPUs
and 6.2 GB:

```bash
rm -rf backend/marketops-server/target/classes/db/migration backend/marketops-server/target/jacoco.exec
JAVA_HOME=~/.sdkman/candidates/java/21.0.10-zulu ./backend/marketops-server/mvnw -B -ntp -f backend/marketops-server/pom.xml verify
```

| Phase | Result |
| --- | --- |
| surefire (unit + architecture) | `Tests run: 1406, Failures: 0, Errors: 0, Skipped: 0` |
| failsafe (integration) | `Tests run: 569, Failures: 1, Errors: 0, Skipped: 0` |
| total | 18:49 min, `BUILD FAILURE` on the one failure below |

### Coverage

Measured from the merged `jacoco.exec` (unit and integration appended to the
same file, which is how the bundle gate is computed):

| Counter | Measured | Gate |
| --- | --- | --- |
| LINE | 0.8361 | 0.80 |
| BRANCH | 0.7061 | 0.70 |

52 branches above what the gate needs. No threshold was
weakened to get there; the four suites in `16f254a` were written instead.

### The one failing test, and what it means

`RepresentativePerformanceIT#commonDiagnosticQueriesMeetTheBaselineOnTheDeclaredProfile`
fails its CRITICAL-lane p95 assertion:

```
Expecting actual:  388962L
to be less than or equal to:  300000L
```

**This is not a regression this Slice introduced.** The same test was run alone,
on the same machine, in a worktree at the Slice base `08ad7da7d9e75b4dd`, with
nothing else running:

| Commit | CRITICAL p95 | Budget | Test wall clock | Dataset `mart.metric_value` |
| --- | --- | --- | --- | --- |
| `08ad7da7` (Slice base) | 326,120 ms | 300,000 ms | 607.8 s | 1,047,420 |
| `384e34e` (this head) | 388,962 ms | 300,000 ms | 650.6 s | 983,940 |

The base already fails. What the p95 measures is the internal latency of the
5,000-variant availability recalculation, and because every request is enqueued
before any is processed, that latency is essentially the wall clock of the whole
sweep — so the assertion is a throughput claim about the machine as much as
about the code. Docker here has 4 of the host's 8 CPUs and 6.2 GB, and dataset
generation alone takes ~249 s on both commits.

Two honest statements follow, and they are different:

- The declared-capacity claim is **unverified on this hardware**, at the Slice
  base and at this head. It is not established, and it is not this Slice's
  doing.
- This head is ~19% slower than the base on a dataset 6% smaller. Three
  measurements exist: 326,120 ms at the base, and 377,769 ms and 388,962 ms at
  two heads of this branch. The head runs were the tail of an 18-minute suite
  and the base run followed only unit tests, so page-cache and container state
  differ between them. The gap is recorded rather than explained, and it has not
  been attributed to any specific change.

The threshold was not moved. `docs/07-phase-evidence/SLICE-V1-003/deferred-release-register.json`
carries the obligation to establish this on representative hardware.

## The browser run

```bash
make up
cd frontend/marketops-console && npm run test:browser
```

`19 passed (1.2m)`, Chromium, against the real backend the suite starts and a
disposable PostgreSQL 18.4 the browser fixture refuses to touch unless it is
empty.

Two things are proven and deliberately kept apart. The three new advertising
reads are refused by the *running backend* without a token and with a made-up
one — that is evidence about the backend. What the console renders is asserted
against bodies supplied through the browser's own network layer; that is
evidence about the console's presentation and about nothing else. No
marketplace, advertising platform or identity provider is contacted.

`TC-BROWSER-012`'s corrupt-export case was red before this run, and had been
since `2229686` put the first advertising panel on the queue view: it asserted
on a page-wide `getByRole('alert')` while meaning the export panel's own alert,
and a second panel reporting its own refusal made that query ambiguous. The
assertion is now scoped to the panel, which is stricter rather than looser — the
message has to appear in the right place.

## Governance validators

```bash
python3 scripts/validate_governance.py
python3 scripts/validate_production_readiness.py
python3 -m unittest discover -s tests -p 'test_*.py'
```

`validate_governance.py` passes. The validator unit suite is 397 green.
`validate_production_readiness.py` now passes three of its four checks and fails
one:

| Check | Result |
| --- | --- |
| TC-GLOBAL-001 Compromise Retirement | PASS |
| TC-GLOBAL-002 Functional JavaDoc Rewrite | 3 violations, all narration in two already-applied migrations |
| TC-GLOBAL-003 Production Naming | PASS |
| TC-GLOBAL-004 Deferred Evidence Boundary | PASS |

TC-GLOBAL-001 failed for two reasons and both are fixed. The approved migration
set stopped at `V0035` — the validator's own comment says a migration is listed
in the change that adds it, and this Slice had not done that for `V0036`–`V0053`
— and the tolerant-schema-creation rule matched the bare string `IF NOT EXISTS`,
which reads PL/pgSQL's `IF NOT EXISTS (SELECT …)` as tolerant DDL. That
conditional is a boolean expression and creates nothing, and the four advertising
migrations are the only ones in the repository that use PL/pgSQL at all, so the
rule refused exactly them. It now matches `IF NOT EXISTS` only where DDL puts
it, which a new validator test pins in both directions.

### The one that is not fixed

TC-GLOBAL-002 flags three comments in `V0040` and `V0047` that narrate what the
schema used to do. The rule is right — a reader of a migration should learn what
is true, not what changed — but the only way to satisfy it is to edit two
migrations that every clean install has already applied and whose checksums this
Slice publishes in `MIGRATION-INVENTORY.json`. Forward-only migration discipline
forbids that, and a comment cannot be corrected by a later migration.

The two rules are in direct conflict for any applied migration, which is an
Owner decision rather than an engineering one. Nothing here works around it:
the violation stands, `validate_production_readiness.py` exits non-zero, and
`S3-AC-200` records it.

## What has not been run

Named plainly, because a reader should not have to infer it from absence.

- **Mutation testing.** No mutation-testing tool is configured in this
  repository and none was added; the adversarial suites above change one fact at
  a time and require exactly one reason to move, which is the property a
  mutation score is a proxy for, but it is not a mutation score.
- **Advertising capacity and SLO measurement.** `AdvertisingSlo` fixes the bounds
  and `ops.ad_slo_observation` records latencies; no advertising load has been
  applied.
- **Anything requiring a real provider.** By prohibition. See
  `deferred-release-register.json`.
