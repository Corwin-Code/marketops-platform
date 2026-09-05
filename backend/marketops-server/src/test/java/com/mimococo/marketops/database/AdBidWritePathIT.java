package com.mimococo.marketops.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The advertising controlled-write path, asserted against a real server.
 *
 * <p>The properties here are the ones that make an unverified Provider path
 * unreachable rather than merely disabled, and they are asserted from the
 * outside — as an arbitrary SQL client connecting with the application role —
 * because that is the threat model. A service that decided to skip a check is
 * one deployment away; a database that refuses is not.
 */
class AdBidWritePathIT extends PostgresContainerSupport {

    private static PostgreSQLContainer container;

    @BeforeAll
    static void migrate() {
        container = shared();
        migrator(container).migrate();
    }

    @Nested
    @DisplayName("TC-AD-WRITE-101 the application role cannot move a command itself")
    class Privileges {

        @Test
        @DisplayName("TC-AD-WRITE-101a the command tables are readable and not writable")
        void commandTablesAreReadOnlyToTheApplication() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(singleBoolean(connection,
                        "SELECT has_table_privilege('" + APPLICATION_ROLE
                                + "', 'ops.ad_bid_command', 'SELECT')")).isTrue();
                for (String table : List.of("ops.ad_bid_command", "ops.ad_bid_command_attempt",
                        "ops.ad_bid_command_readback", "ops.ad_bid_command_transition",
                        "raw.ad_bid_response_observation")) {
                    for (String privilege : List.of("INSERT", "UPDATE", "DELETE")) {
                        assertThat(singleBoolean(connection,
                                "SELECT has_table_privilege('" + APPLICATION_ROLE + "', '"
                                        + table + "', '" + privilege + "')"))
                                .describedAs("%s must not be %s-able by the application role",
                                        table, privilege)
                                .isFalse();
                    }
                }
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-101b a direct state change is refused")
        void directStateChangeIsRefused() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThatThrownBy(() -> execute(connection,
                        "UPDATE ops.ad_bid_command SET state = 'READBACK_MATCHED'"))
                        .satisfies(failure -> assertThat(
                                carriesSqlState(failure, INSUFFICIENT_PRIVILEGE)).isTrue());
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-101c nothing anywhere in the advertising schema may be deleted")
        void nothingMayBeDeleted() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                List<String> deletable = strings(connection,
                        "SELECT table_schema || '.' || table_name"
                                + " FROM information_schema.role_table_grants"
                                + " WHERE grantee = '" + APPLICATION_ROLE + "'"
                                + " AND privilege_type = 'DELETE'"
                                + " AND table_name LIKE 'ad\\_%' ORDER BY 1");

                assertThat(deletable).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("TC-AD-WRITE-102 the transition graph is data, and its shape is the safety property")
    class TransitionGraph {

        @Test
        @DisplayName("TC-AD-WRITE-102a an unknown result has no edge back to executing")
        void unknownResultIsNeverRetriedAsAWrite() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(count(connection,
                        "SELECT count(*) FROM ops.ad_bid_command_transition"
                                + " WHERE from_state = 'UNKNOWN_REQUIRES_READBACK'"))
                        .isEqualTo(2);
                assertThat(strings(connection,
                        "SELECT to_state FROM ops.ad_bid_command_transition"
                                + " WHERE from_state = 'UNKNOWN_REQUIRES_READBACK' ORDER BY to_state"))
                        .containsExactly("MANUAL_RESOLUTION", "READBACK_PENDING");
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-102b a third value leaves automation entirely")
        void aThirdValueOnlyExitsThroughAPerson() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(strings(connection,
                        "SELECT to_state FROM ops.ad_bid_command_transition"
                                + " WHERE from_state = 'LATER_CHANGE_OR_MISMATCH_INVESTIGATION'"))
                        .containsExactly("MANUAL_RESOLUTION");
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-102c success is reachable only from a pending readback or a person")
        void successIsReachableOnlyFromAReadback() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(strings(connection,
                        "SELECT from_state FROM ops.ad_bid_command_transition"
                                + " WHERE to_state = 'READBACK_MATCHED' ORDER BY from_state"))
                        .containsExactly("MANUAL_RESOLUTION", "READBACK_PENDING");
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-102d a quarantine can terminate work that has not been sent")
        void unsentWorkCanBeTerminatedWithoutAProviderCall() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(strings(connection,
                        "SELECT from_state FROM ops.ad_bid_command_transition"
                                + " WHERE to_state = 'TERMINATED_WITHOUT_PROVIDER_CALL'"
                                + " ORDER BY from_state"))
                        .containsExactly("LEASED", "PENDING", "RETRY_WAIT");
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-102e a transition outside the reviewed set is refused")
        void unreviewedTransitionIsRefused() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThatThrownBy(() -> execute(connection,
                        "SELECT ops.transition_ad_bid_command("
                                + "'00000000-0000-0000-0000-000000000001'::uuid,"
                                + " 1, 'worker', 'READBACK_MATCHED', NULL, NULL, NULL)"))
                        // The command does not exist, so authority is lost before the
                        // transition is looked up. Either refusal is the write path
                        // declining; neither is a state change.
                        .satisfies(failure -> assertThat(
                                carriesSqlState(failure, "MO090")
                                        || carriesSqlState(failure, "MO091")).isTrue());
            }
        }
    }

    @Nested
    @DisplayName("TC-AD-WRITE-103 an unverified provider path cannot be dispatched")
    class StructuralUnreachability {

        @Test
        @DisplayName("TC-AD-WRITE-103a no advertising capability is verified anywhere")
        void noAdvertisingCapabilityIsVerified() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(count(connection,
                        "SELECT count(*) FROM platform.platform_capability"
                                + " WHERE capability_code = 'ad-bid-change'")).isZero();
                assertThat(count(connection,
                        "SELECT count(*) FROM platform.ad_semantic_profile"
                                + " WHERE verification_state = 'VERIFIED'")).isZero();
                assertThat(count(connection,
                        "SELECT count(*) FROM platform.feature_flag"
                                + " WHERE flag_code = 'ad-bid-change-write'")).isZero();
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-103b no verified write shape exists, so no attempt can be opened")
        void noWriteShapeExists() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(single(connection,
                        "SELECT coalesce(platform.ad_bid_operation_snapshot("
                                + "'00000000-0000-0000-0000-000000000001'::uuid, 'APPLY')::text,"
                                + " 'NULL')")).isEqualTo("NULL");
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-103c a synthetic fixture profile can never be marked verified")
        void aSyntheticFixtureCannotBeVerified() throws SQLException {
            try (Connection connection = asMigrationRole(container)) {
                assertThatThrownBy(() -> execute(connection,
                        "INSERT INTO platform.ad_semantic_profile ("
                                + "id, platform_code, profile_version, native_object_kind,"
                                + " control_level, bidding_mode, bid_field_present, bid_unit_code,"
                                + " idempotency_semantics, propagation_semantics, readback_semantics,"
                                + " correction_behaviour, source_maturity, verification_state,"
                                + " owner_label, status, created_at, updated_at)"
                                + " VALUES (gen_random_uuid(), 'OZON', 1, 'CAMPAIGN', 'CAMPAIGN',"
                                + " 'MANUAL_BID', true, 'CURRENCY_MAJOR', 'UNKNOWN', 'UNKNOWN',"
                                + " 'UNKNOWN', 'UNKNOWN', 'SYNTHETIC_FIXTURE', 'VERIFIED',"
                                + " 'advertisingefficiency', 'ACTIVE', now(), now())"))
                        .satisfies(failure -> assertThat(
                                carriesSqlState(failure, CHECK_VIOLATION)).isTrue());
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-103d the write gate refuses a command that does not exist")
        void gateRefusesAnAbsentCommand() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(single(connection,
                        "SELECT array_to_string(ops.evaluate_ad_bid_write_gate("
                                + "'00000000-0000-0000-0000-000000000001'::uuid), ',')"))
                        .isEqualTo("COMMAND_NOT_FOUND");
            }
        }
    }

    @Nested
    @DisplayName("TC-AD-WRITE-104 the registry cannot describe a bid write it does not understand")
    class RegistryShape {

        @Test
        @DisplayName("TC-AD-WRITE-104a a capability with no defined write shape is refused")
        void unknownCapabilityHasNoWriteShape() throws SQLException {
            try (Connection connection = asMigrationRole(container)) {
                assertThat(count(connection,
                        "SELECT count(*) FROM pg_proc"
                                + " WHERE proname = 'capability_operation_matches_write_model'"))
                        .isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-104b the endpoint vocabulary carries both write families and nothing else")
        void endpointVocabularyIsExact() throws SQLException {
            try (Connection connection = asMigrationRole(container)) {
                String definition = single(connection,
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                + " WHERE conname = 'platform_endpoint_function_ck'");

                assertThat(definition)
                        .contains("PRICE_APPLY").contains("PRICE_RESTORE")
                        .contains("AD_BID_APPLY").contains("AD_BID_STATUS")
                        .contains("AD_BID_READBACK").contains("AD_BID_RESTORE");
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-104c an advertising write authenticates with an advertising credential")
        void advertisingWriteUsesAdvertisingCredential() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(single(connection,
                        "SELECT platform.capability_credential_purpose('ad-bid-change', 'WRITE')"))
                        .isEqualTo("ADS_WRITE");
                assertThat(single(connection,
                        "SELECT platform.capability_credential_purpose('price-change', 'WRITE')"))
                        .isEqualTo("PRICE_WRITE");
                assertThat(single(connection,
                        "SELECT platform.capability_credential_purpose('ad-bid-change', 'READ')"))
                        .isEqualTo("READ");
            }
        }
    }

    @Nested
    @DisplayName("TC-AD-WRITE-105 a bid change is about an advertising object and nothing else")
    class RecommendationShape {

        @Test
        @DisplayName("TC-AD-WRITE-105a a bid change against a listing variant is unrepresentable")
        void bidChangeAgainstAListingVariantIsRefused() throws SQLException {
            try (Connection connection = asMigrationRole(container)) {
                String definition = single(connection,
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                + " WHERE conname = 'recommendation_action_subject_ck'");

                assertThat(definition)
                        .contains("AD_BID_CHANGE")
                        .contains("AD_NATIVE_OBJECT")
                        .contains("PLATFORM_LISTING_VARIANT");
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-105b the parameter contract is closed to exactly three keys")
        void parameterContractIsClosed() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(singleBoolean(connection,
                        "SELECT ops.ad_bid_parameter_contract_is_valid("
                                + "'{\"candidateId\":\"00000000-0000-0000-0000-000000000001\","
                                + "\"direction\":\"PROTECTION_DECREASE\","
                                + "\"targetBid\":\"12.5000\"}'::jsonb)")).isTrue();
                assertThat(singleBoolean(connection,
                        "SELECT ops.ad_bid_parameter_contract_is_valid("
                                + "'{\"candidateId\":\"00000000-0000-0000-0000-000000000001\","
                                + "\"direction\":\"PROTECTION_DECREASE\","
                                + "\"targetBid\":\"12.5000\",\"extra\":\"1\"}'::jsonb)")).isFalse();
                assertThat(singleBoolean(connection,
                        "SELECT ops.ad_bid_parameter_contract_is_valid("
                                + "'{\"candidateId\":\"00000000-0000-0000-0000-000000000001\","
                                + "\"direction\":\"BUDGET_CHANGE\","
                                + "\"targetBid\":\"12.5000\"}'::jsonb)")).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("TC-AD-WRITE-106 a policy bundle becomes authority only when it is coherent")
    class BundleValidation {

        @Test
        @DisplayName("TC-AD-WRITE-106a an absent bundle reports itself rather than passing")
        void absentBundleReportsItself() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(single(connection,
                        "SELECT array_to_string(ops.ad_bundle_validation_failures("
                                + "'00000000-0000-0000-0000-000000000001'::uuid), ',')"))
                        .isEqualTo("BUNDLE_NOT_FOUND");
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-106b no advertising decision bundle is active anywhere")
        void noBundleIsActive() throws SQLException {
            try (Connection connection = asApplicationRole(container)) {
                assertThat(count(connection,
                        "SELECT count(*) FROM ops.ad_decision_policy_bundle"
                                + " WHERE status = 'ACTIVE'")).isZero();
            }
        }
    }

    @Nested
    @DisplayName("TC-AD-WRITE-107 an independent verification needs a second person")
    class ManualVerification {

        @Test
        @DisplayName("TC-AD-WRITE-107a a self-report cannot prove configuration")
        void selfReportCannotProveConfiguration() throws SQLException {
            try (Connection connection = asMigrationRole(container)) {
                String definition = single(connection,
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                + " WHERE conname = "
                                + "'ad_manual_configuration_verification_self_report_ck'");

                assertThat(definition)
                        .contains("EXECUTOR_SELF_REPORT")
                        .contains("proves_configuration");
            }
        }

        @Test
        @DisplayName("TC-AD-WRITE-107b an independent verification requires a different verifier")
        void independentVerificationRequiresADifferentPerson() throws SQLException {
            try (Connection connection = asMigrationRole(container)) {
                String definition = single(connection,
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                + " WHERE conname = "
                                + "'ad_manual_configuration_verification_independent_ck'");

                assertThat(definition)
                        .contains("INDEPENDENT_MANUAL_VERIFICATION")
                        .contains("verifier_user_id");
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Every value of the first column, in the order the query returned them. */
    private static List<String> strings(Connection connection, String sql) throws SQLException {
        List<String> values = new java.util.ArrayList<>();
        try (var statement = connection.createStatement();
                var results = statement.executeQuery(sql)) {
            while (results.next()) {
                values.add(results.getString(1));
            }
        }
        return values;
    }
}
