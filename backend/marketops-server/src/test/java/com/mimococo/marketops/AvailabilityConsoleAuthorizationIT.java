package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
import com.mimococo.marketops.identityaccess.internal.application.IdentityProviderService;
import com.mimococo.marketops.identityaccess.internal.application.UserAdministrationService;
import com.mimococo.marketops.identityaccess.internal.domain.UserScopeGrantRecord;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The availability console over real filters, real signatures and a real database.
 *
 * <p>The point is that frontend visibility is not authorization. Every route is
 * exercised by a person who is genuinely authenticated and genuinely lacks the
 * grant, because a control that only hides a button is not a control.
 *
 * <p>The cases here are seeded rather than calculated. What is under test is the
 * boundary, and a fixture that had to run a calculation first would fail for
 * reasons that have nothing to do with who may call what.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
@Import(AvailabilityConsoleAuthorizationIT.LocalSigningKey.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AvailabilityConsoleAuthorizationIT {

    private static final String ISSUER = "https://identity.example.invalid/availability";
    private static final String AUDIENCE = "marketops-availability-console";
    private static final String OPERATOR = "availability-console-fixture";
    private static final String QUEUE = "/api/v1/console/availability/cases";
    private static final RSAKey SIGNING_KEY = signingKey();

    private static final UUID ORGANIZATION = UUID.fromString("eeee0000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ORGANIZATION =
            UUID.fromString("eeee0000-0000-0000-0000-000000000002");
    private static final UUID CASE_ID = UUID.fromString("eeee0000-0000-0000-0000-00000000000a");
    private static final UUID OTHER_CASE_ID =
            UUID.fromString("eeee0000-0000-0000-0000-00000000000b");
    private static final UUID SIBLING_CASE_ID =
            UUID.fromString("eeee0000-0000-0000-0000-00000000000c");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired IdentityProviderService providers;
    @Autowired UserAdministrationService users;

    private UUID providerId;
    private UUID userId;
    private UUID primaryVariantId;
    private UUID siblingVariantId;
    private String subject;
    private UserScopeGrantRecord viewGrant;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        var database = TestDatabase.container();
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
        registry.add("marketops.identity.oidc.issuer-uri", () -> ISSUER);
        registry.add("marketops.identity.oidc.jwk-set-uri", () -> ISSUER + "/jwks");
        registry.add("marketops.identity.oidc.audience", () -> AUDIENCE);
    }

    @BeforeAll
    void provisionOperatingGraphAndPerson() {
        primaryVariantId = seedOrganization(ORGANIZATION, "console-acme", CASE_ID);
        siblingVariantId = seedAdditionalCase(ORGANIZATION, "console-sibling", SIBLING_CASE_ID);
        seedOrganization(OTHER_ORGANIZATION, "console-other", OTHER_CASE_ID);

        var provider = providers.register(OPERATOR, "availability-console-provider",
                "Availability OIDC", ISSUER, 900, "synthetic-platform");
        providerId = providers.verifyAndActivate(OPERATOR, provider.id(), "amr", "mfa",
                "evidence://synthetic/availability-console", "Local signed token fixture only",
                provider.version()).id();

        subject = "availability-subject-" + UUID.randomUUID();
        userId = users.provision(OPERATOR, ORGANIZATION, providerId, subject, null,
                "Availability Operator", null).id();
        jdbc.sql("UPDATE iam.user_account SET credentials_valid_from = now() - interval '1 hour'"
                + " WHERE id = :id").param("id", userId).update();
        users.assignRole(OPERATOR, userId, BusinessRoleCode.OWNER, null);
    }

    @Test
    @Order(1)
    @DisplayName("TC-CONSOLE-001 an unauthenticated caller reaches no availability route")
    void anUnauthenticatedCallerReachesNothing() throws Exception {
        mvc.perform(get(QUEUE)).andExpect(status().isUnauthorized());
        mvc.perform(post(QUEUE + "/" + CASE_ID + "/action")
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    @DisplayName("TC-CONSOLE-002 a genuine person without the view grant is refused the queue")
    void theViewGrantIsRequiredToRead() throws Exception {
        mvc.perform(get(QUEUE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("RESOURCE_SCOPE_DENIED"));
    }

    @Test
    @Order(3)
    @DisplayName("TC-CONSOLE-003 the queue returns this organization's work and no other's")
    void theQueueIsOrganizationScoped() throws Exception {
        viewGrant = users.grantScope(OPERATOR, userId, ActionScopeCode.AVAILABILITY_VIEW,
                ResourceScopeType.ORGANIZATION, ORGANIZATION, null);

        mvc.perform(get(QUEUE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + CASE_ID + "')]").exists())
                .andExpect(jsonPath("$[?(@.id=='" + OTHER_CASE_ID + "')]").doesNotExist());
    }

    @Test
    @Order(4)
    @DisplayName("TC-CONSOLE-004 another organization's case is absent rather than forbidden")
    void anotherOrganizationsCaseReadsAsAbsent() throws Exception {
        mvc.perform(get(QUEUE + "/" + OTHER_CASE_ID).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @Order(5)
    @DisplayName("TC-CONSOLE-005 reading is not acting: the view grant cannot record action")
    void readingIsNotActing() throws Exception {
        mvc.perform(post(QUEUE + "/" + CASE_ID + "/action")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("RESOURCE_SCOPE_DENIED"));
    }

    @Test
    @Order(6)
    @DisplayName("TC-CONSOLE-006 a free-text acknowledgement is refused before it reaches a case")
    void aFreeTextAcknowledgementIsRefused() throws Exception {
        users.grantScope(OPERATOR, userId, ActionScopeCode.AVAILABILITY_TASK_ACT,
                ResourceScopeType.ORGANIZATION, ORGANIZATION, null);

        mvc.perform(post(QUEUE + "/" + CASE_ID + "/action")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actionKind":"DATA_OR_MAPPING_REPAIR","evidenceReference":"  ",
                                 "reason":"had a look"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(7)
    @DisplayName("TC-CONSOLE-007 structured action is accepted and moves the case to verification")
    void structuredActionIsAccepted() throws Exception {
        mvc.perform(post(QUEUE + "/" + CASE_ID + "/action")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("VERIFYING"));

        mvc.perform(get(QUEUE + "/" + CASE_ID + "/journal")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventKind=='ACTION_RECORDED')]").exists());

        // The journal records the role the person actually holds. This case is
        // owned by procurement and this person is not procurement; recording
        // them as its owner would be a fabricated attribution.
        assertThat(jdbc.sql("SELECT actor_role_code FROM ops.availability_case_event"
                        + " WHERE case_id = :caseId AND event_kind = 'ACTION_RECORDED'")
                .param("caseId", CASE_ID).query(String.class).single())
                .isEqualTo(BusinessRoleCode.OWNER.name())
                .isNotEqualTo("PRODUCT_PROCUREMENT");
    }

    @Test
    @Order(8)
    @DisplayName("TC-CONSOLE-008 an acceptance cannot be requested without its own grant")
    void anAcceptanceNeedsItsOwnGrant() throws Exception {
        mvc.perform(post(QUEUE + "/" + CASE_ID + "/exceptions")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON).content(exceptionBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("RESOURCE_SCOPE_DENIED"));
    }

    @Test
    @Order(9)
    @DisplayName("TC-CONSOLE-009 approving an acceptance needs a recent authentication")
    void approvingNeedsARecentAuthentication() throws Exception {
        users.grantScope(OPERATOR, userId, ActionScopeCode.AVAILABILITY_EXCEPTION_APPROVE,
                ResourceScopeType.ORGANIZATION, ORGANIZATION, null);
        UUID exceptionId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ops.availability_accepted_exception
                            (id, organization_id, case_id, child_id, cause_code, scope_kind,
                             scope_reference, reason_code, rationale, expected_consequence,
                             evidence_reference, requested_by_user_id, requested_at,
                             decision_owner_role_code, required_authority_level, state,
                             effective_from, expires_at, review_at, occurrence_count,
                             created_at, updated_at)
                        SELECT :exceptionId, availability_case.organization_id,
                               availability_case.id, availability_case.child_id,
                               availability_case.cause_code, 'CHILD',
                               availability_case.child_id::text, 'SEASONAL_PAUSE',
                               'bounded seasonal pause', 'temporary unmet demand',
                               'ev://commercial/seasonal-plan', :userId, now(), 'OWNER',
                               'RISK_AUTHORITY', 'REQUESTED', now(), now() + interval '7 days',
                               now() + interval '3 days', 1, now(), now()
                          FROM ops.availability_case availability_case
                         WHERE availability_case.id = :caseId
                        """).param("exceptionId", exceptionId).param("userId", userId)
                .param("caseId", CASE_ID).update();

        String stale = sign(claims().claim("auth_time",
                Instant.now().minusSeconds(7200).getEpochSecond()));
        mvc.perform(post("/api/v1/console/availability/exceptions/" + exceptionId
                        + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stale)
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"reason\":\"reviewed the exposure\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("STEP_UP_REQUIRED"));
    }

    @Test
    @Order(10)
    @DisplayName("TC-CONSOLE-010 revoking the view grant closes the queue immediately")
    void revokingTheGrantClosesTheQueue() throws Exception {
        mvc.perform(get(QUEUE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());

        users.revokeScope(OPERATOR, viewGrant.id(), "synthetic revocation", viewGrant.version());

        mvc.perform(get(QUEUE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("RESOURCE_SCOPE_DENIED"));
    }

    @Test
    @Order(11)
    @DisplayName("TC-CONSOLE-011 product scope filters the queue and direct case reads")
    void productScopeIsAppliedToEveryRead() throws Exception {
        users.grantScope(OPERATOR, userId, ActionScopeCode.AVAILABILITY_VIEW,
                ResourceScopeType.PRODUCT_VARIANT, primaryVariantId, null);

        mvc.perform(get(QUEUE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + CASE_ID + "')]").exists())
                .andExpect(jsonPath("$[?(@.id=='" + SIBLING_CASE_ID + "')]").doesNotExist());
        mvc.perform(get(QUEUE + "/" + CASE_ID).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());
        mvc.perform(get(QUEUE + "/" + SIBLING_CASE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("RESOURCE_SCOPE_DENIED"));
    }

    @Test
    @Order(12)
    @DisplayName("TC-CONSOLE-012 no route can manually declare a case verified")
    void manualVerificationRouteDoesNotExist() throws Exception {
        mvc.perform(post(QUEUE + "/" + CASE_ID + "/verification")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verified\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(13)
    @DisplayName("TC-CONSOLE-013 availability policy changes require fresh step-up authentication")
    void policyMutationRequiresStepUp() throws Exception {
        users.grantScope(OPERATOR, userId, ActionScopeCode.SUPPLY_POLICY_MANAGE,
                ResourceScopeType.ORGANIZATION, ORGANIZATION, null);
        String stale = sign(claims().claim("auth_time",
                Instant.now().minusSeconds(7200).getEpochSecond()));
        Instant effectiveFrom = Instant.now().plusSeconds(60);

        mvc.perform(post("/api/v1/console/availability/policies/priority")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stale)
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timeWeight":400,"profitWeight":5,"velocityWeight":20,
                                 "lifecycleWeight":25,"confidenceWeight":-10,
                                 "reason":"approved priority policy",
                                 "evidenceReference":"ev://ops/priority",
                                 "effectiveFrom":"%s"}
                                """.formatted(effectiveFrom)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("STEP_UP_REQUIRED"));
    }

    @Test
    @Order(14)
    @DisplayName("TC-CONSOLE-014 inbound attestation is confined to the granted product")
    void inboundAttestationNeedsTheExactProductGrant() throws Exception {
        users.grantScope(OPERATOR, userId, ActionScopeCode.INBOUND_ATTEST,
                ResourceScopeType.PRODUCT_VARIANT, primaryVariantId, null);
        Instant now = Instant.now();
        String body = """
                {"productVariantId":"%s","externalReference":"PO-AUTH-1","quantity":12,
                 "expectedArrivalFrom":"%s","expectedArrivalTo":"%s",
                 "businessStatus":"SUPPLIER_CONFIRMED",
                 "evidenceReference":"ev://po/auth","sourceTime":"%s",
                 "reason":"confirmed purchase order"}
                """;

        mvc.perform(post("/api/v1/console/availability/inbound")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(siblingVariantId, now.plusSeconds(3600),
                                now.plusSeconds(7200), now)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("RESOURCE_SCOPE_DENIED"));
        mvc.perform(post("/api/v1/console/availability/inbound")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(primaryVariantId, now.plusSeconds(3600),
                                now.plusSeconds(7200), now)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productVariantId").value(primaryVariantId.toString()))
                .andExpect(jsonPath("$.versionNo").value(1));
    }

    /**
     * One organization with a card, a company child and a live case.
     *
     * <p>Written directly because the boundary is what is under test. The
     * calculated values are plausible and unused: no assertion here depends on
     * them, and one that did would be testing the calculator through a
     * controller.
     */
    private UUID seedOrganization(UUID organizationId, String code, UUID caseId) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID policyOwner = UUID.randomUUID();
        UUID activationPolicy = UUID.randomUUID();

        update("""
                INSERT INTO core.organization (id, code, display_name, status, created_at,
                        updated_at)
                VALUES (:id, :code, 'Console fixture', 'ACTIVE', now(), now())
                """, "id", organizationId, "code", code);
        UUID provider = seedProvider(code);
        update("""
                INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
                        external_subject, display_name, status, credentials_valid_from,
                        created_at, updated_at)
                VALUES (:id, :organizationId, :providerId, :subject, 'Policy Owner', 'ACTIVE',
                        now(), now(), now())
                """, "id", policyOwner, "organizationId", organizationId, "providerId", provider,
                "subject", code + "-policy-owner");
        update("""
                INSERT INTO core.product (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES (:id, :organizationId, :code, 'Kettle', 'ACTIVE', now(), now())
                """, "id", productId, "organizationId", organizationId, "code", code + "-kettle");
        update("""
                INSERT INTO core.product_variant (id, organization_id, product_id, sku_code,
                        display_name, status, created_at, updated_at)
                VALUES (:id, :organizationId, :productId, :sku, 'Kettle 1L', 'ACTIVE', now(),
                        now())
                """, "id", variantId, "organizationId", organizationId, "productId", productId,
                "sku", code + "-kettle-1l");
        update("""
                INSERT INTO core.work_activation_policy (id, organization_id,
                        high_sustained_cycles, critical_action_sla_minutes,
                        high_action_sla_minutes, blocker_action_sla_minutes, outcome_sla_minutes,
                        verification_window_minutes, owner_user_id, reason, evidence_reference,
                        effective_from, status, policy_version, created_at)
                VALUES (:id, :organizationId, 2, 60, 240, 480, 2880, 1440, :ownerId,
                        'agreed activation policy', 'ev://ops/activation',
                        now() - interval '10 days', 'ACTIVE', 1, now())
                """, "id", activationPolicy, "organizationId", organizationId,
                "ownerId", policyOwner);
        update("""
                INSERT INTO mart.availability_risk_card (id, organization_id, product_variant_id,
                        lane, triggering_child_id, rank_score, policy_version_digest, as_of,
                        calculated_at, calculation_kind, created_at, updated_at)
                VALUES (:id, :organizationId, :variantId, 'CRITICAL', :childId, 300000.0000,
                        repeat('a', 64), now(), now(), 'TARGETED', now(), now())
                """, "id", cardId, "organizationId", organizationId, "variantId", variantId,
                "childId", childId);
        update("""
                INSERT INTO mart.availability_risk_child (id, card_id, organization_id, child_kind,
                        lane, evidence_state, confidence_state, cause_code, profit_lane,
                        demand_selection_reason, conservative_proof, calculation_id, calculated_at,
                        created_at, updated_at)
                VALUES (:id, :cardId, :organizationId, 'COMPANY', 'CRITICAL', 'CONFIRMED', 'HIGH',
                        'COMPANY_SUPPLY_SHORT', 'CONFIRMED_ELIGIBLE',
                        'stable baseline: longest eligible window D30', '{}'::jsonb, :calcId,
                        now(), now(), now())
                """, "id", childId, "cardId", cardId, "organizationId", organizationId,
                "calcId", UUID.randomUUID());
        update("""
                INSERT INTO ops.availability_case (id, organization_id, card_id, child_id,
                        cause_code, cause_key, child_kind, severity, state, accountable_role_code,
                        action_due_at, outcome_due_at, activation_policy_id, first_activated_at,
                        last_evidence_at, correlation_id, created_at, updated_at)
                VALUES (:id, :organizationId, :cardId, :childId, 'COMPANY_SUPPLY_SHORT',
                        :causeKey, 'COMPANY', 'CRITICAL', 'OPEN', 'PRODUCT_PROCUREMENT',
                        now() + interval '1 hour', now() + interval '2 days', :policyId, now(),
                        now(), 'console-fixture', now(), now())
                """, "id", caseId, "organizationId", organizationId, "cardId", cardId,
                "childId", childId, "causeKey", "COMPANY:" + variantId + ":COMPANY_SUPPLY_SHORT",
                "policyId", activationPolicy);
        return variantId;
    }

    private UUID seedAdditionalCase(UUID organizationId, String code, UUID caseId) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID activationPolicy = jdbc.sql("""
                        SELECT id FROM core.work_activation_policy
                         WHERE organization_id = :organizationId
                         ORDER BY policy_version DESC LIMIT 1
                        """).param("organizationId", organizationId).query(UUID.class).single();
        update("""
                INSERT INTO core.product (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES (:id, :organizationId, :code, 'Sibling product', 'ACTIVE', now(), now())
                """, "id", productId, "organizationId", organizationId, "code", code);
        update("""
                INSERT INTO core.product_variant (id, organization_id, product_id, sku_code,
                        display_name, status, created_at, updated_at)
                VALUES (:id, :organizationId, :productId, :sku, 'Sibling variant', 'ACTIVE',
                        now(), now())
                """, "id", variantId, "organizationId", organizationId,
                "productId", productId, "sku", code + "-variant");
        update("""
                INSERT INTO mart.availability_risk_card (id, organization_id, product_variant_id,
                        lane, triggering_child_id, rank_score, policy_version_digest, as_of,
                        calculated_at, calculation_kind, created_at, updated_at)
                VALUES (:id, :organizationId, :variantId, 'HIGH', :childId, 200000.0000,
                        repeat('b', 64), now(), now(), 'TARGETED', now(), now())
                """, "id", cardId, "organizationId", organizationId, "variantId", variantId,
                "childId", childId);
        update("""
                INSERT INTO mart.availability_risk_child (id, card_id, organization_id,
                        child_kind, lane, evidence_state, confidence_state, cause_code,
                        profit_lane, demand_selection_reason, conservative_proof,
                        calculation_id, calculated_at, created_at, updated_at)
                VALUES (:id, :cardId, :organizationId, 'COMPANY', 'HIGH', 'CONFIRMED', 'HIGH',
                        'COMPANY_SUPPLY_SHORT', 'CONFIRMED_ELIGIBLE', 'stable baseline',
                        '{}'::jsonb, :calcId, now(), now(), now())
                """, "id", childId, "cardId", cardId, "organizationId", organizationId,
                "calcId", UUID.randomUUID());
        update("""
                INSERT INTO ops.availability_case (id, organization_id, card_id, child_id,
                        cause_code, cause_key, child_kind, severity, state,
                        accountable_role_code, action_due_at, outcome_due_at,
                        activation_policy_id, first_activated_at, last_evidence_at,
                        correlation_id, created_at, updated_at)
                VALUES (:id, :organizationId, :cardId, :childId, 'COMPANY_SUPPLY_SHORT',
                        :causeKey, 'COMPANY', 'HIGH', 'OPEN', 'PRODUCT_PROCUREMENT',
                        now() + interval '4 hours', now() + interval '2 days', :policyId,
                        now(), now(), 'console-sibling', now(), now())
                """, "id", caseId, "organizationId", organizationId, "cardId", cardId,
                "childId", childId, "causeKey",
                "COMPANY:" + variantId + ":COMPANY_SUPPLY_SHORT", "policyId", activationPolicy);
        return variantId;
    }

    private UUID seedProvider(String code) {
        UUID id = UUID.randomUUID();
        update("""
                INSERT INTO iam.identity_provider (id, code, display_name, issuer, mfa_claim_name,
                        mfa_claim_value, max_auth_age_seconds, verification_state, last_verified_at,
                        evidence_ref, verified_source_title, owner_label, status, created_at,
                        updated_at)
                VALUES (:id, :code, 'Fixture IdP', :issuer, 'amr', 'mfa', 900, 'VERIFIED', now(),
                        'ev://idp', 'IdP docs', 'security', 'ACTIVE', now(), now())
                """, "id", id, "code", code + "-idp",
                "issuer", "https://id.example.test/" + code);
        return id;
    }

    private void update(String statement, Object... namedValues) {
        if ((namedValues.length & 1) != 0) {
            throw new IllegalArgumentException("named values must be supplied in name/value pairs");
        }
        var call = jdbc.sql(statement);
        for (int index = 0; index + 1 < namedValues.length; index += 2) {
            call = call.param((String) namedValues[index], namedValues[index + 1]);
        }
        call.update();
    }

    private static String actionBody() {
        return """
                {"actionKind":"INBOUND_EVIDENCE_BOUND",
                 "evidenceReference":"ev://purchase-order/9001",
                 "reason":"attested inbound bound to the shortfall"}
                """;
    }

    private static String exceptionBody() {
        return """
                {"scopeKind":"CHILD","scopeReference":"company-child","reasonCode":"SEASONAL_PAUSE",
                 "rationale":"the line is paused until the season restarts",
                 "expectedConsequence":"a fortnight of unmet demand on one variant",
                 "evidenceReference":"ev://commercial/seasonal-plan",
                 "effectiveFrom":"2026-09-01T00:00:00Z","expiresAt":"2026-09-08T00:00:00Z",
                 "reviewAt":"2026-09-04T00:00:00Z"}
                """;
    }

    private String bearer() throws JOSEException {
        return "Bearer " + sign(claims());
    }

    private JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(2)))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .claim("auth_time", now.minusSeconds(5).getEpochSecond())
                .claim("amr", List.of("pwd", "mfa"))
                .claim("sid", "availability-session-" + subject);
    }

    private static String sign(JWTClaimsSet.Builder claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("synthetic-key").build(),
                claims.build());
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        return jwt.serialize();
    }

    private static RSAKey signingKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("synthetic-key").generate();
        } catch (JOSEException failed) {
            throw new ExceptionInInitializerError(failed);
        }
    }

    /**
     * Verify the fixture's own signature and nothing else.
     *
     * <p>The claim validator the application configures is exercised by the
     * identity boundary's own test. Repeating it here would make an
     * authorization test fail for token-shape reasons, which is exactly the
     * confusion that makes a boundary test stop being read.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class LocalSigningKey {
        @Bean
        @Primary
        JwtDecoder localDecoder() throws JOSEException {
            return NimbusJwtDecoder.withPublicKey(SIGNING_KEY.toRSAPublicKey()).build();
        }
    }
}
