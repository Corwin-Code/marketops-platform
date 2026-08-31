package com.mimococo.marketops.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The availability-risk schema against a real server.
 *
 * <p>Every rule tested here is one the product would be dishonest without, and
 * every one of them is asserted as a refusal rather than as a successful happy
 * path. A constraint that has never rejected anything is a comment.
 *
 * <p>The tests run on an isolated server because they leave rows behind that a
 * shared one must not carry into another suite.
 */
class AvailabilityRiskSchemaIT extends PostgresContainerSupport {

    private static final PostgreSQLContainer CONTAINER = create();

    private static final UUID ORGANIZATION = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID PRODUCT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID VARIANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    private static final UUID PROVIDER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004");
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000005");
    private static final UUID APPROVER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000006");
    private static final UUID CARD = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000007");

    /** A proof with one term: enough for the constraint, small enough to read. */
    private static final String ESTABLISHED_PROOF = """
            {"terms": [{"code": "PROVEN_UNITS", "label": "owned and fresh", "value": 30}]}""";

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        CONTAINER.start();
        migrator(CONTAINER).migrate();
        try (Connection connection = asMigrationRole(CONTAINER);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO core.organization (id, code, display_name, status, created_at, updated_at)
                    VALUES ('%s', 'acme', 'Acme', 'ACTIVE', now(), now());
                    """.formatted(ORGANIZATION));
            statement.execute("""
                    INSERT INTO core.product (id, organization_id, code, display_name, status,
                                              created_at, updated_at)
                    VALUES ('%s', '%s', 'p1', 'P1', 'ACTIVE', now(), now());
                    """.formatted(PRODUCT, ORGANIZATION));
            statement.execute("""
                    INSERT INTO core.product_variant (id, organization_id, product_id, sku_code,
                                                      display_name, status, created_at, updated_at)
                    VALUES ('%s', '%s', '%s', 'sku-1', 'V1', 'ACTIVE', now(), now());
                    """.formatted(VARIANT, ORGANIZATION, PRODUCT));
            statement.execute("""
                    INSERT INTO iam.identity_provider (id, code, display_name, issuer,
                            mfa_claim_name, mfa_claim_value, max_auth_age_seconds,
                            verification_state, last_verified_at, evidence_ref,
                            verified_source_title, owner_label, status, created_at, updated_at)
                    VALUES ('%s', 'idp', 'IdP', 'https://idp.example', 'amr', 'mfa', 900,
                            'VERIFIED', now(), 'ev://idp', 'IdP docs', 'security', 'ACTIVE',
                            now(), now());
                    """.formatted(PROVIDER));
            for (UUID user : new UUID[] {OWNER, APPROVER}) {
                statement.execute("""
                        INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
                                external_subject, display_name, status, credentials_valid_from,
                                created_at, updated_at)
                        VALUES ('%s', '%s', '%s', 'sub-%s', 'User', 'ACTIVE', now(), now(), now());
                        """.formatted(user, ORGANIZATION, PROVIDER, user));
            }
            statement.execute("""
                    INSERT INTO mart.availability_risk_card (id, organization_id, product_variant_id,
                            lane, rank_score, policy_version_digest, as_of, calculated_at,
                            calculation_kind, created_at, updated_at)
                    VALUES ('%s', '%s', '%s', 'HEALTHY', 0, repeat('a', 64), now(), now(),
                            'TARGETED', now(), now());
                    """.formatted(CARD, ORGANIZATION, VARIANT));
        }
    }

    @Test
    @DisplayName("TC-AVAIL-DB-001 two overlapping active lead-time versions of one scope are refused")
    void overlappingPolicyVersionsAreRefused() throws SQLException {
        try (Connection connection = asMigrationRole(CONTAINER)) {
            insertOrganizationPolicy(connection, UUID.randomUUID(), "now() - interval '1 day'", null);

            assertThatThrownBy(() -> insertOrganizationPolicy(
                    connection, UUID.randomUUID(), "now()", null))
                    .satisfies(thrown -> assertThat(carriesSqlState(thrown, EXCLUSION_VIOLATION))
                            .as("an overlapping active version must be refused by the server")
                            .isTrue());

            // Closing the first interval makes room for a successor. History is
            // retained; the two versions simply do not overlap.
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "UPDATE core.lead_time_safety_policy SET effective_to = now()"
                                + " WHERE effective_to IS NULL");
            }
            insertOrganizationPolicy(connection, UUID.randomUUID(), "now() + interval '1 second'", null);
        }
    }

    @Test
    @DisplayName("TC-AVAIL-DB-002 a scope may not name identifiers it is not scoped by")
    void scopeShapeIsEnforced() throws SQLException {
        try (Connection connection = asMigrationRole(CONTAINER);
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO core.lead_time_safety_policy (id, organization_id, scope_kind,
                            scope_precedence, supplier_code, product_variant_id,
                            lead_time_days_min, lead_time_days_max, safety_days, owner_user_id,
                            reason, evidence_reference, last_reviewed_at, effective_from, status,
                            policy_version, created_at)
                    VALUES ('%s', '%s', 'SUPPLIER', 2, 'sup-a', '%s', 5, 7, 2, '%s',
                            'bad shape', 'ev://x', now(), now(), 'ACTIVE', 1, now());
                    """.formatted(UUID.randomUUID(), ORGANIZATION, VARIANT, OWNER)))
                    .satisfies(thrown -> assertThat(carriesSqlState(thrown, CHECK_VIOLATION)).isTrue());
        }
    }

    @Test
    @DisplayName("TC-AVAIL-DB-003 a company child cannot be healthy on blocked evidence")
    void companyChildCannotBeFalselySafe() throws SQLException {
        try (Connection connection = asMigrationRole(CONTAINER);
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(child(
                    UUID.randomUUID(), "COMPANY", "HEALTHY", "DATA_BLOCKED", "{}")))
                    .satisfies(thrown -> assertThat(carriesSqlState(thrown, CHECK_VIOLATION)).isTrue());
        }
    }

    @Test
    @DisplayName("TC-AVAIL-DB-004 a provisional child cannot exist without proof terms")
    void provisionalChildNeedsProof() throws SQLException {
        try (Connection connection = asMigrationRole(CONTAINER);
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(child(
                    UUID.randomUUID(), "COMPANY", "CRITICAL", "PROVISIONAL", "{}")))
                    .satisfies(thrown -> assertThat(carriesSqlState(thrown, CHECK_VIOLATION)).isTrue());

            // The same row with an argument attached is accepted.
            statement.executeUpdate(child(UUID.randomUUID(), "COMPANY", "CRITICAL", "PROVISIONAL",
                    ESTABLISHED_PROOF));
        }
    }

    @Test
    @DisplayName("TC-AVAIL-DB-005 one live case per cause survives a concurrent duplicate")
    void oneLiveCasePerCause() throws SQLException {
        try (Connection connection = asMigrationRole(CONTAINER)) {
            UUID childId = UUID.randomUUID();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(child(childId, "COMPANY", "CRITICAL", "CONFIRMED", "{}"));
            }
            UUID policyId = insertActivationPolicy(connection);
            String causeKey = "COMPANY|" + VARIANT + "|COMPANY_SUPPLY_SHORT";

            insertCase(connection, UUID.randomUUID(), childId, policyId, causeKey, "OPEN");
            assertThatThrownBy(() -> insertCase(connection, UUID.randomUUID(), childId, policyId,
                    causeKey, "OPEN"))
                    .satisfies(thrown -> assertThat(carriesSqlState(thrown, UNIQUE_VIOLATION))
                            .as("recalculating the same cause must update one case, not raise two")
                            .isTrue());

            // A closed case releases the key: the same cause returning later is
            // a new case, and its history is not overwritten.
            try (Statement statement = connection.createStatement()) {
                // The schema refuses a success that never recorded an action, so
                // closing the case here has to walk both stages. That refusal is
                // the two-stage rule, and it is asserted directly below.
                assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE ops.availability_case SET state = 'VERIFIED_SUCCESS',"
                                + " verified_at = now(), closed_at = now(),"
                                + " closure_reason = 'verified' WHERE cause_key = '"
                                + causeKey + "'"))
                        .as("a verified success cannot exist without a recorded action")
                        .satisfies(thrown ->
                                assertThat(carriesSqlState(thrown, CHECK_VIOLATION)).isTrue());

                statement.executeUpdate("UPDATE ops.availability_case SET state = 'VERIFIED_SUCCESS',"
                        + " action_recorded_at = now(), verification_started_at = now(),"
                        + " verified_at = now(), closed_at = now(), closure_reason = 'verified'"
                        + " WHERE cause_key = '" + causeKey + "'");
            }
            insertCase(connection, UUID.randomUUID(), childId, policyId, causeKey, "OPEN");
        }
    }

    @Test
    @DisplayName("TC-AVAIL-DB-006 an approval needing separation cannot be made by the requester")
    void requesterCannotApproveWhereSeparationIsRequired() throws SQLException {
        try (Connection connection = asMigrationRole(CONTAINER)) {
            UUID exceptionId = seedException(connection);
            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(decision(exceptionId, true, true)))
                        .satisfies(thrown ->
                                assertThat(carriesSqlState(thrown, CHECK_VIOLATION)).isTrue());
                // An independent approver is accepted.
                statement.executeUpdate(decision(exceptionId, false, true));
            }
        }
    }

    @Test
    @DisplayName("TC-AVAIL-DB-007 an active accepted exception cannot exist without an expiry")
    void anAcceptedExceptionAlwaysExpires() throws SQLException {
        try (Connection connection = asMigrationRole(CONTAINER)) {
            UUID childId = UUID.randomUUID();
            UUID caseId = UUID.randomUUID();
            UUID policyId = insertActivationPolicy(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(child(childId, "COMPANY", "HIGH", "CONFIRMED", "{}"));
                insertCase(connection, caseId, childId, policyId,
                        "SCOPE|" + UUID.randomUUID(), "OPEN");
                assertThatThrownBy(() -> statement.executeUpdate("""
                        INSERT INTO ops.availability_accepted_exception (id, organization_id,
                                case_id, child_id, cause_code, scope_kind, scope_reference,
                                reason_code, rationale, expected_consequence, evidence_reference,
                                requested_by_user_id, requested_at, decision_owner_role_code,
                                required_authority_level, state, effective_from,
                                created_at, updated_at)
                        VALUES ('%s', '%s', '%s', '%s', 'COMPANY_SUPPLY_SHORT', 'CHILD', 'child',
                                'SEASONAL_PAUSE', 'paused for the season',
                                'no replenishment until spring', 'ev://note', '%s', now(),
                                'OPS_LEAD', 'OPS_LEAD', 'ACTIVE', now(), now(), now());
                        """.formatted(UUID.randomUUID(), ORGANIZATION, caseId, childId, OWNER)))
                        .satisfies(thrown ->
                                assertThat(carriesSqlState(thrown, CHECK_VIOLATION))
                                        .as("an acceptance without an expiry is a hidden"
                                                + " monitoring exclusion")
                                        .isTrue());
            }
        }
    }

    @Test
    @DisplayName("TC-AVAIL-DB-008 only one recalculation request per variant is pending at a time")
    void recalculationRequestsCollapse() throws SQLException {
        try (Connection connection = asMigrationRole(CONTAINER)) {
            insertRequest(connection, UUID.randomUUID(), "PENDING");
            assertThatThrownBy(() -> insertRequest(connection, UUID.randomUUID(), "PENDING"))
                    .satisfies(thrown -> assertThat(carriesSqlState(thrown, UNIQUE_VIOLATION))
                            .as("a hundred facts in a minute are one recalculation")
                            .isTrue());
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE ops.availability_recalculation_request"
                        + " SET state = 'COMPLETED', completed_at = now() WHERE state = 'PENDING'");
            }
            insertRequest(connection, UUID.randomUUID(), "PENDING");
        }
    }

    @Test
    @DisplayName("TC-AVAIL-DB-009 the application role cannot delete a case, an event or a decision")
    void nothingIsDeletable() throws SQLException {
        try (Connection connection = asApplicationRole(CONTAINER);
             Statement statement = connection.createStatement()) {
            for (String table : new String[] {"ops.availability_case", "ops.availability_case_event",
                    "ops.availability_exception_decision", "ops.availability_slo_observation",
                    "core.inbound_supply_attestation_version"}) {
                assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM " + table))
                        .as("no history in %s may be deleted", table)
                        .satisfies(thrown ->
                                assertThat(carriesSqlState(thrown, INSUFFICIENT_PRIVILEGE)).isTrue());
            }
        }
    }

    @Test
    @DisplayName("TC-AVAIL-DB-010 an action event must carry structured evidence, not free text")
    void freeTextCannotSatisfyTheActionStage() throws SQLException {
        try (Connection connection = asMigrationRole(CONTAINER)) {
            UUID childId = UUID.randomUUID();
            UUID caseId = UUID.randomUUID();
            UUID policyId = insertActivationPolicy(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(child(childId, "COMPANY", "HIGH", "CONFIRMED", "{}"));
                insertCase(connection, caseId, childId, policyId,
                        "ACTION|" + UUID.randomUUID(), "OPEN");
                assertThatThrownBy(() -> statement.executeUpdate("""
                        INSERT INTO ops.availability_case_event (id, case_id, organization_id,
                                sequence_no, event_kind, reason, occurred_at, correlation_id)
                        VALUES ('%s', '%s', '%s', 1, 'ACTION_RECORDED',
                                'I had a look and it seems fine', now(), 'corr-1');
                        """.formatted(UUID.randomUUID(), caseId, ORGANIZATION)))
                        .satisfies(thrown ->
                                assertThat(carriesSqlState(thrown, CHECK_VIOLATION)).isTrue());

                statement.executeUpdate("""
                        INSERT INTO ops.availability_case_event (id, case_id, organization_id,
                                sequence_no, event_kind, action_kind, action_evidence,
                                evidence_reference, actor_user_id, reason, occurred_at,
                                correlation_id)
                        VALUES ('%s', '%s', '%s', 1, 'ACTION_RECORDED', 'INBOUND_EVIDENCE_BOUND',
                                '{"reference": "po-4471"}'::jsonb, 'ev://po/4471', '%s',
                                'bound a supplier-confirmed consignment', now(), 'corr-1');
                        """.formatted(UUID.randomUUID(), caseId, ORGANIZATION, OWNER));
            }
        }
    }

    private void insertOrganizationPolicy(Connection connection, UUID id, String from, String to)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO core.lead_time_safety_policy (id, organization_id, scope_kind,
                            scope_precedence, lead_time_days_min, lead_time_days_max, safety_days,
                            owner_user_id, reason, evidence_reference, last_reviewed_at,
                            effective_from, effective_to, status, policy_version, created_at)
                    VALUES ('%s', '%s', 'ORGANIZATION', 3, 10, 14, 7, '%s', 'baseline', 'ev://1',
                            now(), %s, %s, 'ACTIVE', 1, now());
                    """.formatted(id, ORGANIZATION, OWNER, from, to == null ? "NULL" : to));
        }
    }

    private UUID insertActivationPolicy(Connection connection) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE core.work_activation_policy SET status = 'RETIRED' WHERE status = 'ACTIVE';
                    """);
            statement.executeUpdate("""
                    INSERT INTO core.work_activation_policy (id, organization_id,
                            high_sustained_cycles, critical_action_sla_minutes,
                            high_action_sla_minutes, blocker_action_sla_minutes,
                            outcome_sla_minutes, verification_window_minutes, owner_user_id,
                            reason, evidence_reference, effective_from, status, policy_version,
                            created_at)
                    VALUES ('%s', '%s', 2, 60, 240, 480, 2880, 1440, '%s', 'baseline', 'ev://2',
                            now(), 'ACTIVE', 1, now());
                    """.formatted(id, ORGANIZATION, OWNER));
        }
        return id;
    }

    private void insertCase(Connection connection, UUID id, UUID childId, UUID policyId,
                            String causeKey, String state) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO ops.availability_case (id, organization_id, card_id, child_id,
                            cause_code, cause_key, child_kind, severity, state,
                            accountable_role_code, action_due_at, activation_policy_id,
                            first_activated_at, last_evidence_at, correlation_id,
                            created_at, updated_at)
                    VALUES ('%s', '%s',
                            (SELECT card_id FROM mart.availability_risk_child WHERE id = '%s'),
                            '%s', 'COMPANY_SUPPLY_SHORT', '%s', 'COMPANY',
                            'CRITICAL', '%s', 'PRODUCT_PROCUREMENT', now() + interval '1 hour',
                            '%s', now(), now(), 'corr-1', now(), now());
                    """.formatted(id, ORGANIZATION, childId, childId, causeKey, state, policyId));
        }
    }

    private UUID seedException(Connection connection) throws SQLException {
        UUID childId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID exceptionId = UUID.randomUUID();
        UUID policyId = insertActivationPolicy(connection);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(child(childId, "COMPANY", "CRITICAL", "CONFIRMED", "{}"));
            insertCase(connection, caseId, childId, policyId, "EXC|" + exceptionId, "OPEN");
            statement.executeUpdate("""
                    INSERT INTO ops.availability_accepted_exception (id, organization_id, case_id,
                            child_id, cause_code, scope_kind, scope_reference, reason_code,
                            rationale, expected_consequence, evidence_reference,
                            requested_by_user_id, requested_at, decision_owner_role_code,
                            required_authority_level, state, created_at, updated_at)
                    VALUES ('%s', '%s', '%s', '%s', 'COMPANY_SUPPLY_SHORT', 'CHILD', '%s',
                            'SEASONAL_PAUSE', 'paused for the season',
                            'no replenishment until spring', 'ev://note', '%s', now(),
                            'RISK_AUTHORITY', 'RISK_AUTHORITY', 'REQUESTED', now(), now());
                    """.formatted(exceptionId, ORGANIZATION, caseId, childId, childId, OWNER));
        }
        return exceptionId;
    }

    private String decision(UUID exceptionId, boolean requesterIsApprover, boolean separation) {
        return """
                INSERT INTO ops.availability_exception_decision (id, organization_id, exception_id,
                        decision, authority_level, decided_by_user_id, decided_by_role_code,
                        requester_is_approver, separation_required, authenticated_at,
                        step_up_satisfied, reason, granted_effective_from, granted_expires_at,
                        decided_at, correlation_id)
                VALUES ('%s', '%s', '%s', 'APPROVED', 'RISK_AUTHORITY', '%s', 'RISK_AUTHORITY',
                        %s, %s, now(), true, 'accepted for the season', now(),
                        now() + interval '30 days', now(), 'corr-1');
                """.formatted(UUID.randomUUID(), ORGANIZATION, exceptionId,
                requesterIsApprover ? OWNER : APPROVER, requesterIsApprover, separation);
    }

    private void insertRequest(Connection connection, UUID id, String state) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO ops.availability_recalculation_request (id, organization_id,
                            product_variant_id, trigger_class, fact_accepted_at, requested_at,
                            state, correlation_id)
                    VALUES ('%s', '%s', '%s', 'STOCK_OR_SELLABILITY', now(), now(), '%s', 'corr-1');
                    """.formatted(id, ORGANIZATION, VARIANT, state));
        }
    }

    /**
     * Insert a child on a card of its own.
     *
     * <p>One company child per card is itself a constraint under test, so a
     * fixture that reused a card would collide with the rule rather than
     * exercise the rule it meant to.
     */
    private String child(UUID id, String kind, String lane, String evidence, String proof) {
        UUID variant = UUID.randomUUID();
        UUID card = UUID.randomUUID();
        return """
                INSERT INTO core.product_variant (id, organization_id, product_id, sku_code,
                        display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'sku-%s', 'V', 'ACTIVE', now(), now());
                INSERT INTO mart.availability_risk_card (id, organization_id, product_variant_id,
                        lane, rank_score, policy_version_digest, as_of, calculated_at,
                        calculation_kind, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'HEALTHY', 0, repeat('a', 64), now(), now(),
                        'TARGETED', now(), now());
                INSERT INTO mart.availability_risk_child (id, card_id, organization_id, child_kind,
                        lane, evidence_state, confidence_state, cause_code, profit_lane,
                        demand_selection_reason, conservative_proof, calculation_id, calculated_at,
                        created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s', 'LOW', 'COMPANY_SUPPLY_SHORT',
                        'PROFIT_UNKNOWN', 'd30 selected', '%s'::jsonb, '%s', now(), now(), now());
                """.formatted(variant, ORGANIZATION, PRODUCT,
                        variant.toString().substring(0, 8), card, ORGANIZATION, variant,
                        id, card, ORGANIZATION, kind, lane, evidence, proof, UUID.randomUUID());
    }
}
