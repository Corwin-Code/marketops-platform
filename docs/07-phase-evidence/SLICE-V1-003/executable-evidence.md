# SLICE-V1-003 — executable evidence

Every claim below is a command anybody can run against this tree. Nothing here
is a summary of a summary: the counts are what the runner printed.

Head `77faa37`, branch `feat/SLICE-V1-003-advertising-traffic-efficiency`.
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
| unit | 1480 | domain arithmetic, value states, refusal vocabularies, port contracts, properties |
| architecture | 71 | module boundaries, no second writer, schema vocabulary agreement |
| property (`AdvertisingDomainPropertyTest`) | 18 | determinism, stage consistency, non-compensating materiality and priority, expiry minima, provider-valid targets, absent-is-never-zero |
| property (`WorkTaskEventPropertyTest`) | 4 | an event is one kind of thing, an action stage needs all three of its parts |
| console (vitest) | 269 | evidence vocabulary, the five advertising surfaces, every parser refusal |
| console (playwright) | 25 | the built console in Chromium against the real backend |
| `AdvertisingVerticalPathIT` | 18 | one decision carried the whole way, and each stage refusing for the reason it states |
| `WorkTaskJournalIT` | 7 | view ≠ acknowledgement ≠ action ≠ outcome, and a handover that keeps the age |
| `AdvertisingBriefPublicationIT` | 10 | the daily brief and weekly review as versioned projections, and a published one that cannot be edited |
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
| `ManagedMigrationRunnerIT` | 1 | clean install of 56 migrations, replay applies 0 |
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
| The write gate returns an empty array for a fully configured command | `AdvertisingVerticalPathIT#TC-AD-VERTICAL-009` |
| The call is recorded before it is made, against a frozen operation shape | `AdvertisingVerticalPathIT#TC-AD-VERTICAL-010` |
| A provider answer is classified from the frozen contract, not the adapter | `AdvertisingVerticalPathIT#TC-AD-VERTICAL-011` |
| The readback, not the acceptance, is what proves a write landed | `AdvertisingVerticalPathIT#TC-AD-VERTICAL-012` |
| No settled claim may be made while the sales are young | `AdvertisingVerticalPathIT#TC-AD-VERTICAL-013` |
| Operational counts orders placed; settled counts sales that survived | `AdvertisingVerticalPathIT#TC-AD-VERTICAL-014,015` |
| A late fact restates a window without editing what was published | `AdvertisingVerticalPathIT#TC-AD-VERTICAL-016` |
| A settled regression quarantines the lineage and refuses the next write | `AdvertisingVerticalPathIT#TC-AD-VERTICAL-017` |
| A page open cannot satisfy an action stage | `WorkTaskJournalIT#TC-WF-JOURNAL-001,002` |
| An action carries its action, its evidence and its actor, or it is refused | `WorkTaskJournalIT#TC-WF-JOURNAL-003` |
| What an action achieved is a later reading against an observation | `WorkTaskJournalIT#TC-WF-JOURNAL-004` |
| A task that changes hands keeps the instant it was raised | `WorkTaskJournalIT#TC-WF-JOURNAL-005` |
| A reopen continues its lineage rather than starting one | `WorkTaskJournalIT#TC-WF-JOURNAL-006` |
| The task journal has no UPDATE or DELETE to grant | `WorkTaskJournalIT#TC-WF-JOURNAL-007` |
| A published brief cannot be edited, by either role | `AdvertisingBriefPublicationIT#TC-AD-BRIEF-008` |
| A topic with no canonical source says so instead of reading as empty | `AdvertisingBriefPublicationIT#TC-AD-BRIEF-003`, `AdvertisingBrief.test.tsx#TC-UI-BRIEF-002` |
| A revision states its deltas rather than leaving two bodies to be diffed | `AdvertisingBriefPublicationIT#TC-AD-BRIEF-005` |

## The whole-repository run

One `mvn verify` at this head, on a machine whose Docker is limited to 4 CPUs
and 6.2 GB:

```bash
rm -rf backend/marketops-server/target/classes/db/migration backend/marketops-server/target/jacoco.exec
JAVA_HOME=~/.sdkman/candidates/java/21.0.10-zulu ./backend/marketops-server/mvnw -B -ntp -f backend/marketops-server/pom.xml verify
```

| Phase | Result |
| --- | --- |
| surefire (unit + architecture + property) | `Tests run: 1480, Failures: 0, Errors: 0, Skipped: 0` |
| failsafe (integration) | `Tests run: 604, Failures: 0, Errors: 0, Skipped: 0` |
| coverage gate | `All coverage checks have been met.` |
| total | 21:51 min, `BUILD SUCCESS` |

Alongside it, at the same head:

| Suite | Command | Result |
| --- | --- | --- |
| console | `npm run test:ci` | 19 files, 269 passed |
| browser | `npm run test:browser` | 25 passed in Chromium against the real backend |
| bundle isolation | `npm run verify:bundle` | `only prefixed values reached the bundle` |
| governance | `python3 scripts/validate_governance.py` | `Governance validation passed.` |
| production readiness | `python3 scripts/validate_production_readiness.py` | four checks PASS over 2,759 files |
| tooling | `python3 -m unittest discover -s tests` | `Ran 397 tests … OK` |

### Coverage

Measured from the merged `jacoco.exec` (unit and integration appended to the
same file, which is how the bundle gate is computed, and kept at
`measurements/jacoco-merged-r3.json`):

| Counter | Measured | Gate |
| --- | --- | --- |
| LINE | 0.8589 (21,619 / 25,172) | 0.80 |
| BRANCH | 0.7141 (6,094 / 8,534) | 0.70 |

120 branches above what the gate needs, up from 52. No threshold was weakened to
get there; the suites were written instead.

### The declared-capacity gate

`RepresentativePerformanceIT` passes. CRITICAL-lane p95 **226,229 ms** against a
300,000 ms budget, `maxMillis` 236,035, and zero breaches in every lane. Nothing
was weakened to get there: the threshold is untouched, the test is not excluded,
and the dataset is *larger* than the failing run.

| Head | CRITICAL p95 | Budget | Dataset `mart.metric_value` | Result |
| --- | --- | --- | --- | --- |
| `08ad7da7` (Slice base) | 326,120 ms | 300,000 ms | 1,047,420 | fail |
| `384e34e` | 388,962 ms | 300,000 ms | 983,940 | fail |
| this head | **226,229 ms** | 300,000 ms | **1,047,420** | **pass** |

The run was profiled rather than guessed at. The test now times every statement
the availability phase issues and records the twenty-five most expensive into its
own report, written even when the run fails. The first profile said **478,954
statements** for 5,000 variants — about ninety round trips each, most of them
single-row appends — and four behaviour-preserving changes followed:

- the projection writer collects its thirty appends per variant and writes each
  group in one statement, and the five stage spans a refresh emits do the same;
- one calculation asks each question once, through a memo scoped to that
  calculation and discarded with it — the channel and company views of a variant
  read one snapshot at one instant, so a repeat could only return the first
  answer again;
- `V0054` indexes the demand carry-forward lookup, which ran twice per variant
  against an append-only table with no index leading on `child_id`, so the cost
  of one read grew with everything written before it;
- the dataset's own `ANALYZE` now covers the tables the availability seed writes
  after the script's analyse.

The profile also exposed a defect in the previous head's dataset: it excluded
metrics **by domain**, which dropped `AD_SPEND` and `AD_COST_OF_SALE` —
advertising-domain metrics `MetricEngine` has computed for a listing variant
since `V0015` and the diagnosis payload shows. The exclusion is now the ten
advertising-**object** codes `V0037` added, which `mart.metric_value` cannot hold
a subject for, and the dataset is back to 33 definitions and 1,047,420 values —
the same size the Slice base measured.

Both the failing profile and the passing report are kept in
`docs/07-phase-evidence/SLICE-V1-003/measurements/`, because a run that missed
its service level is exactly the run whose profile somebody needs.

## Two defects the first end-to-end run found

Neither was found by reading. Both were found by asking, for the first time,
whether the machinery actually works — and both had survived precisely because
nothing had ever executed the code.

`ops.complete_ad_bid_command_attempt` (`V0043`) re-hydrated the frozen operation
shape with `SELECT jsonb_populate_record(...) INTO operation`. PL/pgSQL assigns a
select list to a row variable field by field, so the composite landed in the
first field — a `uuid` — and raised `22P02` for every response that carried
bytes. Every acceptance and every readback carries bytes, so **no advertising
attempt could ever be classified and no readback could ever be recorded**. Five
sibling sites, in `V0025` and `V0027`, already use the working form.

`core.ad_qualification_tier_is_monotonic` (`V0037`) self-joined the live tiers on
adjacent rank and asserted `count(*) = 4` over the *join*. Four tiers make three
adjacent pairs, so the check was unsatisfiable with one row per tier and **every
bundle activation failed** with `QUALIFICATION_TIER_MONOTONICITY_VIOLATED`.

Both were corrected in the candidate migrations themselves. `V0037` and `V0043`
have never left this branch: patching forward would have left a clean install
applying a statement that cannot succeed, and would have written a migration
whose stated purpose was to repair something that had never been released. Their
checksums in `MIGRATION-INVENTORY.json` are the corrected bytes; the disposable
databases were recreated and clean-install, replay and exact-base-upgrade
validation rerun.

The reservation a reader should hold about the vertical path is stated in the
test itself: the platform it runs against is `FIXTURE_ADS`, a protocol this
repository specifies, and the semantic profile is `OFFICIAL_VERIFIED` because
the protocol is one the fixture writes. Marking an Ozon profile verified instead
would have been inventing a marketplace fact. `TC-AD-VERTICAL-001` and
`TC-AD-VERTICAL-018` assert that no real marketplace gained a capability, a
command, an allowlist entry or an active bundle, and that
`production_write_enabled` is still false.

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
