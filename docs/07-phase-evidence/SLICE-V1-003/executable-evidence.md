# SLICE-V1-003 — executable evidence

Every claim below is a command anybody can run against this tree. Nothing here
is a summary of a summary: the counts are what the runner printed.

Head `376228a`, branch `feat/SLICE-V1-003-advertising-traffic-efficiency`.
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
| `PriceWritePathIT` | 89 | the price controlled-write path, unchanged by this Slice |
| `AdBidWritePathIT` | 21 | advertising write-path structure: privileges, transition graph, registry shape |
| `AdvertisingPrivilegeBoundaryIT` | 9 | the application role's actual privileges, asserted from outside |
| `AdvertisingTransmissionBoundaryIT` | 7 | nothing leaves after the authority that permitted it stops holding |
| `AdvertisingAuthorityBindingIT` | 6 | an advertising decision binds to the advertising authority |
| `AdvertisingReservationIT` | 9 | overlap refusal, containment, multi-party reenablement |
| `AdvertisingManualShadowIT` | 8 | the Manual Shadow records and never routes |
| `AdvertisingEfficiencyFlowIT` | 19 | the calculation loop, and the two schedules that drive it |
| `AdvertisingDecisionQueryIT` | 4 | the decision-resolution queries against the real schema |
| `AdBidDispatchAuthorityIT` | 1 | the dispatch-authority query type-checks for all four operations |
| `AdBidParameterContractParityIT` | 1 | the Java and SQL parameter contracts accept the same shapes |
| `FlywayMigrationIT` | 11 | migration inventory, approved tables, seeds, role matrix |
| `ManagedMigrationRunnerIT` | 1 | clean install of 53 migrations, replay applies 0 |
| `ManagedProfileMigrationIT` | 4 | managed and standard profiles, upgrade from a prior release |
| `AdvertisingOperationsReadIT` | 7 | the console reads: scope in SQL, envelope axes kept apart, both outcome stages |

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
| A settled claim cannot outrun the Completed-Sales Guard | `OutcomeEvaluationTest#TC-AD-OUTCOME-007,011` |
| A fall in spend is the improvement a decrease wanted | `OutcomeEvaluationTest#TC-AD-OUTCOME-003b` |
| A bid never lands above the intent or off the platform's grid | `BidCandidateTest` (24 cases) |
| A unit whose sales matter is never cut automatically | `BidDirectionForCauseTest#TC-AD-DIR-002` |
| The advertising module writes no table another module owns | `SoleAuthorityArchitectureTest#TC-AUTHORITY-001` |
| Exactly one migration inserts into `ops.ad_bid_command` | `SoleAuthorityArchitectureTest#TC-AUTHORITY-002` |
| Java's vocabulary and the schema's are the same words | `SchemaVocabularyAgreementTest` (5 cases) |
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
| failsafe (integration) | `Tests run: 545, Failures: 1, Errors: 0, Skipped: 0` |
| total | 18:09 min, `BUILD FAILURE` on the one failure below |

### Coverage

Measured from the merged `jacoco.exec` (unit and integration appended to the
same file, which is how the bundle gate is computed):

| Counter | Measured | Gate |
| --- | --- | --- |
| LINE | 0.8294 | 0.80 |
| BRANCH | 0.7040 | 0.70 |

5,814 of 8,259 branches, 33 above what the gate needs. No threshold was
weakened to get there; the four suites in `16f254a` were written instead.

### The one failing test, and what it means

`RepresentativePerformanceIT#commonDiagnosticQueriesMeetTheBaselineOnTheDeclaredProfile`
fails its CRITICAL-lane p95 assertion:

```
Expecting actual:  377769L
to be less than or equal to:  300000L
```

**This is not a regression this Slice introduced.** The same test was run alone,
on the same machine, in a worktree at the Slice base `08ad7da7d9e75b4dd`, with
nothing else running:

| Commit | CRITICAL p95 | Budget | Test wall clock | Dataset `mart.metric_value` |
| --- | --- | --- | --- | --- |
| `08ad7da7` (Slice base) | 326,120 ms | 300,000 ms | 607.8 s | 1,047,420 |
| `376228a` (this head) | 377,769 ms | 300,000 ms | 672.3 s | 983,940 |

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
- This head is ~16% slower than the base on a dataset 6% smaller. That gap was
  measured once each; the head run was the tail of an 18-minute suite and the
  base run followed only unit tests, so page-cache and container state differ.
  It is recorded rather than explained, and it has not been attributed to a
  specific change.

The threshold was not moved. `docs/07-phase-evidence/SLICE-V1-003/deferred-release-register.json`
carries the obligation to establish this on representative hardware.

## What has not been run

Named plainly, because a reader should not have to infer it from absence.

- **Browser end-to-end.** The Playwright suite has not been run against the new
  advertising surfaces.
- **Mutation and adversarial testing.** Not run for the advertising module.
- **Advertising capacity and SLO measurement.** `AdvertisingSlo` fixes the bounds
  and `ops.ad_slo_observation` records latencies; no advertising load has been
  applied.
- **Anything requiring a real provider.** By prohibition. See
  `deferred-release-register.json`.
