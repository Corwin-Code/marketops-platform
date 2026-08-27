package com.mimococo.marketops.database;

import static com.mimococo.marketops.database.PriceWritePathFixture.ALLOWLIST;
import static com.mimococo.marketops.database.PriceWritePathFixture.APPROVAL;
import static com.mimococo.marketops.database.PriceWritePathFixture.AUTHORIZATION;
import static com.mimococo.marketops.database.PriceWritePathFixture.AUTHORIZATION_BOUND_EXCEEDED;
import static com.mimococo.marketops.database.PriceWritePathFixture.AUTHORIZATION_EXHAUSTED;
import static com.mimococo.marketops.database.PriceWritePathFixture.AUTHORIZATION_NOT_USABLE;
import static com.mimococo.marketops.database.PriceWritePathFixture.AUTHORIZATION_SCOPE_MISMATCH;
import static com.mimococo.marketops.database.PriceWritePathFixture.AUTHORITY_LOST;
import static com.mimococo.marketops.database.PriceWritePathFixture.CAPABILITY;
import static com.mimococo.marketops.database.PriceWritePathFixture.CAPABILITY_FLAG;
import static com.mimococo.marketops.database.PriceWritePathFixture.COMMAND;
import static com.mimococo.marketops.database.PriceWritePathFixture.COMPENSATION_UNSAFE;
import static com.mimococo.marketops.database.PriceWritePathFixture.COMPENSATION_WITHOUT_READBACK;
import static com.mimococo.marketops.database.PriceWritePathFixture.GLOBAL_FLAG;
import static com.mimococo.marketops.database.PriceWritePathFixture.GUARDRAIL;
import static com.mimococo.marketops.database.PriceWritePathFixture.LEASE_INVALID;
import static com.mimococo.marketops.database.PriceWritePathFixture.LISTING_VARIANT;
import static com.mimococo.marketops.database.PriceWritePathFixture.MAPPING;
import static com.mimococo.marketops.database.PriceWritePathFixture.STORE;
import static com.mimococo.marketops.database.PriceWritePathFixture.SUCCESS_WITHOUT_READBACK;
import static com.mimococo.marketops.database.PriceWritePathFixture.TRANSITION_NOT_ALLOWED;
import static com.mimococo.marketops.database.PriceWritePathFixture.VARIANT;
import static com.mimococo.marketops.database.PriceWritePathFixture.WRITE_GATE_CLOSED;
import static com.mimococo.marketops.database.PriceWritePathFixture.consume;
import static com.mimococo.marketops.database.PriceWritePathFixture.execute;
import static com.mimococo.marketops.database.PriceWritePathFixture.gateReasons;
import static com.mimococo.marketops.database.PriceWritePathFixture.lease;
import static com.mimococo.marketops.database.PriceWritePathFixture.recordAttempt;
import static com.mimococo.marketops.database.PriceWritePathFixture.recordReadback;
import static com.mimococo.marketops.database.PriceWritePathFixture.recoverLeases;
import static com.mimococo.marketops.database.PriceWritePathFixture.stateOf;
import static com.mimococo.marketops.database.PriceWritePathFixture.transition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The controlled write path as database facts.
 *
 * <p>Every guarantee here is asserted through the same functions the
 * application calls, connected as the application role, because the point of
 * putting them in the database was that they hold for an arbitrary client. A
 * test that reached around them would prove only that the application is
 * well-behaved today.
 *
 * <p>Each case starts from a graph in which a write is permitted and removes
 * exactly one thing. That mirrors how the gate is written — every condition must
 * hold — so a case that changes one condition names the reason it blocks
 * without ambiguity.
 */
class PriceWritePathIT extends PostgresContainerSupport {

    private static final String WORKER = "worker-a";
    private static final String OTHER_WORKER = "worker-b";
    private static final int LEASE_SECONDS = 120;

    private static PostgreSQLContainer container;

    /**
     * The connection the acts under test run on.
     *
     * <p>Deliberately the application's own role. Every guarantee asserted here
     * exists because the database enforces it against whatever client connects,
     * and reaching around the role would prove only that the application is
     * well-behaved today.
     */
    private Connection connection;

    /**
     * The connection the world is arranged on.
     *
     * <p>Arranging a scenario is not one of the acts under test. Using the
     * owning role for it keeps the application role's refusals meaningful:
     * when a test sees INSUFFICIENT_PRIVILEGE, that is the finding rather than
     * an accident of how the fixture was built.
     */
    private Connection arranger;

    @BeforeAll
    static void migrate() {
        container = shared();
        migrator(container).migrate();
    }

    @BeforeEach
    void openAndSeed() throws SQLException {
        arranger = asMigrationRole(container);
        PriceWritePathFixture.reset(arranger);
        PriceWritePathFixture.seed(arranger);
        connection = asApplicationRole(container);
    }

    @AfterEach
    void closeConnections() throws SQLException {
        if (connection != null) {
            connection.close();
        }
        if (arranger != null) {
            PriceWritePathFixture.reset(arranger);
            arranger.close();
        }
    }

    @Nested
    @DisplayName("TC-WRITE-101 the gate is a conjunction and every part is real")
    class WriteGate {

        @ParameterizedTest
        @ValueSource(strings = {
                "target_price = 106", "prior_price = 99", "currency_code = 'USD'",
                "entity_version_digest = repeat('2', 64)",
                "idempotency_key = 'pc-a-different-authorization'"
        })
        void aCommandCannotSubstituteAnyApprovedInput(String change) throws SQLException {
            execute(arranger, "UPDATE ops.price_command SET " + change
                    + " WHERE id = '" + COMMAND + "'");
            assertThat(gateReasons(connection, COMMAND)).contains("COMMAND_AUTHORITY_MISMATCH");
            assertThatThrownBy(() -> lease(connection, COMMAND, WORKER, LEASE_SECONDS))
                    .satisfies(error -> assertThat(carriesSqlState(error, WRITE_GATE_CLOSED)).isTrue());
        }

        @Test
        void changingTheApprovedProposalDoesNotChangeTheAuthorizedPrice() throws SQLException {
            execute(arranger, "UPDATE ops.recommendation SET proposed_parameters = "
                    + "'{\"targetPrice\":\"106\"}'::jsonb WHERE id = '"
                    + PriceWritePathFixture.RECOMMENDATION + "'");
            assertThat(gateReasons(connection, COMMAND)).contains("COMMAND_AUTHORITY_MISMATCH");
        }

        @Test
        void aPolicyChangeInvalidatesThePreviouslyEvaluatedSnapshot() throws SQLException {
            execute(arranger, "UPDATE ops.commercial_policy SET policy_version = 2 WHERE id = '"
                    + PriceWritePathFixture.POLICY + "'");
            assertThat(gateReasons(connection, COMMAND)).contains("COMMAND_AUTHORITY_MISMATCH");
        }

        @Test
        void aNewCanonicalValueInvalidatesTheApprovedEntityDigest() throws SQLException {
            execute(arranger, "UPDATE mart.metric_value SET input_digest = repeat('2', 64)");
            assertThat(gateReasons(connection, COMMAND)).contains("COMMAND_AUTHORITY_MISMATCH");
        }

        @Test
        void creationDerivesThePriceAndIsIdempotentForTheSameAuthorization() throws SQLException {
            allowCreator();
            execute(arranger, "DELETE FROM ops.price_command WHERE id = '" + COMMAND + "'");
            String sql = "SELECT ops.create_price_command('" + PriceWritePathFixture.RECOMMENDATION
                    + "', 0, '" + PriceWritePathFixture.USER + "', 'fixture-create')";
            String first = single(connection, sql);
            assertThat(single(connection, sql)).isEqualTo(first);
            assertThat(single(connection, "SELECT target_price::text || ':' || prior_price::text"
                    + " || ':' || currency_code FROM ops.price_command WHERE id = '" + first + "'"))
                    .isEqualTo("105.0000:100.0000:RUB");
            assertThat(count(connection, "SELECT count(*) FROM ops.price_command")).isEqualTo(1);
            assertThat(count(connection,"SELECT count(*) FROM ops.metadata_audit_event WHERE entity_id='"+first+"'"))
                    .isEqualTo(1);
            assertThat(single(connection,"SELECT actor_id||':'||correlation_id FROM ops.metadata_audit_event WHERE entity_id='"+first+"'"))
                    .isEqualTo(PriceWritePathFixture.USER+":fixture-create");
        }

        @ParameterizedTest
        @ValueSource(strings={"core.product","core.product_variant","core.organization","core.store"})
        void retiringAnOwnedEntityClosesTheExistingCommand(String table) throws SQLException {
            execute(arranger,"UPDATE "+table+" SET status='RETIRED'");
            assertThat(gateReasons(connection,COMMAND)).contains("COMMAND_AUTHORITY_MISMATCH");
            assertThatThrownBy(() -> lease(connection,COMMAND,WORKER,LEASE_SECONDS))
                    .satisfies(error -> assertThat(carriesSqlState(error,WRITE_GATE_CLOSED)).isTrue());
        }

        @Test
        void creationRefusesAnActorWithoutTheResourcesGrantEvenOnAnExistingCommand() {
            assertThatThrownBy(() -> single(connection, "SELECT ops.create_price_command('"
                    + PriceWritePathFixture.RECOMMENDATION + "', 0, '" + PriceWritePathFixture.USER
                    + "', 'fixture-create')"))
                    .satisfies(error -> assertThat(carriesSqlState(error, WRITE_GATE_CLOSED)).isTrue());
        }

        @Test
        void creationRefusesAStaleRecommendationVersion() throws SQLException {
            allowCreator();
            assertThatThrownBy(() -> single(connection, "SELECT ops.create_price_command('"
                    + PriceWritePathFixture.RECOMMENDATION + "', 100, '" + PriceWritePathFixture.USER
                    + "', 'fixture-create')"))
                    .satisfies(error -> assertThat(carriesSqlState(error, WRITE_GATE_CLOSED)).isTrue());
        }

        private void allowCreator() throws SQLException {
            execute(arranger, """
                    INSERT INTO iam.user_role_assignment (id, organization_id, user_id, role_code,
                        effective_from, status, created_at, updated_at)
                    VALUES (gen_random_uuid(), '%s', '%s', 'OWNER', now() - interval '1 hour',
                        'ACTIVE', now(), now())
                    """.formatted(PriceWritePathFixture.ORGANIZATION, PriceWritePathFixture.USER));
            execute(arranger, """
                    INSERT INTO iam.user_scope_grant (id, organization_id, user_id, action_code,
                        store_ref_id, effective_from, status, created_at, updated_at)
                    VALUES (gen_random_uuid(), '%s', '%s', 'PRICE_CHANGE_APPROVE', '%s',
                        now() - interval '1 hour', 'ACTIVE', now(), now())
                    """.formatted(PriceWritePathFixture.ORGANIZATION, PriceWritePathFixture.USER, STORE));
        }

        @Test
        void aFullyConfiguredCommandIsPermitted() throws SQLException {
            assertThat(gateReasons(connection, COMMAND)).isEmpty();
        }

        @Test
        void aDisabledGlobalSwitchBlocks() throws SQLException {
            execute(arranger, "UPDATE platform.feature_flag SET state = 'DISABLED'"
                    + " WHERE id = '" + GLOBAL_FLAG + "'");

            assertThat(gateReasons(connection, COMMAND)).contains("GLOBAL_SWITCH_DISABLED");
        }

        @Test
        void aDisabledCapabilitySwitchBlocks() throws SQLException {
            execute(arranger, "UPDATE platform.feature_flag SET state = 'DISABLED'"
                    + " WHERE id = '" + CAPABILITY_FLAG + "'");

            assertThat(gateReasons(connection, COMMAND))
                    .contains("CAPABILITY_SWITCH_DISABLED");
        }

        @Test
        void aScopedSwitchTurnedOffBlocksEvenWhenTheWiderOnesAreOn() throws SQLException {
            execute(arranger, """
                    INSERT INTO platform.feature_flag
                        (id, flag_code, flag_kind, scope_kind, store_id, state, status,
                         created_at, updated_at)
                    VALUES (gen_random_uuid(), 'price-change-write', 'WRITE_CAPABILITY',
                            'STORE', '%s', 'DISABLED', 'ACTIVE', now(), now())
                    """.formatted(STORE));

            assertThat(gateReasons(connection, COMMAND)).contains("SCOPED_SWITCH_DISABLED");
        }

        @Test
        void anEntityOffTheAllowlistBlocks() throws SQLException {
            execute(arranger, "UPDATE ops.pilot_allowlist_entry"
                    + " SET status = 'REVOKED', revoked_reason = 'pilot ended'"
                    + " WHERE id = '" + ALLOWLIST + "'");

            assertThat(gateReasons(connection, COMMAND)).contains("ENTITY_NOT_ALLOWLISTED");
        }

        @Test
        void anExpiredAuthorizationBlocks() throws SQLException {
            execute(arranger, "UPDATE ops.approval_decision"
                    + " SET decided_at = now() - interval '2 hours',"
                    + " scope_expires_at = now() - interval '1 minute'"
                    + " WHERE id = '" + APPROVAL + "'");

            assertThat(gateReasons(connection, COMMAND))
                    .contains("AUTHORIZATION_INVALID_OR_EXPIRED");
        }

        @Test
        void factsThatMovedSinceTheDecisionBlock() throws SQLException {
            // The approval named a digest of the facts it was made about. When
            // the proposal's facts move, the two no longer agree and the write
            // the person authorised is no longer the write being attempted.
            execute(arranger, "UPDATE ops.recommendation SET entity_version_digest ="
                    + " '9999999999999999999999999999999999999999999999999999999999999999'"
                    + " WHERE id = '" + PriceWritePathFixture.RECOMMENDATION + "'");

            assertThat(gateReasons(connection, COMMAND))
                    .contains("AUTHORIZATION_INVALID_OR_EXPIRED");
        }

        @Test
        void anUnresolvedMappingBlocks() throws SQLException {
            execute(arranger, "UPDATE core.listing_mapping SET status = 'ENDED',"
                    + " effective_to = now() WHERE id = '" + MAPPING + "'");

            assertThat(gateReasons(connection, COMMAND)).contains("MAPPING_UNRESOLVED");
        }

        @Test
        void anOpenMappingConflictBlocks() throws SQLException {
            execute(arranger, """
                    INSERT INTO core.mapping_conflict
                        (id, organization_id, platform_listing_variant_id, conflict_kind,
                         detail, state, detected_at, created_at, updated_at)
                    VALUES (gen_random_uuid(), '%s', '%s', 'DUPLICATE_BARCODE',
                            '{"note": "two variants share a barcode"}'::jsonb, 'OPEN',
                            now(), now(), now())
                    """.formatted(PriceWritePathFixture.ORGANIZATION, LISTING_VARIANT));

            assertThat(gateReasons(connection, COMMAND)).contains("MAPPING_CONFLICT_OPEN");
        }

        @Test
        void anUnverifiedCapabilityBlocks() throws SQLException {
            execute(arranger, "UPDATE platform.platform_capability"
                    + " SET verification_state = 'UNVERIFIED', last_verified_at = NULL,"
                    + " evidence_ref = NULL, verified_source_title = NULL"
                    + " WHERE id = '" + CAPABILITY + "'");

            assertThat(gateReasons(connection, COMMAND)).contains("CAPABILITY_NOT_VERIFIED");
        }

        @Test
        void aMissingExecutionGuardrailPassBlocks() throws SQLException {
            execute(arranger,
                    "DELETE FROM ops.guardrail_evaluation WHERE id = '" + GUARDRAIL + "'");

            assertThat(gateReasons(connection, COMMAND)).contains("GUARDRAIL_NOT_PASSED");
        }

        @Test
        void anApprovalGuardrailPassIsNotAnExecutionPass() throws SQLException {
            execute(arranger, "UPDATE ops.guardrail_evaluation SET purpose = 'APPROVAL'"
                    + " WHERE id = '" + GUARDRAIL + "'");

            assertThat(gateReasons(connection, COMMAND)).contains("GUARDRAIL_NOT_PASSED");
        }

        @Test
        void aClosedGateRefusesTheLeaseRatherThanTheCall() throws SQLException {
            execute(arranger, "UPDATE platform.feature_flag SET state = 'DISABLED'"
                    + " WHERE id = '" + GLOBAL_FLAG + "'");

            assertThatThrownBy(() -> lease(connection, COMMAND, WORKER, LEASE_SECONDS))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(WRITE_GATE_CLOSED);
            assertThat(stateOf(connection, COMMAND)).isEqualTo("PENDING");
        }
    }

    @Nested
    @DisplayName("TC-WRITE-102 only the worker that holds a command may move it")
    class LeaseAndFence {

        @Test
        void leasingBumpsTheFenceAndClaimsTheCommand() throws SQLException {
            long fence = lease(connection, COMMAND, WORKER, LEASE_SECONDS);

            assertThat(fence).isEqualTo(2L);
            assertThat(stateOf(connection, COMMAND)).isEqualTo("LEASED");
        }

        @Test
        void aSecondLeaseOnAClaimedCommandIsRefused() throws SQLException {
            lease(connection, COMMAND, WORKER, LEASE_SECONDS);

            assertThatThrownBy(() -> lease(connection, COMMAND, OTHER_WORKER, LEASE_SECONDS))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(TRANSITION_NOT_ALLOWED);
        }

        @Test
        void aStaleFenceWritesNothing() throws SQLException {
            long fence = lease(connection, COMMAND, WORKER, LEASE_SECONDS);

            assertThatThrownBy(() -> transition(connection, COMMAND, fence - 1, WORKER,
                    "EXECUTING", null, null, null))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(AUTHORITY_LOST);
            assertThat(stateOf(connection, COMMAND)).isEqualTo("LEASED");
        }

        @Test
        void anotherWorkerHoldingTheRightFenceStillWritesNothing() throws SQLException {
            long fence = lease(connection, COMMAND, WORKER, LEASE_SECONDS);

            assertThatThrownBy(() -> transition(connection, COMMAND, fence, OTHER_WORKER,
                    "EXECUTING", null, null, null))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(AUTHORITY_LOST);
        }

        @Test
        void anExpiredLeaseWritesNothing() throws SQLException {
            long fence = lease(connection, COMMAND, WORKER, LEASE_SECONDS);
            execute(arranger, "UPDATE ops.price_command"
                    + " SET lease_expires_at = now() - interval '1 second'"
                    + " WHERE id = '" + COMMAND + "'");

            assertThatThrownBy(() -> transition(connection, COMMAND, fence, WORKER,
                    "EXECUTING", null, null, null))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(AUTHORITY_LOST);
        }

        @Test
        void aLeaseLongerThanTheCeilingIsRefused() {
            assertThatThrownBy(() -> lease(connection, COMMAND, WORKER, 901))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(LEASE_INVALID);
        }
    }

    @Nested
    @DisplayName("TC-WRITE-103 platform acceptance is not success")
    class SuccessRequiresReadback {

        @Test
        void aCommandCannotSucceedWithoutAReadback() throws SQLException {
            long fence = reachReadbackPending();

            assertThatThrownBy(() -> transition(connection, COMMAND, fence, WORKER,
                    "SUCCEEDED", null, null, null))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(SUCCESS_WITHOUT_READBACK);
        }

        @Test
        void aReadbackObservingSomethingElseCannotBeClaimedAsSuccess() throws SQLException {
            long fence = reachReadbackPending();
            UUID attempt = recordAttempt(connection, COMMAND, 1, "READBACK", fence, WORKER,
                    "ACCEPTED");
            UUID readback = recordReadback(connection, COMMAND, attempt, "100.0000",
                    "MATCHES_PRIOR");

            assertThatThrownBy(() -> transition(connection, COMMAND, fence, WORKER,
                    "SUCCEEDED", null, null, readback))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(SUCCESS_WITHOUT_READBACK);
        }

        @Test
        void aReadbackFromAnotherCommandCannotBeBorrowed() throws SQLException {
            long fence = reachReadbackPending();
            UUID attempt = recordAttempt(connection, COMMAND, 1, "READBACK", fence, WORKER,
                    "ACCEPTED");
            recordReadback(connection, COMMAND, attempt, "105.0000", "MATCHES_TARGET");

            assertThatThrownBy(() -> transition(connection, COMMAND, fence, WORKER,
                    "SUCCEEDED", null, null, UUID.randomUUID()))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(SUCCESS_WITHOUT_READBACK);
        }

        @Test
        void aMatchingReadbackCompletesTheCommand() throws SQLException {
            long fence = reachReadbackPending();
            UUID attempt = recordAttempt(connection, COMMAND, 1, "READBACK", fence, WORKER,
                    "ACCEPTED");
            UUID readback = recordReadback(connection, COMMAND, attempt, "105.0000",
                    "MATCHES_TARGET");

            String state = transition(connection, COMMAND, fence, WORKER, "SUCCEEDED", null,
                    null, readback);

            assertThat(state).isEqualTo("SUCCEEDED");
            assertThat(terminalAtIsSet()).isTrue();
        }
    }

    @Nested
    @DisplayName("TC-WRITE-104 an unknown result is never repeated")
    class UnknownResults {

        @Test
        void thereIsNoTransitionFromUnknownBackToExecuting() throws SQLException {
            long fence = lease(connection, COMMAND, WORKER, LEASE_SECONDS);
            transition(connection, COMMAND, fence, WORKER, "EXECUTING", null, null, null);
            transition(connection, COMMAND, fence, WORKER, "UNKNOWN_REQUIRES_READBACK", null,
                    null, null);

            assertThatThrownBy(() -> transition(connection, COMMAND, fence, WORKER,
                    "EXECUTING", null, null, null))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(TRANSITION_NOT_ALLOWED);
        }

        @Test
        void theOnlyWayOutOfUnknownIsAReadbackOrAPerson() throws SQLException {
            assertThat(allowedFrom("UNKNOWN_REQUIRES_READBACK"))
                    .containsExactlyInAnyOrder("READBACK_PENDING", "MANUAL_RESOLUTION");
        }

        @Test
        void anUnknownResultReleasesTheLeaseSoAWorkerCannotSitOnIt() throws SQLException {
            long fence = lease(connection, COMMAND, WORKER, LEASE_SECONDS);
            transition(connection, COMMAND, fence, WORKER, "EXECUTING", null, null, null);
            transition(connection, COMMAND, fence, WORKER, "UNKNOWN_REQUIRES_READBACK", null,
                    null, null);

            assertThat(leaseOwner()).isNull();
        }
    }

    @Nested
    @DisplayName("TC-WRITE-105 a restore may not overwrite a later change")
    class Compensation {

        @ParameterizedTest
        @ValueSource(strings={"ABSENT","REJECTED"})
        void matchingTargetWithoutAPossiblyAppliedWriteCannotAuthorizeCompensation(String applyState) throws SQLException {
            long fence = lease(connection,COMMAND,WORKER,LEASE_SECONDS);
            transition(connection,COMMAND,fence,WORKER,"EXECUTING",null,null,null);
            if (applyState.equals("REJECTED")) {
                UUID apply = recordAttempt(connection,COMMAND,1,"APPLY",fence,WORKER,"ACCEPTED");
                PriceWritePathFixture.completeResponse(connection,apply,"{\"error\":\"synthetic rejection\"}",400);
                assertThat(single(connection,"SELECT outcome_class FROM ops.price_command_attempt WHERE id='"+apply+"'"))
                        .isEqualTo("REJECTED");
            }
            transition(connection,COMMAND,fence,WORKER,"READBACK_PENDING",null,null,null);
            UUID readback = recordAttempt(connection,COMMAND,1,"READBACK",fence,WORKER,"ACCEPTED");
            recordReadback(connection,COMMAND,readback,"105.0000","MATCHES_TARGET");
            transition(connection,COMMAND,fence,WORKER,"READBACK_MISMATCH",null,null,null);
            assertThatThrownBy(() -> transition(connection,COMMAND,fence,WORKER,"COMPENSATION_PENDING",null,null,null))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException)failure).getSQLState()).isEqualTo(COMPENSATION_UNSAFE);
        }

        @Test
        void aRestoreIsRefusedWhenSomethingElseMovedThePrice() throws SQLException {
            long fence = reachReadbackMismatch("140.0000", "DIFFERENT");

            assertThatThrownBy(() -> transition(connection, COMMAND, fence, WORKER,
                    "COMPENSATION_PENDING", null, null, null))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(COMPENSATION_UNSAFE);
        }

        @ParameterizedTest
        @ValueSource(ints={200,503})
        void aRestoreIsAuthorisedWhileThePlatformStillHoldsWhatThisCommandWrote(int applyStatus)
                throws SQLException {
            long fence = reachReadbackMismatch("105.0000", "MATCHES_TARGET",applyStatus);

            String state = transition(connection, COMMAND, fence, WORKER,
                    "COMPENSATION_PENDING", null, null, null);

            assertThat(state).isEqualTo("COMPENSATION_PENDING");
        }

        @Test
        void aRestoreIsNotCompleteUntilThePriorValueIsObserved() throws SQLException {
            long fence = reachReadbackMismatch("105.0000", "MATCHES_TARGET");
            transition(connection, COMMAND, fence, WORKER, "COMPENSATION_PENDING", null, null,
                    null);
            long restoreFence = leaseCompensation();

            assertThatThrownBy(() -> transition(connection, COMMAND, restoreFence, WORKER,
                    "COMPENSATED", null, null, null))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(COMPENSATION_WITHOUT_READBACK);
        }

        @Test
        void anObservedRestoreCompletesTheCommand() throws SQLException {
            long fence = reachReadbackMismatch("105.0000", "MATCHES_TARGET");
            transition(connection, COMMAND, fence, WORKER, "COMPENSATION_PENDING", null, null,
                    null);
            long restoreFence = leaseCompensation();
            UUID fresh = recordAttempt(connection, COMMAND, 2, "READBACK", restoreFence, WORKER, "ACCEPTED");
            recordReadback(connection, COMMAND, fresh, "105.0000", "MATCHES_TARGET");
            UUID restore = recordAttempt(connection, COMMAND, 3, "RESTORE", restoreFence, WORKER, "ACCEPTED");
            PriceWritePathFixture.completeResponse(connection, restore, "{\"accepted\":true}");
            UUID attempt = recordAttempt(connection, COMMAND, 4, "READBACK", restoreFence, WORKER, "ACCEPTED");
            recordReadback(connection, COMMAND, attempt, "100.0000", "MATCHES_PRIOR");

            String state = transition(connection, COMMAND, restoreFence, WORKER, "COMPENSATED",
                    null, null, null);

            assertThat(state).isEqualTo("COMPENSATED");
        }

        @Test
        void aRestoreIsRefusedWhenTheGateHasSinceClosed() throws SQLException {
            long fence = reachReadbackMismatch("105.0000", "MATCHES_TARGET");
            transition(connection, COMMAND, fence, WORKER, "COMPENSATION_PENDING", null, null,
                    null);
            execute(arranger, "UPDATE platform.feature_flag SET state = 'DISABLED'"
                    + " WHERE id = '" + GLOBAL_FLAG + "'");

            assertThatThrownBy(this::leaseCompensation)
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(WRITE_GATE_CLOSED);
        }

        private long leaseCompensation() throws SQLException {
            try (var statement = connection.prepareStatement(
                    "SELECT ops.lease_price_compensation(?, ?, ?)")) {
                statement.setObject(1, COMMAND);
                statement.setString(2, WORKER);
                statement.setInt(3, LEASE_SECONDS);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        }
    }

    @Nested
    @DisplayName("TC-WRITE-106 a worker that vanished hands its work back")
    class LeaseRecovery {

        @Test
        void aClaimedButUncalledCommandReturnsToTheQueue() throws SQLException {
            lease(connection, COMMAND, WORKER, LEASE_SECONDS);
            expireLease();

            assertThat(recoverLeases(connection)).isEqualTo(1);
            assertThat(stateOf(connection, COMMAND)).isEqualTo("PENDING");
        }

        @Test
        void aCommandThatMayHaveWrittenBecomesUnknownRatherThanRetried()
                throws SQLException {
            long fence = lease(connection, COMMAND, WORKER, LEASE_SECONDS);
            transition(connection, COMMAND, fence, WORKER, "EXECUTING", null, null, null);
            expireLease();

            assertThat(recoverLeases(connection)).isEqualTo(1);
            assertThat(stateOf(connection, COMMAND)).isEqualTo("UNKNOWN_REQUIRES_READBACK");
        }

        @Test
        void aLiveLeaseIsLeftAlone() throws SQLException {
            lease(connection, COMMAND, WORKER, LEASE_SECONDS);

            assertThat(recoverLeases(connection)).isZero();
            assertThat(stateOf(connection, COMMAND)).isEqualTo("LEASED");
        }

        private void expireLease() throws SQLException {
            execute(arranger, "UPDATE ops.price_command"
                    + " SET lease_expires_at = now() - interval '1 second'"
                    + " WHERE id = '" + COMMAND + "'");
        }
    }

    @Nested
    @DisplayName("TC-WRITE-107 an attempt records the call that was started")
    class AttemptImmutability {

        @ParameterizedTest
        @ValueSource(strings={"{}","{\"accepted\":false}","{\"accepted\":\"true\"}",
                "{\"accepted\":true,\"accepted\":false}","{\"accepted\":true} {}","null"})
        void aCallerCannotLabelAmbiguousBytesAccepted(String body) throws SQLException {
            long fence=lease(connection,COMMAND,WORKER,LEASE_SECONDS);
            transition(connection,COMMAND,fence,WORKER,"EXECUTING",null,null,null);
            UUID attempt=recordAttempt(connection,COMMAND,1,"APPLY",fence,WORKER,"IN_FLIGHT");
            PriceWritePathFixture.completeResponse(connection,attempt,body);
            assertThat(single(connection,"SELECT outcome_class FROM ops.price_command_attempt WHERE id='"+attempt+"'"))
                    .isEqualTo("UNKNOWN_STATE");
            assertThat(single(connection,"SELECT count(*) FROM raw.price_response_observation WHERE attempt_id='"+attempt+"'"))
                    .isEqualTo("1");
        }

        @Test
        void responseInterpretationUsesThePreparedVersionEvenAfterMaintenance() throws SQLException {
            long fence=reachReadbackPending();
            UUID attempt=recordAttempt(connection,COMMAND,1,"READBACK",fence,WORKER,"IN_FLIGHT");
            String pinnedVersion=single(connection,"SELECT operation_snapshot->'operation'->>'version' FROM ops.price_command_attempt WHERE id='"+attempt+"'");
            execute(arranger,"UPDATE platform.capability_operation SET observed_price_pointer='/unexpected', version=version+1 WHERE capability_id='"
                    +CAPABILITY+"' AND operation='READBACK'");
            PriceWritePathFixture.completeResponse(connection,attempt,"{\"price\":\"105.0000\",\"currency\":\"RUB\",\"unexpected\":\"100.0000\"}");
            assertThat(single(connection,"SELECT observed_price FROM raw.price_response_observation WHERE attempt_id='"+attempt+"'"))
                    .isEqualTo("105.0000");
            assertThat(single(connection,"SELECT operation_version FROM raw.price_response_observation WHERE attempt_id='"+attempt+"'"))
                    .isEqualTo(pinnedVersion);
            assertThat(single(connection,"SELECT operation_snapshot=platform.price_operation_snapshot('"+CAPABILITY+"','READBACK') FROM ops.price_command_attempt WHERE id='"+attempt+"'"))
                    .isEqualTo("f");
        }

        @ParameterizedTest
        @ValueSource(strings={"accepted_value=NULL", "accepted_value='{}'::jsonb",
                "request_template='{\"price\":\"{targetPrice}\"}'",
                "endpoint_id=(SELECT endpoint_id FROM platform.capability_operation WHERE capability_id='00000000-0000-0000-0000-000000000000')"})
        void incompleteVerifiedMutationDefinitionsAreRejected(String mutation) {
            assertThatThrownBy(() -> execute(arranger,"UPDATE platform.capability_operation SET "+mutation
                    +" WHERE capability_id='"+CAPABILITY+"' AND operation='APPLY'"))
                    .isInstanceOf(SQLException.class);
        }

        @Test
        void anApplyCannotUseAReadbackEndpoint() {
            assertThatThrownBy(() -> execute(arranger,"UPDATE platform.capability_operation SET endpoint_id="
                    +"(SELECT endpoint_id FROM platform.capability_operation WHERE capability_id='"+CAPABILITY+"' AND operation='READBACK')"
                    +" WHERE capability_id='"+CAPABILITY+"' AND operation='APPLY'"))
                    .isInstanceOf(SQLException.class).extracting(f -> ((SQLException)f).getSQLState()).isEqualTo("MO036");
        }

        @ParameterizedTest
        @ValueSource(strings={"operation_function='READ_DATA'", "http_method='DELETE'", "read_write_class='READ'"})
        void endpointSemanticDriftClosesTheNextDispatch(String mutation) throws SQLException {
            long fence=lease(connection,COMMAND,WORKER,LEASE_SECONDS);
            transition(connection,COMMAND,fence,WORKER,"EXECUTING",null,null,null);
            execute(arranger,"UPDATE platform.platform_endpoint SET "+mutation+" WHERE id="
                    +"(SELECT endpoint_id FROM platform.capability_operation WHERE capability_id='"+CAPABILITY+"' AND operation='APPLY')");
            assertThatThrownBy(() -> recordAttempt(connection,COMMAND,1,"APPLY",fence,WORKER,"IN_FLIGHT"))
                    .isInstanceOf(SQLException.class).extracting(f -> ((SQLException)f).getSQLState()).isEqualTo("MO033");
        }

        @Test
        void anAttemptIsCompletedExactlyOnce() throws SQLException {
            long fence = lease(connection, COMMAND, WORKER, LEASE_SECONDS);
            transition(connection, COMMAND, fence, WORKER, "EXECUTING", null, null, null);
            UUID attempt = recordAttempt(connection, COMMAND, 1, "APPLY", fence, WORKER, "IN_FLIGHT");
            PriceWritePathFixture.completeResponse(connection, attempt, "{\"accepted\":true}");
            assertThatThrownBy(() -> PriceWritePathFixture.completeResponse(connection, attempt,
                    "{\"accepted\":false}"))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(PriceWritePathFixture.ATTEMPT_ALREADY_COMPLETED);
        }

        @ParameterizedTest
        @ValueSource(strings={"{\"accepted\":true}","{\"accepted\":false}","{}"})
        void aSecondApplyIsRefusedEvenIfSqlKeepsTheCommandExecuting(String body) throws SQLException {
            long fence=lease(connection,COMMAND,WORKER,LEASE_SECONDS);
            transition(connection,COMMAND,fence,WORKER,"EXECUTING",null,null,null);
            UUID attempt=recordAttempt(connection,COMMAND,1,"APPLY",fence,WORKER,"IN_FLIGHT");
            PriceWritePathFixture.completeResponse(connection,attempt,body);
            assertThatThrownBy(() -> recordAttempt(connection,COMMAND,2,"APPLY",fence,WORKER,"IN_FLIGHT"))
                    .isInstanceOf(SQLException.class).extracting(f -> ((SQLException)f).getSQLState()).isEqualTo("MO030");
        }

        @Test
        void theApplicationCannotChangeACommandRowDirectly() throws SQLException {
            assertThatThrownBy(() -> execute(connection,
                    "UPDATE ops.price_command SET state = 'SUCCEEDED' WHERE id = '"
                            + COMMAND + "'"))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
        }

        @Test
        void theApplicationCannotDeleteAReadback() throws SQLException {
            long fence = reachReadbackPending();
            UUID attempt = recordAttempt(connection, COMMAND, 1, "READBACK", fence, WORKER,
                    "ACCEPTED");
            UUID readback = recordReadback(connection, COMMAND, attempt, "105.0000",
                    "MATCHES_TARGET");

            assertThatThrownBy(() -> execute(connection,
                    "DELETE FROM ops.price_command_readback WHERE id = '" + readback + "'"))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
    }

    @Nested
    @DisplayName("TC-WRITE-108 a bounded authorization is bounded in every dimension")
    class PolicyAuthorization {

        @Test
        void aChangeWithinEveryBoundIsPermitted() throws SQLException {
            // The answer is what is left after this use, not before it, so an
            // operator reading it knows how much room remains.
            assertThat(consume(connection, AUTHORIZATION, "0.050000", STORE, VARIANT))
                    .isEqualTo(1);
        }

        @Test
        void aChangeBeyondTheMagnitudeBoundIsRefused() {
            assertThatThrownBy(() ->
                    consume(connection, AUTHORIZATION, "0.200000", STORE, VARIANT))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(AUTHORIZATION_BOUND_EXCEEDED);
        }

        @Test
        void anAuthorizationForAnotherStoreIsRefused() {
            assertThatThrownBy(() -> consume(connection, AUTHORIZATION, "0.050000",
                    UUID.randomUUID(), VARIANT))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(AUTHORIZATION_SCOPE_MISMATCH);
        }

        @Test
        void spendingTheLastUseRetiresTheAuthorization() throws SQLException {
            assertThat(consume(connection, AUTHORIZATION, "0.050000", STORE, VARIANT))
                    .isEqualTo(1);
            assertThat(consume(connection, AUTHORIZATION, "0.050000", STORE, VARIANT))
                    .isZero();

            // Spending the last use moves the row to EXHAUSTED, so the next
            // attempt is refused for what the authorization now is rather than
            // for a counter comparison. That is the more useful answer: an
            // operator sees a state they can look up.
            assertThatThrownBy(() ->
                    consume(connection, AUTHORIZATION, "0.050000", STORE, VARIANT))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(AUTHORIZATION_NOT_USABLE);
        }

        @Test
        void aCounterThatHasReachedItsCeilingIsRefusedEvenWhileStillActive()
                throws SQLException {
            // The status and the counter are separate guards. A row left ACTIVE
            // with no uses left must still refuse, or a status that was never
            // updated would become a way to spend an authorization twice.
            execute(arranger, "UPDATE ops.policy_authorization"
                    + " SET used_count = max_uses WHERE id = '" + AUTHORIZATION + "'");

            assertThatThrownBy(() ->
                    consume(connection, AUTHORIZATION, "0.050000", STORE, VARIANT))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(AUTHORIZATION_EXHAUSTED);
        }

        @Test
        void aRevokedAuthorizationCannotBeSpent() throws SQLException {
            execute(arranger, "UPDATE ops.policy_authorization"
                    + " SET status = 'REVOKED', revoked_reason = 'withdrawn'"
                    + " WHERE id = '" + AUTHORIZATION + "'");

            assertThatThrownBy(() ->
                    consume(connection, AUTHORIZATION, "0.050000", STORE, VARIANT))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(AUTHORIZATION_NOT_USABLE);
        }

        @Test
        void theApplicationCannotMoveTheCounterItself() throws SQLException {
            assertThatThrownBy(() -> execute(connection,
                    "UPDATE ops.policy_authorization SET used_count = 0 WHERE id = '"
                            + AUTHORIZATION + "'"))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
        }

        @Test
        void anAuthorizationThatDoesNotExistIsRefused() {
            assertThatThrownBy(() ->
                    consume(connection, UUID.randomUUID(), "0.050000", STORE, VARIANT))
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo(PriceWritePathFixture.AUTHORIZATION_ABSENT);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private long reachReadbackPending() throws SQLException {
        long fence = lease(connection, COMMAND, WORKER, LEASE_SECONDS);
        transition(connection, COMMAND, fence, WORKER, "EXECUTING", null, null, null);
        transition(connection, COMMAND, fence, WORKER, "READBACK_PENDING", null, null, null);
        return fence;
    }

    private long reachReadbackMismatch(String observedPrice, String matchState)
            throws SQLException {
        return reachReadbackMismatch(observedPrice,matchState,200);
    }

    private long reachReadbackMismatch(String observedPrice,String matchState,int applyStatus) throws SQLException {
        long fence = lease(connection,COMMAND,WORKER,LEASE_SECONDS);
        transition(connection,COMMAND,fence,WORKER,"EXECUTING",null,null,null);
        UUID apply = recordAttempt(connection,COMMAND,1,"APPLY",fence,WORKER,"ACCEPTED");
        PriceWritePathFixture.completeResponse(connection,apply,"{\"accepted\":true}",applyStatus);
        assertThat(single(connection,"SELECT outcome_class FROM ops.price_command_attempt WHERE id='"+apply+"'"))
                .isEqualTo(applyStatus==200 ? "ACCEPTED" : "UNKNOWN_STATE");
        transition(connection,COMMAND,fence,WORKER,"READBACK_PENDING",null,null,null);
        UUID attempt = recordAttempt(connection, COMMAND, 1, "READBACK", fence, WORKER,
                "ACCEPTED");
        recordReadback(connection, COMMAND, attempt, observedPrice, matchState);
        transition(connection, COMMAND, fence, WORKER, "READBACK_MISMATCH", null, null, null);
        return fence;
    }

    private boolean terminalAtIsSet() throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT terminal_at IS NOT NULL FROM ops.price_command WHERE id = ?")) {
            statement.setObject(1, COMMAND);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    private String leaseOwner() throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT lease_owner FROM ops.price_command WHERE id = ?")) {
            statement.setObject(1, COMMAND);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private java.util.List<String> allowedFrom(String state) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT to_state FROM ops.price_command_transition WHERE from_state = ?")) {
            statement.setString(1, state);
            try (var rows = statement.executeQuery()) {
                java.util.List<String> states = new java.util.ArrayList<>();
                while (rows.next()) {
                    states.add(rows.getString(1));
                }
                return states;
            }
        }
    }
}
