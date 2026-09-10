package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
import com.mimococo.marketops.identityaccess.internal.application.IdentityProviderService;
import com.mimococo.marketops.identityaccess.internal.application.UserAdministrationService;
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
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Real signatures, live grants, Console filters and isolated PostgreSQL; no marketplace calls. */
@SpringBootTest
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("ci")
@Import(ListingReworkAuthorizationIT.LocalSigningKey.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ListingReworkAuthorizationIT {
    private static final String ISSUER = "https://identity.example.invalid/listing-rework";
    private static final String AUDIENCE = "marketops-listing-rework";
    private static final String OPERATOR = "listing-rework-fixture";
    private static final String ISSUER_PASSWORD = UUID.randomUUID().toString();
    private static final RSAKey SIGNING_KEY = signingKey();
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE = TestDatabase.isolatedContainer();
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired IdentityProviderService providers;
    @Autowired UserAdministrationService users;
    @Autowired com.mimococo.marketops.listingconversion.internal.application.CalibrationService calibration;
    @Autowired com.mimococo.marketops.operationsworkflow.ListingActionIntake listingIntake;
    @Autowired com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.MeasurementEvidenceRepository measurementEvidence;
    private UUID providerId;
    private UUID userId;
    private String subject;
    private ListingConversionFixture fixture;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
        registry.add("marketops.identity.oidc.issuer-uri", () -> ISSUER);
        registry.add("marketops.identity.oidc.jwk-set-uri", () -> ISSUER + "/jwks");
        registry.add("marketops.identity.oidc.audience", () -> AUDIENCE);
        registry.add("marketops.identity.invocation.jdbc-url", DATABASE::getJdbcUrl);
        registry.add("marketops.identity.invocation.username", () -> "marketops_identity_issuer");
        registry.add("marketops.identity.invocation.password", () -> ISSUER_PASSWORD);
    }

    @BeforeAll
    void provider() throws Exception {
        try (var connection=java.sql.DriverManager.getConnection(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword())) {
            TestDatabase.enableSyntheticIdentityIssuer(connection,ISSUER_PASSWORD);
        }
        var p = providers.register(OPERATOR, "listing-rework-provider", "Listing OIDC", ISSUER, 900, "synthetic-platform");
        providerId = providers.verifyAndActivate(OPERATOR, p.id(), "amr", "mfa",
                "evidence://synthetic/listing-rework", "Local signed fixture", p.version()).id();
    }

    @BeforeEach
    void graph() throws Exception {
        fixture = new ListingConversionFixture(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), TestDatabase.migrationRole(), TestDatabase.migrationPassword()),
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), TestDatabase.applicationRole(), TestDatabase.applicationPassword()),
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        subject = "listing-rework-" + UUID.randomUUID();
        userId = users.provision(OPERATOR, fixture.id("organization"), providerId, subject, null, "Listing operator", null).id();
        jdbc.sql("UPDATE iam.user_account SET credentials_valid_from = now() - interval '1 hour' WHERE id = :id")
                .param("id", userId).update();
    }

    private String endpoint() { return "/api/v1/console/listing/actions/" + fixture.id("actionOne") + "/evaluation/nodes"; }
    private String body() { return "{\"nodeCode\":\"D14\",\"stage\":\"OPERATIONAL\"}"; }

    private Map<String, Long> businessCounts() {
        return Map.of("nodes", count("ops.lc_node_result"), "revisions", count("ops.lc_outcome_revision"),
                "runs", count("mart.calculation_run"), "taskEvents", count("ops.work_task_event"));
    }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single(); }

    @Test
    void unauthenticatedHasNoBusinessEffects() throws Exception {
        var before = businessCounts();
        mvc.perform(post(endpoint()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isUnauthorized());
        assertThat(businessCounts()).isEqualTo(before);
    }

    @Test
    void viewGrantCannotWriteOutcome() throws Exception {
        users.assignRole(OPERATOR, userId, BusinessRoleCode.OWNER, null);
        users.grantScope(OPERATOR, userId, ActionScopeCode.LISTING_CONVERSION_VIEW,
                ResourceScopeType.ORGANIZATION, fixture.id("organization"), null);
        var before = businessCounts();
        mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        assertThat(businessCounts()).isEqualTo(before);
    }

    @Test
    void readOnlyRoleCannotEvaluateEvenWithMatchingGrant() throws Exception {
        users.assignRole(OPERATOR, userId, BusinessRoleCode.READ_ONLY, null);
        users.grantScope(OPERATOR, userId, ActionScopeCode.LISTING_OUTCOME_EVALUATE,
                ResourceScopeType.ORGANIZATION, fixture.id("organization"), null);
        var before = businessCounts();
        mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        assertThat(businessCounts()).isEqualTo(before);
    }

    @Test
    void revokedGrantIsCheckedOnTheNextSignedRequest() throws Exception {
        users.assignRole(OPERATOR, userId, BusinessRoleCode.OWNER, null);
        var grant = users.grantScope(OPERATOR, userId, ActionScopeCode.LISTING_OUTCOME_EVALUATE,
                ResourceScopeType.ORGANIZATION, fixture.id("organization"), null);
        String token = bearer();
        users.revokeScope(OPERATOR, grant.id(), "fixture revocation", grant.version());
        var before = businessCounts();
        mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        assertThat(businessCounts()).isEqualTo(before);
    }

    @Test
    void anotherOrganizationCannotEvaluateKnownAction() throws Exception {
        users.assignRole(OPERATOR, userId, BusinessRoleCode.OWNER, null);
        users.grantScope(OPERATOR, userId, ActionScopeCode.LISTING_OUTCOME_EVALUATE,
                ResourceScopeType.ORGANIZATION, fixture.id("organization"), null);
        var other = new ListingConversionFixture(fixture.migration, fixture.application, fixture.admin);
        var before = businessCounts();
        mvc.perform(post("/api/v1/console/listing/actions/" + other.id("actionOne") + "/evaluation/nodes")
                .header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
        assertThat(businessCounts()).isEqualTo(before);
    }

    @Test
    void anotherStoreGrantCannotEvaluateThisAction() throws Exception {
        UUID store = UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO core.store (id, organization_id, marketplace_account_id, code, display_name,
                    timezone, currency_code, status, created_at, updated_at)
                SELECT :id, organization_id, marketplace_account_id, 'other-store', 'Other store',
                    timezone, currency_code, status, now(), now() FROM core.store WHERE id = :source
                """).param("id", store).param("source", fixture.id("store")).update();
        users.assignRole(OPERATOR, userId, BusinessRoleCode.OWNER, null);
        users.grantScope(OPERATOR, userId, ActionScopeCode.LISTING_OUTCOME_EVALUATE,
                ResourceScopeType.STORE, store, null);
        var before = businessCounts();
        mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        assertThat(businessCounts()).isEqualTo(before);
    }

    @Test
    void financialProjectionRequiresEveryAffectedProductScopeAndRevokesImmediately() throws Exception {
        users.assignRole(OPERATOR, userId, BusinessRoleCode.OWNER, null);
        users.grantScope(OPERATOR, userId, ActionScopeCode.LISTING_CONVERSION_VIEW,
                ResourceScopeType.STORE, fixture.id("store"), null);
        users.grantScope(OPERATOR, userId, ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.STORE, fixture.id("store"), null);
        UUID simulation = UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO ops.lc_simulation (id, organization_id, candidate_id, calculation_run_id,
                    scenario_set, inputs_digest, results, inverse_minimum_quantity, inverse_state, demand_gate_passed, computed_at, evidence_product_variant_ids)
                VALUES (:id, :org, :candidate, :run, '[]', repeat('a',64),
                    '[{"code":"DOWNSIDE","state":"COMPUTED","quantity":"100","netRevenue":"60000",
                      "contributionProfit":"23400","missingInputs":[]}]', 128, 'COMPUTED', true, now(), ARRAY[:variant]::uuid[])
                """).param("id", simulation).param("variant", fixture.id("productVariant")).param("org", fixture.id("organization"))
                .param("candidate", fixture.id("candidateOne")).param("run", fixture.id("calculationRun")).update();
        String url = "/api/v1/console/listing/actions/candidates/" + fixture.id("candidateOne") + "/simulations";
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andDo(r -> { if (r.getResolvedException() != null) throw r.getResolvedException(); })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].inverseState").value("MASKED"))
                .andExpect(jsonPath("$[0].scenarios").isEmpty());
        var dataGrant = users.grantScope(OPERATOR, userId, ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.PRODUCT_VARIANT, fixture.id("productVariant"), null);
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andDo(r -> { if (r.getResolvedException() != null) throw r.getResolvedException(); })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scenarios[0].contributionProfit").value(23400));
        users.revokeScope(OPERATOR, dataGrant.id(), "withdraw financial data", dataGrant.version());
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andDo(r -> { if (r.getResolvedException() != null) throw r.getResolvedException(); })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].inverseState").value("MASKED"))
                .andExpect(jsonPath("$[0].inverseMinimumQuantity").doesNotExist())
                .andExpect(jsonPath("$[0].inputsDigest").doesNotExist());
    }

    @Test
    void descriptionHttpIntakePreservesLongTextAndItsDigest() throws Exception {
        users.assignRole(OPERATOR, userId, BusinessRoleCode.OWNER, null);
        users.grantScope(OPERATOR, userId, ActionScopeCode.LISTING_ACTION_PREPARE,
                ResourceScopeType.STORE, fixture.id("store"), null);
        String text = "  Описание товара\r\n" + "Бережная стирка и мягкая ткань.\n".repeat(100) + "  ";
        var mapper = new tools.jackson.databind.ObjectMapper();
        String response = mvc.perform(post("/api/v1/console/listing/health/listings/" + fixture.id("listing") + "/facts/description")
                .header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("text", text, "languageCode", "ru"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(mapper.readTree(response).path("observationId").asText());
        assertThat(jdbc.sql("SELECT description_text FROM core.lc_description_observation WHERE id = :id")
                .param("id", id).query(String.class).single()).isEqualTo(text);
        assertThat(jdbc.sql("SELECT text_digest FROM core.lc_description_observation WHERE id = :id")
                .param("id", id).query(String.class).single()).isEqualTo(com.mimococo.marketops.shared.Digest.ofText(text));
    }

    private void measurementGrants() {
        users.assignRole(OPERATOR, userId, BusinessRoleCode.OWNER, null);
        for (var code : List.of(ActionScopeCode.INTERNAL_FACT_INTAKE, ActionScopeCode.LISTING_ACTION_PREPARE,
                ActionScopeCode.LISTING_CONVERSION_VIEW)) {
            users.grantScope(OPERATOR, userId, code, ResourceScopeType.ORGANIZATION, fixture.id("organization"), null);
        }
    }

    private tools.jackson.databind.JsonNode postListing(String suffix, Map<String, ?> body) throws Exception {
        return postForListing(fixture.id("listing"),suffix,body);
    }

    private tools.jackson.databind.JsonNode postForListing(UUID listing,String suffix,Map<String,?> body) throws Exception {
        var mapper = new tools.jackson.databind.ObjectMapper();
        String response = mvc.perform(post("/api/v1/console/listing/health/listings/" + listing + suffix)
                .header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andDo(r -> { if (r.getResolvedException() != null) throw r.getResolvedException(); })
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    @Test
    void signedManualVerificationBindsIndependentExactObservationsAndExposesTheirExtent() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        for (var scope:List.of(ActionScopeCode.LISTING_ACTION_LAUNCH,ActionScopeCode.LISTING_ACTION_PREPARE,
                ActionScopeCode.LISTING_MANUAL_VERIFY,ActionScopeCode.LISTING_CONVERSION_VIEW))
            users.grantScope(OPERATOR,userId,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        listingIntake.ensureResponsibilityTask(fixture.id("organization"),fixture.id("recommendationTwo"),
                "Synthetic manual verification responsibility",Instant.now().plusSeconds(86400),Instant.now());
        assertThat(fixture.launch(UUID.randomUUID(),"actionTwo",fixture.id("ownerUser")).path("launched").asBoolean()).isTrue();
        var json=new tools.jackson.databind.ObjectMapper();
        var packetResponse=mvc.perform(post("/api/v1/console/listing/manual/actions/"+fixture.id("actionTwo")+"/packets")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("executorUserId",fixture.id("executorUser")))))
                .andExpect(status().isOk()).andReturn();
        var packet=json.readTree(packetResponse.getResponse().getContentAsString());
        String target=packet.path("targetText").asText();
        UUID listing=fixture.id("listingTwo");
        var management=postForListing(listing,"/facts/description",Map.of("text",target,"languageCode","ru",
                "kizMarkedDeclared",false,"note","Synthetic independent management observation"));
        var foreignDisplay=postListing("/facts/display",Map.of("displayState","DISPLAYED","displayedText",target,
                "evidenceReference","evidence://synthetic/wrong-listing-display"));
        Map<String,Object> verification=new java.util.LinkedHashMap<>(Map.of("basis","INDEPENDENT_HUMAN",
                "managementMatch","MATCHED_TARGET","managementObservationId",management.path("observationId").asText(),
                "displayObservationId",foreignDisplay.path("observationId").asText(),"displayState","DISPLAYED",
                "note","Synthetic independent verification"));
        String route="/api/v1/console/listing/manual/packets/"+packet.path("id").asText()+"/verify";
        mvc.perform(post(route).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(verification))).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_manual_verification WHERE packet_id=:packet")
                .param("packet",UUID.fromString(packet.path("id").asText())).query(Integer.class).single()).isZero();
        var display=postForListing(listing,"/facts/display",Map.of("displayState","DISPLAYED","displayedText",target,
                "evidenceReference","evidence://synthetic/exact-listing-display"));
        verification.put("displayObservationId",display.path("observationId").asText());
        mvc.perform(post(route).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(verification))).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("VERIFIED"))
                .andExpect(jsonPath("$.verifications[0].observationBinding.listingId").value(listing.toString()))
                .andExpect(jsonPath("$.verifications[0].observationBinding.displayClaimExtent").value("OBSERVED_INSTANT_ONLY"));
    }

    @Test
    void completeDetailWindowWithZeroPurchasesIsMeasurableButMissingCoverageIsNot() throws Exception {
        measurementGrants();
        Instant to = Instant.now().minusSeconds(40L * 86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant from = to.minusSeconds(86400);
        postListing("/facts/visit", Map.of("visitKey", "visit-zero-purchase", "visitedAt", from.plusSeconds(30).toString(),
                "sellable", "NO", "channel", "UNKNOWN"));
        Map<String, Object> request = Map.of("windowStart", from.toString(), "windowEnd", to.toString(),
                "retentionDays", 30, "evidencePath", "DETAIL");
        var missing = postListing("/measurements", request);
        assertThat(missing.path("ratioState").asText()).isEqualTo("NOT_AVAILABLE");
        postListing("/facts/measurement-coverage", Map.of("windowStart", from.toString(), "windowEnd", to.toString(),
                "retentionDays", 30, "evidencePath", "DETAIL", "sourceCompleteThrough", to.plusSeconds(31L*86400).toString(),
                "sourceReference", "evidence://synthetic/complete-visit-and-purchase-window", "expectedVisitRows", 1,
                "expectedLinkRows", 0));
        var result = postListing("/measurements", request);
        assertThat(result.path("ratioState").asText()).isEqualTo("DEFINED");
        assertThat(result.path("visitCount").asLong()).isEqualTo(1);
        assertThat(result.path("retainedPurchaseVisitCount").asLong()).isZero();
        assertThat(result.path("primaryRatio").decimalValue()).isEqualByComparingTo("0");
        assertThat(result.path("sourceStratified").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id", UUID.fromString(result.path("id").asText())).query(Long.class).single()).isEqualTo(1);
        // A later unaccounted visit invalidates completeness instead of silently extending the certified window.
        postListing("/facts/visit", Map.of("visitKey", "unaccounted-visit", "visitedAt", from.plusSeconds(60).toString(),
                "sellable", "YES", "channel", "ORGANIC"));
        assertThat(postListing("/measurements", request).path("ratioState").asText()).isEqualTo("NOT_AVAILABLE");
    }

    @Test
    void normalPreparationFreezesTheEvaluationPlanBeforeAnyReviewOrApproval() throws Exception {
        var json=new tools.jackson.databind.ObjectMapper();
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        for (ActionScopeCode scope:List.of(ActionScopeCode.LISTING_ACTION_PREPARE,ActionScopeCode.LISTING_CONVERSION_VIEW)) {
            users.grantScope(OPERATOR,userId,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        }
        mvc.perform(post("/api/v1/console/listing/actions/"+fixture.id("actionOne")+"/cancel")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Withdraw unused synthetic action before preparing the next round\"}"))
                .andExpect(status().isOk());
        var candidateResponse=mvc.perform(post("/api/v1/console/listing/actions/candidates")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("listingId",fixture.id("listing"),"candidateKind","CONTENT_DESCRIPTION",
                        "roundKey","pre-approval-plan","evidenceReferences",List.of("evidence://synthetic/preapproval")))))
                .andExpect(status().isOk()).andReturn();
        String candidate=json.readTree(candidateResponse.getResponse().getContentAsString()).path("id").asText();
        var actionResponse=mvc.perform(post("/api/v1/console/listing/actions/candidates/"+candidate+"/prepare")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("executionPath","MANUAL","targetText",ListingConversionFixture.PRIOR_TEXT_ONE+".",
                        "kizMarkedDeclared",false,"exposureShare",new java.math.BigDecimal("0.01")))))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .withFailMessage("Preparation failed: %s", result.getResolvedException()).isEqualTo(200))
                .andExpect(jsonPath("$.state").value("DRAFT")).andReturn();
        UUID actionId=UUID.fromString(json.readTree(actionResponse.getResponse().getContentAsString()).path("id").asText());
        assertThat(jdbc.sql("""
                SELECT p.frozen_at>=a.created_at AND p.calibration_package_id=a.calibration_package_id
                    AND p.calibration_version=a.calibration_version
                    AND NOT EXISTS (SELECT 1 FROM ops.lc_action_review r WHERE r.action_id=a.id)
                    AND NOT EXISTS (SELECT 1 FROM ops.lc_action_binding b WHERE b.action_id=a.id)
                FROM ops.lc_evaluation_plan p JOIN ops.lc_action a ON a.id=p.action_id WHERE a.id=:id
                """).param("id",actionId).query(Boolean.class).single()).isTrue();
        mvc.perform(get("/api/v1/console/listing/actions/"+actionId+"/evaluation").header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.formalNodes[0].nodeCode").value("D14"))
                .andExpect(jsonPath("$.frozenDefinition.formalNodes[0].maturityDays").value(14));
    }

    @Test
    void approvalBindingKeepsTheReviewedPlanAndLegacyUnboundApprovalCannotBorrowIt() {
        assertThat(jdbc.sql("""
                SELECT b.evaluation_plan_digest=p.plan_digest AND EXISTS (
                    SELECT 1 FROM ops.lc_action_review r WHERE r.action_id=p.action_id
                        AND r.evaluation_plan_digest=p.plan_digest AND r.reviewed_at>=p.frozen_at)
                FROM ops.lc_action_binding b JOIN ops.lc_evaluation_plan p ON p.action_id=b.action_id
                WHERE b.action_id=:action
                """).param("action",fixture.id("actionOne")).query(Boolean.class).single()).isTrue();
        fixture.seed.sql("UPDATE ops.lc_action_binding SET evaluation_plan_digest=NULL WHERE action_id=:id")
                .param("id",fixture.id("actionOne")).update();
        assertThat(bindingGaps("actionOne")).contains("EVALUATION_PLAN_BINDING_MISSING_OR_CHANGED");
        assertThat(bindingGaps("actionTwo")).doesNotContain("EVALUATION_PLAN_BINDING_MISSING_OR_CHANGED");
    }

    @Test
    void callerNumbersCannotCertifyImprovementOrProtectionsFromAnAbsoluteSummaryRatio() throws Exception {
        measurementGrants();
        listingIntake.ensureResponsibilityTask(fixture.id("organization"),fixture.id("recommendationOne"),
                "Synthetic Outcome responsibility",Instant.now().plusSeconds(86400),Instant.now());
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_OUTCOME_EVALUATE,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        seedSummaryProfile();
        Instant to=Instant.now().minusSeconds(40L*86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant from=to.minusSeconds(86400);
        var summary=postListing("/facts/official-summary",Map.of("periodStart",from.toString(),"periodEnd",to.toString(),
                "visits",100,"retainedPurchases",10,"retentionDays",30,"label","Synthetic absolute ratio",
                "observedAt",to.plusSeconds(31L*86400).toString()));
        postListing("/facts/measurement-coverage",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY","sourceCompleteThrough",to.plusSeconds(31L*86400).toString(),
                "sourceReference","evidence://synthetic/absolute-ratio-only","summaryObservationId",summary.path("observationId").asText()));
        var measurement=postListing("/measurements",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY"));
        assertThat(measurement.path("primaryRatio").decimalValue()).isEqualByComparingTo("0.1");
        String request="""
                {"nodeCode":"D14","stage":"OPERATIONAL","measurementId":"%s","conservativeBound":0.099,
                 "directContributionProfit":999999,"linkedScopeProfit":999999,"overallReturnRate":0,
                 "criticalVariantReturnRate":0,"supplyCoverageDays":999,"priorContributionProfit":0,
                 "priorLinkedScopeProfit":0,"priorReturnRate":0.9,"minimumSupplyCoverageDays":0}
                """.formatted(measurement.path("id").asText());
        mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].verdict").value("UNDETERMINED"))
                .andExpect(jsonPath("$.results[0].protectionVerdict").value("UNDETERMINED"))
                .andExpect(jsonPath("$.results[0].stopVerdict").value("UNDETERMINED"))
                .andExpect(jsonPath("$.results[0].evaluationEvidence.nodeWindowQualified").value(false))
                .andExpect(jsonPath("$.results[0].evaluationEvidence.qualificationGaps[0]").value("FROZEN_METHOD_OR_SCHEDULE_UNQUALIFIED"));
        assertThat(jdbc.sql("SELECT conservative_bound IS NULL FROM ops.lc_node_result WHERE plan_id=:plan")
                .param("plan",fixture.id("planOne")).query(Boolean.class).single()).isTrue();
        mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace("OPERATIONAL", "SETTLED")))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT outcome_kind FROM ops.work_task_event WHERE organization_id=:org AND event_kind='OUTCOME_OBSERVED'")
                .param("org",fixture.id("organization")).query(String.class).list()).containsExactly("UNKNOWN","UNKNOWN");
        assertThat(jdbc.sql("SELECT bool_and(evaluation_evidence->>'planDigest'=p.plan_digest) FROM ops.lc_node_result r JOIN ops.lc_evaluation_plan p ON p.id=r.plan_id WHERE p.id=:plan")
                .param("plan",fixture.id("planOne")).query(Boolean.class).single()).isTrue();
    }

    @Test
    void concurrentOutcomeRequestsAppendASingleOrderedRevisionChain() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_OUTCOME_EVALUATE,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        listingIntake.ensureResponsibilityTask(fixture.id("organization"),fixture.id("recommendationOne"),
                "Synthetic concurrent Outcome responsibility",Instant.now().plusSeconds(86400),Instant.now());
        String token=bearer(), route=endpoint(), request=body();
        var ready=new java.util.concurrent.CountDownLatch(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try (var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int index=0;index<2;index++) futures.add(workers.submit(()->{
                ready.countDown();
                if (!start.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("concurrent start timeout");
                return mvc.perform(post(route).header(HttpHeaders.AUTHORIZATION,token)
                        .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse().getStatus();
            }));
            assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future:futures) assertThat(future.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.sql("SELECT revision_no FROM ops.lc_node_result WHERE plan_id=:plan ORDER BY revision_no")
                .param("plan",fixture.id("planOne")).query(Integer.class).list()).containsExactly(0,1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.lc_outcome_revision revision
                  JOIN ops.lc_node_result original ON original.id=revision.original_result_id
                  JOIN ops.lc_node_result revised ON revised.id=revision.revised_result_id
                  WHERE revision.plan_id=:plan AND original.revision_no=0 AND revised.revision_no=1
                """).param("plan",fixture.id("planOne")).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void oneSignedApiLaunchCreatesAndReturnsItsOnlyCommandBeforeCommit() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        for (var scope:List.of(ActionScopeCode.LISTING_ACTION_LAUNCH,ActionScopeCode.LISTING_CONVERSION_VIEW))
            users.grantScope(OPERATOR,userId,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        listingIntake.ensureResponsibilityTask(fixture.id("organization"),fixture.id("recommendationOne"),
                "Synthetic atomic launch responsibility",Instant.now().plusSeconds(86400),Instant.now());
        String route="/api/v1/console/listing/actions/"+fixture.id("actionOne")+"/launch";
        var response=mvc.perform(post(route).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"axes\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.launched").value(true))
                .andExpect(jsonPath("$.commandId").isString()).andReturn();
        var json=new tools.jackson.databind.ObjectMapper();
        UUID command=UUID.fromString(json.readTree(response.getResponse().getContentAsString()).path("commandId").asText());
        mvc.perform(get("/api/v1/console/listing-description-commands/"+command).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
        mvc.perform(post(route).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"axes\":{}}" )).andExpect(status().isConflict());
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_description_command WHERE action_id=:action")
                .param("action",fixture.id("actionOne")).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_description_command_attempt WHERE command_id=:command")
                .param("command",command).query(Integer.class).single()).isZero();
    }

    @Test
    void officialSummaryIsIndependentOfVisitDetailsAndBoundToItsExactCertifiedWindow() throws Exception {
        measurementGrants();
        seedSummaryProfile();
        Instant to = Instant.now().minusSeconds(40L*86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant from = to.minusSeconds(86400);
        var summary = postListing("/facts/official-summary", Map.of("periodStart",from.toString(),"periodEnd",to.toString(),
                "visits",100,"retainedPurchases",10,"retentionDays",30,"label","Qualified synthetic visit count",
                "observedAt",to.plusSeconds(31L*86400).toString()));
        postListing("/facts/measurement-coverage", Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY","sourceCompleteThrough",to.plusSeconds(31L*86400).toString(),
                "sourceReference","evidence://synthetic/complete-summary-window",
                "summaryObservationId",summary.path("observationId").asText()));
        var result = postListing("/measurements",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY"));
        assertThat(result.path("ratioState").asText()).isEqualTo("DEFINED");
        assertThat(result.path("primaryRatio").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(result.path("sourceStratified").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM core.lc_visit_fact WHERE platform_listing_id=:id")
                .param("id",fixture.id("listing")).query(Long.class).single()).isZero();
        var wrong = postListing("/measurements",Map.of("windowStart",from.minusSeconds(86400).toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY"));
        assertThat(wrong.path("ratioState").asText()).isEqualTo("NOT_AVAILABLE");
    }

    @Test
    void contradictorySummaryRetainsRawCountsAndNeverClampsTheNumerator() throws Exception {
        measurementGrants();
        seedSummaryProfile();
        Instant to = Instant.now().minusSeconds(40L*86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant from = to.minusSeconds(86400);
        var summary = postListing("/facts/official-summary", Map.of("periodStart",from.toString(),"periodEnd",to.toString(),
                "visits",10,"retainedPurchases",11,"retentionDays",30,
                "observedAt",to.plusSeconds(31L*86400).toString()));
        postListing("/facts/measurement-coverage", Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY","sourceCompleteThrough",to.plusSeconds(31L*86400).toString(),
                "sourceReference","evidence://synthetic/contradictory-summary",
                "summaryObservationId",summary.path("observationId").asText()));
        var result = postListing("/measurements",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY"));
        assertThat(result.path("ratioState").asText()).isEqualTo("NOT_AVAILABLE");
        assertThat(result.path("primaryRatio").isNull()).isTrue();
        assertThat(result.path("qualificationReasonCodes").toString()).contains("SUMMARY_COUNTS_CONFLICTED");
        assertThat(jdbc.sql("SELECT inputs->'sourceInputs'->>'reported_retained_purchases' FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",UUID.fromString(result.path("id").asText())).query(String.class).single()).isEqualTo("11");
    }

    @Test
    void zeroDenominatorIsUndefinedOnlyWhenTheWholeEmptySourceWindowIsComplete() throws Exception {
        measurementGrants();
        Instant to=Instant.now().minusSeconds(40L*86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant from=to.minusSeconds(86400);
        postListing("/facts/measurement-coverage",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","DETAIL","sourceCompleteThrough",to.plusSeconds(31L*86400).toString(),
                "sourceReference","evidence://synthetic/empty-window","expectedVisitRows",0,"expectedLinkRows",0));
        var result=postListing("/measurements",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","DETAIL"));
        assertThat(result.path("ratioState").asText()).isEqualTo("UNDEFINED");
        assertThat(result.path("primaryRatio").isNull()).isTrue();
        assertThat(result.path("visitCount").asLong()).isZero();
        assertThat(jdbc.sql("SELECT requested_by_user_id FROM mart.calculation_run WHERE id=(SELECT calculation_run_id FROM mart.lc_conversion_measurement WHERE id=:id)")
                .param("id",UUID.fromString(result.path("id").asText())).query(UUID.class).single()).isEqualTo(userId);
    }

    @Test
    void lineageCannotBorrowCoverageFromAnotherWindowOrDropAQualifiedReceipt() throws Exception {
        measurementGrants();
        Instant end=Instant.now().minusSeconds(40L*86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant start=end.minusSeconds(86400);
        for (Instant from:List.of(start,start.minusSeconds(86400))) {
            postListing("/facts/measurement-coverage",Map.of("windowStart",from.toString(),"windowEnd",from.plusSeconds(86400).toString(),
                    "retentionDays",30,"evidencePath","DETAIL","sourceCompleteThrough",end.plusSeconds(31L*86400).toString(),
                    "sourceReference","evidence://synthetic/lineage-window","expectedVisitRows",0,"expectedLinkRows",0));
        }
        var measurement=postListing("/measurements",Map.of("windowStart",start.toString(),"windowEnd",end.toString(),
                "retentionDays",30,"evidencePath","DETAIL"));
        UUID measurementId=UUID.fromString(measurement.path("id").asText());
        UUID wrongReceipt=jdbc.sql("SELECT id FROM core.lc_measurement_coverage WHERE platform_listing_id=:listing AND window_start=:start")
                .param("listing",fixture.id("listing")).param("start",java.sql.Timestamp.from(start.minusSeconds(86400)))
                .query(UUID.class).single();
        String original=jdbc.sql("SELECT to_jsonb(l)::text FROM mart.lc_measurement_lineage l WHERE measurement_id=:id")
                .param("id",measurementId).query(String.class).single();
        // BEFORE INSERT must reject the forged binding before duplicate-ID
        // handling; assert its domain SQLSTATE, not merely any SQL exception.
        for (boolean missing:List.of(false,true)) {
            assertThatThrownBy(()->jdbc.sql("""
                    INSERT INTO mart.lc_measurement_lineage(measurement_id,coverage_id,input_digest,inputs,source_timezone,recorded_at)
                    SELECT measurement_id,CAST(:receipt AS uuid),input_digest,inputs,source_timezone,recorded_at
                    FROM mart.lc_measurement_lineage WHERE measurement_id=:id
                    """).param("receipt",missing?null:wrongReceipt).param("id",measurementId).update())
                    .hasRootCauseInstanceOf(java.sql.SQLException.class)
                    .satisfies(failure->{
                        Throwable root=failure;
                        while (root.getCause()!=null) root=root.getCause();
                        assertThat(((java.sql.SQLException)root).getSQLState()).isEqualTo(missing?"MO093":"MO092");
                    });
        }
        assertThat(jdbc.sql("SELECT to_jsonb(l)::text FROM mart.lc_measurement_lineage l WHERE measurement_id=:id")
                .param("id",measurementId).query(String.class).single()).isEqualTo(original);
    }

    @Test
    void lateSaleReversalCreatesANewMeasurementAndPreservesTheOriginalInputs() throws Exception {
        measurementGrants();
        Instant from=Instant.now().minusSeconds(42L*86400).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant to=from.plusSeconds(86400);
        postListing("/facts/visit",Map.of("visitKey","late-sale-visit","visitedAt",from.plusSeconds(3600).toString(),
                "sellable","YES","channel","ORGANIC"));
        UUID sale=seedSale(from.plusSeconds(3700),null,false);
        postListing("/facts/purchase-link",Map.of("visitKey","late-sale-visit","salesFactId",sale.toString(),"basis","MANUAL_ENTRY"));
        postListing("/facts/measurement-coverage",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","DETAIL","sourceCompleteThrough",to.plusSeconds(31L*86400).toString(),
                "sourceReference","evidence://synthetic/complete-sale-window","expectedVisitRows",1,"expectedLinkRows",1));
        var request=Map.of("windowStart",from.toString(),"windowEnd",to.toString(),"retentionDays",30,"evidencePath","DETAIL");
        var original=postListing("/measurements",request);
        assertThat(original.path("primaryRatio").decimalValue()).isEqualByComparingTo("1");
        assertThat(jdbc.sql("SELECT inputs->'sourceStrata'->'ORGANIC'->>'retained' FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",UUID.fromString(original.path("id").asText())).query(String.class).single()).isEqualTo("1");
        UUID reversal=seedSale(from.plusSeconds(3700),sale,true);
        var revised=postListing("/measurements",request);
        assertThat(revised.path("primaryRatio").decimalValue()).isEqualByComparingTo("0");
        assertThat(revised.path("id").asText()).isNotEqualTo(original.path("id").asText());
        assertThat(jdbc.sql("SELECT primary_ratio FROM mart.lc_conversion_measurement WHERE id=:id")
                .param("id",UUID.fromString(original.path("id").asText())).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("1");
        String lineage=jdbc.sql("SELECT inputs::text FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",UUID.fromString(revised.path("id").asText())).query(String.class).single();
        assertThat(lineage).contains(sale.toString(),reversal.toString());
        assertThat(jdbc.sql("SELECT inputs->'sourceStrata'->'ORGANIC'->>'retained' FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",UUID.fromString(revised.path("id").asText())).query(String.class).single()).isEqualTo("0");
        assertThat(jdbc.sql("SELECT inputs->>'sourceStrataQualified' FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",UUID.fromString(revised.path("id").asText())).query(String.class).single()).isEqualTo("true");
        assertThat(jdbc.sql("SELECT canonical_input_digest=encode(sha256(convert_to(inputs::text,'UTF8')),'hex') FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",UUID.fromString(revised.path("id").asText())).query(Boolean.class).single()).isTrue();
        var source=measurementEvidence.measuredSourceStrata(UUID.fromString(revised.path("id").asText()),fixture.id("listing")).orElseThrow();
        assertThat(source.qualified()).isTrue();
        assertThat(source.counts().path("ORGANIC").path("visits").longValue()).isEqualTo(1);
        assertThat(source.counts().path("ORGANIC").path("retained").longValue()).isZero();
        assertThat(measurementEvidence.measuredSourceStrata(UUID.fromString(revised.path("id").asText()),fixture.id("listingTwo"))).isEmpty();
        assertThat(postListing("/measurements",request).path("primaryRatio").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void displaySnapshotsRetainUnknownReportsAndRespectOriginalAcquisitionTime() throws Exception {
        measurementGrants();
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_MANUAL_VERIFY,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        Instant from=Instant.now().minusSeconds(42L*86400).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant to=from.plusSeconds(86400);
        var prior=postListing("/facts/display",Map.of("displayState","DISPLAYED","displayedText","Synthetic prior display",
                "observedAt",from.minusSeconds(3600).toString(),"evidenceReference","evidence://synthetic/prior-display"));
        postListing("/facts/measurement-coverage",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","DETAIL","sourceCompleteThrough",to.plusSeconds(31L*86400).toString(),
                "sourceReference","evidence://synthetic/empty-display-window","expectedVisitRows",0,"expectedLinkRows",0));
        var request=Map.of("windowStart",from.toString(),"windowEnd",to.toString(),"retentionDays",30,"evidencePath","DETAIL");
        var original=postListing("/measurements",request);
        UUID originalId=UUID.fromString(original.path("id").asText());
        var json=new tools.jackson.databind.ObjectMapper();
        String retained=jdbc.sql("SELECT inputs::text FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",originalId).query(String.class).single();
        var input=json.readTree(retained);
        assertThat(input.path("displayObservations").size()).isEqualTo(1);
        assertThat(input.path("displayObservations").get(0).path("id").asText()).isEqualTo(prior.path("observationId").asText());
        Instant asOf=Instant.parse(input.path("displayObservationAsOf").asText());
        var unknown=postListing("/facts/display",Map.of("displayState","UNKNOWN",
                "observedAt",from.plusSeconds(3600).toString(),"evidenceReference","evidence://synthetic/late-unknown-display"));
        assertThat(measurementEvidence.displaySnapshot(fixture.id("listing"),from,to,asOf))
                .isEqualTo(input.path("displayObservations"));
        var revised=postListing("/measurements",request);
        var revisedInput=json.readTree(jdbc.sql("SELECT inputs::text FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",UUID.fromString(revised.path("id").asText())).query(String.class).single());
        assertThat(revisedInput.path("displayObservations").size()).isEqualTo(2);
        assertThat(revisedInput.path("displayObservations").get(1).path("id").asText()).isEqualTo(unknown.path("observationId").asText());
        assertThat(revisedInput.path("displayObservations").get(1).path("display_state").asText()).isEqualTo("UNKNOWN");
        assertThat(revisedInput.path("fullTargetVersionCoverageQualified").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT inputs::text FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",originalId).query(String.class).single()).isEqualTo(retained);
    }

    private UUID seedSale(Instant occurred, UUID supersedes, boolean reversal) {
        UUID sale=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,
                  sale_stage,retention_window_days,source_fact_key,native_order_key,occurred_at,quantity,currency_code,
                  gross_amount,net_amount,adjustment_kind,supersedes_fact_id)
                VALUES (:id,:org,:provenance,:variant,:store,'RETAINED',30,:key,'synthetic-order',:at,:quantity,'RUB',
                  :amount,:amount,:adjustment,:supersedes)
                """).param("id",sale).param("org",fixture.id("organization")).param("provenance",fixture.id("provenance"))
                .param("variant",fixture.id("listingVariant")).param("store",fixture.id("store")).param("key",sale.toString())
                .param("at",java.sql.Timestamp.from(occurred)).param("quantity",reversal ? -1:1)
                .param("amount",reversal ? -100:100).param("adjustment",reversal ? "REVERSAL":null)
                .param("supersedes",supersedes).update();
        return sale;
    }

    @Test
    void mappingRebindingInvalidatesOnlyDependentActionsAndKeepsOriginalLineage() {
        UUID listing = fixture.id("listing"), variant = fixture.id("listingVariant");
        String original = affectedDigest(listing);
        String other = affectedDigest(fixture.id("listingTwo"));
        String lineage = jdbc.sql("SELECT identity_lineage::text FROM core.lc_affected_set WHERE id=:id")
                .param("id",fixture.id("affectedSetOne")).query(String.class).single();
        assertThat(lineage).contains(fixture.id("productVariant").toString(), "mappingId", "mappingVersion");
        assertThat(bindingGaps("actionOne")).doesNotContain("AFFECTED_SET_DIGEST_CHANGED");
        // Synthetic canonical correction: end the old mapping, create its
        // effective-dated successor; never mutate the old frozen evidence.
        fixture.seed.sql("""
                WITH ended AS (
                    UPDATE core.listing_mapping SET status='ENDED',effective_to=statement_timestamp(),
                        updated_at=statement_timestamp(),version=version+1
                    WHERE platform_listing_variant_id=:variant AND status='ACTIVE' RETURNING *
                ) INSERT INTO core.listing_mapping (id,organization_id,platform_listing_variant_id,
                    product_variant_id,effective_from,status,confirmed_by_user_id,reason,created_at,updated_at)
                SELECT gen_random_uuid(),organization_id,platform_listing_variant_id,:product,
                    effective_to,'ACTIVE',confirmed_by_user_id,'synthetic correction',effective_to,effective_to FROM ended
                """).param("variant",variant).param("product",fixture.id("productVariantTwo")).update();
        assertThat(affectedDigest(listing)).isNotEqualTo(original);
        assertThat(affectedDigest(fixture.id("listingTwo"))).isEqualTo(other);
        assertThat(bindingGaps("actionOne")).contains("AFFECTED_SET_DIGEST_CHANGED");
        assertThat(bindingGaps("actionTwo")).doesNotContain("AFFECTED_SET_DIGEST_CHANGED");
        assertThat(jdbc.sql("SELECT identity_lineage::text FROM core.lc_affected_set WHERE id=:id")
                .param("id",fixture.id("affectedSetOne")).query(String.class).single()).isEqualTo(lineage);
    }

    @Test
    void mappingVersionAndNativeMembershipChangesAreVisibleToBindingChecks() {
        UUID listing = fixture.id("listing");
        String original = affectedDigest(listing);
        fixture.seed.sql("UPDATE core.listing_mapping SET version=version+1 WHERE platform_listing_variant_id=:id")
                .param("id",fixture.id("listingVariant")).update();
        assertThat(affectedDigest(listing)).isNotEqualTo(original);
        String afterVersion = affectedDigest(listing);
        fixture.seed.sql("UPDATE core.platform_listing_variant SET status='ARCHIVED' WHERE id=:id")
                .param("id",fixture.id("listingVariant")).update();
        assertThat(affectedDigest(listing)).isNotEqualTo(afterVersion);
        assertThat(bindingGaps("actionOne")).contains("AFFECTED_SET_DIGEST_CHANGED");
    }

    @Test
    void identityDigestDoesNotDependOnSessionTimezone() throws Exception {
        String original = affectedDigest(fixture.id("listing"));
        try (var connection=fixture.application.getConnection(); var statement=connection.createStatement()) {
            statement.execute("SET TIME ZONE 'Asia/Taipei'");
            try (var query=connection.prepareStatement("SELECT core.lc_listing_affected_set_digest(?)")) {
                query.setObject(1,fixture.id("listing"));
                try (var rows=query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(original);
                }
            }
        }
    }

    private String affectedDigest(UUID listing) {
        return jdbc.sql("SELECT core.lc_listing_affected_set_digest(:id)").param("id",listing)
                .query(String.class).single();
    }

    private List<String> bindingGaps(String action) {
        return jdbc.sql("SELECT unnest(ops.lc_binding_gaps(:id))").param("id",fixture.id(action))
                .query(String.class).list();
    }

    @Test
    void historicalCalibrationRemainsBoundAfterRetirementAndCannotBorrowAnotherVersion() {
        Instant frozen=jdbc.sql("SELECT clock_timestamp()").query(java.time.OffsetDateTime.class).single().toInstant();
        UUID org=fixture.id("organization"), store=fixture.id("store"), pack=fixture.id("calibrationPackage");
        var before=calibration.resolveBound(org,fixture.graph.platform(),store,pack,1,frozen);
        assertThat(before.ok()).isTrue();
        fixture.seed.sql("UPDATE core.lc_calibration_package SET status='RETIRED',retired_at=:at WHERE id=:id")
                .param("id",pack).param("at",java.sql.Timestamp.from(frozen.plusSeconds(1))).update();
        var after=calibration.resolveBound(org,fixture.graph.platform(),store,pack,1,frozen);
        assertThat(after.ok()).isTrue();
        assertThat(after.resolved().values()).isEqualTo(before.resolved().values());
        assertThat(calibration.resolve(org,fixture.graph.platform(),store,frozen.plusSeconds(2)).ok()).isFalse();
        assertThat(calibration.resolveBound(org,fixture.graph.platform(),store,pack,2,frozen).ok()).isFalse();
        assertThat(calibration.resolveBound(UUID.randomUUID(),fixture.graph.platform(),store,pack,1,frozen).ok()).isFalse();
    }

    @Test
    void calibrationLifecycleUsesProfessionalAndIndependentOwnerThroughSignedHttp() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        for (var action:List.of(ActionScopeCode.LISTING_CALIBRATION_PREPARE,ActionScopeCode.LISTING_CALIBRATION_VALIDATE,
                ActionScopeCode.LISTING_CALIBRATION_ACCEPT)) {
            users.grantScope(OPERATOR,userId,action,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        }
        var json=new tools.jackson.databind.ObjectMapper();
        var draft=calibrationDraft("synthetic-correction-calibration");
        var created=postCalibration("",draft);
        String id=created.path("package").path("id").asText();
        String digest=created.path("governance").path("draft_digest").asText();
        var decision=Map.of("digest",digest,"evidenceReference","evidence://synthetic/professional-validation");
        var validated=postCalibration("/"+id+"/validate",decision);
        assertThat(validated.path("governance").path("validated_digest").asText()).isEqualTo(digest);
        mvc.perform(post("/api/v1/console/listing/calibrations/"+id+"/accept")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(decision))).andExpect(status().is4xxClientError());
        assertThat(jdbc.sql("SELECT accepted_at IS NULL FROM ops.lc_calibration_governance WHERE package_id=:id")
                .param("id",UUID.fromString(id)).query(Boolean.class).single()).isTrue();
        // A separately authenticated Owner performs the exact acceptance.
        subject="calibration-independent-owner-"+UUID.randomUUID();
        userId=users.provision(OPERATOR,fixture.id("organization"),providerId,subject,null,"Synthetic independent Owner",null).id();
        jdbc.sql("UPDATE iam.user_account SET credentials_valid_from=now()-interval '1 hour' WHERE id=:id").param("id",userId).update();
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_CALIBRATION_ACCEPT,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        var accepted=postCalibration("/"+id+"/accept",decision);
        assertThat(accepted.path("package").path("status").asText()).isEqualTo("DRAFT");
        var active=postCalibration("/"+id+"/activate",decision);
        assertThat(active.path("package").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT package_id FROM core.lc_resolve_calibration_for(:org,:platform,:store,clock_timestamp(),'DESCRIPTION_CORRECTION')")
                .param("org",fixture.id("organization")).param("platform",fixture.graph.platform()).param("store",fixture.id("store"))
                .query(UUID.class).single()).isEqualTo(UUID.fromString(id));
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_calibration_event WHERE package_id=:id")
                .param("id",UUID.fromString(id)).query(Long.class).single()).isEqualTo(4);
        assertThat(calibration.resolve(fixture.id("organization"),fixture.graph.platform(),fixture.id("store"),Instant.now())
                .resolved().packageId()).isEqualTo(fixture.id("calibrationPackage"));
    }

    private tools.jackson.databind.node.ObjectNode calibrationDraft(String code) {
        var json=new tools.jackson.databind.ObjectMapper();
        var draft=json.createObjectNode();
        draft.put("code",code); draft.put("version",1);
        draft.put("scopeKind","ORGANIZATION"); draft.put("purposeCode","DESCRIPTION_CORRECTION");
        draft.put("effectiveFrom",Instant.now().minusSeconds(60).toString());
        draft.put("effectiveTo",Instant.now().plusSeconds(86400).toString());
        draft.put("evidenceReference","evidence://synthetic/calibration-source");
        draft.put("rationale","Synthetic professional calibration"); draft.put("impact","Exact synthetic scope");
        draft.put("differences","Initial synthetic correction package");
        String values=jdbc.sql("""
                SELECT jsonb_agg(jsonb_build_object('categoryCode',category_code,'numeric',value_numeric,'text',value_text,
                 'json',value_json,'unitCode',unit_code,'scopeNote',scope_note,'windowDays',window_days,'evidenceReference',evidence_reference))::text
                FROM core.lc_calibration_value WHERE package_id=:id
                """).param("id",fixture.id("calibrationPackage")).query(String.class).single();
        draft.set("values",json.readTree(values));
        return draft;
    }

    @Test
    void calibrationValidationRejectsMissingComponentsAndIncorrectExactDigest() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        for (var action:List.of(ActionScopeCode.LISTING_CALIBRATION_PREPARE,ActionScopeCode.LISTING_CALIBRATION_VALIDATE)) {
            users.grantScope(OPERATOR,userId,action,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        }
        var json=new tools.jackson.databind.ObjectMapper();
        var draft=calibrationDraft("synthetic-incomplete-calibration");
        ((tools.jackson.databind.node.ArrayNode) draft.path("values")).remove(0);
        var created=postCalibration("",draft);
        String id=created.path("package").path("id").asText();
        assertThat(created.path("combinationFailures").isEmpty()).isFalse();
        mvc.perform(post("/api/v1/console/listing/calibrations/"+id+"/validate")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("digest",created.path("governance").path("draft_digest").asText(),
                  "evidenceReference","evidence://synthetic/review")))).andExpect(status().isBadRequest());
        var complete=postCalibration("",calibrationDraft("synthetic-complete-calibration"));
        String completeId=complete.path("package").path("id").asText();
        mvc.perform(post("/api/v1/console/listing/calibrations/"+completeId+"/validate")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("digest","f".repeat(64),"evidenceReference","evidence://synthetic/wrong-digest"))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_calibration_event WHERE package_id IN (:a,:b) AND event_kind<>'DRAFTED'")
                .param("a",UUID.fromString(id)).param("b",UUID.fromString(completeId)).query(Long.class).single()).isZero();
    }

    private tools.jackson.databind.JsonNode postCalibration(String suffix,Object body) throws Exception {
        var json=new tools.jackson.databind.ObjectMapper();
        var result=mvc.perform(post("/api/v1/console/listing/calibrations"+suffix).header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andDo(r -> { if (r.getResolvedException()!=null) throw r.getResolvedException(); })
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void seedSummaryProfile() {
        fixture.seed.sql("""
                INSERT INTO core.lc_summary_equivalence_profile (id,organization_id,platform_code,summary_kind,
                  profile_version,proof_state,covers_numerator,covers_denominator,covers_time_attribution,
                  covers_maturity,covers_revision,evidence_reference,published_by_user_id,published_at,effective_from,status)
                VALUES (:id,:org,:platform,'VISITS_AND_RETAINED_PURCHASES',1,'PROVEN',true,true,true,true,true,
                  'evidence://synthetic/summary-equivalence',:owner,now(),now()-interval '1 hour','ACTIVE')
                """).param("id", UUID.randomUUID()).param("org", fixture.id("organization"))
                .param("platform", fixture.graph.platform()).param("owner",fixture.id("ownerUser")).update();
    }

    private String bearer() throws JOSEException {
        Instant now = Instant.now();
        return "Bearer " + sign(new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(2))).expirationTime(Date.from(now.plusSeconds(600)))
                .claim("auth_time", now.minusSeconds(5).getEpochSecond()).claim("amr", List.of("pwd", "mfa"))
                .claim("sid", "listing-session-" + subject));
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
