package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mimococo.marketops.identityaccess.AccessMetadataDirectory;
import com.mimococo.marketops.identityaccess.ServiceAccountEvaluation;
import com.mimococo.marketops.marketplaceintegration.CapabilityDirectory;
import com.mimococo.marketops.marketplaceintegration.CapabilityUsability;
import com.mimococo.marketops.marketplaceintegration.FeatureFlagDirectory;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The metadata maintenance surface end to end: guard, commands, queries,
 * derived read-side state, stable refusals and the audit journal, against a
 * real migrated database.
 *
 * <p>The scenarios run in dependency order and build one operating graph. Every
 * refusal asserts its stable error code, and the journal is read back through
 * the same API an operator uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
@Import(MetadataMaintenanceApiIT.BindingProbeController.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MetadataMaintenanceApiIT {

    private static final String OPERATOR_HEADER = "X-Operator";
    private static final String OPERATOR = "ivan.petrov";
    private static final String BASE = "/api/v1/admin/metadata";

    private static String organizationId;
    private static String otherOrganizationId;
    private static String legalEntityId;
    private static String otherLegalEntityId;
    private static String accountId;
    private static String otherAccountId;
    private static String storeId;
    private static String otherStoreId;
    private static String warehouseId;
    private static String linkId;
    private static String declarationId;
    private static String serviceAccountId;
    private static String allowedSourceId;
    private static String grantId;
    private static String accountCredentialId;
    private static String replacementCredentialId;
    private static String storeSetCredentialId;
    private static String storeSetScopeId;
    private static String capabilityId;
    private static String endpointId;
    private static String flagId;
    private static String writeFlagId;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        var container = TestDatabase.container();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", () -> TestDatabase.applicationRole());
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", () -> TestDatabase.migrationRole());
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccessMetadataDirectory accessMetadataDirectory;

    @Autowired
    private CapabilityDirectory capabilityDirectory;

    @Autowired
    private FeatureFlagDirectory featureFlagDirectory;

    @Test
    @Order(1)
    @DisplayName("TC-API-001 a mutation without operator attribution is refused and journaled")
    void mutationWithoutAttributionIsRefused() throws Exception {
        mockMvc.perform(post(BASE + "/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"attribution-free\",\"displayName\":\"No one\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("OPERATOR_ATTRIBUTION_MISSING"));

        JsonNode denials = getJson(BASE + "/audit-events?action=DENIED");
        assertThat(denials.isEmpty()).isFalse();
        JsonNode latest = denials.get(0);
        assertThat(latest.get("actorType").asText()).isEqualTo("SYSTEM");
        assertThat(latest.get("actorId").asText()).isEqualTo("metadata-maintenance-boundary");
        assertThat(latest.get("denialCode").asText()).isEqualTo("OPERATOR_ATTRIBUTION_MISSING");
    }

    @Test
    @Order(2)
    @DisplayName("TC-API-002 an unknown request field is refused, not silently dropped")
    void unknownFieldIsRefused() throws Exception {
        mutate(post(BASE + "/organizations"),
                "{\"code\":\"smuggler\",\"displayName\":\"Smuggler\",\"secretValue\":\"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));
    }

    @Test
    @Order(3)
    @DisplayName("TC-API-003 binding and referential refusals are safe and journaled once")
    void bindingAndReferentialRefusalsAreSafeAndJournaledOnce() throws Exception {
        assertSafeValidationDenial(
                post(BASE + "/binding-probe/not-a-uuid-sensitive").param("limit", "1"),
                "not-a-uuid-sensitive");
        assertSafeValidationDenial(
                post(BASE + "/binding-probe/" + UUID.randomUUID())
                        .param("limit", "not-an-integer-sensitive"),
                "not-an-integer-sensitive");
        assertSafeValidationDenial(
                post(BASE + "/binding-probe/" + UUID.randomUUID()),
                "MissingServletRequestParameterException");
        assertSafeValidationDenial(
                post(BASE + "/binding-probe/foreign-key"),
                "unrelated_fk_sensitive_detail");
    }

    @Test
    @Order(4)
    @DisplayName("TC-API-010 the operating graph is created through the API")
    void operatingGraphIsCreated() throws Exception {
        organizationId = created(post(BASE + "/organizations"),
                "{\"code\":\"mimococo\",\"displayName\":\"Mimococo\","
                        + "\"defaultTimezone\":\"Europe/Moscow\","
                        + "\"defaultCurrencyCode\":\"RUB\"}")
                .get("id").asText();
        otherOrganizationId = created(post(BASE + "/organizations"),
                "{\"code\":\"neighbour\",\"displayName\":\"Neighbour\"}")
                .get("id").asText();
        legalEntityId = created(post(BASE + "/legal-entities"),
                "{\"organizationId\":\"" + organizationId + "\",\"code\":\"mimococo-llc\","
                        + "\"displayName\":\"Mimococo LLC\",\"countryCode\":\"RU\"}")
                .get("id").asText();
        otherLegalEntityId = created(post(BASE + "/legal-entities"),
                "{\"organizationId\":\"" + otherOrganizationId + "\","
                        + "\"code\":\"neighbour-llc\",\"displayName\":\"Neighbour LLC\"}")
                .get("id").asText();
        accountId = created(post(BASE + "/marketplace-accounts"),
                "{\"legalEntityId\":\"" + legalEntityId + "\",\"platformCode\":\"OZON\","
                        + "\"code\":\"ozon-main\",\"displayName\":\"Ozon Main\"}")
                .get("id").asText();
        otherAccountId = created(post(BASE + "/marketplace-accounts"),
                "{\"legalEntityId\":\"" + otherLegalEntityId + "\","
                        + "\"platformCode\":\"WILDBERRIES\",\"code\":\"wb-main\","
                        + "\"displayName\":\"WB Main\"}")
                .get("id").asText();
        storeId = created(post(BASE + "/stores"),
                "{\"marketplaceAccountId\":\"" + accountId + "\",\"code\":\"ozon-store-1\","
                        + "\"displayName\":\"Ozon Store 1\"}")
                .get("id").asText();
        otherStoreId = created(post(BASE + "/stores"),
                "{\"marketplaceAccountId\":\"" + otherAccountId + "\",\"code\":\"wb-store-1\","
                        + "\"displayName\":\"WB Store 1\"}")
                .get("id").asText();
        warehouseId = created(post(BASE + "/warehouses"),
                "{\"legalEntityId\":\"" + legalEntityId + "\",\"code\":\"msk-hub\","
                        + "\"displayName\":\"Moscow Hub\",\"timezone\":\"Europe/Moscow\"}")
                .get("id").asText();
    }

    @Test
    @Order(5)
    @DisplayName("TC-API-011 a duplicate business code names the surviving resource")
    void duplicateCodeIsRefusedWithTheConflictingResource() throws Exception {
        mutate(post(BASE + "/organizations"),
                "{\"code\":\"mimococo\",\"displayName\":\"Duplicate\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("DUPLICATE_IDENTITY"))
                .andExpect(jsonPath("$.conflictingResourceId").value(organizationId));
    }

    @Test
    @Order(6)
    @DisplayName("TC-API-012 a stale expected version is refused")
    void staleVersionIsRefused() throws Exception {
        mutate(put(BASE + "/organizations/" + organizationId),
                "{\"displayName\":\"Renamed\",\"expectedVersion\":7}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("VERSION_CONFLICT"));
    }

    @Test
    @Order(7)
    @DisplayName("TC-API-013 overlapping active service intervals are refused")
    void overlappingLinkIsRefused() throws Exception {
        linkId = created(post(BASE + "/store-warehouse-links"),
                "{\"storeId\":\"" + storeId + "\",\"warehouseId\":\"" + warehouseId + "\","
                        + "\"fulfillmentModeCode\":\"SELLER_FULFILLED\","
                        + "\"effectiveFrom\":\"2026-01-01T00:00:00Z\","
                        + "\"effectiveTo\":\"2026-07-01T00:00:00Z\"}")
                .get("id").asText();
        mutate(post(BASE + "/store-warehouse-links"),
                "{\"storeId\":\"" + storeId + "\",\"warehouseId\":\"" + warehouseId + "\","
                        + "\"fulfillmentModeCode\":\"SELLER_FULFILLED\","
                        + "\"effectiveFrom\":\"2026-03-01T00:00:00Z\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("EFFECTIVE_RANGE_OVERLAP"));
    }

    @Test
    @Order(8)
    @DisplayName("TC-API-020 grants are explicit, own-organization and unrepeatable")
    void grantsAreExplicitAndBounded() throws Exception {
        serviceAccountId = created(post(BASE + "/service-accounts"),
                "{\"organizationId\":\"" + organizationId + "\",\"code\":\"sync-robot\","
                        + "\"displayName\":\"Sync Robot\",\"purpose\":\"reads metadata\","
                        + "\"ownerLabel\":\"platform-team\",\"expiresAt\":\""
                        + Instant.now().plusSeconds(90 * 24 * 3600) + "\"}")
                .get("account").get("id").asText();

        grantId = created(post(BASE + "/scope-grants"),
                "{\"serviceAccountId\":\"" + serviceAccountId + "\","
                        + "\"permissionCode\":\"READ\",\"resourceType\":\"STORE\","
                        + "\"resourceId\":\"" + storeId + "\","
                        + "\"effectiveFrom\":\"2026-01-01T00:00:00Z\","
                        + "\"reason\":\"scheduled metadata reads\"}")
                .get("id").asText();
        mutate(post(BASE + "/scope-grants"),
                "{\"serviceAccountId\":\"" + serviceAccountId + "\","
                        + "\"permissionCode\":\"READ\",\"resourceType\":\"STORE\","
                        + "\"resourceId\":\"" + storeId + "\","
                        + "\"effectiveFrom\":\"2026-01-01T00:00:00Z\","
                        + "\"reason\":\"repeated grant\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("DUPLICATE_IDENTITY"));
        mutate(post(BASE + "/scope-grants"),
                "{\"serviceAccountId\":\"" + serviceAccountId + "\","
                        + "\"permissionCode\":\"READ\",\"resourceType\":\"STORE\","
                        + "\"resourceId\":\"" + otherStoreId + "\","
                        + "\"effectiveFrom\":\"2026-01-01T00:00:00Z\","
                        + "\"reason\":\"crossing organizations\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("CROSS_ORGANIZATION_REJECTED"));
    }

    @Test
    @Order(9)
    @DisplayName("TC-API-030 secret-like text and malformed references never enter")
    void secretMaterialAndMalformedReferencesAreRefused() throws Exception {
        mutate(post(BASE + "/credentials"),
                credentialBody(accountId, "guarded", "ACCOUNT",
                        "secret-ref://vault/marketops/guarded/read", null)
                        .replace("\"displayName\":\"guarded\"",
                                "\"displayName\":\"api_key = QQ11\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("SECRET_MATERIAL_SUSPECTED"));
        mutate(post(BASE + "/credentials"),
                credentialBody(accountId, "malformed", "ACCOUNT",
                        "vault/not-a-reference", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("SECRET_REFERENCE_INVALID"));
    }

    @Test
    @Order(10)
    @DisplayName("TC-API-031 the credential scope contract is explicit in both modes")
    void credentialScopeContractIsExplicit() throws Exception {
        mutate(post(BASE + "/credentials"),
                credentialBody(accountId, "account-wide", "ACCOUNT",
                        "secret-ref://vault/marketops/ozon-main/read",
                        "[\"" + storeId + "\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));
        mutate(post(BASE + "/credentials"),
                credentialBody(accountId, "store-bound", "STORE_SET",
                        "secret-ref://vault/marketops/ozon-main/store-read", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));
        mutate(post(BASE + "/credentials"),
                credentialBody(accountId, "store-bound", "STORE_SET",
                        "secret-ref://vault/marketops/ozon-main/store-read",
                        "[\"" + otherStoreId + "\"]"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("CROSS_ORGANIZATION_REJECTED"));

        JsonNode accountWide = created(post(BASE + "/credentials"),
                credentialBody(accountId, "account-wide", "ACCOUNT",
                        "secret-ref://vault/marketops/ozon-main/read", null));
        accountCredentialId = accountWide.get("credential").get("id").asText();
        assertThat(accountWide.get("scopeUsability").asText()).isEqualTo("ACCOUNT_WIDE");

        JsonNode storeSet = created(post(BASE + "/credentials"),
                credentialBody(accountId, "store-bound", "STORE_SET",
                        "secret-ref://vault/marketops/ozon-main/store-read",
                        "[\"" + storeId + "\"]"));
        storeSetCredentialId = storeSet.get("credential").get("id").asText();
        storeSetScopeId = storeSet.get("storeScopes").get(0).get("id").asText();
        assertThat(storeSet.get("scopeUsability").asText()).isEqualTo("STORE_SET");
    }

    @Test
    @Order(11)
    @DisplayName("TC-API-032 an emptied store set fails closed instead of widening")
    void emptiedStoreSetFailsClosed() throws Exception {
        mutate(post(BASE + "/credentials/" + accountCredentialId + "/store-scopes"),
                "{\"storeId\":\"" + storeId + "\",\"reason\":\"scoping an account credential\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("INVALID_STATE_TRANSITION"));

        mutate(post(BASE + "/credentials/" + storeSetCredentialId
                        + "/store-scopes/" + storeSetScopeId + "/status"),
                "{\"reason\":\"store paused for the season\",\"expectedVersion\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        JsonNode view = getJson(BASE + "/credentials/" + storeSetCredentialId);
        assertThat(view.get("scopeUsability").asText()).isEqualTo("NO_ACTIVE_STORE_SCOPE");
        assertThat(view.get("credential").get("scopeMode").asText()).isEqualTo("STORE_SET");
    }

    @Test
    @Order(12)
    @DisplayName("TC-API-033 rotation is a lineage with an overlap window")
    void rotationIsALineage() throws Exception {
        mutate(post(BASE + "/credentials"),
                credentialBody(accountId, "aliased", "ACCOUNT",
                        "secret-ref://vault/marketops/ozon-main/read", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("DUPLICATE_IDENTITY"));

        replacementCredentialId = created(post(BASE + "/credentials"),
                credentialBody(accountId, "account-wide-v2", "ACCOUNT",
                        "secret-ref://vault/marketops/ozon-main/read-v2", null)
                        .replace("}", ",\"replacesCredentialId\":\""
                                + accountCredentialId + "\"}"))
                .get("credential").get("id").asText();

        JsonNode predecessor = getJson(BASE + "/credentials/" + accountCredentialId);
        assertThat(predecessor.get("rotationStatus").asText()).isEqualTo("BEING_REPLACED");
    }

    @Test
    @Order(13)
    @DisplayName("TC-API-040 the registry records structure and refuses VERIFIED")
    void registryRecordsStructureAndRefusesVerified() throws Exception {
        capabilityId = created(post(BASE + "/capabilities"),
                "{\"platformCode\":\"OZON\",\"capabilityCode\":\"orders.read\","
                        + "\"displayName\":\"Read orders\","
                        + "\"appliesTo\":\"MARKETPLACE_ACCOUNT\","
                        + "\"readWriteClass\":\"READ\",\"subscriptionRequired\":\"UNKNOWN\","
                        + "\"ownerLabel\":\"platform-team\"}")
                .get("id").asText();
        endpointId = created(post(BASE + "/endpoints"),
                "{\"platformCode\":\"OZON\",\"endpointCode\":\"orders.list\","
                        + "\"apiVersion\":\"v3\",\"capabilityId\":\"" + capabilityId + "\","
                        + "\"readWriteClass\":\"READ\",\"paginationModel\":\"UNKNOWN\","
                        + "\"idempotencySupport\":\"UNKNOWN\","
                        + "\"ownerLabel\":\"platform-team\"}")
                .get("id").asText();

        mutate(post(BASE + "/capabilities/" + capabilityId + "/verification"),
                "{\"target\":\"UNVERIFIED\",\"reason\":\"registered for future evidence\","
                        + "\"expectedVersion\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value("UNVERIFIED"));
        mutate(post(BASE + "/capabilities/" + capabilityId + "/verification"),
                "{\"target\":\"VERIFIED\",\"reason\":\"attempting verification\","
                        + "\"expectedVersion\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title")
                        .value("CAPABILITY_VERIFICATION_NOT_SUPPORTED"));

        JsonNode journal = getJson(
                BASE + "/capabilities/" + capabilityId + "/verification-events");
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0).get("fromState").asText()).isEqualTo("UNKNOWN");
        assertThat(journal.get(0).get("toState").asText()).isEqualTo("UNVERIFIED");
        assertThat(journal.get(0).get("actor").asText()).isEqualTo(OPERATOR);
    }

    @Test
    @Order(14)
    @DisplayName("TC-API-041 subject statuses stay unknown and evaluate fail-closed")
    void subjectStatusesStayUnknownAndFailClosed() throws Exception {
        mutate(post(BASE + "/capability-subject-statuses"),
                "{\"capabilityId\":\"" + capabilityId + "\",\"storeId\":\""
                        + storeId + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));
        mutate(post(BASE + "/capability-subject-statuses"),
                "{\"capabilityId\":\"" + capabilityId + "\","
                        + "\"marketplaceAccountId\":\"" + otherAccountId + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));

        created(post(BASE + "/capability-subject-statuses"),
                "{\"capabilityId\":\"" + capabilityId + "\","
                        + "\"marketplaceAccountId\":\"" + accountId + "\"}");

        JsonNode matrix = getJson(
                BASE + "/capability-subject-statuses?capabilityId=" + capabilityId);
        assertThat(matrix).hasSize(1);
        assertThat(matrix.get(0).get("status").get("availability").asText())
                .isEqualTo("UNKNOWN");
        assertThat(matrix.get(0).get("usability").asText()).isEqualTo("NOT_VERIFIED");
    }

    @Test
    @Order(15)
    @DisplayName("TC-API-042 permission requirements record the platform's own language")
    void permissionRequirementsAreRecorded() throws Exception {
        created(post(BASE + "/platform-permission-requirements"),
                "{\"platformCode\":\"OZON\",\"capabilityId\":\"" + capabilityId + "\","
                        + "\"requirementKind\":\"API_ROLE\","
                        + "\"externalCode\":\"seller-orders-read\","
                        + "\"verificationState\":\"UNVERIFIED\"}");
        mutate(post(BASE + "/platform-permission-requirements"),
                "{\"platformCode\":\"OZON\",\"capabilityId\":\"" + capabilityId + "\","
                        + "\"endpointId\":\"" + endpointId + "\","
                        + "\"requirementKind\":\"API_ROLE\",\"externalCode\":\"twice\","
                        + "\"verificationState\":\"UNKNOWN\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));
        mutate(post(BASE + "/platform-permission-requirements"),
                "{\"platformCode\":\"OZON\",\"capabilityId\":\"" + capabilityId + "\","
                        + "\"requirementKind\":\"API_ROLE\","
                        + "\"externalCode\":\"claimed-verified\","
                        + "\"verificationState\":\"VERIFIED\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title")
                        .value("CAPABILITY_VERIFICATION_NOT_SUPPORTED"));

        JsonNode listed = getJson(BASE
                + "/platform-permission-requirements?capabilityId=" + capabilityId);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("externalCode").asText())
                .isEqualTo("seller-orders-read");
    }

    @Test
    @Order(16)
    @DisplayName("TC-API-050 flags start disabled; the write direction is gated, the kill direction is not")
    void flagsFailClosedInTheWriteDirection() throws Exception {
        JsonNode flag = created(post(BASE + "/feature-flags"),
                "{\"flagCode\":\"metadata.console\",\"flagKind\":\"OPERATIONAL\","
                        + "\"scopeKind\":\"GLOBAL\"}");
        flagId = flag.get("id").asText();
        assertThat(flag.get("state").asText()).isEqualTo("DISABLED");

        mutate(post(BASE + "/feature-flags/" + flagId + "/state"),
                "{\"target\":\"ENABLED\",\"reason\":\"console rollout\","
                        + "\"expectedVersion\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ENABLED"));

        writeFlagId = created(post(BASE + "/feature-flags"),
                "{\"flagCode\":\"ozon.price.write\",\"flagKind\":\"WRITE_CAPABILITY\","
                        + "\"scopeKind\":\"CAPABILITY\",\"capabilityId\":\""
                        + capabilityId + "\"}")
                .get("id").asText();
        mutate(post(BASE + "/feature-flags/" + writeFlagId + "/state"),
                "{\"target\":\"ENABLED\",\"reason\":\"attempting a platform write\","
                        + "\"expectedVersion\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("PRODUCTION_WRITE_DISABLED"));

        mutate(post(BASE + "/feature-flags/" + flagId + "/status"),
                "{\"reason\":\"retiring while enabled\",\"expectedVersion\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("INVALID_STATE_TRANSITION"));
        mutate(post(BASE + "/feature-flags/" + flagId + "/state"),
                "{\"target\":\"DISABLED\",\"reason\":\"kill direction is never gated\","
                        + "\"expectedVersion\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DISABLED"));
    }

    @Test
    @Order(17)
    @DisplayName("TC-API-060 retirement is vetoed while live references exist")
    void retirementIsVetoedWhileReferencesExist() throws Exception {
        mutate(post(BASE + "/marketplace-accounts/" + accountId + "/status"),
                "{\"target\":\"RETIRED\",\"reason\":\"attempting retirement\","
                        + "\"expectedVersion\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("REFERENCED_ENTITY_ACTIVE"));
        mutate(post(BASE + "/stores/" + storeId + "/status"),
                "{\"target\":\"RETIRED\",\"reason\":\"attempting retirement\","
                        + "\"expectedVersion\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("REFERENCED_ENTITY_ACTIVE"));
    }

    @Test
    @Order(18)
    @DisplayName("TC-API-070 the journal attributes changes and denials truthfully")
    void journalAttributesChangesAndDenials() throws Exception {
        JsonNode changes = getJson(BASE + "/audit-events?entityType=credential"
                + "&action=CREATE");
        assertThat(changes.isEmpty()).isFalse();
        assertThat(changes.get(0).get("actorType").asText()).isEqualTo("OPERATOR");
        assertThat(changes.get(0).get("actorId").asText()).isEqualTo(OPERATOR);
        assertThat(changes.get(0).get("occurredAt").asText()).isNotBlank();

        JsonNode denials = getJson(BASE + "/audit-events?action=DENIED"
                + "&sourceDomain=MARKETPLACE_INTEGRATION");
        assertThat(denials.isEmpty()).isFalse();
        assertThat(denials.get(0).get("denialCode").asText()).isNotBlank();

        String body = mockMvc.perform(get(BASE + "/audit-events?limit=5"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("secret-ref://vault/marketops/guarded")
                .doesNotContain("api_key");
    }

    @Test
    @Order(19)
    @DisplayName("TC-API-071 queries need no attribution and lists paginate by keyset")
    void queriesAreOpenAndPaged() throws Exception {
        JsonNode organizations = getJson(BASE + "/organizations?limit=1");
        assertThat(organizations).hasSize(1);
        String firstCode = organizations.get(0).get("code").asText();

        JsonNode next = getJson(BASE + "/organizations?limit=1&afterCode=" + firstCode);
        assertThat(next).hasSize(1);
        assertThat(next.get(0).get("code").asText()).isGreaterThan(firstCode);
    }

    @Test
    @Order(20)
    @DisplayName("TC-API-080 core metadata supports complete update, lookup, list and lifecycle paths")
    void coreMetadataSupportsCompleteMaintenancePaths() throws Exception {
        ok(put(BASE + "/organizations/" + organizationId),
                "{\"displayName\":\"Mimococo Updated\","
                        + "\"defaultTimezone\":\"Europe/Moscow\","
                        + "\"defaultCurrencyCode\":\"RUB\",\"expectedVersion\":0}");
        ok(put(BASE + "/legal-entities/" + legalEntityId),
                "{\"displayName\":\"Mimococo Legal Updated\","
                        + "\"registeredName\":\"Mimococo Registered\","
                        + "\"countryCode\":\"RU\",\"expectedVersion\":0}");
        ok(put(BASE + "/marketplace-accounts/" + accountId),
                "{\"displayName\":\"Ozon Main Updated\","
                        + "\"nativeAccountKey\":\"seller-001\","
                        + "\"reason\":\"native identity confirmed\",\"expectedVersion\":0}");
        ok(put(BASE + "/stores/" + storeId),
                "{\"displayName\":\"Ozon Store Updated\","
                        + "\"nativeStoreKey\":\"store-001\","
                        + "\"timezone\":\"Europe/Moscow\",\"currencyCode\":\"RUB\","
                        + "\"reason\":\"native identity confirmed\",\"expectedVersion\":0}");
        ok(put(BASE + "/warehouses/" + warehouseId),
                "{\"displayName\":\"Moscow Hub Updated\","
                        + "\"timezone\":\"Europe/Moscow\",\"expectedVersion\":0}");

        getJson(BASE + "/organizations/" + organizationId);
        getJson(BASE + "/legal-entities/" + legalEntityId);
        getJson(BASE + "/marketplace-accounts/" + accountId);
        getJson(BASE + "/stores/" + storeId);
        getJson(BASE + "/warehouses/" + warehouseId);
        assertThat(getJson(BASE + "/legal-entities?organizationId=" + organizationId)).isNotEmpty();
        assertThat(getJson(BASE + "/marketplace-accounts?organizationId=" + organizationId)).isNotEmpty();
        assertThat(getJson(BASE + "/stores?organizationId=" + organizationId)).isNotEmpty();
        assertThat(getJson(BASE + "/warehouses?organizationId=" + organizationId)).isNotEmpty();

        String lifecycleOrganization = created(post(BASE + "/organizations"),
                "{\"code\":\"lifecycle-org\",\"displayName\":\"Lifecycle Org\"}")
                .get("id").asText();
        transition("organizations", lifecycleOrganization, "SUSPENDED", 0);
        transition("organizations", lifecycleOrganization, "ACTIVE", 1);
        transition("organizations", lifecycleOrganization, "RETIRED", 2);

        String lifecycleLegalEntity = created(post(BASE + "/legal-entities"),
                "{\"organizationId\":\"" + organizationId + "\","
                        + "\"code\":\"lifecycle-legal\",\"displayName\":\"Lifecycle Legal\"}")
                .get("id").asText();
        transition("legal-entities", lifecycleLegalEntity, "SUSPENDED", 0);
        transition("legal-entities", lifecycleLegalEntity, "ACTIVE", 1);
        transition("legal-entities", lifecycleLegalEntity, "RETIRED", 2);

        String lifecycleAccount = created(post(BASE + "/marketplace-accounts"),
                "{\"legalEntityId\":\"" + legalEntityId + "\",\"platformCode\":\"OZON\","
                        + "\"code\":\"lifecycle-account\","
                        + "\"displayName\":\"Lifecycle Account\"}")
                .get("id").asText();
        transition("marketplace-accounts", lifecycleAccount, "SUSPENDED", 0);
        transition("marketplace-accounts", lifecycleAccount, "ACTIVE", 1);
        transition("marketplace-accounts", lifecycleAccount, "RETIRED", 2);

        String lifecycleStore = created(post(BASE + "/stores"),
                "{\"marketplaceAccountId\":\"" + accountId + "\","
                        + "\"code\":\"lifecycle-store\",\"displayName\":\"Lifecycle Store\"}")
                .get("id").asText();
        transition("stores", lifecycleStore, "SUSPENDED", 0);
        transition("stores", lifecycleStore, "ACTIVE", 1);
        transition("stores", lifecycleStore, "RETIRED", 2);

        String lifecycleWarehouse = created(post(BASE + "/warehouses"),
                "{\"legalEntityId\":\"" + legalEntityId + "\","
                        + "\"code\":\"lifecycle-warehouse\","
                        + "\"displayName\":\"Lifecycle Warehouse\","
                        + "\"timezone\":\"Europe/Moscow\"}")
                .get("id").asText();
        transition("warehouses", lifecycleWarehouse, "SUSPENDED", 0);
        transition("warehouses", lifecycleWarehouse, "ACTIVE", 1);
        transition("warehouses", lifecycleWarehouse, "RETIRED", 2);
    }

    @Test
    @Order(21)
    @DisplayName("TC-API-081 associations update, list and terminate without deleting history")
    void associationsSupportCompleteMaintenancePaths() throws Exception {
        ok(put(BASE + "/store-warehouse-links/" + linkId),
                "{\"effectiveFrom\":\"2026-01-01T00:00:00Z\","
                        + "\"effectiveTo\":\"2027-07-01T00:00:00Z\","
                        + "\"note\":\"extended operating window\",\"expectedVersion\":0}");
        ok(post(BASE + "/store-warehouse-links/" + linkId + "/status"),
                "{\"target\":\"ENDED\",\"reason\":\"window closed\","
                        + "\"expectedVersion\":1}");

        declarationId = created(post(BASE + "/store-fulfillment-declarations"),
                "{\"storeId\":\"" + storeId + "\","
                        + "\"fulfillmentModeCode\":\"MARKETPLACE_FULFILLED\","
                        + "\"effectiveFrom\":\"2026-01-01T00:00:00Z\"}")
                .get("id").asText();
        ok(post(BASE + "/store-fulfillment-declarations/" + declarationId + "/status"),
                "{\"target\":\"ENDED\",\"reason\":\"declaration closed\","
                        + "\"expectedVersion\":0}");

        assertThat(getJson(BASE + "/store-warehouse-links?storeId=" + storeId)).isNotEmpty();
        assertThat(getJson(BASE + "/store-fulfillment-declarations?storeId=" + storeId))
                .isNotEmpty();
        mutate(post(BASE + "/store-warehouse-links"),
                "{\"storeId\":\"" + otherStoreId + "\",\"warehouseId\":\""
                        + warehouseId + "\",\"fulfillmentModeCode\":\"SELLER_FULFILLED\","
                        + "\"effectiveFrom\":\"2028-01-01T00:00:00Z\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("CROSS_ORGANIZATION_REJECTED"));
        mutate(post(BASE + "/store-fulfillment-declarations"),
                "{\"storeId\":\"" + storeId + "\","
                        + "\"fulfillmentModeCode\":\"UNKNOWN-MODE\","
                        + "\"effectiveFrom\":\"2028-01-01T00:00:00Z\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @Order(22)
    @DisplayName("TC-API-082 service accounts, sources and grants complete their lifecycle")
    void identityAccessSupportsCompleteMaintenancePaths() throws Exception {
        ok(put(BASE + "/service-accounts/" + serviceAccountId),
                "{\"displayName\":\"Sync Robot Updated\","
                        + "\"purpose\":\"reads and reconciles metadata\","
                        + "\"ownerLabel\":\"platform-operations\","
                        + "\"expiresAt\":\"2027-12-31T00:00:00Z\",\"expectedVersion\":0}");
        allowedSourceId = created(post(BASE + "/service-accounts/" + serviceAccountId
                        + "/allowed-sources"),
                "{\"cidr\":\"10.20.0.0/16\",\"note\":\"operations network\"}")
                .get("id").asText();
        assertThat(getJson(BASE + "/service-accounts/" + serviceAccountId
                + "/allowed-sources")).hasSize(1);
        ok(post(BASE + "/service-accounts/" + serviceAccountId + "/allowed-sources/"
                        + allowedSourceId + "/status"),
                "{\"target\":\"WITHDRAWN\",\"reason\":\"network retired\","
                        + "\"expectedVersion\":0}");

        assertThat(getJson(BASE + "/scope-grants?serviceAccountId=" + serviceAccountId))
                .isNotEmpty();
        ok(post(BASE + "/scope-grants/" + grantId + "/revoke"),
                "{\"reason\":\"scheduled access ended\",\"expectedVersion\":0}");
        created(post(BASE + "/scope-grants"),
                "{\"serviceAccountId\":\"" + serviceAccountId + "\","
                        + "\"permissionCode\":\"READ\",\"resourceType\":\"ORGANIZATION\","
                        + "\"resourceId\":\"" + organizationId + "\","
                        + "\"effectiveFrom\":\"2026-01-01T00:00:00Z\","
                        + "\"reason\":\"organization reconciliation\"}");

        assertThat(accessMetadataDirectory.evaluate(UUID.fromString(serviceAccountId)))
                .isEqualTo(ServiceAccountEvaluation.ACTIVE);
        ok(post(BASE + "/service-accounts/" + serviceAccountId + "/status"),
                "{\"target\":\"DISABLED\",\"reason\":\"maintenance pause\","
                        + "\"expectedVersion\":1}");
        assertThat(accessMetadataDirectory.evaluate(UUID.fromString(serviceAccountId)))
                .isEqualTo(ServiceAccountEvaluation.DISABLED);
        ok(post(BASE + "/service-accounts/" + serviceAccountId + "/status"),
                "{\"target\":\"ACTIVE\",\"reason\":\"maintenance complete\","
                        + "\"expectedVersion\":2}");
        ok(post(BASE + "/service-accounts/" + serviceAccountId + "/status"),
                "{\"target\":\"REVOKED\",\"reason\":\"account retired\","
                        + "\"expectedVersion\":3}");
        assertThat(accessMetadataDirectory.evaluate(UUID.fromString(serviceAccountId)))
                .isEqualTo(ServiceAccountEvaluation.REVOKED);
        getJson(BASE + "/service-accounts/" + serviceAccountId);
        assertThat(getJson(BASE + "/service-accounts?organizationId=" + organizationId))
                .isNotEmpty();
    }

    @Test
    @Order(23)
    @DisplayName("TC-API-083 credentials update, transition and change scope explicitly")
    void credentialsSupportCompleteMaintenancePaths() throws Exception {
        ok(put(BASE + "/credentials/" + accountCredentialId),
                "{\"displayName\":\"Account Credential Updated\","
                        + "\"custodianLabel\":\"platform-operations\","
                        + "\"expectedVersion\":0}");
        ok(post(BASE + "/credentials/" + accountCredentialId + "/status"),
                "{\"target\":\"DISABLED\",\"reason\":\"rotation pause\","
                        + "\"expectedVersion\":1}");
        ok(post(BASE + "/credentials/" + accountCredentialId + "/status"),
                "{\"target\":\"ACTIVE\",\"reason\":\"rotation resumed\","
                        + "\"expectedVersion\":2}");

        JsonNode narrowed = ok(post(BASE + "/credentials/" + accountCredentialId + "/scope-mode"),
                "{\"target\":\"STORE_SET\",\"storeIds\":[\"" + storeId + "\"],"
                        + "\"reason\":\"narrow to one store\",\"expectedVersion\":3}");
        String narrowedScopeId = narrowed.get("storeScopes").get(0).get("id").asText();
        ok(post(BASE + "/credentials/" + accountCredentialId + "/store-scopes/"
                        + narrowedScopeId + "/status"),
                "{\"reason\":\"prepare account scope\",\"expectedVersion\":0}");
        ok(post(BASE + "/credentials/" + accountCredentialId + "/scope-mode"),
                "{\"target\":\"ACCOUNT\",\"reason\":\"restore account scope\","
                        + "\"expectedVersion\":4}");

        ok(put(BASE + "/credentials/" + storeSetCredentialId),
                "{\"displayName\":\"Store Credential Updated\","
                        + "\"custodianLabel\":\"platform-operations\","
                        + "\"expectedVersion\":0}");
        created(post(BASE + "/credentials/" + storeSetCredentialId + "/store-scopes"),
                "{\"storeId\":\"" + storeId + "\",\"reason\":\"restore store scope\"}");
        ok(post(BASE + "/credentials/" + storeSetCredentialId + "/status"),
                "{\"target\":\"REVOKED\",\"reason\":\"credential retired\","
                        + "\"expectedVersion\":1}");

        getJson(BASE + "/credentials/" + accountCredentialId);
        getJson(BASE + "/credentials/" + replacementCredentialId);
        assertThat(getJson(BASE + "/credentials?marketplaceAccountId=" + accountId))
                .hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @Order(24)
    @DisplayName("TC-API-084 registry update, verification and both subject/evidence paths work")
    void registrySupportsCompleteMaintenancePaths() throws Exception {
        ok(put(BASE + "/capabilities/" + capabilityId),
                "{\"attributes\":{\"platformCode\":\"OZON\","
                        + "\"capabilityCode\":\"orders.read\","
                        + "\"displayName\":\"Read orders updated\","
                        + "\"description\":\"Evidence-aware order retrieval\","
                        + "\"appliesTo\":\"MARKETPLACE_ACCOUNT\","
                        + "\"readWriteClass\":\"READ\","
                        + "\"subscriptionRequired\":\"NO\","
                        + "\"ownerLabel\":\"platform-operations\"},"
                        + "\"expectedVersion\":1}");
        ok(post(BASE + "/capabilities/" + capabilityId + "/verification"),
                "{\"target\":\"UNKNOWN\",\"reason\":\"evidence withdrawn\","
                        + "\"expectedVersion\":2}");

        ok(put(BASE + "/endpoints/" + endpointId),
                "{\"attributes\":{\"platformCode\":\"OZON\","
                        + "\"endpointCode\":\"orders.list\",\"apiVersion\":\"v3\","
                        + "\"httpMethod\":\"POST\",\"pathTemplate\":\"/v3/orders/list\","
                        + "\"capabilityId\":\"" + capabilityId + "\","
                        + "\"readWriteClass\":\"READ\",\"paginationModel\":\"CURSOR\","
                        + "\"rateLimitPerMinute\":60,\"rateLimitNote\":\"per seller\","
                        + "\"quotaNote\":\"documented quota\","
                        + "\"idempotencySupport\":\"NO\","
                        + "\"lateDataBehavior\":\"eventual\","
                        + "\"freshnessExpectation\":\"five minutes\","
                        + "\"businessKeyNote\":\"posting number\","
                        + "\"schemaVersion\":\"2026-08\","
                        + "\"ownerLabel\":\"platform-operations\"},"
                        + "\"expectedVersion\":0}");
        ok(post(BASE + "/endpoints/" + endpointId + "/verification"),
                "{\"target\":\"UNVERIFIED\",\"reason\":\"contract not proven\","
                        + "\"expectedVersion\":1}");
        ok(post(BASE + "/endpoints/" + endpointId + "/verification"),
                "{\"target\":\"UNKNOWN\",\"reason\":\"evidence withdrawn\","
                        + "\"expectedVersion\":2}");

        String auxiliaryCapability = created(post(BASE + "/capabilities"),
                "{\"platformCode\":\"OZON\",\"capabilityCode\":\"auxiliary.read\","
                        + "\"displayName\":\"Auxiliary Read\","
                        + "\"appliesTo\":\"STORE\",\"readWriteClass\":\"READ\","
                        + "\"subscriptionRequired\":\"UNKNOWN\","
                        + "\"ownerLabel\":\"platform-team\"}")
                .get("id").asText();
        created(post(BASE + "/capability-subject-statuses"),
                "{\"capabilityId\":\"" + auxiliaryCapability + "\",\"storeId\":\""
                        + storeId + "\"}");
        assertThat(capabilityDirectory.usabilityForAccount(
                UUID.fromString(capabilityId), UUID.fromString(accountId)))
                .isEqualTo(CapabilityUsability.NOT_VERIFIED);
        assertThat(capabilityDirectory.usabilityForStore(
                UUID.fromString(auxiliaryCapability), UUID.fromString(storeId)))
                .isEqualTo(CapabilityUsability.NOT_VERIFIED);

        created(post(BASE + "/platform-permission-requirements"),
                "{\"platformCode\":\"OZON\",\"endpointId\":\"" + endpointId + "\","
                        + "\"requirementKind\":\"OAUTH_SCOPE\","
                        + "\"externalCode\":\"orders.read\","
                        + "\"description\":\"official read scope\","
                        + "\"verificationState\":\"UNVERIFIED\"}");

        getJson(BASE + "/capabilities/" + capabilityId);
        getJson(BASE + "/endpoints/" + endpointId);
        assertThat(getJson(BASE + "/capabilities?platformCode=OZON")).isNotEmpty();
        assertThat(getJson(BASE + "/endpoints?platformCode=OZON")).isNotEmpty();
        assertThat(getJson(BASE + "/capabilities/" + capabilityId
                + "/verification-events")).hasSize(2);
        assertThat(getJson(BASE + "/endpoints/" + endpointId
                + "/verification-events")).hasSize(2);
        assertThat(getJson(BASE + "/platform-permission-requirements?endpointId="
                + endpointId)).hasSize(1);
        mutate(post(BASE + "/platform-permission-requirements"),
                "{\"platformCode\":\"OZON\",\"requirementKind\":\"API_ROLE\","
                        + "\"externalCode\":\"unbound\","
                        + "\"verificationState\":\"UNKNOWN\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));

        String auxiliaryEndpoint = created(post(BASE + "/endpoints"),
                "{\"platformCode\":\"OZON\",\"endpointCode\":\"auxiliary.list\","
                        + "\"apiVersion\":\"v1\",\"capabilityId\":\""
                        + auxiliaryCapability + "\",\"readWriteClass\":\"READ\","
                        + "\"paginationModel\":\"NONE\","
                        + "\"idempotencySupport\":\"UNKNOWN\","
                        + "\"ownerLabel\":\"platform-team\"}")
                .get("id").asText();
        ok(post(BASE + "/endpoints/" + auxiliaryEndpoint + "/status"),
                "{\"reason\":\"auxiliary endpoint retired\",\"expectedVersion\":0}");
        ok(post(BASE + "/capabilities/" + auxiliaryCapability + "/status"),
                "{\"reason\":\"auxiliary capability retired\",\"expectedVersion\":0}");
    }

    @Test
    @Order(25)
    @DisplayName("TC-API-085 every feature-flag scope is validated and evaluated fail-closed")
    void featureFlagsCoverEveryScope() throws Exception {
        assertThat(featureFlagDirectory.isEnabledGlobal("metadata.console")).isFalse();
        getJson(BASE + "/feature-flags/" + flagId);
        assertThat(getJson(BASE + "/feature-flags?limit=1")).hasSize(1);
        ok(post(BASE + "/feature-flags/" + flagId + "/status"),
                "{\"reason\":\"console flag retired\",\"expectedVersion\":2}");

        String platformFlag = flag("metadata.platform", "PLATFORM",
                "\"platformCode\":\"OZON\"");
        String accountFlag = flag("metadata.account", "MARKETPLACE_ACCOUNT",
                "\"marketplaceAccountId\":\"" + accountId + "\"");
        String storeFlag = flag("metadata.store", "STORE",
                "\"storeId\":\"" + storeId + "\"");
        String capabilityFlag = flag("metadata.capability", "CAPABILITY",
                "\"capabilityId\":\"" + capabilityId + "\"");

        enable(platformFlag);
        enable(accountFlag);
        enable(storeFlag);
        enable(capabilityFlag);
        assertThat(featureFlagDirectory.isEnabledForPlatform("metadata.platform", "OZON"))
                .isTrue();
        assertThat(featureFlagDirectory.isEnabledForAccount(
                "metadata.account", UUID.fromString(accountId))).isTrue();
        assertThat(featureFlagDirectory.isEnabledForStore(
                "metadata.store", UUID.fromString(storeId))).isTrue();
        assertThat(featureFlagDirectory.isEnabledForCapability(
                "metadata.capability", UUID.fromString(capabilityId))).isTrue();
        assertThat(featureFlagDirectory.isEnabledForPlatform("metadata.platform", null))
                .isFalse();
        assertThat(featureFlagDirectory.isEnabledForAccount("metadata.account", null))
                .isFalse();
        assertThat(featureFlagDirectory.isEnabledForStore("metadata.store", null)).isFalse();
        assertThat(featureFlagDirectory.isEnabledForCapability("metadata.capability", null))
                .isFalse();
        assertThat(featureFlagDirectory.isEnabledGlobal(null)).isFalse();

        mutate(post(BASE + "/feature-flags"),
                "{\"flagCode\":\"metadata.invalid-scope\","
                        + "\"flagKind\":\"OPERATIONAL\",\"scopeKind\":\"GLOBAL\","
                        + "\"platformCode\":\"OZON\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"));
    }

    private void transition(String resource, String id, String target, long expectedVersion)
            throws Exception {
        ok(post(BASE + "/" + resource + "/" + id + "/status"),
                "{\"target\":\"" + target + "\",\"reason\":\"lifecycle exercise\","
                        + "\"expectedVersion\":" + expectedVersion + "}");
    }

    private String flag(String code, String scopeKind, String scopeField) throws Exception {
        return created(post(BASE + "/feature-flags"),
                "{\"flagCode\":\"" + code + "\",\"flagKind\":\"OPERATIONAL\","
                        + "\"scopeKind\":\"" + scopeKind + "\"," + scopeField + "}")
                .get("id").asText();
    }

    private void enable(String id) throws Exception {
        ok(post(BASE + "/feature-flags/" + id + "/state"),
                "{\"target\":\"ENABLED\",\"reason\":\"evaluation exercise\","
                        + "\"expectedVersion\":0}");
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        String response = mutate(request, body)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private org.springframework.test.web.servlet.ResultActions mutate(
            MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request
                .header(OPERATOR_HEADER, OPERATOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void assertSafeValidationDenial(MockHttpServletRequestBuilder request,
                                            String forbiddenText) throws Exception {
        int denialsBefore = denialEvents().size();
        String response = mockMvc.perform(request.header(OPERATOR_HEADER, OPERATOR))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail")
                        .value("The request could not be processed as submitted."))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(forbiddenText);
        JsonNode denials = denialEvents();
        assertThat(denials).hasSize(denialsBefore + 1);
        JsonNode latest = denials.get(0);
        assertThat(latest.get("denialCode").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(latest.get("actorType").asText()).isEqualTo("OPERATOR");
        assertThat(latest.get("actorId").asText()).isEqualTo(OPERATOR);
        assertThat(latest.get("correlationId").asText()).isNotBlank();
    }

    private JsonNode denialEvents() throws Exception {
        return getJson(BASE + "/audit-events?action=DENIED&limit=50");
    }

    private JsonNode created(MockHttpServletRequestBuilder request, String body)
            throws Exception {
        String response = mutate(request, body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode getJson(String uri) throws Exception {
        String response = mockMvc.perform(get(uri))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private static String credentialBody(String account, String code, String scopeMode,
                                         String reference, String storeIds) {
        return "{\"marketplaceAccountId\":\"" + account + "\","
                + "\"code\":\"" + code + "\","
                + "\"displayName\":\"" + code + "\","
                + "\"purposeCode\":\"READ\","
                + "\"scopeMode\":\"" + scopeMode + "\","
                + "\"secretReference\":\"" + reference + "\","
                + "\"effectiveFrom\":\"" + Instant.now().minusSeconds(60) + "\","
                + "\"expiresAt\":\"" + Instant.now().plusSeconds(180 * 24 * 3600) + "\","
                + "\"custodianLabel\":\"platform-team\""
                + (storeIds == null ? "" : ",\"storeIds\":" + storeIds)
                + "}";
    }

    @RestController
    static class BindingProbeController {

        @PostMapping(BASE + "/binding-probe/{entityId}")
        void bind(@PathVariable("entityId") UUID entityId,
                  @RequestParam("limit") int limit) {
            // Successful binding is sufficient; this test surface has no mutation body.
        }

        @PostMapping(BASE + "/binding-probe/foreign-key")
        void unrelatedForeignKeyViolation() {
            throw new DataIntegrityViolationException(
                    "unrelated_fk_sensitive_detail",
                    new SQLException("unrelated_fk_sensitive_detail", "23503"));
        }
    }
}
