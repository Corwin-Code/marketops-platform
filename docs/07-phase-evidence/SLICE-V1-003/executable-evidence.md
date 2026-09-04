# SLICE-V1-003 — executable evidence

Every claim below is a command anybody can run against this tree. Nothing here
is a summary of a summary: the counts are what the runner printed.

Head `41105cd`, branch `feat/SLICE-V1-003-advertising-traffic-efficiency`.
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
| unit | 1248 | domain arithmetic, value states, refusal vocabularies, port contracts |
| architecture | 69 | module boundaries, no second writer, schema vocabulary agreement |
| `PriceWritePathIT` | 89 | the price controlled-write path, unchanged by this Slice |
| `AdBidWritePathIT` | 21 | advertising write-path structure: privileges, transition graph, registry shape |
| `AdvertisingPrivilegeBoundaryIT` | 9 | the application role's actual privileges, asserted from outside |
| `AdvertisingTransmissionBoundaryIT` | 7 | nothing leaves after the authority that permitted it stops holding |
| `AdvertisingAuthorityBindingIT` | 6 | an advertising decision binds to the advertising authority |
| `AdvertisingReservationIT` | 9 | overlap refusal, containment, multi-party reenablement |
| `AdvertisingManualShadowIT` | 8 | the Manual Shadow records and never routes |
| `AdvertisingEfficiencyFlowIT` | 14 | the calculation loop from evidence to a projected case |
| `AdvertisingDecisionQueryIT` | 4 | the decision-resolution queries against the real schema |
| `AdBidDispatchAuthorityIT` | 1 | the dispatch-authority query type-checks for all four operations |
| `AdBidParameterContractParityIT` | 1 | the Java and SQL parameter contracts accept the same shapes |
| `FlywayMigrationIT` | 11 | migration inventory, approved tables, seeds, role matrix |
| `ManagedMigrationRunnerIT` | 1 | clean install of 53 migrations, replay applies 0 |
| `ManagedProfileMigrationIT` | 4 | managed and standard profiles, upgrade from a prior release |

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
| A settled claim cannot outrun the Completed-Sales Guard | `OutcomeEvaluationTest#TC-AD-OUTCOME-007,011` |
| A fall in spend is the improvement a decrease wanted | `OutcomeEvaluationTest#TC-AD-OUTCOME-003b` |
| A bid never lands above the intent or off the platform's grid | `BidCandidateTest` (24 cases) |
| A unit whose sales matter is never cut automatically | `BidDirectionForCauseTest#TC-AD-DIR-002` |
| The advertising module writes no table another module owns | `SoleAuthorityArchitectureTest#TC-AUTHORITY-001` |
| Exactly one migration inserts into `ops.ad_bid_command` | `SoleAuthorityArchitectureTest#TC-AUTHORITY-002` |
| Java's vocabulary and the schema's are the same words | `SchemaVocabularyAgreementTest` (5 cases) |
| The application role holds DELETE on no advertising table | `AdvertisingPrivilegeBoundaryIT#TC-AD-PRIV-101` |
| Every state-moving function is SECURITY DEFINER with a pinned search_path | `AdvertisingPrivilegeBoundaryIT#TC-AD-PRIV-103` |

## What has not been run

Named plainly, because a reader should not have to infer it from absence.

- **Coverage.** JaCoCo has not been run since the advertising application and
  infrastructure classes landed. The bundle gate is line 0.80 / branch 0.70.
- **Browser end-to-end.** There is no Advertising Control console surface yet, so
  there is nothing to drive.
- **Mutation and adversarial testing.** Not run for the advertising module.
- **Capacity and SLO measurement.** `AdvertisingSlo` fixes the bounds and
  `ops.ad_slo_observation` records latencies; no load has been applied.
- **Full-repository regression.** The suites above were run individually and in
  groups; a single whole-repository run has not been recorded at this head.
- **Anything requiring a real provider.** By prohibition. See
  `deferred-release-register.json`.
