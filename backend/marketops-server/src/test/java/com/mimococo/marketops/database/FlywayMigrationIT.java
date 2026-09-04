package com.mimococo.marketops.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Establishes what the approved migration set produces, and what the earliest
 * migration does when the database is not the empty one it expects.
 *
 * <p>The negative case is the reason the migration refuses to tolerate an
 * existing schema, so it is asserted in full rather than described.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlywayMigrationIT extends PostgresContainerSupport {

    /** The approved migration files in their application order. */
    private static final List<String> APPROVED_MIGRATIONS = List.of(
            "V0001__create_foundation_schemas.sql",
            "V0002__enable_btree_gist_extension.sql",
            "V0003__create_metadata_audit_event.sql",
            "V0004__create_core_organization_metadata.sql",
            "V0005__create_iam_access_metadata.sql",
            "V0006__create_platform_registry_metadata.sql",
            "V0007__create_ingestion_control_plane_authority.sql",
            "V0008__attach_control_epoch_triggers.sql",
            "V0009__create_control_boundary_kinds_and_decision_evidence.sql",
            "V0010__create_ingestion_run_checkpoint_and_raw_evidence.sql",
            "V0011__create_human_identity_and_business_authorization.sql",
            "V0012__create_product_listing_identity_and_mapping.sql",
            "V0013__create_cross_domain_operating_facts.sql",
            "V0014__create_internal_fact_intake_and_file_import.sql",
            "V0015__create_canonical_metric_definitions_and_values.sql",
            "V0016__create_deterministic_diagnosis_rules_and_findings.sql",
            "V0017__create_ai_projection_invocation_and_output.sql",
            "V0018__create_recommendation_task_and_approval_workflow.sql",
            "V0019__create_commercial_policy_and_guardrails.sql",
            "V0020__create_price_command_outbox_readback_and_write_gate.sql",
            "V0021__create_platform_api_profile_and_request_shape.sql",
            "V0022__create_ingestion_run_lifecycle_and_replay_guard.sql",
            "V0023__create_declared_normalization_and_drift_observation.sql",
            "V0024__create_capability_write_operation_shape.sql",
            "V0025__create_price_command_attempt_completion_and_lease_recovery.sql",
            "V0026__rename_operational_capability_column_to_action_kind.sql",
            "V0027__create_account_bound_registry_verification.sql",
            "V0028__create_bounded_diagnostic_export.sql",
            "V0029__version_profit_economics_and_commercial_inputs.sql",
            "V0030__create_availability_risk_policy_inbound_and_case.sql",
            "V0031__track_sustained_availability_lane.sql",
            "V0032__create_availability_fact_feed_cursor.sql",
            "V0033__track_case_improvement_observation.sql",
            "V0034__close_availability_deep_review_findings.sql",
            "V0035__close_availability_targeted_findings.sql",
            "V0036__create_advertising_identity_and_official_facts.sql",
            "V0037__create_advertising_conversion_freshness_and_qualification.sql");

    private static PostgreSQLContainer container;

    @BeforeAll
    static void migrate() {
        container = shared();
        migrator(container).migrate();
    }

    @Test
    @Order(0)
    @DisplayName("TC-DB-100 the container and running server are exactly PostgreSQL 18.4")
    void serverReleaseIsExactlyPinned() throws SQLException {
        assertThat(container.getDockerImageName()).isEqualTo(IMAGE);
        try (Connection connection = asSuperuser(container)) {
            assertThat(single(connection, "SHOW server_version_num")).isEqualTo("180004");
            assertThat(single(connection, "SHOW server_version")).startsWith("18.4");
        }
    }

    @Test
    @Order(1)
    @DisplayName("TC-DB-101 the eight foundation schemas exist")
    void foundationSchemasExist() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            List<String> present = strings(connection,
                    "SELECT nspname FROM pg_namespace WHERE nspname IN "
                            + quotedFoundationSchemas() + " ORDER BY nspname");

            assertThat(present)
                    .containsExactlyInAnyOrderElementsOf(FOUNDATION_SCHEMAS);
        }
    }

    @Test
    @Order(2)
    @DisplayName("TC-DB-102 every foundation schema belongs to the migrating role")
    void foundationSchemasAreOwnedByTheMigratingRole() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            List<String> owners = strings(connection,
                    "SELECT n.nspname || '=' || r.rolname FROM pg_namespace n "
                            + "JOIN pg_roles r ON r.oid = n.nspowner "
                            + "WHERE n.nspname IN " + quotedFoundationSchemas()
                            + " ORDER BY n.nspname");

            assertThat(owners).hasSize(FOUNDATION_SCHEMAS.size());
            assertThat(owners).allSatisfy(entry ->
                    Assertions.assertThat(entry).endsWith("=" + MIGRATION_ROLE));
        }
    }

    @Test
    @Order(3)
    @DisplayName("TC-DB-110 the approved tables exist, and nothing else does")
    void exactlyTheMetadataTablesExist() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            List<String> tables = strings(connection,
                    "SELECT schemaname || '.' || tablename FROM pg_tables "
                            + "WHERE schemaname IN " + quotedFoundationSchemas()
                            + " ORDER BY 1");

            assertThat(tables).containsExactly(
                    "core.ad_affected_set",
                    "core.ad_allowable_cpa_definition",
                    "core.ad_conversion_definition",
                    "core.ad_freshness_profile",
                    "core.ad_native_object",
                    "core.ad_object_configuration_observation",
                    "core.ad_object_relationship",
                    "core.ad_optimization_qualification_policy",
                    "core.availability_priority_policy",
                    "core.cost_version",
                    "core.demand_observation_policy",
                    "core.economics_projection_component",
                    "core.economics_projection_family",
                    "core.economics_projection_profile",
                    "core.exception_materiality_policy",
                    "core.fact_provenance",
                    "core.finance_input_version",
                    "core.fulfillment_mode",
                    "core.inbound_supply_attestation",
                    "core.inbound_supply_attestation_version",
                    "core.internal_stock_snapshot",
                    "core.lead_time_safety_policy",
                    "core.legal_entity",
                    "core.listing_health_observation",
                    "core.listing_mapping",
                    "core.listing_mapping_candidate",
                    "core.listing_price_observation",
                    "core.listing_stock_observation",
                    "core.listing_traffic_observation",
                    "core.mapping_conflict",
                    "core.marketplace_account",
                    "core.marketplace_platform",
                    "core.organization",
                    "core.platform_listing",
                    "core.platform_listing_variant",
                    "core.product",
                    "core.product_barcode",
                    "core.product_variant",
                    "core.return_quality_policy",
                    "core.source_feed_watermark",
                    "core.store",
                    "core.store_fulfillment_declaration",
                    "core.store_warehouse_link",
                    "core.supply_ownership_declaration",
                    "core.warehouse",
                    "core.work_activation_policy",
                    "iam.action_scope",
                    "iam.business_role",
                    "iam.business_role_action_scope",
                    "iam.identity_decision_event",
                    "iam.identity_provider",
                    "iam.permission_kind",
                    "iam.service_account",
                    "iam.service_account_allowed_source",
                    "iam.service_account_scope_grant",
                    "iam.user_account",
                    "iam.user_role_assignment",
                    "iam.user_scope_grant",
                    "ledger.ad_linked_sale_event",
                    "ledger.ad_object_fact",
                    "ledger.ad_object_listing_allocation",
                    "ledger.ad_spend_fact",
                    "ledger.finance_fee_fact",
                    "ledger.return_fact",
                    "ledger.return_inventory_transition",
                    "ledger.return_quality_evidence_snapshot",
                    "ledger.sales_fact",
                    "mart.availability_risk_card",
                    "mart.availability_risk_child",
                    "mart.availability_risk_evidence",
                    "mart.availability_risk_factor",
                    "mart.calculation_run",
                    "mart.demand_window_observation",
                    "mart.diagnosis_finding",
                    "mart.diagnosis_finding_input",
                    "mart.diagnosis_rule",
                    "mart.diagnosis_rule_input",
                    "mart.diagnostic_export_row",
                    "mart.metric_definition",
                    "mart.metric_input_reference",
                    "mart.metric_value",
                    "ops.ai_claim_evidence",
                    "ops.ai_invocation",
                    "ops.ai_model",
                    "ops.ai_output_claim",
                    "ops.ai_projection_definition",
                    "ops.ai_projection_field",
                    "ops.ai_provider",
                    "ops.approval_decision",
                    "ops.authorization_decision_evidence",
                    "ops.availability_accepted_exception",
                    "ops.availability_case",
                    "ops.availability_case_event",
                    "ops.availability_exception_decision",
                    "ops.availability_exception_delegation",
                    "ops.availability_fact_cursor",
                    "ops.availability_recalculation_request",
                    "ops.availability_reconciliation_run",
                    "ops.availability_slo_observation",
                    "ops.availability_trace_event",
                    "ops.commercial_policy",
                    "ops.commercial_policy_limit",
                    "ops.diagnostic_export",
                    "ops.diagnostic_export_part",
                    "ops.endpoint_quota_window",
                    "ops.guardrail_evaluation",
                    "ops.ingestion_checkpoint",
                    "ops.ingestion_run",
                    "ops.kill_switch_event",
                    "ops.metadata_audit_event",
                    "ops.pilot_allowlist_entry",
                    "ops.policy_authorization",
                    "ops.policy_limit_kind",
                    "ops.price_command",
                    "ops.price_command_attempt",
                    "ops.price_command_readback",
                    "ops.price_command_transition",
                    "ops.recommendation",
                    "ops.recommendation_evidence",
                    "ops.work_task",
                    "platform.ad_semantic_profile",
                    "platform.capability_operation",
                    "platform.capability_subject_status",
                    "platform.capability_verification_event",
                    "platform.control_boundary_kind",
                    "platform.control_epoch",
                    "platform.control_epoch_membership_guard",
                    "platform.control_route_inventory",
                    "platform.credential_metadata",
                    "platform.credential_purpose",
                    "platform.credential_store_scope",
                    "platform.feature_flag",
                    "platform.ingestion_job",
                    "platform.platform_api_profile",
                    "platform.platform_auth_header",
                    "platform.platform_capability",
                    "platform.platform_endpoint",
                    "platform.platform_permission_requirement",
                    "platform.registry_verification_case",
                    "raw.price_response_observation",
                    "raw.raw_acquisition_observation",
                    "raw.raw_content",
                    "raw.raw_logical_unit",
                    "staging.canonical_field",
                    "staging.import_batch",
                    "staging.import_row",
                    "staging.import_schema_profile",
                    "staging.normalization_checkpoint",
                    "staging.normalization_field",
                    "staging.normalization_mapping",
                    "staging.schema_drift_observation");
        }
    }

    @Test
    @Order(3)
    @DisplayName("TC-DB-118 the reference seeds are the approved rows and nothing more")
    void referenceSeedsAreExact() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            assertThat(strings(connection,
                    "SELECT code FROM core.marketplace_platform ORDER BY code"))
                    .containsExactly("OZON", "WILDBERRIES");
            assertThat(strings(connection,
                    "SELECT code FROM core.fulfillment_mode ORDER BY code"))
                    .containsExactly("MARKETPLACE_FULFILLED", "SELLER_FULFILLED", "UNKNOWN");
            assertThat(strings(connection,
                    "SELECT code FROM iam.permission_kind ORDER BY code"))
                    .containsExactly("ADS", "CREDENTIAL_ADMIN", "FINANCE", "READ", "WRITE");
            assertThat(strings(connection,
                    "SELECT code FROM platform.credential_purpose ORDER BY code"))
                    .containsExactly(
                            "ADS_WRITE", "FINANCE", "INVENTORY_WRITE", "PRICE_WRITE", "READ");
            assertThat(strings(connection,
                    "SELECT kind FROM platform.control_boundary_kind ORDER BY ordinal"))
                    .containsExactly(
                            "SERVICE_ACCOUNT_EXPIRY",
                            "SELECTED_SCOPE_GRANT_END",
                            "FUTURE_SCOPE_GRANT_START",
                            "SELECTED_CREDENTIAL_EXPIRY",
                            "FUTURE_CREDENTIAL_START",
                            "STORE_SCOPE_BOUNDARY");
            // Every platform carries its membership guard from the migration
            // that created the platform, so the serialization point exists
            // before any job can reference it.
            assertThat(strings(connection,
                    "SELECT platform_code FROM platform.control_epoch_membership_guard"
                            + " WHERE guard_kind = 'PLATFORM_JOB_SET' ORDER BY platform_code"))
                    .containsExactly("OZON", "WILDBERRIES");
            assertThat(strings(connection,
                    "SELECT code FROM iam.business_role ORDER BY ordinal"))
                    .containsExactly("OWNER", "OPERATIONS", "FINANCE", "READ_ONLY",
                            "MARKETPLACE_OPERATOR", "PRODUCT_PROCUREMENT", "TECH_DATA",
                            "FINANCE_ANALYST", "OPS_LEAD", "RISK_AUTHORITY", "AUDITOR");
            assertThat(strings(connection,
                    "SELECT code FROM iam.action_scope ORDER BY ordinal"))
                    .containsExactly(
                            "DIAGNOSTIC_VIEW", "EVIDENCE_VIEW", "MAPPING_RESOLVE",
                            "INTERNAL_FACT_INTAKE", "RECOMMENDATION_MANAGE", "TASK_ASSIGN",
                            "PRICE_CHANGE_APPROVE", "COMMERCIAL_POLICY_MANAGE",
                            "COMMAND_RESOLVE", "KILL_SWITCH_OPERATE",
                            "AVAILABILITY_VIEW", "INBOUND_ATTEST", "SUPPLY_POLICY_MANAGE",
                            "AVAILABILITY_TASK_ACT", "AVAILABILITY_EXCEPTION_REQUEST",
                            "AVAILABILITY_EXCEPTION_APPROVE",
                            "ADVERTISING_VIEW", "ADVERTISING_TASK_ACT",
                            "ADVERTISING_EXCEPTION_REQUEST", "AD_BID_CHANGE_ENDORSE",
                            "AD_BID_CHANGE_APPROVE", "ADVERTISING_POLICY_MANAGE");
            // A role matrix that grew without review is the quiet way a
            // read-only profile acquires the ability to move a price.
            assertThat(strings(connection,
                    "SELECT action_code FROM iam.business_role_action_scope"
                            + " WHERE role_code = 'READ_ONLY' ORDER BY action_code"))
                    .containsExactly("DIAGNOSTIC_VIEW", "EVIDENCE_VIEW");
            assertThat(count(connection,
                    "SELECT count(*) FROM iam.business_role_action_scope"
                            + " WHERE role_code <> 'OWNER' AND action_code IN"
                            + " ('PRICE_CHANGE_APPROVE', 'COMMERCIAL_POLICY_MANAGE')"))
                    .isZero();
            // The availability step-up actions are held only by the roles the
            // Contract makes accountable for them. Publishing a lead time and
            // accepting a risk both silently change what the queue reports, so
            // widening either is a migration a reviewer has to see.
            assertThat(strings(connection,
                    "SELECT role_code FROM iam.business_role_action_scope"
                            + " WHERE action_code = 'SUPPLY_POLICY_MANAGE' ORDER BY role_code"))
                    .containsExactly("OWNER", "PRODUCT_PROCUREMENT");
            assertThat(strings(connection,
                    "SELECT role_code FROM iam.business_role_action_scope"
                            + " WHERE action_code = 'AVAILABILITY_EXCEPTION_APPROVE'"
                            + " ORDER BY role_code"))
                    .containsExactly("OPS_LEAD", "OWNER", "RISK_AUTHORITY");
            // An auditor reads. A role that could act on a case or accept a risk
            // would not be an audit role.
            assertThat(strings(connection,
                    "SELECT action_code FROM iam.business_role_action_scope"
                            + " WHERE role_code = 'AUDITOR' ORDER BY action_code"))
                    .containsExactly("ADVERTISING_VIEW", "AVAILABILITY_VIEW",
                            "DIAGNOSTIC_VIEW", "EVIDENCE_VIEW");
            // The two halves of the advertising Maker-Checker chain are held by
            // different roles on purpose. Endorsement is the Operations Lead's;
            // the final per-command approval is the Owner's. A role holding both
            // would let one person move a live bid alone, which is the exact
            // outcome the Contract's material route exists to prevent.
            assertThat(strings(connection,
                    "SELECT role_code FROM iam.business_role_action_scope"
                            + " WHERE action_code = 'AD_BID_CHANGE_ENDORSE' ORDER BY role_code"))
                    .containsExactly("OPS_LEAD", "OWNER");
            assertThat(strings(connection,
                    "SELECT role_code FROM iam.business_role_action_scope"
                            + " WHERE action_code = 'AD_BID_CHANGE_APPROVE' ORDER BY role_code"))
                    .containsExactly("OWNER");
            assertThat(strings(connection,
                    "SELECT role_code FROM iam.business_role_action_scope"
                            + " WHERE action_code = 'ADVERTISING_POLICY_MANAGE'"
                            + " ORDER BY role_code"))
                    .containsExactly("OPS_LEAD", "OWNER");
            // No advertising Semantic Profile is seeded. A synthetic fixture can
            // never be VERIFIED, so even a fixture cannot open a Provider path.
            assertThat(count(connection,
                    "SELECT count(*) FROM platform.ad_semantic_profile"))
                    .isZero();
            assertThat(strings(connection,
                    "SELECT metric_code FROM mart.metric_definition"
                            + " WHERE domain = 'ADVERTISING' AND status = 'ACTIVE'"
                            + " ORDER BY metric_code"))
                    .containsExactly(
                            "AD_ATTRIBUTION_GAP_RATIO",
                            "AD_COST_OF_SALE",
                            "AD_ELIGIBLE_TRAFFIC",
                            "AD_LINKED_COMPLETED_SALE_CONVERSION",
                            "AD_LINKED_ORDER_CONVERSION",
                            "AD_LINKED_RETAINED_SALE_CONVERSION",
                            "AD_SPEND",
                            "ADVERTISING_CONTRIBUTION_PROFIT",
                            "ALLOWABLE_CPA",
                            "CONTRIBUTION_PROFIT_PER_AD_RUB",
                            "MAX_CPC",
                            "PROVIDER_ATTRIBUTED_CONVERSION");
            assertThat(strings(connection,
                    "SELECT metric_code FROM mart.metric_definition"
                            + " WHERE domain = 'PROFIT' AND status = 'ACTIVE'"
                            + " ORDER BY metric_code"))
                    .containsExactly(
                            "BREAK_EVEN_PRICE", "CONTRIBUTION_MARGIN", "MINIMUM_PRICE",
                            "OBSERVED_SELLING_PRICE", "OPERATIONAL_CONTRIBUTION_PROFIT",
                            "SETTLED_CONTRIBUTION_PROFIT");
            assertThat(strings(connection,
                    "SELECT rule_code FROM mart.diagnosis_rule ORDER BY ordinal"))
                    .containsExactly(
                            "DATA_BLOCKED", "NEGATIVE_MARGIN", "STOCKOUT_RISK", "HIGH_RETURN",
                            "LOW_IMPRESSION", "LOW_CLICK_THROUGH", "LOW_CONVERSION",
                            "ADVERTISING_INEFFICIENT", "PRICE_BELOW_MINIMUM");
            // An unknown platform result must never be repeatable as a write.
            // The absence of this transition is the guarantee, so it is asserted
            // directly rather than inferred from the code that reads the table.
            assertThat(count(connection,
                    "SELECT count(*) FROM ops.price_command_transition"
                            + " WHERE from_state = 'UNKNOWN_REQUIRES_READBACK'"
                            + " AND to_state IN ('EXECUTING', 'LEASED', 'SUCCEEDED')"))
                    .isZero();
            assertThat(strings(connection,
                    "SELECT to_state FROM ops.price_command_transition"
                            + " WHERE from_state = 'READBACK_PENDING' ORDER BY to_state"))
                    .containsExactly("READBACK_MISMATCH", "RETRY_WAIT", "SUCCEEDED",
                            "UNKNOWN_REQUIRES_READBACK");
            // No projection field may carry a buyer attribute or free source text.
            assertThat(strings(connection,
                    "SELECT DISTINCT data_classification FROM ops.ai_projection_field"
                            + " ORDER BY 1"))
                    .containsExactly("CANONICAL_METRIC", "DETERMINISTIC_FINDING",
                            "OPAQUE_IDENTIFIER", "OPERATING_ATTRIBUTE");
            assertThat(single(connection,
                    "SELECT extname FROM pg_extension WHERE extname = 'btree_gist'"))
                    .isEqualTo("btree_gist");
        }
    }

    @Test
    @Order(4)
    @DisplayName("TC-DB-111 the migration history holds the approved set in order")
    void historyHoldsTheApprovedSet() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            List<String> applied = strings(connection,
                    "SELECT script FROM public.flyway_schema_history "
                            + "WHERE type = 'SQL' ORDER BY installed_rank");

            assertThat(applied).containsExactly(APPROVED_MIGRATIONS.toArray(String[]::new));
            assertThat(count(connection,
                    "SELECT count(*) FROM public.flyway_schema_history WHERE success = false"))
                    .isZero();
        }
    }

    @Test
    @Order(5)
    @DisplayName("TC-DB-112 migrating an up-to-date database applies nothing")
    void repeatedMigrationAppliesNothing() throws SQLException {
        long before;
        try (Connection connection = asMigrationRole(container)) {
            before = count(connection, "SELECT count(*) FROM public.flyway_schema_history");
        }

        MigrateResult result = migrator(container).migrate();

        assertThat(result.migrationsExecuted).isZero();
        try (Connection connection = asMigrationRole(container)) {
            assertThat(count(connection, "SELECT count(*) FROM public.flyway_schema_history"))
                    .isEqualTo(before);
        }
    }

    @Test
    @Order(6)
    @DisplayName("TC-DB-113 the source tree carries exactly the approved migrations")
    void exactlyTheApprovedMigrationsAreDeclared() throws Exception {
        Path migrations = repositoryRoot()
                .resolve("backend/marketops-server/src/main/resources/db/migration");

        try (var entries = Files.list(migrations)) {
            List<String> names = entries.map(path -> path.getFileName().toString()).sorted().toList();

            assertThat(names).containsExactly(APPROVED_MIGRATIONS.toArray(String[]::new));
        }
    }

    /**
     * TC-DB-103 — a database that already carries a foundation schema.
     *
     * <p>The case runs against a server of its own, because it has to leave the
     * database in a state no other test may observe. Twelve observations are made
     * in order, so a failure identifies which guarantee broke rather than only
     * that the migration behaved unexpectedly.
     */
    @Test
    @Order(7)
    @DisplayName("TC-DB-103 a pre-existing schema fails the migration and leaves nothing behind")
    void contaminatedDatabaseFailsAndRollsBack() throws Exception {
        try (PostgreSQLContainer contaminated = create()) {
            contaminated.start();

            // 1 — the roles the initialisation script creates are present.
            try (Connection connection = asSuperuser(contaminated)) {
                assertThat(count(connection,
                        "SELECT count(*) FROM pg_roles WHERE rolname IN ('"
                                + MIGRATION_ROLE + "','" + APPLICATION_ROLE + "')"))
                        .isEqualTo(2);
            }

            // 2 — no foundation schema exists yet.
            try (Connection connection = asSuperuser(contaminated)) {
                assertThat(count(connection,
                        "SELECT count(*) FROM pg_namespace WHERE nspname IN "
                                + quotedFoundationSchemas()))
                        .isZero();
            }

            // 3 — something other than the migration creates one of them.
            try (Connection connection = asSuperuser(contaminated);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA iam");
            }

            // 4 — and it belongs to whatever created it, not to the migrating role.
            try (Connection connection = asSuperuser(contaminated)) {
                assertThat(schemaOwner(connection, "iam")).isEqualTo(contaminated.getUsername());
            }

            // 5 — the migration refuses to run against that database.
            Flyway flyway = migrator(contaminated);
            Throwable failure = Assertions.catchThrowable(flyway::migrate);
            assertThat(failure).isNotNull();

            // 6 — and the reason is that the schema already exists.
            assertThat(carriesSqlState(failure, DUPLICATE_SCHEMA))
                    .as("the failure must be a duplicate schema, not an unrelated error")
                    .isTrue();

            try (Connection connection = asSuperuser(contaminated)) {
                // 7 — the schemas the migration would have created are absent, so the
                // statements that ran before the failure were rolled back.
                assertThat(count(connection,
                        "SELECT count(*) FROM pg_namespace WHERE nspname IN "
                                + quotedFoundationSchemasExcept("iam")))
                        .isZero();

                // 8 — the pre-existing schema is untouched, including its owner.
                assertThat(schemaOwner(connection, "iam")).isEqualTo(contaminated.getUsername());

                // 9 — the history table exists, because Flyway creates it before it
                // runs anything.
                assertThat(single(connection, "SELECT to_regclass('public.flyway_schema_history')"))
                        .isNotNull();

                // 10 — it holds no record of the attempt. PostgreSQL applies the
                // migration inside a transaction, so the failure rolled the history
                // insert back together with the schema creation.
                assertThat(count(connection,
                        "SELECT count(*) FROM public.flyway_schema_history WHERE type = 'SQL'"))
                        .isZero();

                // 11 — and in particular there is no failed row to repair. A recovery
                // procedure that expects one would wait for something that never
                // appears on this database.
                assertThat(count(connection,
                        "SELECT count(*) FROM public.flyway_schema_history WHERE success = false"))
                        .isZero();
            }

            // 12 — once the contamination is removed the same migration succeeds,
            // which shows the refusal was about the database and not about the file.
            try (Connection connection = asSuperuser(contaminated);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA iam");
            }
            MigrateResult recovered = migrator(contaminated).migrate();
            assertThat(recovered.migrationsExecuted).isEqualTo(APPROVED_MIGRATIONS.size());
            try (Connection connection = asSuperuser(contaminated)) {
                assertThat(count(connection,
                        "SELECT count(*) FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner "
                                + "WHERE n.nspname IN " + quotedFoundationSchemas()
                                + " AND r.rolname = '" + MIGRATION_ROLE + "'"))
                        .isEqualTo(FOUNDATION_SCHEMAS.size());
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("TC-DB-114 destroying the schema is not an available operation")
    void cleanIsRefused() {
        assertThatThrownBy(() -> migrator(container).clean())
                .as("no path in this project may drop a schema")
                .isInstanceOf(Exception.class);
    }

    @Test
    @Order(9)
    @DisplayName("TC-DB-115 provider-preinstalled btree_gist cannot satisfy immutable V0002")
    void preinstalledExtensionDoesNotBecomeAnAppliedMigration() throws Exception {
        try (PostgreSQLContainer isolated = create()) {
            isolated.start();
            // Execute V0001 normally so even a valid history cannot make V0002
            // tolerate an extension installed by a separate control plane.
            Flyway.configure().configuration(migrator(isolated).getConfiguration())
                    .target("0001").load().migrate();
            try (Connection connection = asSuperuser(isolated);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE EXTENSION btree_gist WITH SCHEMA public");
            }
            Throwable failure = Assertions.catchThrowable(() -> migrator(isolated).migrate());
            assertThat(carriesSqlState(failure, "42710"))
                    .as("immutable V0002 must refuse a duplicate extension")
                    .isTrue();
            try (Connection connection = asSuperuser(isolated)) {
                assertThat(strings(connection,
                        "SELECT script FROM public.flyway_schema_history WHERE type='SQL' ORDER BY installed_rank"))
                        .containsExactly("V0001__create_foundation_schemas.sql");
                assertThat(single(connection,
                        "SELECT extname FROM pg_extension WHERE extname='btree_gist'"))
                        .isEqualTo("btree_gist");
                assertThat(single(connection,"SELECT to_regclass('ops.metadata_audit_event')")).isNull();
            }
        }
    }

    private static String schemaOwner(Connection connection, String schema) throws SQLException {
        return single(connection,
                "SELECT r.rolname FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner "
                        + "WHERE n.nspname = '" + schema + "'");
    }

    private static String quotedFoundationSchemas() {
        return quoted(FOUNDATION_SCHEMAS);
    }

    private static String quotedFoundationSchemasExcept(String excluded) {
        return quoted(FOUNDATION_SCHEMAS.stream().filter(name -> !name.equals(excluded)).toList());
    }

    private static String quoted(List<String> names) {
        return names.stream()
                .map(name -> "'" + name + "'")
                .reduce((left, right) -> left + "," + right)
                .map(joined -> "(" + joined + ")")
                .orElseThrow();
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
