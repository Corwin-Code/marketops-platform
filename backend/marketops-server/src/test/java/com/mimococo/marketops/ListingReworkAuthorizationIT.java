package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
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
        var mapper = new tools.jackson.databind.ObjectMapper();
        String response = mvc.perform(post("/api/v1/console/listing/health/listings/" + fixture.id("listing") + suffix)
                .header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andDo(r -> { if (r.getResolvedException() != null) throw r.getResolvedException(); })
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
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
        UUID reversal=seedSale(from.plusSeconds(3700),sale,true);
        var revised=postListing("/measurements",request);
        assertThat(revised.path("primaryRatio").decimalValue()).isEqualByComparingTo("0");
        assertThat(revised.path("id").asText()).isNotEqualTo(original.path("id").asText());
        assertThat(jdbc.sql("SELECT primary_ratio FROM mart.lc_conversion_measurement WHERE id=:id")
                .param("id",UUID.fromString(original.path("id").asText())).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("1");
        String lineage=jdbc.sql("SELECT inputs::text FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",UUID.fromString(revised.path("id").asText())).query(String.class).single();
        assertThat(lineage).contains(sale.toString(),reversal.toString());
        assertThat(postListing("/measurements",request).path("primaryRatio").decimalValue()).isEqualByComparingTo("0");
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
