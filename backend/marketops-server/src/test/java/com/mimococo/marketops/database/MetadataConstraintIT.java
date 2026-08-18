package com.mimococo.marketops.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the relational invariants the schema claims, each by attempting the
 * row the invariant forbids and asserting the exact SQLSTATE.
 *
 * <p>Every negative case runs in its own rolled-back transaction, so the shared
 * database keeps only the fixture graph and the deliberately committed rows.
 */
class MetadataConstraintIT extends PostgresContainerSupport {

    private static PostgreSQLContainer container;

    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();
    private static final UUID ENTITY_A = UUID.randomUUID();
    private static final UUID ENTITY_B = UUID.randomUUID();
    private static final UUID ACCOUNT_A = UUID.randomUUID();
    private static final UUID ACCOUNT_B = UUID.randomUUID();
    private static final UUID STORE_A = UUID.randomUUID();
    private static final UUID STORE_B = UUID.randomUUID();
    private static final UUID WAREHOUSE_A = UUID.randomUUID();
    private static final UUID CAPABILITY_OZON = UUID.randomUUID();
    private static final UUID SERVICE_ACCOUNT_A = UUID.randomUUID();
    private static final UUID CREDENTIAL_A = UUID.randomUUID();

    @BeforeAll
    static void migrateAndSeedFixture() throws SQLException {
        container = shared();
        migrator(container).migrate();
        try (Connection connection = asApplicationRole(container);
             Statement statement = connection.createStatement()) {
            statement.execute(organization(ORG_A, "org-a"));
            statement.execute(organization(ORG_B, "org-b"));
            statement.execute(legalEntity(ENTITY_A, ORG_A, "entity-a"));
            statement.execute(legalEntity(ENTITY_B, ORG_B, "entity-b"));
            statement.execute(account(ACCOUNT_A, ORG_A, ENTITY_A, "OZON", "account-a"));
            statement.execute(account(ACCOUNT_B, ORG_B, ENTITY_B, "WILDBERRIES", "account-b"));
            statement.execute(store(STORE_A, ORG_A, ACCOUNT_A, "store-a"));
            statement.execute(store(STORE_B, ORG_B, ACCOUNT_B, "store-b"));
            statement.execute(warehouse(WAREHOUSE_A, ORG_A, ENTITY_A, "warehouse-a"));
            statement.execute(capability(CAPABILITY_OZON, "OZON", "orders.read"));
            statement.execute(serviceAccount(SERVICE_ACCOUNT_A, ORG_A, "robot-a"));
            statement.execute(credential(CREDENTIAL_A, ORG_A, ACCOUNT_A, "credential-a",
                    "secret-ref://vault/marketops/account-a/read"));
        }
    }

    @Test
    @DisplayName("TC-DB-201 a store cannot join another organization's account")
    void crossOrganizationStoreIsUnrepresentable() throws SQLException {
        assertRejected(FOREIGN_KEY_VIOLATION,
                store(UUID.randomUUID(), ORG_B, ACCOUNT_A, "smuggled-store"));
    }

    @Test
    @DisplayName("TC-DB-202 an endpoint cannot serve another platform's capability")
    void crossPlatformEndpointLinkIsUnrepresentable() throws SQLException {
        assertRejected(FOREIGN_KEY_VIOLATION,
                endpoint(UUID.randomUUID(), "WILDBERRIES", "orders.pull", CAPABILITY_OZON));
    }

    @Test
    @DisplayName("TC-DB-203 a capability status cannot name another platform's account")
    void crossPlatformSubjectStatusIsUnrepresentable() throws SQLException {
        assertRejected(FOREIGN_KEY_VIOLATION,
                subjectStatus(UUID.randomUUID(), ORG_B, "OZON", CAPABILITY_OZON, ACCOUNT_B));
    }

    @Test
    @DisplayName("TC-DB-204 a credential scope row cannot name another account's store")
    void crossAccountCredentialScopeIsUnrepresentable() throws SQLException {
        assertRejected(FOREIGN_KEY_VIOLATION,
                storeScope(UUID.randomUUID(), CREDENTIAL_A, ACCOUNT_A, STORE_B));
    }

    @Test
    @DisplayName("TC-DB-205 overlapping active service intervals are excluded")
    void overlappingActiveLinksAreExcluded() throws SQLException {
        try (Connection connection = asApplicationRole(container);
             Statement statement = connection.createStatement()) {
            statement.execute(link(UUID.randomUUID(), ORG_A, STORE_A, WAREHOUSE_A,
                    "2026-01-01", "2026-06-01", "ACTIVE"));
        }
        assertRejected(EXCLUSION_VIOLATION,
                link(UUID.randomUUID(), ORG_A, STORE_A, WAREHOUSE_A,
                        "2026-03-01", null, "ACTIVE"));
        try (Connection connection = asApplicationRole(container);
             Statement statement = connection.createStatement()) {
            statement.execute(link(UUID.randomUUID(), ORG_A, STORE_A, WAREHOUSE_A,
                    "2026-06-01", null, "ACTIVE"));
        }
    }

    @Test
    @DisplayName("TC-DB-206 the same live scope cannot be granted twice")
    void duplicateActiveGrantIsUnique() throws SQLException {
        try (Connection connection = asApplicationRole(container);
             Statement statement = connection.createStatement()) {
            statement.execute(grant(UUID.randomUUID(), ORG_A, SERVICE_ACCOUNT_A, STORE_A));
        }
        assertRejected(UNIQUE_VIOLATION,
                grant(UUID.randomUUID(), ORG_A, SERVICE_ACCOUNT_A, STORE_A));
    }

    @Test
    @DisplayName("TC-DB-207 the flag scope matrix admits exactly one reference shape")
    void flagScopeMatrixIsExact() throws SQLException {
        assertRejected(CHECK_VIOLATION,
                "INSERT INTO platform.feature_flag (id, flag_code, flag_kind, scope_kind,"
                        + " platform_code, state, status, created_at, updated_at)"
                        + " VALUES ('" + UUID.randomUUID() + "', 'globally-scoped',"
                        + " 'OPERATIONAL', 'GLOBAL', 'OZON', 'DISABLED', 'ACTIVE',"
                        + " now(), now())");
        assertRejected(CHECK_VIOLATION,
                "INSERT INTO platform.feature_flag (id, flag_code, flag_kind, scope_kind,"
                        + " state, status, created_at, updated_at)"
                        + " VALUES ('" + UUID.randomUUID() + "', 'retired-enabled',"
                        + " 'OPERATIONAL', 'GLOBAL', 'ENABLED', 'RETIRED', now(), now())");
    }

    @Test
    @DisplayName("TC-DB-209 the flag scope key is generated from the scope columns")
    void flagScopeKeyIsGenerated() throws SQLException {
        UUID flag = UUID.randomUUID();
        try (Connection connection = asApplicationRole(container);
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO platform.feature_flag (id, flag_code, flag_kind, scope_kind,"
                            + " marketplace_account_id, state, status, created_at, updated_at)"
                            + " VALUES ('" + flag + "', 'account-scoped', 'OPERATIONAL',"
                            + " 'MARKETPLACE_ACCOUNT', '" + ACCOUNT_A + "', 'DISABLED',"
                            + " 'ACTIVE', now(), now())");
        }
        try (Connection connection = asApplicationRole(container)) {
            assertThat(single(connection,
                    "SELECT scope_key FROM platform.feature_flag WHERE id = '" + flag + "'"))
                    .isEqualTo("MARKETPLACE_ACCOUNT:" + ":" + ACCOUNT_A + ":" + ":");
        }
    }

    @Test
    @DisplayName("TC-DB-210 a malformed or duplicated secret reference is refused")
    void secretReferenceShapeAndUniquenessHold() throws SQLException {
        assertRejected(CHECK_VIOLATION,
                credential(UUID.randomUUID(), ORG_A, ACCOUNT_A, "bad-reference",
                        "vault/not-a-reference"));
        assertRejected(UNIQUE_VIOLATION,
                credential(UUID.randomUUID(), ORG_A, ACCOUNT_A, "aliased-credential",
                        "secret-ref://vault/marketops/account-a/read"));
    }

    @Test
    @DisplayName("TC-DB-211 revocation releases a secret reference for a successor")
    void revocationReleasesTheSecretReference() throws SQLException {
        UUID retiring = UUID.randomUUID();
        String reference = "secret-ref://vault/marketops/account-a/rotating";
        try (Connection connection = asApplicationRole(container);
             Statement statement = connection.createStatement()) {
            statement.execute(credential(retiring, ORG_A, ACCOUNT_A, "rotating-old", reference));
            statement.execute("UPDATE platform.credential_metadata SET status = 'REVOKED'"
                    + " WHERE id = '" + retiring + "'");
            statement.execute(credential(UUID.randomUUID(), ORG_A, ACCOUNT_A,
                    "rotating-new", reference));
        }
    }

    @Test
    @DisplayName("TC-DB-212 recorded verification cannot leave UNVERIFIED, and provenance is mandatory")
    void verificationChecksHold() throws SQLException {
        assertRejected(CHECK_VIOLATION,
                credential(UUID.randomUUID(), ORG_A, ACCOUNT_A, "verified-credential",
                        "secret-ref://vault/marketops/account-a/other")
                        .replace("'UNVERIFIED'", "'VERIFIED'"));
        assertRejected(CHECK_VIOLATION,
                "INSERT INTO platform.platform_capability (id, platform_code,"
                        + " capability_code, display_name, applies_to, read_write_class,"
                        + " subscription_required, verification_state, owner_label,"
                        + " contract_test_status, status, created_at, updated_at)"
                        + " VALUES ('" + UUID.randomUUID() + "', 'OZON', 'orders.push',"
                        + " 'Push orders', 'MARKETPLACE_ACCOUNT', 'WRITE', 'UNKNOWN',"
                        + " 'VERIFIED', 'platform-team', 'NOT_IMPLEMENTED', 'ACTIVE',"
                        + " now(), now())");
    }

    private static void assertRejected(String sqlState, String sql) throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                Throwable failure = Assertions.catchThrowable(() -> statement.execute(sql));
                assertThat(failure)
                        .as("the statement must be refused with SQLSTATE %s", sqlState)
                        .isNotNull();
                assertThat(carriesSqlState(failure, sqlState))
                        .as("expected SQLSTATE %s from: %s", sqlState, failure)
                        .isTrue();
            } finally {
                connection.rollback();
            }
        }
    }

    private static String organization(UUID id, String code) {
        return "INSERT INTO core.organization (id, code, display_name, status,"
                + " created_at, updated_at) VALUES ('" + id + "', '" + code + "', '"
                + code + "', 'ACTIVE', now(), now())";
    }

    private static String legalEntity(UUID id, UUID organizationId, String code) {
        return "INSERT INTO core.legal_entity (id, organization_id, code, display_name,"
                + " status, created_at, updated_at) VALUES ('" + id + "', '"
                + organizationId + "', '" + code + "', '" + code + "', 'ACTIVE',"
                + " now(), now())";
    }

    private static String account(
            UUID id, UUID organizationId, UUID legalEntityId, String platform, String code) {
        return "INSERT INTO core.marketplace_account (id, organization_id, legal_entity_id,"
                + " platform_code, code, display_name, status, created_at, updated_at)"
                + " VALUES ('" + id + "', '" + organizationId + "', '" + legalEntityId
                + "', '" + platform + "', '" + code + "', '" + code + "', 'ACTIVE',"
                + " now(), now())";
    }

    private static String store(UUID id, UUID organizationId, UUID accountId, String code) {
        return "INSERT INTO core.store (id, organization_id, marketplace_account_id, code,"
                + " display_name, status, created_at, updated_at) VALUES ('" + id + "', '"
                + organizationId + "', '" + accountId + "', '" + code + "', '" + code
                + "', 'ACTIVE', now(), now())";
    }

    private static String warehouse(
            UUID id, UUID organizationId, UUID legalEntityId, String code) {
        return "INSERT INTO core.warehouse (id, organization_id, legal_entity_id, code,"
                + " display_name, status, created_at, updated_at) VALUES ('" + id + "', '"
                + organizationId + "', '" + legalEntityId + "', '" + code + "', '" + code
                + "', 'ACTIVE', now(), now())";
    }

    private static String link(UUID id, UUID organizationId, UUID storeId, UUID warehouseId,
                               String from, String to, String status) {
        return "INSERT INTO core.store_warehouse_link (id, organization_id, store_id,"
                + " warehouse_id, fulfillment_mode_code, effective_from, effective_to,"
                + " status, created_at, updated_at) VALUES ('" + id + "', '"
                + organizationId + "', '" + storeId + "', '" + warehouseId
                + "', 'SELLER_FULFILLED', '" + from + "', "
                + (to == null ? "NULL" : "'" + to + "'") + ", '" + status
                + "', now(), now())";
    }

    private static String serviceAccount(UUID id, UUID organizationId, String code) {
        return "INSERT INTO iam.service_account (id, organization_id, code, display_name,"
                + " purpose, owner_label, expires_at, status, created_at, updated_at)"
                + " VALUES ('" + id + "', '" + organizationId + "', '" + code + "', '"
                + code + "', 'reads metadata', 'platform-team',"
                + " now() + interval '90 days', 'ACTIVE', now(), now())";
    }

    private static String grant(
            UUID id, UUID organizationId, UUID serviceAccountId, UUID storeId) {
        return "INSERT INTO iam.service_account_scope_grant (id, organization_id,"
                + " service_account_id, permission_code, store_ref_id, effective_from,"
                + " status, created_at, updated_at) VALUES ('" + id + "', '"
                + organizationId + "', '" + serviceAccountId + "', 'READ', '" + storeId
                + "', now(), 'ACTIVE', now(), now())";
    }

    private static String capability(UUID id, String platform, String code) {
        return "INSERT INTO platform.platform_capability (id, platform_code,"
                + " capability_code, display_name, applies_to, read_write_class,"
                + " subscription_required, verification_state, owner_label,"
                + " contract_test_status, status, created_at, updated_at)"
                + " VALUES ('" + id + "', '" + platform + "', '" + code + "', '" + code
                + "', 'MARKETPLACE_ACCOUNT', 'READ', 'UNKNOWN', 'UNKNOWN',"
                + " 'platform-team', 'NOT_IMPLEMENTED', 'ACTIVE', now(), now())";
    }

    private static String endpoint(UUID id, String platform, String code, UUID capabilityId) {
        return "INSERT INTO platform.platform_endpoint (id, platform_code, endpoint_code,"
                + " api_version, capability_id, read_write_class, pagination_model,"
                + " idempotency_support, verification_state, owner_label,"
                + " contract_test_status, status, created_at, updated_at)"
                + " VALUES ('" + id + "', '" + platform + "', '" + code + "', 'v1', '"
                + capabilityId + "', 'READ', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN',"
                + " 'platform-team', 'NOT_IMPLEMENTED', 'ACTIVE', now(), now())";
    }

    private static String subjectStatus(
            UUID id, UUID organizationId, String platform, UUID capabilityId, UUID accountId) {
        return "INSERT INTO platform.capability_subject_status (id, organization_id,"
                + " platform_code, capability_id, marketplace_account_id, availability,"
                + " created_at, updated_at) VALUES ('" + id + "', '" + organizationId
                + "', '" + platform + "', '" + capabilityId + "', '" + accountId
                + "', 'UNKNOWN', now(), now())";
    }

    private static String credential(
            UUID id, UUID organizationId, UUID accountId, String code, String reference) {
        return "INSERT INTO platform.credential_metadata (id, organization_id,"
                + " marketplace_account_id, code, display_name, purpose_code, scope_mode,"
                + " secret_reference, effective_from, expires_at, status, custodian_label,"
                + " verification_state, created_at, updated_at) VALUES ('" + id + "', '"
                + organizationId + "', '" + accountId + "', '" + code + "', '" + code
                + "', 'READ', 'ACCOUNT', '" + reference + "', now(),"
                + " now() + interval '180 days', 'ACTIVE', 'platform-team',"
                + " 'UNVERIFIED', now(), now())";
    }

    private static String storeScope(UUID id, UUID credentialId, UUID accountId, UUID storeId) {
        return "INSERT INTO platform.credential_store_scope (id, credential_id,"
                + " marketplace_account_id, store_id, status, created_at, updated_at)"
                + " VALUES ('" + id + "', '" + credentialId + "', '" + accountId + "', '"
                + storeId + "', 'ACTIVE', now(), now())";
    }
}
