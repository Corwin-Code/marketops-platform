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
import java.math.BigDecimal;
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
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Autowired ListingDescriptionLoopback loopback;
    @Autowired JdbcClient jdbc;
    @Autowired IdentityProviderService providers;
    @Autowired UserAdministrationService users;
    @Autowired com.mimococo.marketops.listingconversion.internal.application.CalibrationService calibration;
    @Autowired com.mimococo.marketops.operationsworkflow.ListingActionIntake listingIntake;
    @Autowired com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.MeasurementEvidenceRepository measurementEvidence;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.mimococo.marketops.aicopilot.port.ModelGatewayPort modelGateway;
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
    void graph(org.junit.jupiter.api.TestInfo info) throws Exception {
        fixture = new ListingConversionFixture(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), TestDatabase.migrationRole(), TestDatabase.migrationPassword()),
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), TestDatabase.applicationRole(), TestDatabase.applicationPassword()),
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()),
                info.getTestMethod().orElseThrow().getName().equals("restorationRejectsACompletedSourceWhoseCapturedPriorIsMissing"));
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
    void listingAuthorityExpiresDuringTheSameDatabaseStatement() {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_LAUNCH,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        // A deadline written after statement start distinguishes a volatile wall-clock check from a cached statement time.
        fixture.seed.sql("""
                DO $body$
                DECLARE actor uuid := '%s'; org uuid := '%s'; store_ref uuid := '%s';
                BEGIN
                  IF NOT ops.lc_actor_holds_action(actor,org,store_ref,'LISTING_ACTION_LAUNCH') THEN
                    RAISE EXCEPTION 'fixture must have current launch authority';
                  END IF;
                  UPDATE iam.user_scope_grant SET effective_to=clock_timestamp()
                    WHERE user_id=actor AND action_code='LISTING_ACTION_LAUNCH';
                  IF ops.lc_actor_holds_action(actor,org,store_ref,'LISTING_ACTION_LAUNCH') THEN
                    RAISE EXCEPTION 'statement-start time must not preserve expired launch authority';
                  END IF;
                END $body$
                """.formatted(userId,fixture.id("organization"),fixture.id("store"))).update();
    }

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
    void foreignListingMeasurementCannotWriteOutcomeOrReturnEvaluation() throws Exception {
        measurementGrants();
        users.grantScope(OPERATOR, userId, ActionScopeCode.LISTING_OUTCOME_EVALUATE,
                ResourceScopeType.ORGANIZATION, fixture.id("organization"), null);
        Instant to = Instant.now().minusSeconds(40L * 86400)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant from = to.minusSeconds(86400);
        postForListing(fixture.id("listingTwo"), "/facts/visit", Map.of(
                "visitKey", "foreign-listing-outcome-measurement",
                "visitedAt", from.plusSeconds(30).toString(), "sellable", "YES", "channel", "ORGANIC"));
        postForListing(fixture.id("listingTwo"), "/facts/measurement-coverage", Map.of(
                "windowStart", from.toString(), "windowEnd", to.toString(), "retentionDays", 30,
                "evidencePath", "DETAIL", "sourceCompleteThrough", to.plusSeconds(31L * 86400).toString(),
                "sourceReference", "evidence://synthetic/foreign-listing-outcome-measurement",
                "expectedVisitRows", 1, "expectedLinkRows", 0));
        var measurement = postForListing(fixture.id("listingTwo"), "/measurements", Map.of(
                "windowStart", from.toString(), "windowEnd", to.toString(),
                "retentionDays", 30, "evidencePath", "DETAIL"));
        assertThat(measurement.path("platformListingId").asText())
                .isEqualTo(fixture.id("listingTwo").toString());
        assertThat(measurement.path("ratioState").asText()).isEqualTo("DEFINED");

        var before = businessCounts();
        var json = new tools.jackson.databind.ObjectMapper();
        mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "nodeCode", "D14", "stage", "OPERATIONAL",
                        "measurementId", measurement.path("id").asText()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("RESOURCE_SCOPE_DENIED"))
                .andExpect(jsonPath("$.planId").doesNotExist())
                .andExpect(jsonPath("$.results").doesNotExist());
        assertThat(businessCounts()).isEqualTo(before);
    }

    @Test
    void nativeScopeNeedsCompleteEnumerationAndUnchangedRefreshPreservesTheDependency() throws Exception {
        var identity=applicationContext.getBean(com.mimococo.marketops.productlisting.ListingScopeEvidence.class);
        var health=applicationContext.getBean(com.mimococo.marketops.listingconversion.internal.application.ListingHealthService.class);
        UUID listing=fixture.id("listing");
        String nativeListing=jdbc.sql("SELECT native_listing_key FROM core.platform_listing WHERE id=:id")
                .param("id",listing).query(String.class).single();
        String nativeVariant=jdbc.sql("SELECT native_variant_key FROM core.platform_listing_variant WHERE id=:id")
                .param("id",fixture.id("listingVariant")).query(String.class).single();
        // Native scope resolution must not reinterpret accepted Policy when a session time zone changes.
        try(var connection=fixture.application.getConnection(); var statement=connection.createStatement()) {
            String utc,local;
            statement.execute("SET TIME ZONE 'UTC'");
            try(var row=statement.executeQuery("SELECT ops.lc_calibration_digest('"+fixture.id("calibrationPackage")+"')")) {
                row.next();utc=row.getString(1);
            }
            statement.execute("SET TIME ZONE 'Asia/Taipei'");
            try(var row=statement.executeQuery("SELECT ops.lc_calibration_digest('"+fixture.id("calibrationPackage")+"')")) {
                row.next();local=row.getString(1);
            }
            assertThat(local).isEqualTo(utc);
        }
        String original=identity.snapshot(listing,Instant.now()).digest();
        String url="/api/v1/console/listing/health/listings/"+listing+"/facts/native-scope";
        var mapper=new tools.jackson.databind.ObjectMapper();
        java.util.function.BiFunction<String,String,Map<String,Object>> capture=(kind,state)->{
            Map<String,Object> body=new java.util.LinkedHashMap<>();
            body.put("scopeKind",kind); body.put("nativeScopeKey",kind.equals("WHOLE_LISTING")?nativeListing:nativeVariant);
            body.put("nativeVariantKeys",List.of(nativeVariant)); body.put("coverageState",state);
            body.put("expectedMemberCount",state.equals("COMPLETE")?1:2);
            body.put("continuationReference",state.equals("COMPLETE")?null:"fixture:next-page");
            body.put("sourceReference","  fixture:actual-native-enumeration  ");
            body.put("scopeBasisReference","fixture:whole-listing-management-boundary");
            body.put("observedAt",Instant.now().toString());
            body.put("verificationExpiresAt",Instant.now().plusSeconds(3600).toString());
            return body;
        };
        long before=count("core.platform_listing_scope_observation");
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_CONVERSION_VIEW,ResourceScopeType.STORE,fixture.id("store"),null);
        mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(capture.apply("WHOLE_LISTING","COMPLETE")))).andExpect(status().isForbidden());
        assertThat(count("core.platform_listing_scope_observation")).isEqualTo(before);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_MANUAL_VERIFY,ResourceScopeType.STORE,fixture.id("store"),null);
        var response=mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(capture.apply("WHOLE_LISTING","COMPLETE"))))
                .andDo(r->{if(r.getResolvedException()!=null) throw r.getResolvedException();})
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID observation=UUID.fromString(mapper.readTree(response).path("observationId").asText());
        assertThat(identity.snapshot(listing,Instant.now()).digest()).isEqualTo(original);
        assertThat(health.freezeAffectedSet(listing).id()).isEqualTo(fixture.id("affectedSetOne"));
        assertThat(jdbc.sql("SELECT source_reference FROM core.platform_listing_scope_observation WHERE id=:id")
                .param("id",observation).query(String.class).single()).isEqualTo("  fixture:actual-native-enumeration  ");
        assertThatThrownBy(()->jdbc.sql("UPDATE core.platform_listing_scope_observation SET coverage_state='UNKNOWN' WHERE id=:id")
                .param("id",observation).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(identity.snapshot(listing,Instant.now().plusSeconds(3601)).identityLineage().path("nativeScope").path("reasonCodes").toString())
                .contains("NATIVE_SCOPE_VERIFICATION_EXPIRED");

        mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(capture.apply("WHOLE_LISTING","PARTIAL")))).andExpect(status().isOk());
        assertThat(health.freezeAffectedSet(listing).resolution().state()).isEqualTo("INCOMPLETE");
        assertThatThrownBy(()->fixture.launch(UUID.randomUUID(),"actionOne",fixture.id("ownerUser")))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(capture.apply("NATIVE_VARIANT","COMPLETE")))).andExpect(status().isOk());
        assertThat(health.freezeAffectedSet(listing).resolution().state()).isEqualTo("INCOMPLETE");
        Map<String,Object> incompletePage=capture.apply("WHOLE_LISTING","COMPLETE");
        incompletePage.put("continuationReference","fixture:more-pages");
        mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(incompletePage))).andExpect(status().is4xxClientError());

        UUID additional=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO core.platform_listing_variant(id,organization_id,platform_listing_id,native_variant_key,
                    first_seen_at,last_seen_at,status,created_at,updated_at,version)
                VALUES(:id,:org,:listing,'new-native-member',now(),now(),'OBSERVED',now(),now(),0)
                """).param("id",additional).param("org",fixture.id("organization")).param("listing",listing).update();
        fixture.seed.sql("""
                INSERT INTO core.listing_mapping(id,organization_id,platform_listing_variant_id,product_variant_id,
                    effective_from,status,confirmed_by_user_id,reason,created_at,updated_at,version)
                VALUES(:id,:org,:variant,:product,now(),'ACTIVE',:user,'synthetic native member',now(),now(),0)
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization")).param("variant",additional)
                .param("product",fixture.id("productVariant")).param("user",fixture.id("ownerUser")).update();
        Map<String,Object> full=capture.apply("WHOLE_LISTING","COMPLETE");
        full.put("nativeVariantKeys",List.of(nativeVariant,"new-native-member"));full.put("expectedMemberCount",2);
        mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(full))).andExpect(status().isOk());
        var complete=health.freezeAffectedSet(listing);
        assertThat(complete.resolution().state()).withFailMessage("Full native capture resolution: %s",complete.resolution()).isEqualTo("COMPLETE");
        assertThat(complete.resolution().listingVariantIds()).containsExactlyInAnyOrder(fixture.id("listingVariant"),additional);
        assertThat(complete.digest()).isNotEqualTo(original);
        assertThatThrownBy(()->fixture.launch(UUID.randomUUID(),"actionOne",fixture.id("ownerUser")))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_description_command WHERE action_id=:id")
                .param("id",fixture.id("actionOne")).query(Integer.class).single()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={-120,120})
    void nativeScopeRecordingUsesDatabaseChronologyDespiteApplicationClockOffset(int seconds) throws Exception {
        var intake=applicationContext.getBean(com.mimococo.marketops.listingconversion.internal.application.ListingFactIntakeService.class);
        var actionRows=applicationContext.getBean(com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository.class);
        var health=applicationContext.getBean(com.mimococo.marketops.listingconversion.internal.application.ListingHealthService.class);
        var originalClock=(java.time.Clock)org.springframework.test.util.ReflectionTestUtils.getField(intake,"clock");
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_MANUAL_VERIFY,
                ResourceScopeType.STORE,fixture.id("store"),null);
        String listingKey=jdbc.sql("SELECT native_listing_key FROM core.platform_listing WHERE id=:id")
                .param("id",fixture.id("listing")).query(String.class).single();
        String memberKey=jdbc.sql("SELECT native_variant_key FROM core.platform_listing_variant WHERE id=:id")
                .param("id",fixture.id("listingVariant")).query(String.class).single();
        var json=new tools.jackson.databind.ObjectMapper();
        String endpoint="/api/v1/console/listing/health/listings/"+fixture.id("listing")+"/facts/native-scope";
        Map<String,Object> capture=new java.util.LinkedHashMap<>(Map.of("scopeKind","WHOLE_LISTING","nativeScopeKey",listingKey,
                "nativeVariantKeys",List.of(memberKey),"coverageState","PARTIAL","expectedMemberCount",2,
                "continuationReference","fixture:next-page","sourceReference","fixture:clock-bound-source",
                "scopeBasisReference","fixture:whole-listing","observedAt",actionRows.databaseNow().toString(),
                "verificationExpiresAt",actionRows.databaseNow().plusSeconds(3600).toString()));
        mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(capture))).andExpect(status().isOk());
        assertThat(health.freezeAffectedSet(fixture.id("listing")).resolution().state()).isEqualTo("INCOMPLETE");
        Instant observed=actionRows.databaseNow();
        capture.put("coverageState","COMPLETE");capture.put("expectedMemberCount",1);
        capture.remove("continuationReference");capture.put("observedAt",observed.toString());
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(intake,"clock",java.time.Clock.offset(originalClock,java.time.Duration.ofSeconds(seconds)));
            var response=mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(capture))).andExpect(status().isOk()).andReturn();
            UUID receipt=UUID.fromString(json.readTree(response.getResponse().getContentAsString()).path("observationId").asText());
            var complete=health.freezeAffectedSet(fixture.id("listing"));
            assertThat(complete.resolution().state()).withFailMessage("After completed capture under clock offset %s: %s",seconds,complete.resolution()).isEqualTo("COMPLETE");
            assertThat(jdbc.sql("SELECT observed_at=:observed AND recorded_at<=clock_timestamp() FROM core.platform_listing_scope_observation WHERE id=:id")
                    .param("observed",java.sql.Timestamp.from(observed)).param("id",receipt).query(Boolean.class).single()).isTrue();
            long scopesBefore=count("core.platform_listing_scope_observation");
            long provenanceBefore=count("core.fact_provenance");
            capture.put("observedAt",actionRows.databaseNow().plusSeconds(60).toString());
            mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(capture))).andExpect(status().isBadRequest());
            assertThat(count("core.platform_listing_scope_observation")).isEqualTo(scopesBefore);
            assertThat(count("core.fact_provenance")).isEqualTo(provenanceBefore);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(intake,"clock",originalClock);
        }
    }

    @Test
    void retainedNecessaryFailureActivatesOneContinuousTaskWithoutAnActionProposal() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        for(var scope:List.of(ActionScopeCode.LISTING_CONVERSION_VIEW,ActionScopeCode.LISTING_ACTION_PREPARE))
            users.grantScope(OPERATOR,userId,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        String endpoint="/api/v1/console/listing/health/listings/"+fixture.id("listing");
        var json=new tools.jackson.databind.ObjectMapper();
        mvc.perform(post(endpoint+"/recompute").header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk());
        mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.diagnosticResponsibilities").isEmpty());

        postListing("/facts/description",Map.of("text","Synthetic English description","languageCode","en",
                "kizMarkedDeclared",false));
        mvc.perform(post(endpoint+"/recompute").header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk());
        mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.health.necessaryState").value("PASS"))
                .andExpect(jsonPath("$.health.eligibility.EVALUATION").value("INELIGIBLE"))
                .andExpect(jsonPath("$.health.opportunities[0].code").value("DESCRIPTION_NOT_RUSSIAN"))
                .andExpect(jsonPath("$.diagnosticResponsibilities").isEmpty());
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_task_responsibility WHERE platform_listing_id=:listing AND cause_code='DESCRIPTION_NOT_RUSSIAN'")
                .param("listing",fixture.id("listing")).query(Long.class).single()).isZero();

        seedSummaryProfile();
        UUID opportunityTask=null;
        for(int replay=0;replay<2;replay++) {
            mvc.perform(post(endpoint+"/recompute").header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk());
            var listing=json.readTree(mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.health.necessaryState").value("PASS"))
                    .andExpect(jsonPath("$.health.eligibility.EVALUATION").value("ELIGIBLE"))
                    .andExpect(jsonPath("$.diagnosticResponsibilities.length()").value(1))
                    .andReturn().getResponse().getContentAsString());
            UUID currentTask=UUID.fromString(diagnosticStatus(listing,"DESCRIPTION_NOT_RUSSIAN")
                    .path("taskId").asText());
            if(replay==0) opportunityTask=currentTask;
            assertThat(currentTask).isEqualTo(opportunityTask);
        }
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.work_task t JOIN ops.lc_task_responsibility r ON r.task_id=t.id
                 WHERE r.platform_listing_id=:listing AND r.cause_code='DESCRIPTION_NOT_RUSSIAN'
                   AND r.responsibility_lane='QUALIFIED_OPPORTUNITY'
                """).param("listing",fixture.id("listing")).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.work_task_event WHERE task_id=:task AND event_kind='RAISED'")
                .param("task",opportunityTask).query(Long.class).single()).isEqualTo(1);

        long actionCount=jdbc.sql("SELECT count(*) FROM ops.lc_action").query(Long.class).single();
        UUID containment=fixture.contain(UUID.randomUUID(),fixture.id("ownerUser"),fixture.id("listing"));
        UUID task=null;
        Instant origin=null;
        for(int replay=0;replay<2;replay++) {
            mvc.perform(post(endpoint+"/recompute").header(HttpHeaders.AUTHORIZATION,bearer()))
                    .andExpect(result->assertThat(result.getResponse().getStatus())
                            .withFailMessage("Risk activation failed: %s",result.getResolvedException()).isEqualTo(200));
            var response=mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.diagnosticResponsibilities.length()").value(2))
                    .andReturn().getResponse().getContentAsString();
            var current=diagnosticStatus(json.readTree(response),"NOT_CONTAINED");
            UUID currentTask=UUID.fromString(current.path("taskId").asText());
            Instant currentOrigin=Instant.parse(current.path("firstRaisedAt").asText());
            if(replay==0) {task=currentTask;origin=currentOrigin;}
            assertThat(currentTask).isEqualTo(task);
            assertThat(currentOrigin).isEqualTo(origin);
            assertThat(current.path("clockState").asText()).isEqualTo("CONTINUOUS_RISK");
            assertThat(Instant.parse(current.path("acknowledgementDueAt").asText())).isEqualTo(origin.plusSeconds(900));
            assertThat(Instant.parse(current.path("actionDueAt").asText())).isEqualTo(origin.plusSeconds(3600));
            assertThat(current.path("acknowledgedAt").isNull()).isTrue();
        }
        assertThat(jdbc.sql("SELECT recommendation_id IS NULL FROM ops.work_task WHERE id=:id").param("id",task)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_action").query(Long.class).single()).isEqualTo(actionCount);
        var review=json.readTree(mvc.perform(get("/api/v1/console/listing/operations-review?storeId="+fixture.id("store"))
                .header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        var firstRow=review.path("current").path("rows").get(0);
        assertThat(firstRow.path("health").path("platformListingId").asText()).isEqualTo(fixture.id("listing").toString());
        assertThat(firstRow.path("lane").asText()).isEqualTo("NECESSARY_RISK");
        assertThat(firstRow.path("responsibilities").get(0).path("causeCode").asText()).isEqualTo("NOT_CONTAINED");
        assertThat(firstRow.path("responsibilities").get(0).path("lane").asText()).isEqualTo("NECESSARY_RISK");
        assertThat(firstRow.path("responsibilities").get(1).path("causeCode").asText()).isEqualTo("DESCRIPTION_NOT_RUSSIAN");
        assertThat(firstRow.path("responsibilities").get(1).path("lane").asText()).isEqualTo("QUALIFIED_OPPORTUNITY");
        mvc.perform(post(endpoint+"/responsibilities/"+task+"/acknowledgement").header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isForbidden());
        users.grantScope(OPERATOR,userId,ActionScopeCode.TASK_ASSIGN,ResourceScopeType.STORE,fixture.id("store"),null);
        mvc.perform(post(endpoint+"/responsibilities/"+task+"/acknowledgement").header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isNoContent());
        var acknowledged=json.readTree(mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(diagnosticStatus(acknowledged,"NOT_CONTAINED").path("acknowledgedAt").isNull()).isFalse();
        assertThat(diagnosticStatus(acknowledged,"NOT_CONTAINED").path("firstAttributableActionAt").isNull()).isTrue();
        mvc.perform(post("/api/v1/console/advertising/tasks/"+task+"/action").header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"actionKind\":\"REPAIRED\",\"evidenceReference\":\"fixture://caller\",\"reason\":\"Caller label is not source resolution\"}"))
                .andExpect(status().isForbidden());
        assertThatThrownBy(()->fixture.app.sql("""
                INSERT INTO ops.work_task(id,organization_id,title,state,created_at,updated_at)
                VALUES (:id,:org,'An unbound Task is still refused','OPEN',clock_timestamp(),clock_timestamp())
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization")).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        mvc.perform(post("/api/v1/console/workflow/tasks/"+task+"/closure").header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true,\"closureReason\":\"Click is not risk resolution\",\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        fixture.attest(UUID.randomUUID(),containment,fixture.id("ownerUser"),"REPAIR_ATTESTATION");
        fixture.attest(UUID.randomUUID(),containment,fixture.id("verifierUser"),"BUSINESS_CONSENT");
        fixture.reenable(containment,fixture.id("ownerUser"));
        mvc.perform(post(endpoint+"/recompute").header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/console/workflow/tasks/"+task+"/closure").header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true,\"closureReason\":\"Independent repair and consent followed by current diagnosis\",\"expectedVersion\":0}"))
                .andExpect(status().isNoContent());
        fixture.contain(UUID.randomUUID(),fixture.id("ownerUser"),fixture.id("listing"));
        mvc.perform(post(endpoint+"/recompute").header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk());
        var reopened=applicationContext.getBean(com.mimococo.marketops.operationsworkflow.ListingTaskSloQuery.class)
                .diagnosticsForListing(fixture.id("listing")).stream()
                .filter(diagnostic->diagnostic.causeCode().equals("NOT_CONTAINED")).findFirst().orElseThrow().status();
        assertThat(reopened.taskId()).isEqualTo(task);
        assertThat(reopened.firstRaisedAt()).isEqualTo(origin);
        assertThat(reopened.acknowledgedAt()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM ops.work_task_event WHERE task_id=:id AND event_kind='RAISED'")
                .param("id",task).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.work_task_event WHERE task_id=:id AND event_kind='REOPENED'")
                .param("id",task).query(Long.class).single()).isEqualTo(1);
    }

    private static tools.jackson.databind.JsonNode diagnosticStatus(tools.jackson.databind.JsonNode listing,
                                                                     String causeCode) {
        for(var responsibility:listing.path("diagnosticResponsibilities"))
            if(causeCode.equals(responsibility.path("causeCode").asText())) return responsibility.path("status");
        throw new AssertionError("Missing diagnostic responsibility "+causeCode);
    }

    @Test
    void reacquiringOldNativeEnumerationCannotRenewItsSourceFreshness() throws Exception {
        var identity=applicationContext.getBean(com.mimococo.marketops.productlisting.ListingScopeEvidence.class);
        UUID listing=UUID.randomUUID(), variant=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO core.platform_listing(id,organization_id,store_id,marketplace_account_id,platform_code,
                    native_listing_key,first_seen_at,last_seen_at,status,created_at,updated_at,version)
                SELECT :id,organization_id,store_id,marketplace_account_id,platform_code,
                    :key,now()-interval '3 hours',now()-interval '3 hours','OBSERVED',now(),now(),0
                FROM core.platform_listing WHERE id=:source
                """).param("id",listing).param("key",listing.toString()).param("source",fixture.id("listing")).update();
        fixture.seed.sql("""
                INSERT INTO core.platform_listing_variant(id,organization_id,platform_listing_id,native_variant_key,
                    first_seen_at,last_seen_at,status,created_at,updated_at,version)
                VALUES(:id,:org,:listing,'stale-native-member',now()-interval '3 hours',now()-interval '3 hours',
                    'OBSERVED',now(),now(),0)
                """).param("id",variant).param("org",fixture.id("organization")).param("listing",listing).update();
        fixture.seed.sql("""
                INSERT INTO core.listing_mapping(id,organization_id,platform_listing_variant_id,product_variant_id,
                    effective_from,status,confirmed_by_user_id,reason,created_at,updated_at,version)
                VALUES(:id,:org,:variant,:product,now()-interval '3 hours','ACTIVE',:user,
                    'Synthetic stale source fixture',now(),now(),0)
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization")).param("variant",variant)
                .param("product",fixture.id("productVariant")).param("user",fixture.id("ownerUser")).update();
        assertThat(identity.snapshot(listing,Instant.now()).identityLineage().path("nativeScope").path("reasonCodes").toString())
                .contains("NATIVE_SCOPE_UNPROVEN");
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_MANUAL_VERIFY,ResourceScopeType.STORE,fixture.id("store"),null);
        Instant observed=Instant.now().minusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var mapper=new tools.jackson.databind.ObjectMapper();
        Map<String,Object> capture=new java.util.LinkedHashMap<>();
        capture.put("scopeKind","WHOLE_LISTING"); capture.put("nativeScopeKey",listing.toString());
        capture.put("nativeVariantKeys",List.of("stale-native-member")); capture.put("coverageState","COMPLETE");
        capture.put("expectedMemberCount",1); capture.put("sourceReference","fixture:unchanged-old-enumeration");
        capture.put("scopeBasisReference","fixture:whole-native-listing"); capture.put("observedAt",observed.toString());
        String digest=null;
        for(int days=1;days<=2;days++) {
            capture.put("verificationExpiresAt",Instant.now().plusSeconds(days*86400L).toString());
            mvc.perform(post("/api/v1/console/listing/health/listings/"+listing+"/facts/native-scope")
                    .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(capture))).andExpect(status().isOk());
            var snapshot=identity.snapshot(listing,Instant.now());
            assertThat(snapshot.identityLineage().path("nativeScope").path("state").asText()).isEqualTo("INCOMPLETE");
            assertThat(snapshot.identityLineage().path("nativeScope").path("reasonCodes").toString())
                    .contains("NATIVE_SCOPE_SOURCE_STALE").doesNotContain("NATIVE_SCOPE_VERIFICATION_EXPIRED");
            if(digest!=null) assertThat(snapshot.digest()).isEqualTo(digest);
            digest=snapshot.digest();
        }
        assertThat(jdbc.sql("""
                SELECT count(*) FROM core.platform_listing_scope_observation s JOIN core.fact_provenance p ON p.id=s.provenance_id
                 WHERE s.platform_listing_id=:listing AND s.observed_at=:observed AND p.source_time=s.observed_at
                  AND p.ingestion_time=s.recorded_at AND s.recorded_at>s.observed_at
                """).param("listing",listing).param("observed",java.sql.Timestamp.from(observed)).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void conditionalSimulationRetainsExactBasisWithoutPublishingMetricOrGrantingAdmission() throws Exception {
        users.assignRole(OPERATOR, userId, BusinessRoleCode.OWNER, null);
        for (var code : List.of(ActionScopeCode.LISTING_ACTION_PREPARE, ActionScopeCode.LISTING_CONVERSION_VIEW,
                ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW)) {
            users.grantScope(OPERATOR, userId, code, ResourceScopeType.STORE, fixture.id("store"), null);
        }
        var finance = users.grantScope(OPERATOR, userId, ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.PRODUCT_VARIANT, fixture.id("productVariant"), null);
        String url = "/api/v1/console/listing/actions/candidates/" + fixture.id("candidateOne");
        String request = """
                {"listPrice":100,"sellerDiscountRate":0,"discountAlreadyInNetRevenue":false,"unitCost":50,
                 "stepFees":[{"priceFloor":0,"feePerUnit":0}],"feesKnown":true,"currencyCode":"RUB",
                 "expenses":{"fixedPromotionFee":{"amount":600,"currencyCode":"RUB"},
                    "returnLossPerUnit":{"amount":0,"currencyCode":"RUB"},
                    "advertisingPerUnit":{"amount":0,"currencyCode":"RUB"},
                    "variableTaxPerUnit":{"amount":0,"currencyCode":"RUB"}},
                 "scenarios":[{"code":"MINIMUM","quantity":14,"necessary":true,"conservative":true}],
                 "referenceProfitLine":100,
                 "context":{"periodStart":"2026-09-01T00:00:00Z","periodEnd":"2026-10-01T00:00:00Z",
                    "sourceReferences":{"fixedPromotionFee":"  fixture:explicit-fixed-fee-600  "},
                    "commercialDeclaration":{"engagementKind":"SELLER_DIRECT_DISCOUNT","nativePromotionKey":"fixture-discount",
                        "terms":{"sellerDiscountRate":"0","fixedPromotionFee":"600"},"priceFreeze":false,"autoParticipation":false,
                        "termsEvidenceReference":"fixture://declared-terms","obligations":{"fixedPromotionFee":"600 RUB"}},
                    "assumptions":"Declared single price tier and fixed commitment; synthetic conditional input"}}
                """;
        Long runsBefore = jdbc.sql("SELECT count(*) FROM mart.calculation_run WHERE organization_id=:org")
                .param("org", fixture.id("organization")).query(Long.class).single();
        var response = mvc.perform(post(url + "/simulate").header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andDo(r -> { if (r.getResolvedException() != null) throw r.getResolvedException(); })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inverseMinimumQuantity").value(14))
                .andExpect(jsonPath("$.scenarios[0].contributionProfit").value(100))
                .andExpect(jsonPath("$.conditionalScenariosPassed").value(true))
                .andExpect(jsonPath("$.qualificationState").value("UNQUALIFIED"))
                .andExpect(jsonPath("$.inputSnapshot.context.sourceReferences.fixedPromotionFee").value("  fixture:explicit-fixed-fee-600  "))
                .andExpect(jsonPath("$.inputSnapshot.inputs.currencyCode").value("RUB"))
                .andExpect(jsonPath("$.inputSnapshot.sourceKind").value("DECLARED_CONDITIONAL_INPUTS"))
                .andExpect(jsonPath("$.inputSnapshot.purposeCode").value("PROMOTION"))
                .andReturn().getResponse().getContentAsString();
        UUID simulation = UUID.fromString(new tools.jackson.databind.ObjectMapper().readTree(response).path("id").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM mart.calculation_run WHERE organization_id=:org")
                .param("org", fixture.id("organization")).query(Long.class).single()).isEqualTo(runsBefore);
        assertThat(jdbc.sql("""
                SELECT calculation_run_id IS NULL AND demand_gate_passed IS NULL
                  AND inputs_digest=encode(sha256(convert_to(input_snapshot::text,'UTF8')),'hex')
                  AND input_snapshot->>'submittedBy'=:actor
                  AND input_snapshot->>'promotionTermsDigest'=ops.lc_promotion_terms_digest(input_snapshot->'context'->'commercialDeclaration')
                  AND input_snapshot->>'commercialDeclarationState'='EXACT_DECLARATION_ONLY'
                  FROM ops.lc_simulation WHERE id=:id
                """).param("actor", userId.toString()).param("id", simulation).query(Boolean.class).single()).isTrue();
        var simulationSnapshot = new tools.jackson.databind.ObjectMapper().readTree(response).path("inputSnapshot");
        var repository = applicationContext.getBean(com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository.class);
        String scopeDigest = simulationSnapshot.path("nativeIdentityDigest").asText();
        String termsDigest = simulationSnapshot.path("promotionTermsDigest").asText();
        assertThat(repository.matchingSimulationDigest(simulation, fixture.id("organization"), fixture.id("candidateOne"),
                scopeDigest, termsDigest,"PROMOTION",fixture.id("calibrationPackage"),1,Instant.now())).isEmpty();
        assertThat(repository.matchingSimulationDigest(simulation, fixture.id("organization"), UUID.randomUUID(),
                scopeDigest, termsDigest,"PROMOTION",fixture.id("calibrationPackage"),1,Instant.now())).isEmpty();
        assertThat(repository.matchingSimulationDigest(simulation, fixture.id("organization"), fixture.id("candidateOne"),
                "0".repeat(64), termsDigest,"PROMOTION",fixture.id("calibrationPackage"),1,Instant.now())).isEmpty();
        assertThat(repository.matchingSimulationDigest(simulation, fixture.id("organization"), fixture.id("candidateOne"),
                scopeDigest, "0".repeat(64),"PROMOTION",fixture.id("calibrationPackage"),1,Instant.now())).isEmpty();
        assertThat(simulationSnapshot.path("knownPromotionContext").path("coverage").asText()).isEqualTo("KNOWN_RECORDS_ONLY");
        fixture.seed.sql("""
                INSERT INTO ops.lc_promotion_engagement(id,organization_id,store_id,platform_listing_id,
                    engagement_kind,native_promotion_key,terms,price_freeze,auto_participation,terms_evidence_reference,
                    adopted,obligations,state,created_at,updated_at,version)
                VALUES(:id,:org,:store,:listing,'OFFICIAL_PROMOTION_PARTICIPATION','newly-observed-activity',
                    '{"period":"unknown"}'::jsonb,false,false,'fixture:observed-unqualified-activity',true,
                    '{"remaining":"unknown"}'::jsonb,'ACTIVE',clock_timestamp(),clock_timestamp(),0)
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization")).param("store",fixture.id("store"))
                .param("listing",fixture.id("listing")).update();
        var changedContext=repository.knownPromotionContext(fixture.id("organization"),fixture.id("listing"));
        assertThat(changedContext.path("digest").asText())
                .isNotEqualTo(simulationSnapshot.path("knownPromotionContext").path("digest").asText());
        assertThat(repository.matchingSimulationDigest(simulation, fixture.id("organization"), fixture.id("candidateOne"),
                scopeDigest, termsDigest,"PROMOTION",fixture.id("calibrationPackage"),1,Instant.now())).isEmpty();
        var boundedRequest=(tools.jackson.databind.node.ObjectNode)new tools.jackson.databind.ObjectMapper().readTree(request);
        boundedRequest.put("purpose","BOUNDED_EXPLORATION");
        mvc.perform(post(url + "/simulate").header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(boundedRequest.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputSnapshot.purposeCode").value("BOUNDED_EXPLORATION"));
        boundedRequest.put("purpose","LISTING_CONVERSION");
        mvc.perform(post(url + "/simulate").header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(boundedRequest.toString()))
                .andExpect(status().is4xxClientError());
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO ops.lc_simulation (id, organization_id, candidate_id, scenario_set, inputs_digest,
                    results, inverse_minimum_quantity, inverse_state, demand_gate_passed, computed_at,
                    model_version, input_snapshot, conditional_scenarios_passed)
                SELECT :newId, organization_id, candidate_id, scenario_set, inputs_digest, results,
                    inverse_minimum_quantity, inverse_state, true, computed_at, model_version, input_snapshot,
                    conditional_scenarios_passed FROM ops.lc_simulation WHERE id=:id
                """).param("newId", UUID.randomUUID()).param("id", simulation).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        mvc.perform(post(url + "/simulate").header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(request.replace("\"quantity\":14", "\"quantity\":-1")))
                .andExpect(status().is4xxClientError());
        users.revokeScope(OPERATOR, finance.id(), "withdraw simulation financial access", finance.version());
        mvc.perform(get(url + "/simulations").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].inverseState").value("MASKED"))
                .andExpect(jsonPath("$[0].inputSnapshot").doesNotExist())
                .andExpect(jsonPath("$[0].conditionalScenariosPassed").doesNotExist());
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
    void listingAssistanceUsesOnlyAllowlistedEvidenceAndCannotExecuteItsOwnSuggestion() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        for (var scope:List.of(ActionScopeCode.LISTING_CONVERSION_VIEW,ActionScopeCode.DIAGNOSTIC_VIEW,
                ActionScopeCode.EVIDENCE_VIEW,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW))
            users.grantScope(OPERATOR,userId,scope,ResourceScopeType.STORE,fixture.id("store"),null);
        var productEvidence=users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.PRODUCT_VARIANT,fixture.id("productVariant"),null);
        UUID provider=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO ops.ai_provider(id,provider_code,display_name,invocation_url,request_template,
                    response_pointer,auth_header_name,auth_value_template,eligibility_state,last_verified_at,
                    evidence_ref,verified_source_title,owner_label,status,created_at,updated_at)
                VALUES(:id,:code,'Synthetic local model','https://fixture.invalid/model','{}','/answer',
                    'Authorization','Bearer {value}','VERIFIED',clock_timestamp(),'fixture://listing-assistance',
                    'Synthetic protocol only','test-fixture','ACTIVE',clock_timestamp(),clock_timestamp())
                """).param("id",provider).param("code","listing-assistance-"+provider).update();
        fixture.seed.sql("""
                INSERT INTO ops.ai_model(id,provider_id,model_code,display_name,secret_reference,max_context_tokens,
                    status,created_at,updated_at)
                VALUES(:id,:provider,'fixture-listing-model','Synthetic listing model',
                    'secret-ref://fixture/listing-model',16000,'ACTIVE',clock_timestamp(),clock_timestamp())
                """).param("id",UUID.randomUUID()).param("provider",provider).update();
        var json=new tools.jackson.databind.ObjectMapper();
        long actionsBefore=count("ops.lc_action"),descriptionCommandsBefore=count("ops.lc_description_command");
        long priceCommandsBefore=count("ops.price_command"),adCommandsBefore=count("ops.ad_bid_command");
        try {
            org.mockito.Mockito.when(modelGateway.invoke(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
                com.mimococo.marketops.aicopilot.port.ModelRequest request=call.getArgument(0);
                assertThat(request.userPrompt()).contains("listing.subjectRef="+fixture.id("listing"),
                        "listing.memberRef="+fixture.id("listingVariant"),
                        "listing.assistancePurpose=RUSSIAN_DESCRIPTION","metrics.valueRef=")
                        .doesNotContain("fictional-listing-one",ListingConversionFixture.TARGET_TEXT_ONE);
                var paths=request.userPrompt().lines().filter(line->line.contains("="))
                        .map(line->line.substring(0,line.indexOf('='))).collect(java.util.stream.Collectors.toSet());
                var allowed=new java.util.HashSet<>(jdbc.sql("""
                        SELECT field_path FROM ops.ai_projection_field
                         WHERE projection_code='LISTING_ASSISTANCE' AND projection_version=1
                        """).query(String.class).list());
                assertThat(allowed).containsAll(paths);
                var matcher=java.util.regex.Pattern.compile("(?m)^metrics\\.valueRef=([0-9a-f-]{36})$")
                        .matcher(request.userPrompt());
                assertThat(matcher.find()).isTrue();
                String metric=matcher.group(1);
                return com.mimococo.marketops.aicopilot.port.ModelResponse.answered("""
                        {"facts":[{"statement":"The cited canonical value is available.","evidenceRefs":["%s"]}],
                         "recommendations":[
                          {"statement":"Предложите формулировку только для проверки человеком.",
                           "actionCapability":"LISTING_CONTENT_REVIEW","proposedParameters":{"reviewFocus":"Проверить подтвержденные сведения."},
                           "expectedEffect":"Human review only","risk":"Unsupported details remain unknown","validationWindowDays":14},
                          {"statement":"Change a price directly.","actionCapability":"PRICE_CHANGE",
                           "proposedParameters":{"targetPrice":1,"currencyCode":"RUB"},
                           "expectedEffect":"Unknown","risk":"No authority","validationWindowDays":14}]}
                        """.formatted(metric),7);
            });
            String route="/api/v1/console/listing/health/listings/"+fixture.id("listing")+"/assistance";
            var response=mvc.perform(post(route).header(HttpHeaders.AUTHORIZATION,bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"window\":\"D30\",\"purpose\":\"RUSSIAN_DESCRIPTION\"}"))
                    .andDo(result->{ if(result.getResolvedException()!=null) throw result.getResolvedException(); })
                    .andExpect(status().isOk()).andReturn();
            var result=json.readTree(response.getResponse().getContentAsString());
            assertThat(result.path("state").asText()).isEqualTo("PARTIAL_OUTPUT_REJECTED");
            assertThat(result.path("claims").get(0).path("accepted").asBoolean()).isTrue();
            assertThat(result.path("claims").get(1).path("accepted").asBoolean()).isTrue();
            assertThat(result.path("claims").get(2).path("accepted").asBoolean()).isFalse();
            assertThat(result.path("claims").get(0).path("metricValueRefs").get(0).asText()).isNotBlank();
            assertThat(result.path("claims").get(2).path("rejectionCode").asText())
                    .isEqualTo("LISTING_ASSISTANCE_ACTION_OUT_OF_SCOPE");
            UUID invocation=UUID.fromString(result.path("invocationId").asText());
            mvc.perform(get(route+"/"+invocation).header(HttpHeaders.AUTHORIZATION,bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.invocationId").value(invocation.toString()));
            mvc.perform(post(route).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"window\":\"D30\",\"purpose\":\"PRICE_CHANGE\"}"))
                    .andExpect(status().isBadRequest());
            users.revokeScope(OPERATOR,productEvidence.id(),"Withdraw exact product evidence",productEvidence.version());
            mvc.perform(post(route).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"window\":\"D30\",\"purpose\":\"RUSSIAN_DESCRIPTION\"}"))
                    .andExpect(status().isForbidden());
            org.mockito.Mockito.verify(modelGateway,org.mockito.Mockito.times(1))
                    .invoke(org.mockito.ArgumentMatchers.any());
            assertThat(count("ops.lc_action")).isEqualTo(actionsBefore);
            assertThat(count("ops.lc_description_command")).isEqualTo(descriptionCommandsBefore);
            assertThat(count("ops.price_command")).isEqualTo(priceCommandsBefore);
            assertThat(count("ops.ad_bid_command")).isEqualTo(adCommandsBefore);
        } finally {
            fixture.seed.sql("UPDATE ops.ai_provider SET status='RETIRED',updated_at=clock_timestamp() WHERE id=:id")
                    .param("id",provider).update();
        }
    }

    @Test
    void feedbackReviewAndExperienceRemainRevisionAwareAndSourceRevocationFailsClosed() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        var sourceView=users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_CONVERSION_VIEW,
                ResourceScopeType.STORE,fixture.id("store"),null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.EVIDENCE_VIEW,
                ResourceScopeType.STORE,fixture.id("store"),null);
        ExperienceTarget target=seedExperienceTarget();
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_CONVERSION_VIEW,
                ResourceScopeType.STORE,target.storeId(),null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_PREPARE,
                ResourceScopeType.STORE,target.storeId(),null);
        FormalOutcome formal=qualifiedFormalOutcome(false);
        UUID sourceResult=formal.resultId();

        Instant feedbackAt=Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID rawObservation=seedFeedbackObservation("""
                {"items":[{"id":"feedback-r3","offer_id":"fictional-listing","text":"Не пахнет"}]}
                """,feedbackAt);
        var json=new tools.jackson.databind.ObjectMapper();
        String feedbackRoute="/api/v1/console/listing/health/listings/"+fixture.id("listing")+"/feedback";
        Map<String,Object> source=Map.of("rawObservationId",rawObservation,"itemPointer","/items/0",
                "identityPointer","/id","listingPointer","/offer_id","textPointer","/text");
        var first=mvc.perform(post(feedbackRoute+"/sources").header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(source)))
                .andExpect(status().isOk()).andReturn();
        UUID feedbackId=UUID.fromString(json.readTree(first.getResponse().getContentAsString()).path("itemId").asText());
        mvc.perform(post(feedbackRoute+"/sources").header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(source)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.itemId").value(feedbackId.toString()));
        assertThat(jdbc.sql("SELECT count(*) FROM core.lc_feedback_item WHERE platform_listing_id=:listing")
                .param("listing",fixture.id("listing")).query(Long.class).single()).isEqualTo(1);
        String classifications=feedbackRoute+"/"+feedbackId+"/classifications";
        mvc.perform(post(classifications).header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"themeCode":"ODOR","qualificationState":"CONFIRMED","classifierVersion":"fixture-candidate-1",
                     "reason":"Synthetic candidate ignored the Russian negation"}
                    """)).andExpect(status().isOk());
        mvc.perform(post(classifications).header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"themeCode":"NO_ODOR","qualificationState":"CONFIRMED","classifierVersion":"human-review-1",
                     "reason":"Human review preserves the explicit negation"}
                    """)).andExpect(status().isOk());
        mvc.perform(get(feedbackRoute+"/"+feedbackId).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classifications[0].themeCode").value("ODOR"))
                .andExpect(jsonPath("$.classifications[1].themeCode").value("NO_ODOR"));
        Instant from=feedbackAt.minusSeconds(60),to=Instant.now().minusMillis(1);
        String period="?from="+from+"&to="+to;
        mvc.perform(get(feedbackRoute+"/themes"+period).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].themeCode").value("NO_ODOR"))
                .andExpect(jsonPath("$[0].mentionCount").value(1))
                .andExpect(jsonPath("$[1]").doesNotExist());

        var reviewResponse=mvc.perform(get("/api/v1/console/listing/operations-review?storeId="+fixture.id("store"))
                .header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk()).andReturn();
        var review=json.readTree(reviewResponse.getResponse().getContentAsString());
        assertThat(review.path("current").path("asOf")).isEqualTo(review.path("daily").path("asOf"));
        assertThat(review.path("current").path("asOf")).isEqualTo(review.path("weekly").path("asOf"));
        assertThat(review.path("current").path("rows")).isEqualTo(review.path("daily").path("rows"));
        assertThat(review.path("current").path("rows")).isEqualTo(review.path("weekly").path("rows"));

        String experienceRoute="/api/v1/console/listing/experience";
        var experience=mvc.perform(post(experienceRoute).header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "sourceActionId",formal.actionId(),"sourceResultId",sourceResult,
                        "targetListingId",target.listingId(),"candidateKind","CONTENT_DESCRIPTION",
                        "applicabilityEvidenceReference","evidence://synthetic/same-bounded-content-purpose"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceListingId").value(fixture.id("listing").toString()))
                .andExpect(jsonPath("$.applicabilityState").value("APPLICABLE_FOR_REVIEW"))
                .andReturn();
        UUID experienceId=UUID.fromString(json.readTree(experience.getResponse().getContentAsString()).path("id").asText());
        changeExperienceTargetScope(target);
        mvc.perform(get(experienceRoute+"?targetListingId="+target.listingId())
                .header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(experienceId.toString()))
                .andExpect(jsonPath("$[0].applicabilityState").value("TARGET_SCOPE_CHANGED"));
        UUID revisedResult=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO ops.lc_node_result
                SELECT (jsonb_populate_record(NULL::ops.lc_node_result,to_jsonb(original)||jsonb_build_object(
                    'id',CAST(:revised AS text),'revision_no',original.revision_no+1,
                    'evaluated_at',CAST(clock_timestamp() AS text),
                    'evaluation_evidence',original.evaluation_evidence||jsonb_build_object('fixtureRevision',true)))).*
                  FROM ops.lc_node_result original WHERE original.id=:original
                """).param("revised",revisedResult).param("original",sourceResult).update();
        fixture.seed.sql("""
                INSERT INTO ops.lc_outcome_revision(id,organization_id,plan_id,original_result_id,revised_result_id,
                    revision_reason,late_fact_reference,recorded_at)
                SELECT gen_random_uuid(),organization_id,plan_id,:original,:revised,'CORRECTION',
                    'fixture://source-result-revision',clock_timestamp() FROM ops.lc_node_result WHERE id=:revised
                """).param("original",sourceResult).param("revised",revisedResult).update();
        mvc.perform(get(experienceRoute+"?targetListingId="+target.listingId())
                .header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(experienceId.toString()))
                .andExpect(jsonPath("$[0].applicabilityState").value("SOURCE_REVISED"));
        users.revokeScope(OPERATOR,sourceView.id(),"Withdraw current source Listing view",sourceView.version());
        mvc.perform(get(experienceRoute+"?targetListingId="+target.listingId())
                .header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_experience_application WHERE id=:id")
                .param("id",experienceId).query(Long.class).single()).isEqualTo(1);
    }

    private record ExperienceTarget(UUID storeId,UUID listingId) { }

    private void changeExperienceTargetScope(ExperienceTarget target) {
        fixture.seed.sql("""
                UPDATE core.listing_mapping SET version=version+1,updated_at=clock_timestamp()
                 WHERE platform_listing_variant_id=(SELECT id FROM core.platform_listing_variant
                    WHERE platform_listing_id=:listing ORDER BY id LIMIT 1) AND status='ACTIVE'
                """).param("listing",target.listingId()).update();
        UUID affected=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO core.lc_affected_set(id,organization_id,platform_listing_id,affected_set_digest,
                    platform_listing_variant_ids,product_variant_ids,resolution_state,unresolved_reason_codes,
                    resolved_at,created_at)
                SELECT :id,organization_id,platform_listing_id,core.lc_listing_affected_set_digest(:listing),
                    platform_listing_variant_ids,product_variant_ids,'COMPLETE','{}',clock_timestamp(),clock_timestamp()
                  FROM core.lc_affected_set WHERE platform_listing_id=:listing ORDER BY created_at DESC LIMIT 1
                """).param("id",affected).param("listing",target.listingId()).update();
        fixture.seed.sql("""
                INSERT INTO mart.lc_listing_health
                SELECT (jsonb_populate_record(NULL::mart.lc_listing_health,to_jsonb(source)||jsonb_build_object(
                    'id',CAST(gen_random_uuid() AS text),'affected_set_id',CAST(:affected AS text),
                    'health_version',source.health_version+1,'computed_at',CAST(clock_timestamp() AS text)))).*
                  FROM mart.lc_listing_health source WHERE source.platform_listing_id=:listing
                  ORDER BY source.health_version DESC LIMIT 1
                """).param("affected",affected).param("listing",target.listingId()).update();
    }

    private ExperienceTarget seedExperienceTarget() {
        UUID store=UUID.randomUUID(),listing=UUID.randomUUID(),variant=UUID.randomUUID();
        UUID scope=UUID.randomUUID(),affected=UUID.randomUUID(),health=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO core.store
                SELECT (jsonb_populate_record(NULL::core.store,to_jsonb(source)||jsonb_build_object(
                    'id',CAST(:id AS text),'code',:code,'native_store_key',:nativeKey,
                    'display_name','Synthetic experience target store'))).*
                  FROM core.store source WHERE source.id=:source
                """).param("id",store).param("code","experience-"+store)
                .param("nativeKey","experience-"+store).param("source",fixture.id("store")).update();
        fixture.seed.sql("""
                INSERT INTO core.platform_listing
                SELECT (jsonb_populate_record(NULL::core.platform_listing,to_jsonb(source)||jsonb_build_object(
                    'id',CAST(:id AS text),'store_id',CAST(:store AS text),'native_listing_key',:nativeKey))).*
                  FROM core.platform_listing source WHERE source.id=:source
                """).param("id",listing).param("store",store).param("nativeKey","experience-target-"+listing)
                .param("source",fixture.id("listingTwo")).update();
        fixture.seed.sql("""
                INSERT INTO core.platform_listing_variant
                SELECT (jsonb_populate_record(NULL::core.platform_listing_variant,to_jsonb(source)||jsonb_build_object(
                    'id',CAST(:id AS text),'platform_listing_id',CAST(:listing AS text),
                    'native_variant_key',:nativeKey,'native_sku_key',:nativeKey))).*
                  FROM core.platform_listing_variant source WHERE source.id=:source
                """).param("id",variant).param("listing",listing).param("nativeKey","experience-variant-"+variant)
                .param("source",fixture.id("listingVariantTwo")).update();
        fixture.seed.sql("""
                INSERT INTO core.listing_mapping
                SELECT (jsonb_populate_record(NULL::core.listing_mapping,to_jsonb(source)||jsonb_build_object(
                    'id',CAST(gen_random_uuid() AS text),'platform_listing_variant_id',CAST(:variant AS text),
                    'reason','Synthetic current experience target mapping'))).*
                  FROM core.listing_mapping source
                 WHERE source.platform_listing_variant_id=:source AND source.status='ACTIVE'
                """).param("variant",variant).param("source",fixture.id("listingVariantTwo")).update();
        fixture.seed.sql("""
                INSERT INTO core.platform_listing_scope_observation
                SELECT (jsonb_populate_record(NULL::core.platform_listing_scope_observation,to_jsonb(source)||jsonb_build_object(
                    'id',CAST(:id AS text),'platform_listing_id',CAST(:listing AS text),'native_scope_key',:listingKey,
                    'native_variant_keys',jsonb_build_array(:variantKey),'source_reference','fixture://experience-target-scope'))).*
                  FROM core.platform_listing_scope_observation source
                 WHERE source.platform_listing_id=:source ORDER BY source.recorded_at DESC LIMIT 1
                """).param("id",scope).param("listing",listing).param("listingKey","experience-target-"+listing)
                .param("variantKey","experience-variant-"+variant).param("source",fixture.id("listingTwo")).update();
        fixture.seed.sql("""
                INSERT INTO core.lc_affected_set(id,organization_id,platform_listing_id,affected_set_digest,
                    platform_listing_variant_ids,product_variant_ids,resolution_state,unresolved_reason_codes,resolved_at,created_at)
                VALUES(:id,:org,:listing,core.lc_listing_affected_set_digest(:listing),ARRAY[:variant]::uuid[],
                    ARRAY[:product]::uuid[],'COMPLETE','{}',clock_timestamp(),clock_timestamp())
                """).param("id",affected).param("org",fixture.id("organization")).param("listing",listing)
                .param("variant",variant).param("product",fixture.id("productVariantTwo")).update();
        fixture.seed.sql("""
                INSERT INTO mart.lc_listing_health
                SELECT (jsonb_populate_record(NULL::mart.lc_listing_health,to_jsonb(source)||jsonb_build_object(
                    'id',CAST(:id AS text),'store_id',CAST(:store AS text),'platform_listing_id',CAST(:listing AS text),
                    'affected_set_id',CAST(:affected AS text),'health_version',1,
                    'computed_at',CAST(clock_timestamp() AS text)))).*
                  FROM mart.lc_listing_health source WHERE source.id=:source
                """).param("id",health).param("store",store).param("listing",listing).param("affected",affected)
                .param("source",fixture.id("healthTwo")).update();
        return new ExperienceTarget(store,listing);
    }

    private UUID seedFeedbackObservation(String body,Instant sourceTime) {
        var custody=applicationContext.getBean(com.mimococo.marketops.marketplaceintegration.RawCustody.class);
        var content=custody.store("listing-feedback-fixture",body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID service=UUID.randomUUID(),endpoint=UUID.randomUUID(),job=UUID.randomUUID(),run=UUID.randomUUID();
        UUID unit=UUID.randomUUID(),observation=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO iam.service_account(id,organization_id,code,display_name,purpose,owner_label,status,
                    expires_at,created_at,updated_at)
                VALUES(:id,:org,:code,'Synthetic feedback custody','INGESTION','test-fixture','ACTIVE',
                    clock_timestamp()+interval '1 day',clock_timestamp(),clock_timestamp())
                """).param("id",service).param("org",fixture.id("organization"))
                .param("code","feedback-"+service).update();
        fixture.seed.sql("""
                INSERT INTO platform.platform_endpoint(id,platform_code,endpoint_code,api_version,read_write_class,
                    pagination_model,idempotency_support,verification_state,owner_label,contract_test_status,status,
                    created_at,updated_at)
                VALUES(:id,:platform,:code,'v1','READ','NONE','UNKNOWN','UNVERIFIED','test-fixture',
                    'NOT_IMPLEMENTED','ACTIVE',clock_timestamp(),clock_timestamp())
                """).param("id",endpoint).param("platform",fixture.graph.platform())
                .param("code","feedback."+endpoint).update();
        fixture.seed.sql("""
                INSERT INTO platform.ingestion_job(id,organization_id,marketplace_account_id,platform_code,
                    service_account_id,endpoint_id,job_code,display_name,status,created_at,updated_at,dataset_kind,store_id)
                VALUES(:id,:org,:account,:platform,:service,:endpoint,:code,'Synthetic feedback acquisition','PAUSED',
                    clock_timestamp(),clock_timestamp(),'LISTING',:store)
                """).param("id",job).param("org",fixture.id("organization")).param("account",fixture.id("account"))
                .param("platform",fixture.graph.platform()).param("service",service).param("endpoint",endpoint)
                .param("code","feedback-"+job).param("store",fixture.id("store")).update();
        fixture.seed.sql("""
                INSERT INTO ops.ingestion_run(id,job_id,state,fence_token,attempt_no,last_call_seq,created_at,updated_at)
                VALUES(:id,:job,'SUCCEEDED',1,1,1,clock_timestamp(),clock_timestamp())
                """).param("id",run).param("job",job).update();
        fixture.seed.sql("""
                INSERT INTO raw.raw_logical_unit(id,job_id,marketplace_account_id,unit_kind,source_unit_key,source_time)
                VALUES(:id,:job,:account,'LISTING_FEEDBACK',:key,:sourceTime)
                """).param("id",unit).param("job",job).param("account",fixture.id("account"))
                .param("key","feedback-"+unit).param("sourceTime",java.sql.Timestamp.from(sourceTime)).update();
        fixture.seed.sql("""
                INSERT INTO raw.raw_acquisition_observation(id,run_id,logical_unit_id,content_id,call_seq,native_status,
                    outcome_class,response_complete,pagination_outcome)
                VALUES(:id,:run,:unit,:content,1,'fixture-success','SUCCESS_BYTES',true,'END')
                """).param("id",observation).param("run",run).param("unit",unit)
                .param("content",content.contentId()).update();
        return observation;
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

    private record ManualExecutor(UUID id,String subject,UUID viewGrantId,long viewGrantVersion) { }

    private ManualExecutor provisionManualExecutor() {
        Instant effectiveFrom=Instant.now().minusSeconds(60);
        String executorSubject="listing-manual-executor-"+UUID.randomUUID();
        UUID executor=users.provision(OPERATOR,fixture.id("organization"),providerId,executorSubject,null,
                "Listing manual executor",null).id();
        jdbc.sql("UPDATE iam.user_account SET credentials_valid_from=now()-interval '1 hour' WHERE id=:id")
                .param("id",executor).update();
        users.assignRole(OPERATOR,executor,BusinessRoleCode.MARKETPLACE_OPERATOR,effectiveFrom);
        var viewGrant=users.grantScope(OPERATOR,executor,ActionScopeCode.LISTING_CONVERSION_VIEW,
                ResourceScopeType.STORE,fixture.id("store"),effectiveFrom);
        users.grantScope(OPERATOR,executor,ActionScopeCode.LISTING_MANUAL_EXECUTE,
                ResourceScopeType.STORE,fixture.id("store"),effectiveFrom);
        return new ManualExecutor(executor,executorSubject,viewGrant.id(),viewGrant.version());
    }

    @Test
    void issuedManualPacketRequiresCurrentExecutorStoreViewForListAndDetail() throws Exception {
        var executor=provisionManualExecutor();

        Instant effectiveFrom=Instant.now().minusSeconds(60);
        users.assignRole(OPERATOR, userId, BusinessRoleCode.OWNER, effectiveFrom);
        users.grantScope(OPERATOR, userId, ActionScopeCode.LISTING_ACTION_LAUNCH,
                ResourceScopeType.ORGANIZATION, fixture.id("organization"), effectiveFrom);
        listingIntake.ensureResponsibilityTask(fixture.id("organization"), fixture.id("recommendationTwo"),
                "Synthetic current-access packet responsibility", Instant.now().plusSeconds(86400), Instant.now());
        assertThat(fixture.launch(UUID.randomUUID(), "actionTwo", fixture.id("ownerUser"))
                .path("launched").asBoolean()).isTrue();
        var json = new tools.jackson.databind.ObjectMapper();
        var issued = mvc.perform(post("/api/v1/console/listing/manual/actions/" + fixture.id("actionTwo") + "/packets")
                .header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("executorUserId", executor.id()))))
                .andExpect(status().isOk()).andReturn();
        UUID packetId = UUID.fromString(json.readTree(issued.getResponse().getContentAsString()).path("id").asText());
        String target = jdbc.sql("SELECT target_text FROM ops.lc_manual_packet WHERE id=:id")
                .param("id", packetId).query(String.class).single();
        String retainedPacket = jdbc.sql("SELECT to_jsonb(p)::text FROM ops.lc_manual_packet p WHERE id=:id")
                .param("id", packetId).query(String.class).single();
        String retainedIssuanceAudit = jdbc.sql("""
                SELECT to_jsonb(e)::text FROM ops.metadata_audit_event e
                 WHERE entity_type='lc-manual-packet' AND entity_id=:id AND action='CREATE'
                 ORDER BY occurred_at LIMIT 1
                """).param("id", packetId).query(String.class).single();

        String issuerSubject = subject;
        subject=executor.subject();
        String executorToken = bearer();
        subject = issuerSubject;
        String packets = "/api/v1/console/listing/manual/packets";
        String packet = packets + "/" + packetId;
        mvc.perform(get(packets).header(HttpHeaders.AUTHORIZATION, executorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(packetId.toString()))
                .andExpect(jsonPath("$[0].targetText").value(target));
        mvc.perform(get(packet).header(HttpHeaders.AUTHORIZATION, executorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(packetId.toString()))
                .andExpect(jsonPath("$.targetText").value(target));

        users.revokeScope(OPERATOR,executor.viewGrantId(),"withdraw executor store view",executor.viewGrantVersion());
        mvc.perform(get(packets).header(HttpHeaders.AUTHORIZATION, executorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get(packet).header(HttpHeaders.AUTHORIZATION, executorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("RESOURCE_SCOPE_DENIED"))
                .andExpect(jsonPath("$.targetText").doesNotExist());
        assertThat(jdbc.sql("SELECT to_jsonb(p)::text FROM ops.lc_manual_packet p WHERE id=:id")
                .param("id", packetId).query(String.class).single()).isEqualTo(retainedPacket);
        assertThat(jdbc.sql("""
                SELECT to_jsonb(e)::text FROM ops.metadata_audit_event e
                 WHERE entity_type='lc-manual-packet' AND entity_id=:id AND action='CREATE'
                 ORDER BY occurred_at LIMIT 1
                """).param("id", packetId).query(String.class).single()).isEqualTo(retainedIssuanceAudit);
    }

    @Test
    void signedManualVerificationBindsIndependentExactObservationsAndExposesTheirExtent() throws Exception {
        Instant effectiveFrom=Instant.now().minusSeconds(60);
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,effectiveFrom);
        for (var scope:List.of(ActionScopeCode.LISTING_ACTION_LAUNCH,ActionScopeCode.LISTING_ACTION_PREPARE,
                ActionScopeCode.LISTING_MANUAL_VERIFY,ActionScopeCode.LISTING_CONVERSION_VIEW))
            users.grantScope(OPERATOR,userId,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),effectiveFrom);
        var executor=provisionManualExecutor();
        listingIntake.ensureResponsibilityTask(fixture.id("organization"),fixture.id("recommendationTwo"),
                "Synthetic manual verification responsibility",Instant.now().plusSeconds(86400),Instant.now());
        assertThat(fixture.launch(UUID.randomUUID(),"actionTwo",fixture.id("ownerUser")).path("launched").asBoolean()).isTrue();
        var json=new tools.jackson.databind.ObjectMapper();
        var packetResponse=mvc.perform(post("/api/v1/console/listing/manual/actions/"+fixture.id("actionTwo")+"/packets")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("executorUserId",executor.id()))))
                .andExpect(status().isOk()).andReturn();
        var packet=json.readTree(packetResponse.getResponse().getContentAsString());
        String target=packet.path("targetText").asText();
        UUID listing=fixture.id("listingTwo");
        String verifierSubject=subject;
        subject=executor.subject();
        String executorToken=bearer();
        subject=verifierSubject;
        mvc.perform(post("/api/v1/console/listing/manual/packets/"+packet.path("id").asText()+"/report")
                .header(HttpHeaders.AUTHORIZATION,executorToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("operationTime",Instant.now().toString(),"reportState","APPLIED",
                        "note","Synthetic executor report, separate from verification"))))
                .andExpect(status().isOk());
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
    void lateAssociationBranchesRequireQualifiedEvidenceForTheExactListingActionAndEvent() throws Exception {
        Instant effectiveFrom=Instant.now().minusSeconds(60);
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,effectiveFrom);
        for (var scope:List.of(ActionScopeCode.LISTING_ACTION_LAUNCH,ActionScopeCode.LISTING_ACTION_PREPARE,
                ActionScopeCode.LISTING_MANUAL_VERIFY,ActionScopeCode.LISTING_CONVERSION_VIEW))
            users.grantScope(OPERATOR,userId,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),effectiveFrom);
        var executor=provisionManualExecutor();
        listingIntake.ensureResponsibilityTask(fixture.id("organization"),fixture.id("recommendationTwo"),
                "Synthetic late association responsibility",Instant.now().plusSeconds(86400),Instant.now());
        assertThat(fixture.launch(UUID.randomUUID(),"actionTwo",fixture.id("ownerUser")).path("launched").asBoolean()).isTrue();
        var json=new tools.jackson.databind.ObjectMapper();
        var issued=mvc.perform(post("/api/v1/console/listing/manual/actions/"+fixture.id("actionTwo")+"/packets")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("executorUserId",executor.id()))))
                .andExpect(status().isOk()).andReturn();
        UUID packet=UUID.fromString(json.readTree(issued.getResponse().getContentAsString()).path("id").asText());
        Instant packetIssued=jdbc.sql("SELECT launched_at FROM ops.lc_launch WHERE action_id=:action")
                .param("action",fixture.id("actionTwo")).query(java.time.OffsetDateTime.class).single().toInstant();
        Instant operation=packetIssued;
        Instant packetExpires=jdbc.sql("SELECT issued_at FROM ops.lc_manual_packet WHERE id=:id")
                .param("id",packet).query(java.time.OffsetDateTime.class).single().toInstant().plusNanos(1_000);
        fixture.seed.sql("UPDATE ops.lc_manual_packet SET issued_at=:issued,expires_at=:expires WHERE id=:id")
                .param("issued",java.sql.Timestamp.from(packetIssued))
                .param("expires",java.sql.Timestamp.from(packetExpires)).param("id",packet).update();
        String verifierSubject=subject;
        subject=executor.subject();
        String executorToken=bearer();
        subject=verifierSubject;
        mvc.perform(post("/api/v1/console/listing/manual/packets/"+packet+"/report")
                .header(HttpHeaders.AUTHORIZATION,executorToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("operationTime",operation,"reportState","APPLIED",
                        "note","Synthetic operation reported after its finite packet expired"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reports[0].operationQualification")
                        .value("LAWFUL_LATE_REPORT"));
        Instant reportTime=jdbc.sql("SELECT reported_at FROM ops.lc_manual_report WHERE packet_id=:packet")
                .param("packet",packet).query(java.time.OffsetDateTime.class).single().toInstant();
        UUID listingTwo=fixture.id("listingTwo");
        String targetText=jdbc.sql("SELECT target_text FROM ops.lc_action WHERE id=:id")
                .param("id",fixture.id("actionTwo")).query(String.class).single();
        var management=postForListing(listingTwo,"/facts/description",Map.of("text",targetText,"languageCode","ru",
                "kizMarkedDeclared",false,"note","Independent exact late-result observation"));
        var display=postForListing(listingTwo,"/facts/display",Map.of("displayState","DISPLAYED",
                "displayedText",targetText,"evidenceReference","evidence://synthetic/late-exact-display"));
        String listingTwoAssociations="/api/v1/console/listing/governance/listings/"+listingTwo+"/late-associations";
        Map<String,Object> lawful=new java.util.LinkedHashMap<>();
        lawful.put("observationId",management.path("observationId").asText());
        lawful.put("actionId",fixture.id("actionTwo"));lawful.put("associationKind","LAWFUL_LATE_REPORT");
        lawful.put("operationTime",operation);lawful.put("reportTime",reportTime);
        lawful.put("forwardDisposition","Retain the exact late report without a second approval");
        var lawfulResponse=mvc.perform(post(listingTwoAssociations).header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(lawful)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("LINKED")).andReturn();
        UUID lawfulId=UUID.fromString(json.readTree(lawfulResponse.getResponse().getContentAsString()).path("id").asText());
        Instant deviationAt=packetIssued.minusSeconds(3600);
        Map<String,Object> deviation=new java.util.LinkedHashMap<>();
        deviation.put("observationId",management.path("observationId").asText());
        deviation.put("associationKind","UNAUTHORISED_DEVIATION");deviation.put("operationTime",deviationAt);
        deviation.put("reportTime",deviationAt.plusSeconds(60));deviation.put("authorityGap","No action covered the event");
        deviation.put("forwardDisposition","Require a distinct prospective approval and exact verification");
        var deviationResponse=mvc.perform(post(listingTwoAssociations).header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(deviation)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN")).andReturn();
        UUID deviationId=UUID.fromString(json.readTree(deviationResponse.getResponse().getContentAsString()).path("id").asText());
        Map<String,Object> invalidForward=new java.util.LinkedHashMap<>(deviation);
        invalidForward.put("operationTime",operation);invalidForward.put("reportTime",reportTime);
        invalidForward.put("authorityGap","An already launched action is not a prospective remedy");
        var invalidForwardResponse=mvc.perform(post(listingTwoAssociations).header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalidForward)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN")).andReturn();
        UUID invalidForwardId=UUID.fromString(json.readTree(invalidForwardResponse.getResponse().getContentAsString())
                .path("id").asText());
        Map<String,Object> exactUnresolved=new java.util.LinkedHashMap<>();
        exactUnresolved.put("observationId",management.path("observationId").asText());
        exactUnresolved.put("actionId",fixture.id("actionTwo"));exactUnresolved.put("associationKind","UNRESOLVED_CHANGE");
        exactUnresolved.put("operationTime",operation);exactUnresolved.put("reportTime",reportTime);
        exactUnresolved.put("forwardDisposition","Require exact independent verification of the proposed action");
        var exactUnresolvedResponse=mvc.perform(post(listingTwoAssociations).header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(exactUnresolved)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("UNDER_VERIFICATION")).andReturn();
        UUID exactUnresolvedId=UUID.fromString(json.readTree(exactUnresolvedResponse.getResponse().getContentAsString())
                .path("id").asText());
        Map<String,Object> unresolved=Map.of("observationId",fixture.id("observationOne"),
                "associationKind","UNRESOLVED_CHANGE","operationTime",deviationAt,"reportTime",deviationAt.plusSeconds(60),
                "forwardDisposition","Keep the unrelated Listing blocked until its own evidence exists");
        var unresolvedResponse=mvc.perform(post("/api/v1/console/listing/governance/listings/"+fixture.id("listing")+"/late-associations")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(unresolved))).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("UNDER_VERIFICATION")).andReturn();
        UUID unresolvedId=UUID.fromString(json.readTree(unresolvedResponse.getResponse().getContentAsString()).path("id").asText());

        String verificationRoute="/api/v1/console/listing/manual/packets/"+packet+"/verify";
        Map<String,Object> verification=new java.util.LinkedHashMap<>(Map.of("basis","INDEPENDENT_HUMAN",
                "managementMatch","UNKNOWN","managementObservationId",management.path("observationId").asText(),
                "displayObservationId",display.path("observationId").asText(),"displayState","DISPLAYED",
                "note","Existing but unqualified verification cannot close retained deviation"));
        mvc.perform(post(verificationRoute).header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(verification)))
                .andExpect(status().isOk());
        UUID unqualified=jdbc.sql("""
                SELECT id FROM ops.lc_manual_verification WHERE packet_id=:packet AND qualification_state='UNQUALIFIED'
                 ORDER BY verified_at DESC LIMIT 1
                """).param("packet",packet).query(UUID.class).single();
        verification.put("managementMatch","MATCHED_TARGET");
        verification.put("note","Independent exact Listing action and observed target verification");
        mvc.perform(post(verificationRoute).header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(verification)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("VERIFIED"));
        UUID qualified=jdbc.sql("""
                SELECT id FROM ops.lc_manual_verification WHERE packet_id=:packet AND qualification_state='QUALIFIED'
                 ORDER BY verified_at DESC LIMIT 1
                """).param("packet",packet).query(UUID.class).single();
        String close="/api/v1/console/listing/governance/late-associations/"+deviationId+"/close";
        mvc.perform(post(close).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("verificationId",UUID.randomUUID()))))
                .andExpect(status().is4xxClientError());
        mvc.perform(post(close).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("verificationId",unqualified))))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/v1/console/listing/governance/late-associations/"+unresolvedId+"/close")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("verificationId",qualified))))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/v1/console/listing/governance/late-associations/"+lawfulId+"/close")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("verificationId",qualified))))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/v1/console/listing/governance/late-associations/"+invalidForwardId+"/close")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("verificationId",qualified))))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/v1/console/listing/governance/late-associations/"+exactUnresolvedId+"/close")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("verificationId",qualified))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.closureVerificationId").value(qualified.toString()));
        mvc.perform(post(close).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("verificationId",qualified))))
                .andDo(result->{ if(result.getResolvedException()!=null) throw result.getResolvedException(); })
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.closureAssessment.qualification").value("QUALIFIED"))
                .andExpect(jsonPath("$.closureAssessment.resolutionActionId").value(fixture.id("actionTwo").toString()))
                .andExpect(jsonPath("$.closureVerificationId").value(qualified.toString()));
        assertThat(jdbc.sql("SELECT state FROM ops.lc_late_association WHERE id=:id").param("id",lawfulId)
                .query(String.class).single()).isEqualTo("LINKED");
        assertThat(jdbc.sql("SELECT state FROM ops.lc_late_association WHERE id=:id").param("id",unresolvedId)
                .query(String.class).single()).isEqualTo("UNDER_VERIFICATION");
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
    void preparationChronologyUsesDatabaseTimeDespiteAnApplicationClockAheadByOneMinute() throws Exception {
        Object actionService=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(applicationContext.getBean("listingActionService"));
        Object evaluation=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(applicationContext.getBean("evaluationService"));
        Object actionClock=org.springframework.test.util.ReflectionTestUtils.getField(actionService,"clock");
        Object evaluationClock=org.springframework.test.util.ReflectionTestUtils.getField(evaluation,"clock");
        try {
            var ahead=java.time.Clock.offset(java.time.Clock.systemUTC(),java.time.Duration.ofMinutes(1));
            org.springframework.test.util.ReflectionTestUtils.setField(actionService,"clock",ahead);
            org.springframework.test.util.ReflectionTestUtils.setField(evaluation,"clock",ahead);
            normalPreparationFreezesTheEvaluationPlanBeforeAnyReviewOrApproval();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(actionService,"clock",actionClock);
            org.springframework.test.util.ReflectionTestUtils.setField(evaluation,"clock",evaluationClock);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"OFFICIAL_PROMOTION_PARTICIPATION,true,NONE",
            "OFFICIAL_PROMOTION_PARTICIPATION,false,STOCK","SELLER_DIRECT_DISCOUNT,true,PROFIT",
            "SELLER_DIRECT_DISCOUNT,false,RETURN","OFFICIAL_PROMOTION_PARTICIPATION,true,DEMAND"})
    void promotionDeclarationIsFrozenBeforeReviewAndBoundToManualEntry(String kind,boolean major,
                                                                        String sourceChange) throws Exception {
        var json=new tools.jackson.databind.ObjectMapper();
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        Instant periodStart=Instant.now().plusSeconds(2L*86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant periodEnd=periodStart.plusSeconds(7L*86400);
        java.math.BigDecimal fixedFee=new java.math.BigDecimal(major?"600.0000":"0.0000");
        var declaration=new com.mimococo.marketops.listingconversion.PromotionTerms(kind,"fixture-promotion-17",
                Map.of("finalPrice","200.0000","currency","RUB","period",periodStart+"/"+periodEnd,
                        "feeSchedule","fixture://commercial-fees","coexistence","fixture://known-existing-offer"),
                major,false," fixture://exact-promotion-source ",
                Map.of("fixedFee",fixedFee.toPlainString(),"exitTerms","fixture://bounded-exit-and-residual"));
        var qualifiedEvidence=seedQualifiedPromotionEvidence(json,declaration,periodStart,periodEnd,fixedFee);
        for(var scope:List.of(ActionScopeCode.LISTING_ACTION_PREPARE,ActionScopeCode.LISTING_ACTION_LAUNCH,
                ActionScopeCode.LISTING_CONVERSION_VIEW,ActionScopeCode.LISTING_PROMOTION_MANAGE,
                ActionScopeCode.LISTING_MANUAL_EXECUTE,ActionScopeCode.LISTING_MANUAL_VERIFY))
            users.grantScope(OPERATOR,userId,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        var authorStoreEvidence=users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.STORE,fixture.id("store"),null);
        var authorProductEvidence=users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.PRODUCT_VARIANT,fixture.id("productVariant"),null);
        mvc.perform(post("/api/v1/console/listing/health/listings/"+fixture.id("listing")+"/facts/promotion")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(PromotionContextFixture.completeSingleActivityRequest(
                        declaration,"NOT_PARTICIPATING",Instant.now(),periodStart.minusSeconds(86400),
                        periodEnd.plusSeconds(86400),Instant.now().plusSeconds(86400),
                        "fixture://complete-pre-action-promotion-context","STOPPED","CLEARED",null,null,Map.of()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.observationId").isNotEmpty());
        mvc.perform(post("/api/v1/console/listing/actions/"+fixture.id("actionOne")+"/cancel")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Replace unused synthetic action with a promotion declaration\"}"))
                .andExpect(status().isOk());
        var candidateResponse=mvc.perform(post("/api/v1/console/listing/actions/candidates")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("listingId",fixture.id("listing"),"candidateKind",kind,
                    "roundKey","promotion-terms-"+kind.toLowerCase(java.util.Locale.ROOT).replace('_','-'),"evidenceReferences",List.of("fixture://commercial-terms"),"expectedEffect",Map.of()))))
                .andExpect(result->assertThat(result.getResponse().getStatus()).withFailMessage("Promotion candidate: %s",result.getResolvedException()).isEqualTo(200)).andReturn();
        UUID candidate=UUID.fromString(json.readTree(candidateResponse.getResponse().getContentAsString()).path("id").asText());
        var simulationRequest=new java.util.LinkedHashMap<String,Object>();
        simulationRequest.put("purpose","PROMOTION");
        simulationRequest.put("listPrice",new java.math.BigDecimal("200"));
        simulationRequest.put("sellerDiscountRate",java.math.BigDecimal.ZERO);
        simulationRequest.put("discountAlreadyInNetRevenue",false);
        simulationRequest.put("unitCost",new java.math.BigDecimal("50"));
        simulationRequest.put("stepFees",List.of(Map.of("priceFloor",0,"feePerUnit",15)));
        simulationRequest.put("feesKnown",true);
        simulationRequest.put("currencyCode","RUB");
        simulationRequest.put("expenses",Map.of(
                "fixedPromotionFee",Map.of("amount",fixedFee,"currencyCode","RUB"),
                "returnLossPerUnit",Map.of("amount",2,"currencyCode","RUB"),
                "advertisingPerUnit",Map.of("amount",2,"currencyCode","RUB"),
                "variableTaxPerUnit",Map.of("amount",1,"currencyCode","RUB")));
        simulationRequest.put("scenarios",List.of(Map.of("code","DOWNSIDE","quantity",14,
                "necessary",true,"conservative",true)));
        simulationRequest.put("referenceProfitLine",new java.math.BigDecimal("100"));
        simulationRequest.put("context",Map.of("periodStart",periodStart,"periodEnd",periodEnd,
                "sourceReferences",Map.of("fixedPromotionFee","fixture://governed-fixed-fee"),
                "commercialDeclaration",declaration,
                "assumptions","Finite accepted downside scenario and exact governed promotion inputs"));
        var simulationResponse=mvc.perform(post("/api/v1/console/listing/actions/candidates/"+candidate+"/simulate")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(simulationRequest)))
                .andExpect(result->assertThat(result.getResponse().getStatus())
                        .withFailMessage("Qualified promotion simulation: %s",result.getResolvedException()).isEqualTo(200))
                .andExpect(jsonPath("$.qualificationState").value("QUALIFIED_CONDITIONAL_ECONOMICS"))
                .andExpect(jsonPath("$.inputSnapshot.qualificationState").value("QUALIFIED_CONDITIONAL_ECONOMICS"))
                .andExpect(jsonPath("$.inputSnapshot.calibrationPackageId")
                        .value(qualifiedEvidence.calibrationPackageId().toString())).andReturn();
        UUID simulationId=UUID.fromString(json.readTree(simulationResponse.getResponse().getContentAsString()).path("id").asText());
        Map<String,Object> terms=Map.of("engagementKind",declaration.engagementKind(),
                "nativePromotionKey",declaration.nativePromotionKey(),"terms",declaration.terms(),
                "priceFreeze",declaration.priceFreeze(),"autoParticipation",declaration.autoParticipation(),
                "termsEvidenceReference",declaration.termsEvidenceReference(),"obligations",declaration.obligations());
        String prepare="/api/v1/console/listing/actions/candidates/"+candidate+"/prepare";
        mvc.perform(post(prepare).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"executionPath\":\"MANUAL\",\"exposureShare\":0.01}"))
                .andExpect(status().isBadRequest());
        for(String flag:List.of("priceFreeze","autoParticipation")) {
            var missingFlag=new java.util.LinkedHashMap<>(terms);missingFlag.remove(flag);
            mvc.perform(post(prepare).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("executionPath","MANUAL","promotionTerms",missingFlag))))
                    .andExpect(status().isBadRequest());
        }
        var response=mvc.perform(post(prepare).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("executionPath","MANUAL","purpose","PROMOTION",
                        "exposureShare",0.01,"promotionTerms",terms,"simulationId",simulationId))))
                .andExpect(result->assertThat(result.getResponse().getStatus()).withFailMessage("Promotion preparation: %s",result.getResolvedException()).isEqualTo(200))
                .andExpect(jsonPath("$.state").value("DRAFT")).andReturn();
        var prepared=json.readTree(response.getResponse().getContentAsString());
        UUID action=UUID.fromString(prepared.path("id").asText());
        UUID recommendation=UUID.fromString(prepared.path("recommendationId").asText());
        String digest=prepared.path("promotionTermsDigest").asText();
        assertThat(digest).matches("[0-9a-f]{64}");
        assertThat(digest).isEqualTo(qualifiedEvidence.promotionTermsDigest());
        users.revokeScope(OPERATOR,authorStoreEvidence.id(),"Withdraw author disclosure after exact preparation",authorStoreEvidence.version());
        users.revokeScope(OPERATOR,authorProductEvidence.id(),"Withdraw author disclosure after exact preparation",authorProductEvidence.version());
        String actionPath="/api/v1/console/listing/actions/"+action;
        mvc.perform(get(actionPath+"/promotion-terms").header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullDisclosure").value(false))
                .andExpect(jsonPath("$.terms").isEmpty()).andExpect(jsonPath("$.digest").value(digest));
        assertThat(jdbc.sql("SELECT proposed_parameters->>'promotionTermsDigest' FROM ops.recommendation WHERE id=:id")
                .param("id",recommendation).query(String.class).single()).isEqualTo(digest);
        String authorToken=bearer();
        UUID authorUserId=userId;
        subject="promotion-independent-reviewer-"+UUID.randomUUID();
        UUID reviewer=users.provision(OPERATOR,fixture.id("organization"),providerId,subject,null,"Promotion reviewer",null).id();
        jdbc.sql("UPDATE iam.user_account SET credentials_valid_from=now()-interval '1 hour' WHERE id=:id").param("id",reviewer).update();
        userId=reviewer;
        users.assignRole(OPERATOR,reviewer,BusinessRoleCode.OWNER,null);
        for(var scope:List.of(ActionScopeCode.LISTING_ACTION_REVIEW,ActionScopeCode.LISTING_ACTION_APPROVE_MATERIAL,
                ActionScopeCode.LISTING_ACTION_APPROVE_ORDINARY,ActionScopeCode.LISTING_CONVERSION_VIEW,ActionScopeCode.LISTING_MANUAL_VERIFY))
            users.grantScope(OPERATOR,reviewer,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        mvc.perform(post(actionPath+"/review").header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"verdict\":\"ATTESTED\",\"reason\":\"Review the exact proposed commercial declaration\"}"))
                .andExpect(status().isForbidden());
        users.grantScope(OPERATOR,reviewer,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.STORE,fixture.id("store"),null);
        mvc.perform(get(actionPath+"/promotion-terms").header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullDisclosure").value(false))
                .andExpect(jsonPath("$.terms").isEmpty());
        var productDisclosure=users.grantScope(OPERATOR,reviewer,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.PRODUCT_VARIANT,fixture.id("productVariant"),null);
        mvc.perform(get(actionPath+"/promotion-terms").header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullDisclosure").value(true))
                .andExpect(jsonPath("$.terms.terms.finalPrice").value("200.0000"));
        mvc.perform(post(actionPath+"/review").header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(meaningReviewRequest(action,major)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.materialityRoute").value(major?"MATERIAL_IMPACT":"ORDINARY_IMPACT"));
        var authority=applicationContext.getBean(
                com.mimococo.marketops.operationsworkflow.ListingActionDecisionAuthority.class);
        var preApproval=authority.recheckedDecisionScope(recommendation).orElseThrow();
        var unresolved=authority.unresolvedReasons(recommendation);
        var expectedProtection=Map.of("supplyVerdict","PASS",
                "financialInputState","CANONICAL_INPUT_AVAILABLE","currentProfitVerdict","PASS",
                "currentReturnVerdict","PASS","unitProfitFloorVerdict","PASS",
                "selectedSimulationInputState","QUALIFIED_CURRENT");
        assertThat("CURRENT".equals(preApproval.materialityRecheck().get("state"))
                && expectedProtection.entrySet().stream().allMatch(entry ->
                    entry.getValue().equals(preApproval.protectionRecheck().get(entry.getKey())))
                && unresolved.isEmpty())
                .withFailMessage("Pre-approval scope is not current: calibration=%s materiality=%s protection=%s unresolved=%s",
                        preApproval.calibrationRecheck(),preApproval.materialityRecheck(),
                        preApproval.protectionRecheck(),unresolved)
                .isTrue();
        long version=jdbc.sql("SELECT version FROM ops.recommendation WHERE id=:id").param("id",recommendation).query(Long.class).single();
        mvc.perform(post("/api/v1/console/workflow/recommendations/"+recommendation+"/approval")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion",version,"reason","Approve exact synthetic promotion declaration"))))
                .andExpect(result->assertThat(result.getResponse().getStatus()).withFailMessage("Promotion approval: %s",result.getResolvedException()).isEqualTo(200));
        if(!sourceChange.equals("NONE")) {
            var changedProtection=changeCurrentPromotionProtection(sourceChange,qualifiedEvidence);
            var rechecked=applicationContext.getBean(
                    com.mimococo.marketops.operationsworkflow.ListingActionDecisionAuthority.class)
                    .recheckedDecisionScope(recommendation).orElseThrow();
            var expectedError=com.mimococo.marketops.shared.ErrorCode.GUARDRAIL_BLOCKED;
            switch(sourceChange) {
                case "STOCK" -> assertThat(rechecked.protectionRecheck()).containsEntry("supplyVerdict","FAIL");
                case "PROFIT" -> assertThat(rechecked.protectionRecheck())
                        .containsEntry("currentProfitVerdict","FAIL").containsEntry("unitProfitFloorVerdict","PASS");
                case "RETURN" -> assertThat(rechecked.protectionRecheck())
                        .containsEntry("currentReturnVerdict","FAIL").containsEntry("currentProfitVerdict","PASS");
                case "DEMAND" -> {
                    assertThat(rechecked.calibrationRecheck()).containsEntry("state","CALIBRATION_DEPENDENCIES_CHANGED")
                            .containsEntry("currentPackageId",changedProtection);
                    expectedError=com.mimococo.marketops.shared.ErrorCode.BINDING_INAPPLICABLE;
                }
                default -> throw new AssertionError("unsupported current-source change "+sourceChange);
            }
            var exactError=expectedError;
            mvc.perform(post(actionPath+"/launch").header(HttpHeaders.AUTHORIZATION,authorToken)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"axes\":{}}"))
                    .andExpect(result->assertThat(result.getResolvedException()).isInstanceOfSatisfying(
                            com.mimococo.marketops.shared.OperationRejectedException.class,
                            failure->assertThat(failure.errorCode()).isEqualTo(exactError)));
            assertThat(jdbc.sql("""
                    SELECT (SELECT count(*) FROM ops.lc_launch WHERE action_id=:id)=0
                      AND (SELECT count(*) FROM ops.lc_description_command WHERE action_id=:id)=0
                      AND (SELECT count(*) FROM ops.lc_exposure_occupation WHERE action_id=:id)=0
                    """).param("id",action).query(Boolean.class).single()).isTrue();
            return;
        }
        mvc.perform(post(actionPath+"/launch").header(HttpHeaders.AUTHORIZATION,authorToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"axes\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.launched").value(true)).andExpect(jsonPath("$.commandId").isEmpty());
        assertThat(jdbc.sql("""
                SELECT count(*)=2 AND bool_and(outcome='PASS'
                  AND detail->>'protectionRecheck.selectedSimulationInputState'='QUALIFIED_CURRENT'
                  AND detail->>'protectionRecheck.currentProfitVerdict'='PASS'
                  AND detail->>'protectionRecheck.currentReturnVerdict'='PASS'
                  AND detail->>'protectionRecheck.unitProfitFloorVerdict'='PASS'
                  AND detail->>'protectionRecheck.supplyVerdict'='PASS')
                FROM ops.guardrail_evaluation WHERE recommendation_id=:id AND purpose IN ('APPROVAL','EXECUTION')
                """).param("id",recommendation).query(Boolean.class).single()).isTrue();
        var references=applicationContext.getBean(
                com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.InternalReferenceRepository.class);
        references.endOpenFinanceInput(fixture.id("organization"),"PROMOTION_FIXED_FEE","PROMOTION",
                fixture.id("store"),periodStart,"Supersede the exact future source",
                kind,declaration.nativePromotionKey());
        references.insertFinanceInput(UUID.randomUUID(),fixture.id("organization"),"PROMOTION_FIXED_FEE","PROMOTION",
                fixture.id("store"),null,"AMOUNT",null,fixedFee.add(java.math.BigDecimal.ONE),"RUB",
                qualifiedEvidence.provenanceId(),periodStart,Instant.now(),kind,declaration.nativePromotionKey(),periodEnd);
        var rechecked=applicationContext.getBean(com.mimococo.marketops.operationsworkflow.ListingActionDecisionAuthority.class)
                .recheckedDecisionScope(recommendation).orElseThrow();
        assertThat(rechecked.protectionRecheck().get("selectedSimulationInputState")).isEqualTo("INVALIDATED");
        assertThat(rechecked.protectionRecheck().get("selectedSimulationInvalidatedInputs")).contains("fixedFeeEvidence");
        assertThatThrownBy(()->fixture.seed.sql("UPDATE ops.lc_action SET promotion_terms=jsonb_set(promotion_terms,'{terms,finalPrice}','\"1\"') WHERE id=:id")
                .param("id",action).update()).satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        var packetResponse=mvc.perform(post("/api/v1/console/listing/manual/actions/"+action+"/packets")
                .header(HttpHeaders.AUTHORIZATION,authorToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("executorUserId",authorUserId))))
                .andExpect(status().isOk()).andReturn();
        UUID packet=UUID.fromString(json.readTree(packetResponse.getResponse().getContentAsString()).path("id").asText());
        assertThat(jdbc.sql("SELECT promotion_terms_digest FROM ops.lc_manual_packet WHERE id=:id")
                .param("id",packet).query(String.class).single()).isEqualTo(digest);
        var altered=new java.util.LinkedHashMap<>(terms);altered.put("terms",Map.of("finalPrice","1","currency","RUB"));
        String entry="/api/v1/console/listing/manual/actions/"+action+"/engagements";
        mvc.perform(post(entry).header(HttpHeaders.AUTHORIZATION,authorToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(altered))).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_promotion_engagement WHERE action_id=:id")
                .param("id",action).query(Integer.class).single()).isZero();
        var entered=mvc.perform(post(entry).header(HttpHeaders.AUTHORIZATION,authorToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(terms))).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullDisclosure").value(false)).andExpect(jsonPath("$.terms").isEmpty())
                .andExpect(jsonPath("$.obligations").isEmpty()).andExpect(jsonPath("$.termsEvidenceReference").isEmpty()).andReturn();
        String engagementId=json.readTree(entered.getResponse().getContentAsString()).path("id").asText();
        String engagementPath="/api/v1/console/listing/manual/engagements/"+engagementId;
        String engagementsPath="/api/v1/console/listing/manual/engagements?listingId="+fixture.id("listing");
        mvc.perform(get(engagementPath).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullDisclosure").value(true))
                .andExpect(jsonPath("$.terms.finalPrice").value("200.0000"))
                .andExpect(jsonPath("$.obligations.fixedFee").value(major?"600.0000":"0.0000"));
        mvc.perform(get(engagementsPath).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].fullDisclosure").value(true))
                .andExpect(jsonPath("$[0].terms.finalPrice").value("200.0000"));
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_description_command WHERE action_id=:id")
                .param("id",action).query(Integer.class).single()).isZero();
        assertThat(loopback.received).isEmpty();
        assertThatThrownBy(()->fixture.seed.sql("UPDATE ops.lc_promotion_engagement SET terms=jsonb_set(terms,'{finalPrice}','\"1\"') WHERE action_id=:id")
                .param("id",action).update()).satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThatThrownBy(()->fixture.seed.sql("UPDATE ops.lc_promotion_engagement SET obligations=jsonb_set(obligations,'{fixedFee}','\"1\"') WHERE action_id=:id")
                .param("id",action).update()).satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        Instant operation=Instant.now();
        String packetPath="/api/v1/console/listing/manual/packets/"+packet;
        mvc.perform(post(packetPath+"/report").header(HttpHeaders.AUTHORIZATION,authorToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("operationTime",operation.toString(),"reportState","APPLIED",
                    "note","Synthetic executor report, not independent proof")))).andExpect(status().isOk());
        String factPath="/api/v1/console/listing/health/listings/"+fixture.id("listing")+"/facts/promotion";
        Map<String,Object> observed=Map.of("declaration",terms,"engagementKind",kind,"nativePromotionKey","fixture-promotion-17","participationState","PARTICIPATING",
                "observedAt",operation.plusNanos(1).toString(),"evidenceReference","fixture://independent-native-participation");
        var selfObserved=mvc.perform(post(factPath).header(HttpHeaders.AUTHORIZATION,authorToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(observed))).andExpect(status().isOk()).andReturn();
        String selfObservation=json.readTree(selfObserved.getResponse().getContentAsString()).path("observationId").asText();
        var verification=new java.util.LinkedHashMap<String,Object>();
        verification.put("basis","INDEPENDENT_HUMAN");verification.put("managementMatch","MATCHED_TARGET");
        verification.put("displayState","UNKNOWN");verification.put("note","Independently verify exact participation only");
        verification.put("promotionObservationId",selfObservation);
        mvc.perform(post(packetPath+"/verify").header(HttpHeaders.AUTHORIZATION,authorToken).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(verification))).andExpect(status().isForbidden());
        mvc.perform(post(packetPath+"/verify").header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(verification))).andExpect(status().isForbidden());
        for(String state:List.of("UNKNOWN","NOT_PARTICIPATING")) {
            var different=new java.util.LinkedHashMap<>(observed);different.put("participationState",state);
            var observation=mvc.perform(post(factPath).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(different))).andExpect(status().isOk()).andReturn();
            verification.put("promotionObservationId",json.readTree(observation.getResponse().getContentAsString()).path("observationId").asText());
            mvc.perform(post(packetPath+"/verify").header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(verification))).andExpect(status().isBadRequest());
        }
        var incomplete=new java.util.LinkedHashMap<>(observed);incomplete.remove("declaration");
        var unknownTerms=mvc.perform(post(factPath).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(incomplete))).andExpect(status().isOk()).andReturn();
        verification.put("promotionObservationId",json.readTree(unknownTerms.getResponse().getContentAsString()).path("observationId").asText());
        mvc.perform(post(packetPath+"/verify").header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(verification))).andExpect(status().isBadRequest());
        var changed=new java.util.LinkedHashMap<>(observed);changed.put("declaration",altered);
        var differentTerms=mvc.perform(post(factPath).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(changed))).andExpect(status().isOk()).andReturn();
        verification.put("promotionObservationId",json.readTree(differentTerms.getResponse().getContentAsString()).path("observationId").asText());
        mvc.perform(post(packetPath+"/verify").header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(verification))).andExpect(status().isBadRequest());
        var stale=new java.util.LinkedHashMap<>(observed);stale.put("observedAt",operation.minusSeconds(1).toString());
        var staleFact=mvc.perform(post(factPath).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(stale))).andExpect(status().isOk()).andReturn();
        verification.put("promotionObservationId",json.readTree(staleFact.getResponse().getContentAsString()).path("observationId").asText());
        mvc.perform(post(packetPath+"/verify").header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(verification))).andExpect(status().isForbidden());
        var foreignDeclaration=new java.util.LinkedHashMap<>(terms);foreignDeclaration.put("nativePromotionKey","different-native-promotion");
        var foreignObserved=new java.util.LinkedHashMap<>(observed);foreignObserved.put("declaration",foreignDeclaration);
        foreignObserved.put("nativePromotionKey","different-native-promotion");
        var foreignFact=mvc.perform(post(factPath).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(foreignObserved))).andExpect(status().isOk()).andReturn();
        verification.put("promotionObservationId",json.readTree(foreignFact.getResponse().getContentAsString()).path("observationId").asText());
        mvc.perform(post(packetPath+"/verify").header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(verification))).andExpect(status().isForbidden());
        var qualified=mvc.perform(post(factPath).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(observed))).andExpect(status().isOk()).andReturn();
        String observationId=json.readTree(qualified.getResponse().getContentAsString()).path("observationId").asText();
        verification.put("promotionObservationId",observationId);
        verification.put("basis","OFFICIAL_EVIDENCE");
        mvc.perform(post(packetPath+"/verify").header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(verification))).andExpect(status().isForbidden());
        verification.put("basis","INDEPENDENT_HUMAN");
        mvc.perform(post(packetPath+"/verify").header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(verification)))
                .andExpect(result->assertThat(result.getResponse().getStatus()).withFailMessage("Promotion verification: %s",result.getResolvedException()).isEqualTo(200))
                .andExpect(jsonPath("$.state").value("VERIFIED"))
                .andExpect(jsonPath("$.verifications[0].observationBinding.purpose").value("PARTICIPATION"))
                .andExpect(jsonPath("$.verifications[0].observationBinding.claimExtent").value("OBSERVED_INSTANT_ONLY"));
        assertThat(jdbc.sql("SELECT state FROM ops.lc_action WHERE id=:id").param("id",action).query(String.class).single()).isEqualTo("VERIFIED");
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_exposure_occupation WHERE action_id=:id AND state<>'RELEASED'")
                .param("id",action).query(Integer.class).single()).isPositive();
        assertThatThrownBy(()->jdbc.sql("UPDATE core.lc_promotion_observation SET participation_state='UNKNOWN' WHERE id=:id")
                .param("id",UUID.fromString(observationId)).update())
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("42501"));
        users.revokeScope(OPERATOR,productDisclosure.id(),"Withdraw current financial disclosure",productDisclosure.version());
        mvc.perform(post(packetPath+"/verify").header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(verification))).andExpect(status().isForbidden());
        mvc.perform(get(actionPath+"/promotion-terms").header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullDisclosure").value(false))
                .andExpect(jsonPath("$.terms").isEmpty());
        mvc.perform(get(engagementPath).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullDisclosure").value(false))
                .andExpect(jsonPath("$.terms").isEmpty()).andExpect(jsonPath("$.obligations").isEmpty());
        mvc.perform(get(engagementsPath).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].fullDisclosure").value(false))
                .andExpect(jsonPath("$[0].terms").isEmpty()).andExpect(jsonPath("$[0].obligations").isEmpty());
        // Exact independent participation is an instant claim, not customer display, financial
        // qualification, exit authorization, staged release or a business Outcome.
    }

    private record QualifiedPromotionEvidence(UUID calibrationPackageId,String promotionTermsDigest,
                                              UUID provenanceId,CurrentBusinessProtectionBasis protectionBasis) { }
    private record CurrentBusinessProtectionBasis(Instant asOf,Instant currentStart,Instant currentEnd,
                                                   Instant referenceStart) { }

    private CurrentBusinessProtectionBasis currentBusinessProtectionBasis() {
        Instant now=jdbc.sql("SELECT clock_timestamp()")
                .query(java.time.OffsetDateTime.class).single().toInstant();
        Instant currentEnd=now.minusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant currentStart=currentEnd.minusSeconds(7L*86400);
        return new CurrentBusinessProtectionBasis(now,currentStart,currentEnd,currentStart.minusSeconds(7L*86400));
    }

    private void configureCurrentBusinessProtectionRules(tools.jackson.databind.ObjectMapper json,
            tools.jackson.databind.node.ObjectNode draft,CurrentBusinessProtectionBasis basis,
            Map<String,Object> additionalDemandRules) {
        var demandRules=new java.util.LinkedHashMap<String,Object>();
        demandRules.put("scenarios",List.of(Map.of("code","BASE","quantity",10,"necessary",true,"conservative",false)));
        demandRules.put("supplyScenarios",List.of(Map.of("code","FINITE_CURRENT_SUPPLY",
                "productVariantId",fixture.id("productVariant").toString(),"companyDailyFulfillmentUnits",100,
                "coverageDays",7,"evidenceReference","fixture://accepted-current-supply")));
        demandRules.putAll(additionalDemandRules);
        for(var node:draft.path("values")) {
            var value=(tools.jackson.databind.node.ObjectNode)node;
            switch(value.path("categoryCode").asText()) {
                case "NON_WORSENING_PROFIT_BOUND" -> {
                    value.put("windowDays",7);
                    value.set("json",json.valueToTree(Map.of("currentAccountingComparisons",Map.of(
                            fixture.id("listing").toString(),Map.of("periodStart",basis.referenceStart().toString(),
                                    "periodEnd",basis.currentStart().toString(),
                                    "evidenceReference","fixture://accepted-current-accounting-reference")))));
                }
                case "NON_WORSENING_RETURN_BOUND" -> value.put("windowDays",7);
                case "CRITICAL_GROUP_RULE" -> value.set("json",json.valueToTree(Map.of(
                        "groups",List.of(),"protectionScopeBases",Map.of(fixture.id("listing").toString(),Map.of(
                                "evidenceReference","fixture://accepted-protection-scope","linkedProfitScopes",List.of(),
                                "criticalReturnVariantIds",List.of(fixture.id("listingVariant").toString()))))));
                case "DEMAND_SCENARIO_SET" -> value.set("json",json.valueToTree(demandRules));
                case "FRESHNESS_RULE" -> value.set("json",json.valueToTree(Map.of(
                        "nativeScope",Map.of("MANUAL_ENTRY",Map.of("maximumAgeSeconds",3600),
                                "MARKETPLACE_RAW",Map.of("maximumAgeSeconds",3600)),
                        "materialityExposure",Map.of("maximumVerificationAgeSeconds",3600,
                                "maximumPeriodEndAgeSeconds",86400),
                        "businessProtection",Map.of("maximumVerificationAgeSeconds",3600,
                                "maximumPeriodEndAgeSeconds",86400))));
                default -> { }
            }
        }
    }

    private QualifiedPromotionEvidence seedQualifiedPromotionEvidence(tools.jackson.databind.ObjectMapper json,
            com.mimococo.marketops.listingconversion.PromotionTerms declaration,
            Instant periodStart,Instant periodEnd,java.math.BigDecimal fixedFee) throws Exception {
        var protection=currentBusinessProtectionBasis();
        Instant now=protection.asOf();
        var draft=calibrationDraft("promotion-cal-"+UUID.randomUUID().toString().substring(0,8));
        draft.put("purposeCode","PROMOTION");
        configureCurrentBusinessProtectionRules(json,draft,protection,Map.of("economicScenarioBases",Map.of(
                fixture.id("listing").toString(),Map.of("periodStart",periodStart.toString(),
                        "periodEnd",periodEnd.toString(),"evidenceReference","fixture://accepted-promotion-demand",
                        "minimumContributionProfit",100,"currencyCode","RUB",
                        "profitEvidenceReference","fixture://accepted-promotion-profit-line",
                        "necessaryScenarios",List.of(Map.of("code","DOWNSIDE","quantity",14,
                                "conservative",true,"evidenceReference","fixture://accepted-downside"))))));
        UUID packageId=UUID.fromString(activateSyntheticCalibrationWithIndependentOwner(draft));
        seedPromotionEconomicsProfile(periodEnd);

        UUID provenance=seedCurrentBusinessProtectionSources(protection,"promotion");
        var references=applicationContext.getBean(
                com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.InternalReferenceRepository.class);
        references.insertCostVersion(UUID.randomUUID(),fixture.id("organization"),fixture.id("productVariant"),
                "PURCHASE","RUB",new java.math.BigDecimal("50"),provenance,periodStart,now);
        var actionRepository=applicationContext.getBean(
                com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository.class);
        String termsDigest=actionRepository.promotionTermsDigest(declaration);
        references.insertFinanceInput(UUID.randomUUID(),fixture.id("organization"),"PROMOTION_FIXED_FEE","PROMOTION",
                fixture.id("store"),null,"AMOUNT",null,fixedFee,"RUB",provenance,periodStart,now,
                declaration.engagementKind(),declaration.nativePromotionKey(),periodEnd,null,null);
        for(var input:Map.of("PROMOTION_BUYER_PAYMENT_PER_UNIT",new java.math.BigDecimal("180"),
                "PROMOTION_SELLER_REVENUE_PER_UNIT",new java.math.BigDecimal("200"),
                "PROMOTION_PLATFORM_COMPENSATION_PER_UNIT",new java.math.BigDecimal("20")).entrySet())
            references.insertFinanceInput(UUID.randomUUID(),fixture.id("organization"),input.getKey(),"PROMOTION",
                    fixture.id("store"),null,"AMOUNT",null,input.getValue(),"RUB",provenance,periodStart,now,
                    declaration.engagementKind(),declaration.nativePromotionKey(),periodEnd,fixture.id("listing"),termsDigest);
        return new QualifiedPromotionEvidence(packageId,termsDigest,provenance,protection);
    }

    private void seedPromotionEconomicsProfile(Instant periodEnd) {
        UUID profile=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO core.store_fulfillment_declaration(id,organization_id,store_id,
                    fulfillment_mode_code,effective_from,effective_to,status,created_at,updated_at)
                VALUES(:id,:org,:store,'MARKETPLACE_FULFILLED',clock_timestamp()-interval '1 day',
                    :until,'ACTIVE',clock_timestamp(),clock_timestamp())
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization"))
                .param("store",fixture.id("store"))
                .param("until",java.sql.Timestamp.from(periodEnd.plusSeconds(86400))).update();
        fixture.seed.sql("""
                INSERT INTO core.economics_projection_profile(id,profile_version,organization_id,platform_code,
                    marketplace_account_id,store_id,fulfillment_mode_code,currency_code,effective_from,effective_to,
                    verification_state,verified_at,verification_expires_at,evidence_reference,
                    minimum_supported_price,maximum_supported_price,status,created_at)
                VALUES(:id,1,:org,:platform,:account,:store,'MARKETPLACE_FULFILLED','RUB',
                    clock_timestamp()-interval '1 day',:until,'ENGINEERING_VERIFIED',
                    clock_timestamp()-interval '1 minute',:until,'fixture://promotion/economics',1,1000,
                    'ACTIVE',clock_timestamp())
                """).param("id",profile).param("org",fixture.id("organization"))
                .param("platform",fixture.graph.platform()).param("account",fixture.id("account"))
                .param("store",fixture.id("store"))
                .param("until",java.sql.Timestamp.from(periodEnd.plusSeconds(86400))).update();
        fixture.seed.sql("""
                INSERT INTO core.economics_projection_family(profile_id,family_code,applicability_state,
                    evidence_reference)
                SELECT :profile,family,'REQUIRED','fixture://promotion/economics/'||family
                  FROM unnest(ARRAY['COMMISSION','FULFILLMENT_DELIVERY','STORAGE','PROMOTION',
                    'OTHER_VARIABLE','RETURN_LOSS','ADVERTISING','VARIABLE_TAX']) family
                """).param("profile",profile).update();
        fixture.seed.sql("""
                INSERT INTO core.economics_projection_component(id,profile_id,component_code,family_code,
                    component_kind,fixed_amount,evidence_reference)
                SELECT gen_random_uuid(),:profile,component_code,family_code,'FIXED',amount,
                    'fixture://promotion/economics/'||component_code
                  FROM (VALUES
                    ('COMMISSION','COMMISSION',10.0000::numeric),
                    ('FULFILLMENT','FULFILLMENT_DELIVERY',5.0000::numeric),
                    ('STORAGE','STORAGE',0.0000::numeric),
                    ('PROMOTION','PROMOTION',0.0000::numeric),
                    ('OTHER_VARIABLE','OTHER_VARIABLE',0.0000::numeric),
                    ('RETURN_LOSS','RETURN_LOSS',2.0000::numeric),
                    ('ADVERTISING','ADVERTISING',2.0000::numeric),
                    ('VARIABLE_TAX','VARIABLE_TAX',1.0000::numeric))
                    component(component_code,family_code,amount)
                """).param("profile",profile).update();
    }

    private String changeCurrentPromotionProtection(String sourceChange,QualifiedPromotionEvidence evidence) throws Exception {
        Instant now=jdbc.sql("SELECT clock_timestamp()").query(java.time.OffsetDateTime.class).single().toInstant();
        switch(sourceChange) {
            case "STOCK" -> fixture.seed.sql("""
                    INSERT INTO core.listing_stock_observation(id,organization_id,provenance_id,
                        platform_listing_variant_id,fulfillment_mode_code,source_fact_key,observed_at,
                        available_quantity,reserved_quantity)
                    VALUES(:id,:org,:source,:variant,'MARKETPLACE_FULFILLED',:key,:at,0,0)
                    """).param("id",UUID.randomUUID()).param("org",fixture.id("organization"))
                    .param("source",evidence.provenanceId()).param("variant",fixture.id("listingVariant"))
                    .param("key","promotion-stock-loss-"+UUID.randomUUID())
                    .param("at",java.sql.Timestamp.from(now.minusNanos(1))).update();
            case "PROFIT","RETURN" -> {
                UUID provenance=seedFormalProvenance("promotion-current-"+sourceChange.toLowerCase(java.util.Locale.ROOT),
                        now.minusSeconds(2));
                seedProtectionPeriod(provenance,"promotion-current-"+sourceChange.toLowerCase(java.util.Locale.ROOT),"D7",
                        evidence.protectionBasis().currentStart(),evidence.protectionBasis().currentEnd(),
                        now.minusNanos(1),sourceChange.equals("PROFIT")?new BigDecimal("900"):new BigDecimal("1100"),
                        sourceChange.equals("RETURN")?new BigDecimal("10"):BigDecimal.ONE);
            }
            case "DEMAND" -> {
                var json=new tools.jackson.databind.ObjectMapper();
                var draft=calibrationDraft("promotion-current-demand-"+UUID.randomUUID().toString().substring(0,8),
                        evidence.calibrationPackageId());
                draft.put("purposeCode","PROMOTION");draft.put("version",2);
                draft.put("replacesPackageId",evidence.calibrationPackageId().toString());
                for(var value:draft.path("values")) if(value.path("categoryCode").asText().equals("DEMAND_SCENARIO_SET")) {
                    var demand=(tools.jackson.databind.node.ObjectNode)value.path("json").deepCopy();
                    ((tools.jackson.databind.node.ObjectNode)demand.path("scenarios").get(0)).put("quantity",11);
                    ((tools.jackson.databind.node.ObjectNode)value).set("json",demand);
                }
                return activateSyntheticCalibrationWithIndependentOwner(draft);
            }
            default -> throw new AssertionError("unsupported current-source change "+sourceChange);
        }
        return sourceChange;
    }

    private UUID seedCurrentBusinessProtectionSources(CurrentBusinessProtectionBasis basis,String evidenceNamespace) {
        Instant now=basis.asOf(),currentStart=basis.currentStart(),currentEnd=basis.currentEnd();
        Instant referenceStart=basis.referenceStart();
        UUID provenance=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,
                    recorded_by_user_id,evidence_note)
                VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:actor,:note)
                """).param("id",provenance).param("org",fixture.id("organization"))
                .param("at",java.sql.Timestamp.from(now.minusSeconds(30))).param("actor",userId)
                .param("note","Qualified current business protection fixture: "+evidenceNamespace).update();
        for(int index=0;index<2;index++) {
            Instant from=index==0?referenceStart:currentStart;
            Instant until=index==0?currentStart:currentEnd;
            Instant computed=now.minusSeconds(3-index);
            UUID run=UUID.randomUUID();
            fixture.seed.sql("""
                    INSERT INTO mart.calculation_run(id,organization_id,trigger_kind,scope_kind,store_ref_id,
                        window_code,period_start,period_end,definition_set_digest,state,subject_count,value_count,
                        correlation_id,started_at,completed_at,requested_by_user_id)
                    VALUES(:id,:org,'MANUAL','STORE',:store,'D7',:from,:until,:digest,'SUCCEEDED',1,4,
                        :correlation,:started,:completed,:actor)
                    """).param("id",run).param("org",fixture.id("organization")).param("store",fixture.id("store"))
                    .param("from",java.sql.Timestamp.from(from)).param("until",java.sql.Timestamp.from(until))
                    .param("digest",com.mimococo.marketops.shared.Digest.ofText(evidenceNamespace+"-protection-definitions"))
                    .param("correlation","qualified-"+evidenceNamespace+"-protection-"+index)
                    .param("started",java.sql.Timestamp.from(computed.minusSeconds(1)))
                    .param("completed",java.sql.Timestamp.from(computed.plusSeconds(1))).param("actor",userId).update();
            var values=new java.util.LinkedHashMap<String,java.math.BigDecimal>();
            values.put("OPERATIONAL_CONTRIBUTION_PROFIT",new java.math.BigDecimal(index==0?"1000":"1100"));
            values.put("RETURN_UNITS",new java.math.BigDecimal(index==0?"2":"1"));
            values.put("COMPLETED_UNITS",new java.math.BigDecimal("100"));
            values.put("REQUIRED_PROFIT_PER_UNIT",new java.math.BigDecimal("5"));
            for(var metric:values.entrySet()) {
                UUID valueId=UUID.randomUUID();
                boolean money=metric.getKey().contains("PROFIT");
                fixture.seed.sql("""
                        INSERT INTO mart.metric_value(id,organization_id,calculation_run_id,metric_code,
                            definition_version,subject_kind,subject_id,window_code,period_start,period_end,
                            value_state,numeric_value,currency_code,confidence_state,estimated,oldest_source_time,
                            freshness_seconds,input_digest,computed_at)
                        SELECT :id,:org,:run,:code,max(definition_version),'PLATFORM_LISTING_VARIANT',:subject,
                            'D7',:from,:until,'AVAILABLE',:amount,:currency,'CANONICAL_CONFIRMED',false,
                            :from,0,:digest,:computed FROM mart.metric_definition WHERE metric_code=:code
                        """).param("id",valueId).param("org",fixture.id("organization")).param("run",run)
                        .param("code",metric.getKey()).param("subject",fixture.id("listingVariant"))
                        .param("from",java.sql.Timestamp.from(from)).param("until",java.sql.Timestamp.from(until))
                        .param("amount",metric.getValue()).param("currency",money?"RUB":null)
                        .param("digest",com.mimococo.marketops.shared.Digest.ofText(metric.getKey()+":"+from))
                        .param("computed",java.sql.Timestamp.from(computed)).update();
                fixture.seed.sql("""
                        INSERT INTO mart.metric_input_reference(id,metric_value_id,reference_kind,reference_id)
                        VALUES(:id,:value,'FACT_PROVENANCE',:source)
                        """).param("id",UUID.randomUUID()).param("value",valueId).param("source",provenance).update();
            }
        }
        fixture.seed.sql("""
                INSERT INTO core.lead_time_safety_policy(id,organization_id,scope_kind,scope_precedence,
                    lead_time_days_min,lead_time_days_max,safety_days,owner_user_id,reason,evidence_reference,
                    last_reviewed_at,effective_from,status,policy_version,created_at)
                VALUES(:id,:org,'ORGANIZATION',3,1,2,1,:actor,'Finite current supply horizon',
                    'fixture://current-protection/lead-time',:at,:effective,'ACTIVE',1,:at)
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization")).param("actor",userId)
                .param("at",java.sql.Timestamp.from(now)).param("effective",java.sql.Timestamp.from(now.minusSeconds(86400))).update();
        fixture.seed.sql("""
                INSERT INTO core.demand_observation_policy(id,organization_id,minimum_sample_units,
                    acceleration_ratio,deceleration_ratio,outlier_share_ratio,minimum_coverage_ratio,
                    carry_forward_max_days,stock_freshness_max_minutes,owner_user_id,reason,evidence_reference,
                    effective_from,status,policy_version,created_at)
                VALUES(:id,:org,5,1.50,0.60,0.70,0.60,14,1440,:actor,'Finite current demand observation',
                    'fixture://current-protection/demand-policy',:effective,'ACTIVE',1,:at)
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization")).param("actor",userId)
                .param("effective",java.sql.Timestamp.from(now.minusSeconds(40L*86400)))
                .param("at",java.sql.Timestamp.from(now)).update();
        fixture.seed.sql("""
                INSERT INTO core.supply_ownership_declaration(id,organization_id,store_id,fulfillment_mode_code,
                    distinctness,evidence_reference,declared_by_user_id,reason,effective_from,status,policy_version,created_at)
                VALUES(:id,:org,:store,'MARKETPLACE_FULFILLED','PHYSICALLY_DISTINCT',
                    'fixture://current-protection/supply-ownership',:actor,'Exact platform holding is distinct',
                    :effective,'ACTIVE',1,:at)
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization"))
                .param("store",fixture.id("store")).param("actor",userId)
                .param("effective",java.sql.Timestamp.from(now.minusSeconds(86400)))
                .param("at",java.sql.Timestamp.from(now)).update();
        fixture.seed.sql("""
                INSERT INTO core.listing_stock_observation(id,organization_id,provenance_id,
                    platform_listing_variant_id,fulfillment_mode_code,source_fact_key,observed_at,
                    available_quantity,reserved_quantity)
                VALUES(:id,:org,:source,:variant,'MARKETPLACE_FULFILLED',:key,:at,10000,0)
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization")).param("source",provenance)
                .param("variant",fixture.id("listingVariant")).param("key",evidenceNamespace+"-stock-"+UUID.randomUUID())
                .param("at",java.sql.Timestamp.from(now.minusSeconds(10))).update();
        fixture.seed.sql("""
                INSERT INTO core.listing_stock_observation(id,organization_id,provenance_id,
                    platform_listing_variant_id,fulfillment_mode_code,source_fact_key,observed_at,
                    available_quantity,reserved_quantity)
                VALUES(:id,:org,:source,:variant,'MARKETPLACE_FULFILLED',:key,:at,10000,0)
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization")).param("source",provenance)
                .param("variant",fixture.id("listingVariant")).param("key",evidenceNamespace+"-stock-history-"+UUID.randomUUID())
                .param("at",java.sql.Timestamp.from(referenceStart.minusSeconds(1))).update();
        fixture.seed.sql("""
                INSERT INTO core.listing_health_observation(id,organization_id,provenance_id,
                    platform_listing_variant_id,source_fact_key,observed_at,sellable)
                VALUES(:id,:org,:source,:variant,:key,:at,'YES')
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization")).param("source",provenance)
                .param("variant",fixture.id("listingVariant")).param("key",evidenceNamespace+"-health-"+UUID.randomUUID())
                .param("at",java.sql.Timestamp.from(now.minusSeconds(10))).update();
        fixture.seed.sql("""
                INSERT INTO core.listing_health_observation(id,organization_id,provenance_id,
                    platform_listing_variant_id,source_fact_key,observed_at,sellable)
                VALUES(:id,:org,:source,:variant,:key,:at,'YES')
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization")).param("source",provenance)
                .param("variant",fixture.id("listingVariant")).param("key",evidenceNamespace+"-health-history-"+UUID.randomUUID())
                .param("at",java.sql.Timestamp.from(referenceStart.minusSeconds(1))).update();
        String salePrefix=evidenceNamespace+"-demand-"+UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,store_id,
                    platform_listing_variant_id,source_fact_key,native_order_key,occurred_at,sale_stage,
                    quantity,currency_code,gross_amount,discount_amount,net_amount)
                SELECT gen_random_uuid(),:org,:source,:store,:variant,:prefix||'-'||day,:prefix||'-order-'||day,
                    CAST(:at AS timestamptz)-(day||' days')::interval,'COMPLETED',1,'RUB',200,0,200
                FROM generate_series(1,30) day
                """).param("org",fixture.id("organization")).param("source",provenance)
                .param("store",fixture.id("store")).param("variant",fixture.id("listingVariant"))
                .param("prefix",salePrefix).param("at",java.sql.Timestamp.from(now)).update();
        return provenance;
    }

    @Test
    void adoptedPromotionWithoutHistoricalProductScopeRequiresOrganizationFinancialDisclosure() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_PROMOTION_MANAGE,
                ResourceScopeType.STORE,fixture.id("store"),null);
        var viewGrant=users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_CONVERSION_VIEW,
                ResourceScopeType.STORE,fixture.id("store"),null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.STORE,fixture.id("store"),null);
        var json=new tools.jackson.databind.ObjectMapper();
        var response=mvc.perform(post("/api/v1/console/listing/manual/listings/"+fixture.id("listing")+"/engagements")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("engagementKind","SELLER_DIRECT_DISCOUNT",
                    "nativePromotionKey","observed-existing-promotion","terms",Map.of("price","200.0000"),
                    "priceFreeze",false,"autoParticipation",false,"termsEvidenceReference","fixture://observed-existing",
                    "obligations",Map.of("knownCommitment","600.0000")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.adopted").value(true))
                .andExpect(jsonPath("$.fullDisclosure").value(false)).andExpect(jsonPath("$.terms").isEmpty())
                .andExpect(jsonPath("$.obligations").isEmpty()).andReturn();
        String id=json.readTree(response.getResponse().getContentAsString()).path("id").asText();
        String path="/api/v1/console/listing/manual/engagements/"+id;
        var financial=users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullDisclosure").value(true)).andExpect(jsonPath("$.terms.price").value("200.0000"))
                .andExpect(jsonPath("$.obligations.knownCommitment").value("600.0000"));
        users.revokeScope(OPERATOR,financial.id(),"Withdraw organization financial scope",financial.version());
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullDisclosure").value(false)).andExpect(jsonPath("$.terms").isEmpty());
        users.revokeScope(OPERATOR,viewGrant.id(),"Withdraw current view scope",viewGrant.version());
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isForbidden());
        // Recorded observed terms do not establish historical authorization or qualified adoption.
    }

    @Test
    void signedAllowancePreviewUsesCanonicalDemandAndShowsMissingRequiredAxes() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        String endpoint="/api/v1/console/listing/actions/"+fixture.id("actionOne")+"/allowance-preview";
        mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"axes\":{\"CONCURRENT_LISTINGS\":0,\"AFFECTED_VARIANTS\":0}}"))
                .andExpect(status().isForbidden());
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_CONVERSION_VIEW,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"axes\":{\"CONCURRENT_LISTINGS\":0,\"AFFECTED_VARIANTS\":0}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolved").value(true))
                .andExpect(jsonPath("$.gaps").isEmpty()).andExpect(jsonPath("$.axes[0].requestedValue").value(1))
                .andExpect(jsonPath("$.axes[1].requestedValue").value(1));
        fixture.seed.sql("UPDATE ops.lc_exposure_allowance SET status='RETIRED' WHERE id=:id")
                .param("id",fixture.id("allowanceVariants")).update();
        mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"axes\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolved").value(false))
                .andExpect(jsonPath("$.gaps[0]").value("AFFECTED_VARIANTS:ALLOWANCE_MISSING"));
    }

    @Test
    void normalPreparationFreezesTheEvaluationPlanBeforeAnyReviewOrApproval() throws Exception {
        prepareDescriptionForExposure(new java.math.BigDecimal("0.01"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={-120,120})
    void normalPreparationBindsOriginalTaskClocksAndAcknowledgementIsNotAnAction(int clockOffsetSeconds) throws Exception {
        UUID action=prepareDescriptionForExposure(java.math.BigDecimal.ZERO);
        String endpoint="/api/v1/console/listing/actions/"+action+"/responsibility";
        var json=new tools.jackson.databind.ObjectMapper();
        var response=mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bound").value(true))
                .andReturn().getResponse().getContentAsString();
        var original=json.readTree(response).path("status");
        UUID task=UUID.fromString(original.path("taskId").asText());
        UUID recommendation=jdbc.sql("SELECT recommendation_id FROM ops.lc_action WHERE id=:id")
                .param("id",action).query(UUID.class).single();
        Instant raised=Instant.parse(original.path("firstRaisedAt").asText());
        var clocks=applicationContext.getBean(com.mimococo.marketops.operationsworkflow.ListingTaskSloQuery.class);
        var after=clocks.statusForRecommendation(recommendation,
                Instant.parse(original.path("actionDueAt").asText()).plusSeconds(1)).orElseThrow();
        assertThat(after.acknowledgementBreached()).isTrue();
        assertThat(after.actionBreached()).isTrue();
        assertThat(after.outcomeMaturityDueAt()).isEqualTo(raised.plus(java.time.Duration.ofDays(30)));
        assertThat(jdbc.sql("SELECT due_at FROM ops.work_task WHERE id=:id").param("id",task)
                .query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(after.actionDueAt());
        assertThat(original.path("acknowledgedAt").isNull()).isTrue();
        assertThat(original.path("firstAttributableActionAt").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM ops.work_task_event WHERE task_id=:id AND event_kind='ACKNOWLEDGED'")
                .param("id",task).query(Long.class).single()).isZero();

        // Listing view alone cannot grant the existing shared Task action scope.
        mvc.perform(post(endpoint+"/acknowledgement").header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isForbidden());
        users.grantScope(OPERATOR,userId,ActionScopeCode.TASK_ASSIGN,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        Object taskService=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(applicationContext.getBean("workTaskService"));
        var originalClock=(java.time.Clock)org.springframework.test.util.ReflectionTestUtils.getField(taskService,"clock");
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(taskService,"clock",
                    java.time.Clock.offset(originalClock,java.time.Duration.ofSeconds(clockOffsetSeconds)));
            mvc.perform(post(endpoint+"/acknowledgement").header(HttpHeaders.AUTHORIZATION,bearer()))
                    .andExpect(status().isNoContent());
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(taskService,"clock",originalClock);
        }
        var acknowledged=clocks.statusForRecommendation(recommendation).orElseThrow();
        assertThat(acknowledged.acknowledgedAt()).withFailMessage("Task acknowledgement chronology: %s",
                jdbc.sql("""
                        SELECT jsonb_build_object('origin',r.first_raised_at,'recorded',r.recorded_at,
                          'databaseNow',clock_timestamp(),'acknowledgements',
                          (SELECT jsonb_agg(e.occurred_at) FROM ops.work_task_event e
                             WHERE e.task_id=r.task_id AND e.event_kind='ACKNOWLEDGED'))::text
                        FROM ops.lc_task_responsibility r WHERE r.task_id=:id
                        """).param("id",task).query(String.class).single()).isNotNull();
        assertThat(acknowledged.firstAttributableActionAt()).isNull();
        String closureEndpoint="/api/v1/console/workflow/tasks/"+task+"/closure";
        mvc.perform(post(closureEndpoint).header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "done",true,"closureReason","Acknowledgement is not disposition","expectedVersion",0))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/console/advertising/tasks/"+task+"/action")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("actionKind","DECISION_ENDORSED",
                        "evidenceReference","evidence://synthetic/caller-labelled",
                        "reason","Caller text cannot substitute for qualified Listing review"))))
                .andExpect(status().isForbidden());
        assertThat(clocks.statusForRecommendation(recommendation).orElseThrow().firstAttributableActionAt()).isNull();
        for (int version=0;version<2;version++) {
            UUID assignee=version==0?fixture.id("ownerUser"):fixture.id("executorUser");
            mvc.perform(post("/api/v1/console/workflow/tasks/"+task+"/assignment")
                    .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("assigneeUserId",assignee,"expectedVersion",version))))
                    .andExpect(status().isNoContent());
        }
        assertThat(clocks.statusForRecommendation(recommendation).orElseThrow().firstRaisedAt()).isEqualTo(raised);
        assertThat(clocks.statusForRecommendation(recommendation).orElseThrow().basisDigest())
                .isEqualTo(original.path("basisDigest").asText());
        var resolved=calibration.resolve(fixture.id("organization"),fixture.graph.platform(),fixture.id("store"),Instant.now());
        UUID same=listingIntake.ensureGovernedResponsibilityTask(fixture.id("organization"),recommendation,
                "Recalculation must retain original responsibility",
                com.mimococo.marketops.listingconversion.internal.application.CalibrationService.responsibilityBasis(resolved),
                raised.plusSeconds(600));
        assertThat(same).isEqualTo(task);
        assertThat(clocks.statusForRecommendation(recommendation).orElseThrow().firstRaisedAt()).isEqualTo(raised);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.work_task WHERE recommendation_id=:id")
                .param("id",recommendation).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(()->fixture.seed.sql("UPDATE ops.lc_task_responsibility SET first_raised_at=first_raised_at+interval '1 hour' WHERE task_id=:id")
                .param("id",task).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        UUID reviewer=independentMeaningReviewer();
        Object intake=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(listingIntake);
        var intakeClock=(java.time.Clock)org.springframework.test.util.ReflectionTestUtils.getField(intake,"clock");
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(intake,"clock",
                    java.time.Clock.offset(intakeClock,java.time.Duration.ofSeconds(clockOffsetSeconds)));
            mvc.perform(post("/api/v1/console/listing/actions/"+action+"/review").header(HttpHeaders.AUTHORIZATION,bearer())
                    .contentType(MediaType.APPLICATION_JSON).content(meaningReviewRequest(action,false)))
                    .andExpect(status().isOk());
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(intake,"clock",intakeClock);
        }
        assertThat(clocks.statusForRecommendation(recommendation).orElseThrow().firstAttributableActionAt()).isNotNull();
        assertThat(clocks.statusForRecommendation(recommendation).orElseThrow().acknowledgedAt())
                .isEqualTo(acknowledged.acknowledgedAt());
        users.grantScope(OPERATOR,reviewer,ActionScopeCode.TASK_ASSIGN,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        mvc.perform(post(closureEndpoint).header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "done",true,"closureReason","Qualified review completed the proposal work","expectedVersion",2))))
                .andExpect(status().isNoContent());
        assertThat(listingIntake.ensureGovernedResponsibilityTask(fixture.id("organization"),recommendation,
                "Recurring work retains the original clock",
                com.mimococo.marketops.listingconversion.internal.application.CalibrationService.responsibilityBasis(resolved),
                raised.plusSeconds(1200))).isEqualTo(task);
        var reopened=clocks.statusForRecommendation(recommendation).orElseThrow();
        assertThat(reopened.firstRaisedAt()).isEqualTo(raised);
        assertThat(reopened.basisDigest()).isEqualTo(original.path("basisDigest").asText());
        assertThat(reopened.acknowledgedAt()).isNull();
        assertThat(reopened.firstAttributableActionAt()).isNull();
        assertThat(clocks.statusForRecommendation(recommendation,reopened.actionDueAt().plusSeconds(1))
                .orElseThrow().actionBreached()).isTrue();
        mvc.perform(post(closureEndpoint).header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "done",true,"closureReason","Old action cannot close recurring work","expectedVersion",4))))
                .andExpect(status().isForbidden());
    }

    private UUID prepareDescriptionForExposure(java.math.BigDecimal reportedExposure) throws Exception {
        return prepareDescriptionForExposure(reportedExposure,ListingConversionFixture.PRIOR_TEXT_ONE+".");
    }

    @Test
    void finiteDeferralKeepsOriginalClocksAndReassessesChangedDiagnosis() throws Exception {
        UUID action=prepareDescriptionForExposure(java.math.BigDecimal.ZERO);
        String healthEndpoint="/api/v1/console/listing/health/listings/"+fixture.id("listing");
        mvc.perform(post(healthEndpoint+"/recompute").header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk());
        String endpoint="/api/v1/console/listing/actions/"+action+"/responsibility";
        var json=new tools.jackson.databind.ObjectMapper();
        var initial=json.readTree(mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("status");
        UUID task=UUID.fromString(initial.path("taskId").asText());
        String request="{\"minutes\":60,\"reason\":\"Reconsider the same scoped work within an explicit finite period\"}";
        mvc.perform(post(endpoint+"/deferrals").header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        users.grantScope(OPERATOR,userId,ActionScopeCode.TASK_ASSIGN,ResourceScopeType.STORE,fixture.id("store"),null);
        var deferred=mvc.perform(post(endpoint+"/deferrals").header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var repeated=mvc.perform(post(endpoint+"/deferrals").header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(repeated)).isEqualTo(json.readTree(deferred));
        mvc.perform(post(endpoint+"/deferrals").header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(request.replace("60","1441")))
                .andExpect(status().is4xxClientError());
        mvc.perform(post(healthEndpoint+"/recompute").header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk());
        mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status.deferral.state").value("ACTIVE"));
        fixture.contain(UUID.randomUUID(),fixture.id("ownerUser"),fixture.id("listing"));
        mvc.perform(post(healthEndpoint+"/recompute").header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk());
        var changed=json.readTree(mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("status");
        assertThat(changed.path("deferral").path("state").asText()).isEqualTo("INVALIDATED");
        assertThat(changed.path("deferral").path("reviewHealthId").asText()).isNotBlank();
        for(String field:List.of("firstRaisedAt","acknowledgementDueAt","actionDueAt","outcomeMaturityDueAt","basisDigest"))
            assertThat(changed.path(field)).isEqualTo(initial.path(field));
        assertThat(changed.path("acknowledgedAt").isNull()).isTrue();
        assertThat(changed.path("firstAttributableActionAt").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM ops.work_task_event WHERE task_id=:id AND event_kind='DEFERRED'")
                .param("id",task).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.work_task_event WHERE task_id=:id AND event_kind='REASSESSMENT_REQUIRED'")
                .param("id",task).query(Long.class).single()).isEqualTo(1);
    }

    private UUID prepareDescriptionForExposure(java.math.BigDecimal reportedExposure,String targetText) throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        return prepareDescriptionForPurpose(reportedExposure,targetText,"LISTING_CONVERSION");
    }

    private UUID prepareDescriptionForPurpose(java.math.BigDecimal reportedExposure,String targetText,String purpose) throws Exception {
        return prepareDescriptionForPurpose(reportedExposure,targetText,purpose,3600);
    }

    private UUID prepareDescriptionForPurpose(java.math.BigDecimal reportedExposure,String targetText,String purpose,long useSeconds) throws Exception {
        return prepareDescriptionForPurpose(reportedExposure,targetText,purpose,useSeconds,"MANUAL");
    }

    private UUID prepareDescriptionForPurpose(java.math.BigDecimal reportedExposure,String targetText,String purpose,
                                              long useSeconds,String executionPath) throws Exception {
        var json=new tools.jackson.databind.ObjectMapper();
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
        var preparation=new java.util.LinkedHashMap<String,Object>(Map.of("executionPath",executionPath,"targetText",targetText,
                "kizMarkedDeclared",false,"exposureShare",reportedExposure,"purpose",purpose));
        if(!purpose.equals("LISTING_CONVERSION")) {
            var basis=new java.util.LinkedHashMap<String,Object>(Map.of(
                    "evidenceReference","evidence://synthetic/independent-correction",
                    "useConditions",List.of("Retain only while the corrected product fact remains accurate"),
                    "endConditions",List.of("Reassess if the corrected fact or necessary protection changes")));
            if(purpose.equals("BOUNDED_EXPLORATION")) basis.put("useUntil",Instant.now().plusSeconds(useSeconds).toString());
            preparation.put("purposeBasis",basis);
        }
        var actionResponse=mvc.perform(post("/api/v1/console/listing/actions/candidates/"+candidate+"/prepare")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(preparation)))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .withFailMessage("Preparation failed: %s", result.getResolvedException()).isEqualTo(200))
                .andExpect(jsonPath("$.state").value("DRAFT")).andReturn();
        UUID actionId=UUID.fromString(json.readTree(actionResponse.getResponse().getContentAsString()).path("id").asText());
        if (purpose.equals("LISTING_CONVERSION")) {
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
        return actionId;
    }

    private UUID replaceDescriptionAction(UUID listing,UUID actionToCancel,String roundKey) throws Exception {
        var json=new tools.jackson.databind.ObjectMapper();
        for(var scope:List.of(ActionScopeCode.LISTING_ACTION_PREPARE,ActionScopeCode.LISTING_CONVERSION_VIEW))
            users.grantScope(OPERATOR,userId,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        if(actionToCancel!=null) {
            mvc.perform(post("/api/v1/console/listing/actions/"+actionToCancel+"/cancel")
                    .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Replace the unused synthetic action under the current calibration\"}"))
                    .andExpect(status().isOk());
        }
        String currentText=jdbc.sql("""
                SELECT description_text FROM core.lc_description_observation
                 WHERE platform_listing_id=:listing ORDER BY observed_at DESC,acquired_at DESC LIMIT 1
                """).param("listing",listing).query(String.class).single();
        var candidate=mvc.perform(post("/api/v1/console/listing/actions/candidates")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("listingId",listing,"candidateKind","CONTENT_DESCRIPTION",
                        "roundKey",roundKey+"-"+UUID.randomUUID(),
                        "evidenceReferences",List.of("evidence://synthetic/calibration-version")))))
                .andExpect(status().isOk()).andReturn();
        UUID candidateId=UUID.fromString(json.readTree(candidate.getResponse().getContentAsString()).path("id").asText());
        var prepared=mvc.perform(post("/api/v1/console/listing/actions/candidates/"+candidateId+"/prepare")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("executionPath","MANUAL","purpose","LISTING_CONVERSION",
                        "targetText",currentText+".","kizMarkedDeclared",false,"exposureShare",BigDecimal.ZERO))))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(json.readTree(prepared.getResponse().getContentAsString()).path("id").asText());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"0","1"})
    void requestExposureCannotLowerOrRaiseCanonicalClassification(String reported) throws Exception {
        fixture.seedRetainedSalesExposure(new java.math.BigDecimal("300000"),new java.math.BigDecimal("1000000"));
        UUID action=prepareDescriptionForExposure(new java.math.BigDecimal(reported));
        assertThat(jdbc.sql("""
                SELECT materiality_route='MATERIAL_IMPACT' AND exposure_axis_material
                  AND materiality_evidence->>'state'='QUALIFIED'
                  AND (materiality_evidence#>>'{projection,share}')::numeric=0.3
                  AND jsonb_array_length(materiality_evidence#>'{projection,memberValues}')=1
                FROM ops.lc_action WHERE id=:id
                """).param("id",action).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(()->fixture.seed.sql("UPDATE ops.lc_action SET materiality_evidence='{}' WHERE id=:id")
                .param("id",action).update()).hasMessageContaining("prepared materiality evidence is immutable");
        mvc.perform(get("/api/v1/console/listing/actions/"+action).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.materialityEvidence").doesNotExist());
    }

    @Test void missingMemberExposureCannotBorrowTheRequestsZero() throws Exception {
        fixture.seed.sql("""
                DELETE FROM mart.metric_input_reference WHERE metric_value_id IN (
                  SELECT id FROM mart.metric_value WHERE subject_id=:member AND metric_code='RETAINED_NET_SALES')
                """).param("member",fixture.id("listingVariant")).update();
        fixture.seed.sql("DELETE FROM mart.metric_value WHERE subject_id=:member AND metric_code='RETAINED_NET_SALES'")
                .param("member",fixture.id("listingVariant")).update();
        UUID action=prepareDescriptionForExposure(java.math.BigDecimal.ZERO);
        assertThat(jdbc.sql("""
                SELECT materiality_route='MATERIALITY_UNRESOLVED' AND exposure_axis_material IS NULL
                  AND materiality_evidence->>'state'='EXPOSURE_UNRESOLVED'
                  AND jsonb_exists(materiality_evidence#>'{projection,gaps}','EXPOSURE_MEMBER_VALUE_MISSING')
                FROM ops.lc_action WHERE id=:id
                """).param("id",action).query(Boolean.class).single()).isTrue();
        subject="exposure-independent-reviewer-"+UUID.randomUUID();
        UUID reviewer=users.provision(OPERATOR,fixture.id("organization"),providerId,subject,null,"Exposure reviewer",null).id();
        jdbc.sql("UPDATE iam.user_account SET credentials_valid_from=now()-interval '1 hour' WHERE id=:id").param("id",reviewer).update();
        users.assignRole(OPERATOR,reviewer,BusinessRoleCode.OWNER,null);
        for(var scope:List.of(ActionScopeCode.LISTING_ACTION_REVIEW,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW))
            users.grantScope(OPERATOR,reviewer,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        mvc.perform(post("/api/v1/console/listing/actions/"+action+"/review").header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(meaningReviewRequest(action,false)))
                .andExpect(result->assertThat(result.getResolvedException()).isInstanceOfSatisfying(
                    com.mimococo.marketops.shared.OperationRejectedException.class,
                    failure->assertThat(failure.errorCode()).isEqualTo(com.mimococo.marketops.shared.ErrorCode.MATERIALITY_UNRESOLVED)));
        assertThat(jdbc.sql("SELECT state FROM ops.lc_action WHERE id=:id").param("id",action).query(String.class).single()).isEqualTo("DRAFT");
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_action_review WHERE action_id=:id").param("id",action).query(Long.class).single()).isZero();
    }

    private UUID independentMeaningReviewer() {
        subject="meaning-reviewer-"+UUID.randomUUID();
        UUID reviewer=users.provision(OPERATOR,fixture.id("organization"),providerId,subject,null,"Meaning reviewer",null).id();
        jdbc.sql("UPDATE iam.user_account SET credentials_valid_from=now()-interval '1 hour' WHERE id=:id").param("id",reviewer).update();
        users.assignRole(OPERATOR,reviewer,BusinessRoleCode.OWNER,null);
        for(var scope:List.of(ActionScopeCode.LISTING_ACTION_REVIEW,ActionScopeCode.LISTING_CONVERSION_VIEW,
                ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ActionScopeCode.LISTING_ACTION_APPROVE_MATERIAL,ActionScopeCode.LISTING_ACTION_APPROVE_ORDINARY))
            users.grantScope(OPERATOR,reviewer,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        return reviewer;
    }

    private record ApprovedApiCorrection(UUID actionId,UUID recommendationId,UUID approvalId) { }

    private ApprovedApiCorrection prepareApprovedApiCorrection(String targetText,BigDecimal reportedExposure,
                                                                boolean major) throws Exception {
        ensureSyntheticDescriptionCorrection();
        UUID action=prepareDescriptionForPurpose(reportedExposure,targetText,"DESCRIPTION_CORRECTION",3600,"API");
        userId=independentMeaningReviewer();
        mvc.perform(post("/api/v1/console/listing/actions/"+action+"/review")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(meaningReviewRequest(action,major))).andExpect(status().isOk());
        UUID recommendation=jdbc.sql("SELECT recommendation_id FROM ops.lc_action WHERE id=:id")
                .param("id",action).query(UUID.class).single();
        long version=jdbc.sql("SELECT version FROM ops.recommendation WHERE id=:id")
                .param("id",recommendation).query(Long.class).single();
        var json=new tools.jackson.databind.ObjectMapper();
        mvc.perform(post("/api/v1/console/workflow/recommendations/"+recommendation+"/approval")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion",version,
                        "reason","Approve the exact independently reviewed description correction"))))
                .andExpect(result->assertThat(result.getResponse().getStatus())
                        .withFailMessage("Correction approval failed: %s",result.getResolvedException()).isEqualTo(200));
        UUID approval=jdbc.sql("""
                SELECT id FROM ops.approval_decision
                 WHERE recommendation_id=:recommendation AND decision='APPROVED'
                 ORDER BY decided_at DESC,id DESC LIMIT 1
                """).param("recommendation",recommendation).query(UUID.class).single();
        return new ApprovedApiCorrection(action,recommendation,approval);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void acceptedMeaningConditionsDetermineRouteWithTheSameSmallExposure(boolean major) throws Exception {
        var json=new tools.jackson.databind.ObjectMapper();
        String original="Не использовать для детей. " + "Мягкая ткань. ".repeat(60);
        String target=major?original.substring(3):original+"\n";
        ensureSyntheticDescriptionCorrection();
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OPERATIONS,null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_PREPARE,
                ResourceScopeType.STORE,fixture.id("store"),null);
        mvc.perform(post("/api/v1/console/listing/health/listings/"+fixture.id("listing")+"/facts/description")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text",original,"languageCode","ru")))).andExpect(status().isOk());
        UUID action=prepareDescriptionForPurpose(java.math.BigDecimal.ONE,target,"DESCRIPTION_CORRECTION");
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_REVIEW,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        String authorRequest=meaningReviewRequest(action,major);
        mvc.perform(post("/api/v1/console/listing/actions/"+action+"/review")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON).content(authorRequest))
                .andExpect(status().isForbidden());
        UUID reviewer=independentMeaningReviewer();
        mvc.perform(get("/api/v1/console/listing/actions/"+action+"/review-basis").header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentText").value(original))
                .andExpect(jsonPath("$.targetText").value(target));
        mvc.perform(post("/api/v1/console/listing/actions/"+action+"/review")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON).content(meaningReviewRequest(action,major)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REVIEWED"))
                .andExpect(jsonPath("$.materialityRoute").value(major?"MATERIAL_IMPACT":"ORDINARY_IMPACT"));
        assertThat(jdbc.sql("""
                SELECT r.reviewer_user_id=:reviewer AND r.content_axis_material=:major AND NOT r.exposure_axis_material
                  AND (r.exposure_evidence#>>'{projection,share}')::numeric=0.0001
                  AND ops.lc_action_has_meaning_review(a.id,statement_timestamp())
                FROM ops.lc_action a JOIN ops.lc_action_review r ON r.action_id=a.id WHERE a.id=:id
                """).param("reviewer",reviewer).param("major",major).param("id",action).query(Boolean.class).single()).isTrue();
        String reviewerToken=bearer();
        subject="meaning-ops-lead-"+UUID.randomUUID();
        UUID opsLead=users.provision(OPERATOR,fixture.id("organization"),providerId,subject,null,"Meaning Ops Lead",null).id();
        jdbc.sql("UPDATE iam.user_account SET credentials_valid_from=now()-interval '1 hour' WHERE id=:id").param("id",opsLead).update();
        users.assignRole(OPERATOR,opsLead,BusinessRoleCode.OPS_LEAD,null);
        for(var scope:List.of(ActionScopeCode.LISTING_ACTION_APPROVE_ORDINARY,ActionScopeCode.LISTING_ACTION_APPROVE_MATERIAL))
            users.grantScope(OPERATOR,opsLead,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        UUID recommendation=jdbc.sql("SELECT recommendation_id FROM ops.lc_action WHERE id=:id").param("id",action).query(UUID.class).single();
        long version=jdbc.sql("SELECT version FROM ops.recommendation WHERE id=:id").param("id",recommendation).query(Long.class).single();
        String approvalRequest=json.writeValueAsString(Map.of("expectedVersion",version,"reason","Approve exact reviewed meaning and scope"));
        String approvalPath="/api/v1/console/workflow/recommendations/"+recommendation+"/approval";
        mvc.perform(post(approvalPath).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON).content(approvalRequest))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM ops.approval_decision WHERE recommendation_id=:id")
                .param("id",recommendation).query(Long.class).single()).isZero();
        users.grantScope(OPERATOR,opsLead,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        mvc.perform(post(approvalPath).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON).content(approvalRequest))
                .andExpect(major?status().isForbidden():status().isOk());
        if (major) mvc.perform(post(approvalPath).header(HttpHeaders.AUTHORIZATION,reviewerToken)
                .contentType(MediaType.APPLICATION_JSON).content(approvalRequest)).andExpect(status().isOk());
        assertThatThrownBy(()->fixture.seed.sql("UPDATE ops.lc_action SET content_axis_material=NOT content_axis_material WHERE id=:id")
                .param("id",action).update()).satisfies(f->assertThat(ListingConversionFixture.sqlState(f)).isEqualTo("MO107"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"UNKNOWN","MISSING","DUPLICATE","EXTRA","STALE_BASIS","INCOMPLETE","NO_APPLICABLE","NO_EVIDENCE","MISSING_ASSESSMENT","SECRET_TEXT"})
    void incompleteOrUnboundMeaningCannotAdvanceDraft(String caseCode) throws Exception {
        UUID action=prepareDescriptionForExposure(java.math.BigDecimal.ZERO);
        independentMeaningReviewer();
        var json=new tools.jackson.databind.ObjectMapper();
        var request=(tools.jackson.databind.node.ObjectNode)json.readTree(meaningReviewRequest(action,false));
        var assessment=(tools.jackson.databind.node.ObjectNode)request.path("meaningAssessment");
        var answers=(tools.jackson.databind.node.ArrayNode)assessment.path("answers");
        switch(caseCode) {
            case "UNKNOWN" -> ((tools.jackson.databind.node.ObjectNode)answers.get(0)).put("state","UNKNOWN");
            case "MISSING" -> answers.remove(0);
            case "DUPLICATE" -> answers.set(1,answers.get(0).deepCopy());
            case "EXTRA" -> answers.add(answers.get(0).deepCopy());
            case "STALE_BASIS" -> assessment.put("basisDigest","f".repeat(64));
            case "INCOMPLETE" -> assessment.put("complete",false);
            case "NO_APPLICABLE" -> answers.forEach(a->((tools.jackson.databind.node.ObjectNode)a).put("state","DOES_NOT_APPLY"));
            case "NO_EVIDENCE" -> assessment.put("evidenceReference","");
            case "MISSING_ASSESSMENT" -> request.remove("meaningAssessment");
            case "SECRET_TEXT" -> ((tools.jackson.databind.node.ObjectNode)answers.get(0)).put("reason","password=synthetic-refusal-only");
            default -> throw new AssertionError(caseCode);
        }
        mvc.perform(post("/api/v1/console/listing/actions/"+action+"/review").header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(result->assertThat(result.getResolvedException()).isInstanceOfSatisfying(
                    com.mimococo.marketops.shared.OperationRejectedException.class,
                    failure->assertThat(failure.errorCode()).isEqualTo(caseCode.equals("SECRET_TEXT")?com.mimococo.marketops.shared.ErrorCode.SECRET_MATERIAL_SUSPECTED
                        :com.mimococo.marketops.shared.ErrorCode.MATERIALITY_UNRESOLVED)));
        assertThat(jdbc.sql("SELECT state='DRAFT' AND content_axis_material IS NULL FROM ops.lc_action WHERE id=:id")
                .param("id",action).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_action_review WHERE action_id=:id").param("id",action).query(Long.class).single()).isZero();
    }

    private String meaningReviewRequest(UUID action,boolean major) throws Exception {
        var json=new tools.jackson.databind.ObjectMapper();
        var basisResponse=mvc.perform(get("/api/v1/console/listing/actions/"+action+"/review-basis")
                .header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleState").value("QUALIFIED")).andReturn();
        var basis=json.readTree(basisResponse.getResponse().getContentAsString());
        var answers=new java.util.ArrayList<Map<String,String>>();
        for(var condition:basis.path("conditions")) answers.add(Map.of("code",condition.path("code").asText(),
                "state",condition.path("axis").asText().equals("MATERIAL")==major?"APPLIES":"DOES_NOT_APPLY",
                "reason","Explicit synthetic professional assessment of the complete proposed change"));
        return json.writeValueAsString(Map.of("verdict","ATTESTED","reason","Independent structured review",
                "meaningAssessment",Map.of("model","LC_MEANING_REVIEW_1","basisDigest",basis.path("basisDigest").asText(),
                    "complete",true,"evidenceReference","evidence://synthetic/meaning-review","answers",answers)));
    }

    @Test void fabricatedHighExposureCannotUpgradeAKnownSmallExposure() throws Exception {
        UUID action=prepareDescriptionForExposure(java.math.BigDecimal.ONE);
        assertThat(jdbc.sql("""
                SELECT materiality_route='MATERIALITY_UNRESOLVED' AND content_axis_material IS NULL AND NOT exposure_axis_material
                  AND (materiality_evidence#>>'{projection,share}')::numeric=0.0001
                FROM ops.lc_action WHERE id=:id
                """).param("id",action).query(Boolean.class).single()).isTrue();
    }

    private void changeCurrentExposure(String change) {
        fixture.seedRetainedSalesExposure(new java.math.BigDecimal(switch(change) {
            case "MATERIAL" -> "300000";
            case "BETWEEN_BOUNDS" -> "100000";
            case "ZERO_STORE" -> "0";
            case "SAME_AXIS" -> "200";
            default -> throw new AssertionError(change);
        }),new java.math.BigDecimal(change.equals("ZERO_STORE")?"0":"1000000"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"MATERIAL","BETWEEN_BOUNDS","ZERO_STORE"})
    void changedOrUnknownExposureCannotConsumeAnOrdinaryReviewAtApproval(String change) throws Exception {
        UUID action=prepareDescriptionForExposure(java.math.BigDecimal.ZERO);
        independentMeaningReviewer();
        mvc.perform(post("/api/v1/console/listing/actions/"+action+"/review").header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(meaningReviewRequest(action,false))).andExpect(status().isOk());
        changeCurrentExposure(change);
        UUID recommendation=jdbc.sql("SELECT recommendation_id FROM ops.lc_action WHERE id=:id").param("id",action).query(UUID.class).single();
        long version=jdbc.sql("SELECT version FROM ops.recommendation WHERE id=:id").param("id",recommendation).query(Long.class).single();
        var json=new tools.jackson.databind.ObjectMapper();
        mvc.perform(post("/api/v1/console/workflow/recommendations/"+recommendation+"/approval")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion",version,"reason","Current evidence must still qualify"))))
                .andExpect(result->assertThat(result.getResolvedException()).isInstanceOfSatisfying(
                    com.mimococo.marketops.shared.OperationRejectedException.class,
                    failure->assertThat(failure.errorCode()).isEqualTo(com.mimococo.marketops.shared.ErrorCode.GUARDRAIL_BLOCKED)));
        assertThat(jdbc.sql("SELECT state='REVIEWED' AND materiality_route='ORDINARY_IMPACT' AND NOT exposure_axis_material FROM ops.lc_action WHERE id=:id")
                .param("id",action).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_action_binding WHERE action_id=:id").param("id",action).query(Long.class).single()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"MATERIAL","BETWEEN_BOUNDS","ZERO_STORE","SAME_AXIS"})
    void launchRechecksCurrentExposureThroughTheSharedExecutionGuardrail(String change) throws Exception {
        var requestsBefore=List.copyOf(loopback.received);
        var approved=prepareApprovedApiCorrection(ListingConversionFixture.TARGET_TEXT_ONE,
                BigDecimal.ZERO,false);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_LAUNCH,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        changeCurrentExposure(change);
        var decisions=applicationContext.getBean(com.mimococo.marketops.operationsworkflow.ListingActionDecisionAuthority.class);
        var scope=decisions.recheckedDecisionScope(approved.recommendationId()).orElseThrow();
        var json=new tools.jackson.databind.ObjectMapper();
        var evidence=scope.materialityRecheck();
        assertThat(evidence.get("projectionDigest")).matches("[0-9a-f]{64}");
        assertThat(json.readTree(evidence.get("metricValueIds")).size()).isEqualTo(2);
        assertThat(evidence.containsKey("share")).isFalse();
        assertThat(evidence.containsKey("projection")).isFalse();
        boolean same=change.equals("SAME_AXIS");
        assertThat(scope.materialityResolved()).isEqualTo(same);
        assertThat(evidence.get("state")).isEqualTo(same?"CURRENT":change.equals("MATERIAL")
                ?"EXPOSURE_CLASSIFICATION_CHANGED":"CURRENT_EXPOSURE_UNRESOLVED");
        var attempt=mvc.perform(post("/api/v1/console/listing/actions/"+approved.actionId()+"/launch")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"axes\":{}}"));
        if (same) {
            attempt.andExpect(status().isOk()).andExpect(jsonPath("$.launched").value(true));
            assertThat(jdbc.sql("""
                    SELECT detail->>'materialityRecheck.state'='CURRENT'
                      AND detail->>'materialityRecheck.projectionDigest' ~ '^[0-9a-f]{64}$'
                      AND jsonb_array_length((detail->>'materialityRecheck.metricValueIds')::jsonb)=2
                      AND NOT jsonb_exists(authority_snapshot,'materialityRecheck')
                    FROM ops.guardrail_evaluation WHERE recommendation_id=:id AND purpose='EXECUTION'
                    ORDER BY evaluated_at DESC LIMIT 1
                    """).param("id",approved.recommendationId()).query(Boolean.class).single()).isTrue();

        } else {
            attempt.andExpect(result->assertThat(result.getResolvedException()).isInstanceOfSatisfying(
                    com.mimococo.marketops.shared.OperationRejectedException.class,
                    failure->assertThat(failure.errorCode()).isEqualTo(com.mimococo.marketops.shared.ErrorCode.GUARDRAIL_BLOCKED)));
            assertThat(jdbc.sql("SELECT state FROM ops.lc_action WHERE id=:id").param("id",approved.actionId()).query(String.class).single()).isEqualTo("APPROVED");
            assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_description_command WHERE action_id=:id")
                    .param("id",approved.actionId()).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_exposure_occupation WHERE action_id=:id")
                    .param("id",approved.actionId()).query(Long.class).single()).isZero();
        }
        assertThat(loopback.received).isEqualTo(requestsBefore);
    }

    @Test
    void promotionFixedFeesRemainExactAcrossActivitiesPeriodsAndKnowledgeTimes() {
        Instant at=Instant.now(), end=at.plusSeconds(604800), known=at.minusSeconds(60);
        UUID provenance=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note)
                VALUES(:id,:org,'MANUAL_ENTRY',:known,:known,:actor,'Synthetic fixed activity fee source')
                """).param("id",provenance).param("org",fixture.id("organization")).param("known",java.sql.Timestamp.from(known))
                .param("actor",userId).update();
        for (String activity:List.of("ACTIVITY_A","ACTIVITY_B")) fixture.seed.sql("""
                INSERT INTO core.finance_input_version(id,organization_id,input_code,scope_kind,store_ref_id,value_kind,
                    amount_value,currency_code,provenance_id,effective_from,effective_to,status,created_at,updated_at,
                    promotion_kind,native_promotion_key)
                VALUES(:id,:org,'PROMOTION_FIXED_FEE','PROMOTION',:store,'AMOUNT',:amount,'RUB',:source,:start,:end,
                    'ACTIVE',:known,:known,'OFFICIAL_PROMOTION_PARTICIPATION',:activity)
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization")).param("store",fixture.id("store"))
                .param("amount",activity.equals("ACTIVITY_A")?600:900).param("source",provenance)
                .param("start",java.sql.Timestamp.from(at)).param("end",java.sql.Timestamp.from(end))
                .param("known",java.sql.Timestamp.from(known)).param("activity",activity).update();
        var query=applicationContext.getBean(com.mimococo.marketops.operatingfacts.OperatingFactQuery.class);
        var aFee=query.promotionFixedFee(fixture.id("organization"),fixture.id("store"),"OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A",at,end,at).orElseThrow();
        var bFee=query.promotionFixedFee(fixture.id("organization"),fixture.id("store"),"OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_B",at,end,at).orElseThrow();
        assertThat(aFee.amountValue().amount()).isEqualByComparingTo("600");
        assertThat(bFee.amountValue().amount()).isEqualByComparingTo("900");
        assertThat(query.promotionFixedFee(fixture.id("organization"),fixture.id("store"),"OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A",at,end.plusSeconds(1),at)).isEmpty();
        assertThat(query.promotionFixedFee(fixture.id("organization"),fixture.id("store"),"OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A",at,end,known.minusSeconds(1))).isEmpty();
        assertThat(query.promotionFixedFee(fixture.id("organization"),UUID.randomUUID(),"OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A",at,end,at)).isEmpty();
        assertThat(query.financeInput(fixture.id("organization"),"PROMOTION_FIXED_FEE",fixture.id("store"),null,at.plusSeconds(1))).isEmpty();
        var references=applicationContext.getBean(com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.InternalReferenceRepository.class);
        references.endOpenFinanceInput(fixture.id("organization"),"PROMOTION_FIXED_FEE","PROMOTION",fixture.id("store"),at,
                "Correct the exact activity fee", "OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A");
        UUID corrected=UUID.randomUUID();
        references.insertFinanceInput(corrected,fixture.id("organization"),"PROMOTION_FIXED_FEE","PROMOTION",fixture.id("store"),null,
                "AMOUNT",null,new java.math.BigDecimal("650"),"RUB",provenance,at,known,
                "OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A",end);
        assertThat(jdbc.sql("SELECT status='CANCELLED' AND effective_from=:start AND effective_to=:end FROM core.finance_input_version WHERE id=:id")
                .param("start",java.sql.Timestamp.from(at)).param("end",java.sql.Timestamp.from(end))
                .param("id",aFee.financeInputVersionId()).query(Boolean.class).single()).isTrue();
        assertThat(query.promotionFixedFee(fixture.id("organization"),fixture.id("store"),"OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A",at,end,at)
                .orElseThrow().financeInputVersionId()).isEqualTo(corrected);
        assertThat(query.promotionFixedFee(fixture.id("organization"),fixture.id("store"),"OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_B",at,end,at)
                .orElseThrow().financeInputVersionId()).isEqualTo(bFee.financeInputVersionId());
        UUID future=UUID.randomUUID();
        references.insertFinanceInput(future,fixture.id("organization"),"PROMOTION_FIXED_FEE","PROMOTION",fixture.id("store"),null,
                "AMOUNT",null,new java.math.BigDecimal("800"),"RUB",provenance,end,known,
                "OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A",end.plusSeconds(604800));
        Instant split=at.plusSeconds(86400);
        references.endOpenFinanceInput(fixture.id("organization"),"PROMOTION_FIXED_FEE","PROMOTION",fixture.id("store"),split,
                "Later terms apply only from the supported instant", "OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A");
        assertThat(query.promotionFixedFee(fixture.id("organization"),fixture.id("store"),"OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A",at,split,at)
                .orElseThrow().financeInputVersionId()).isEqualTo(corrected);
        assertThat(query.promotionFixedFee(fixture.id("organization"),fixture.id("store"),"OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A",end,end.plusSeconds(604800),at)
                .orElseThrow().financeInputVersionId()).isEqualTo(future);
        assertThat(query.promotionFixedFee(fixture.id("organization"),fixture.id("store"),"OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A",at,end,at)).isEmpty();
        for(String code:List.of("PROMOTION_BUYER_PAYMENT_PER_UNIT","PROMOTION_SELLER_REVENUE_PER_UNIT","PROMOTION_PLATFORM_COMPENSATION_PER_UNIT"))
            references.insertFinanceInput(UUID.randomUUID(),fixture.id("organization"),code,"PROMOTION",fixture.id("store"),null,
                    "AMOUNT",null,new java.math.BigDecimal(code.contains("BUYER")?"80":code.contains("SELLER")?"100":"20"),"RUB",provenance,at,known,
                    "OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A",end,fixture.id("listing"),"a".repeat(64));
        assertThat(query.promotionRevenueInputs(fixture.id("organization"),fixture.id("store"),fixture.id("listing"),
                "OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A","a".repeat(64),at,end,at)).hasSize(3);
        assertThat(query.promotionRevenueInputs(fixture.id("organization"),fixture.id("store"),fixture.id("listingTwo"),
                "OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A","a".repeat(64),at,end,at)).isEmpty();
        assertThat(query.promotionRevenueInputs(fixture.id("organization"),fixture.id("store"),fixture.id("listing"),
                "OFFICIAL_PROMOTION_PARTICIPATION","ACTIVITY_A","b".repeat(64),at,end,at)).isEmpty();
    }

    @Test
    void purchaseCostPeriodsKeepTheirExactBoundaryAndKnowledgeTime() {
        Instant at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),middle=at.plusSeconds(3600),end=at.plusSeconds(7200),known=at.minusSeconds(60);
        UUID provenance=UUID.randomUUID(),first=UUID.randomUUID(),second=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note)
                VALUES(:id,:org,'MANUAL_ENTRY',:known,:known,:actor,'Synthetic period cost source')
                """).param("id",provenance).param("org",fixture.id("organization")).param("known",java.sql.Timestamp.from(known))
                .param("actor",userId).update();
        for (int i=0;i<2;i++) fixture.seed.sql("""
                INSERT INTO core.cost_version(id,organization_id,product_variant_id,cost_kind,currency_code,unit_cost,
                    provenance_id,effective_from,effective_to,status,created_at,updated_at)
                VALUES(:id,:org,:variant,'PURCHASE','RUB',:amount,:source,:start,:end,'ACTIVE',:known,:known)
                """).param("id",i==0?first:second).param("org",fixture.id("organization"))
                .param("variant",fixture.id("productVariantTwo")).param("amount",i==0?10:20).param("source",provenance)
                .param("start",java.sql.Timestamp.from(i==0?at:middle)).param("end",java.sql.Timestamp.from(i==0?middle:end))
                .param("known",java.sql.Timestamp.from(i==0?known:at.plusSeconds(60))).update();
        var query=applicationContext.getBean(com.mimococo.marketops.operatingfacts.OperatingFactQuery.class);
        var initiallyKnown=query.purchaseCosts(fixture.id("organization"),fixture.id("productVariantTwo"),at,end,at);
        assertThat(initiallyKnown).extracting(v->v.cost().costVersionId()).contains(first).doesNotContain(second);
        assertThat(initiallyKnown.stream().filter(v->v.cost().costVersionId().equals(first)).findFirst().orElseThrow().effectiveTo())
                .isEqualTo(middle);
        var laterKnown=query.purchaseCosts(fixture.id("organization"),fixture.id("productVariantTwo"),at,end,at.plusSeconds(61));
        assertThat(laterKnown).extracting(v->v.cost().costVersionId()).contains(first,second);
        assertThat(query.purchaseCosts(fixture.id("organization"),fixture.id("productVariantTwo"),at,middle,at.plusSeconds(61)))
                .extracting(v->v.cost().costVersionId()).contains(first).doesNotContain(second);
        assertThat(query.purchaseCosts(UUID.randomUUID(),fixture.id("productVariantTwo"),at,end,at.plusSeconds(61))).isEmpty();
    }

    @Test
    void structuralHealthCannotAuthorizeLaunchWithoutBusinessProtectionEvidence() throws Exception {
        var requestsBefore=List.copyOf(loopback.received);
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_LAUNCH,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        listingIntake.ensureResponsibilityTask(fixture.id("organization"),fixture.id("recommendationOne"),
                "Synthetic business protection responsibility",Instant.now().plusSeconds(86400),Instant.now());
        // This fixture has real current exposure/meaning/approval bindings, but
        // no canonical profit or return values. Its structural PASS cannot fill those gaps.
        assertThat(jdbc.sql("""
                SELECT count(*) FROM mart.metric_value WHERE subject_id=:member AND metric_code IN
                  ('OPERATIONAL_CONTRIBUTION_PROFIT','SETTLED_CONTRIBUTION_PROFIT','RETURN_UNITS','COMPLETED_UNITS')
                """).param("member",fixture.id("listingVariant")).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT necessary_state FROM mart.lc_listing_health WHERE platform_listing_id=:listing
                ORDER BY health_version DESC LIMIT 1
                """).param("listing",fixture.id("listing")).query(String.class).single()).isEqualTo("PASS");
        var attempted=mvc.perform(post("/api/v1/console/listing/actions/"+fixture.id("actionOne")+"/launch")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"axes\":{}}"))
                .andReturn();
        var response=new tools.jackson.databind.ObjectMapper().readTree(attempted.getResponse().getContentAsString());
        var effects=new java.util.LinkedHashMap<String,Long>();
        for(String table:List.of("lc_launch","lc_description_command","lc_exposure_occupation"))
            effects.put(table,jdbc.sql("SELECT count(*) FROM ops."+table+" WHERE action_id=:id")
                    .param("id",fixture.id("actionOne")).query(Long.class).single());
        assertThat(loopback.received).isEqualTo(requestsBefore);
        assertThat(attempted.getResolvedException()).withFailMessage(
                "Missing canonical business protection must block: HTTP %s, launched=%s, durable effects=%s",
                attempted.getResponse().getStatus(),response.path("launched").asText(),effects).isInstanceOfSatisfying(
                com.mimococo.marketops.shared.OperationRejectedException.class,
                failure->assertThat(failure.errorCode()).isEqualTo(com.mimococo.marketops.shared.ErrorCode.GUARDRAIL_BLOCKED));
        assertThat(jdbc.sql("SELECT state FROM ops.lc_action WHERE id=:id").param("id",fixture.id("actionOne"))
                .query(String.class).single()).isEqualTo("APPROVED");
        effects.forEach((table,count)->assertThat(count).as(table).isZero());
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
        var json=new tools.jackson.databind.ObjectMapper();
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
        // A transport retry over the same frozen measurement is idempotent and
        // cannot manufacture an Outcome revision without a new source fact.
        mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(request)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT revision_no FROM ops.lc_node_result WHERE plan_id=:plan AND stage='OPERATIONAL'")
                .param("plan",fixture.id("planOne")).query(Integer.class).list()).containsExactly(0);

        UUID originalSummary=UUID.fromString(summary.path("observationId").asText());
        UUID correctedSummary=UUID.randomUUID(),correctedProvenance=UUID.randomUUID();
        Instant correctionObserved=Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        fixture.seed.sql("""
                INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,
                    recorded_by_user_id,evidence_note)
                VALUES(:id,:org,'MANUAL_ENTRY',:observed,clock_timestamp(),:actor,
                    'Synthetic late correction to an official summary')
                """).param("id",correctedProvenance).param("org",fixture.id("organization"))
                .param("observed",java.sql.Timestamp.from(correctionObserved)).param("actor",userId).update();
        fixture.seed.sql("""
                INSERT INTO core.lc_official_summary_observation(id,organization_id,provenance_id,store_id,
                    platform_listing_id,source_fact_key,summary_kind,period_start,period_end,reported_visits,
                    reported_retained_purchases,reported_conversion_label,observed_at,acquired_at,
                    supersedes_fact_id,retention_window_days)
                SELECT :id,organization_id,:provenance,store_id,platform_listing_id,:sourceKey,summary_kind,
                    period_start,period_end,reported_visits,11,'Synthetic corrected absolute ratio',:observed,
                    clock_timestamp(),id,retention_window_days
                FROM core.lc_official_summary_observation WHERE id=:original
                """).param("id",correctedSummary).param("provenance",correctedProvenance)
                .param("sourceKey","corrected-summary:"+correctedSummary)
                .param("observed",java.sql.Timestamp.from(correctionObserved)).param("original",originalSummary).update();
        postListing("/facts/measurement-coverage",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY","sourceCompleteThrough",correctionObserved.toString(),
                "sourceReference","evidence://synthetic/corrected-absolute-ratio",
                "summaryObservationId",correctedSummary.toString()));
        var revisedMeasurement=postListing("/measurements",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY"));
        assertThat(revisedMeasurement.path("primaryRatio").decimalValue()).isEqualByComparingTo("0.11");
        assertThat(jdbc.sql("""
                SELECT prior.canonical_input_digest<>revised.canonical_input_digest
                  AND revised.inputs->'sourceInputs'->>'supersedes_fact_id'=:original
                FROM mart.lc_measurement_lineage prior,mart.lc_measurement_lineage revised
                WHERE prior.measurement_id=:prior AND revised.measurement_id=:revised
                """).param("original",originalSummary.toString())
                .param("prior",UUID.fromString(measurement.path("id").asText()))
                .param("revised",UUID.fromString(revisedMeasurement.path("id").asText()))
                .query(Boolean.class).single()).isTrue();
        var revisedRequest=(tools.jackson.databind.node.ObjectNode)json.readTree(request);
        revisedRequest.put("measurementId",revisedMeasurement.path("id").asText());
        revisedRequest.put("lateFactReference","official-summary-correction:"+correctedSummary);
        mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(revisedRequest.toString()))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT revision_no FROM ops.lc_node_result WHERE plan_id=:plan AND stage='OPERATIONAL' ORDER BY revision_no")
                .param("plan",fixture.id("planOne")).query(Integer.class).list()).containsExactly(0,1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.lc_outcome_revision revision
                  JOIN ops.lc_node_result original ON original.id=revision.original_result_id
                  JOIN ops.lc_node_result revised ON revised.id=revision.revised_result_id
                  WHERE revision.plan_id=:plan AND original.revision_no=0 AND revised.revision_no=1
                    AND original.measurement_id<>revised.measurement_id
                    AND revision.revision_reason='LATE_FACT'
                    AND revision.late_fact_reference=:reference
                """).param("plan",fixture.id("planOne"))
                .param("reference","official-summary-correction:"+correctedSummary)
                .query(Long.class).single()).isEqualTo(1);
        mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace("OPERATIONAL", "SETTLED")))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT outcome_kind FROM ops.work_task_event WHERE organization_id=:org AND event_kind='OUTCOME_OBSERVED'")
                .param("org",fixture.id("organization")).query(String.class).list()).containsExactly("UNKNOWN","UNKNOWN","UNKNOWN");
        assertThat(jdbc.sql("SELECT bool_and(evaluation_evidence->>'planDigest'=p.plan_digest) FROM ops.lc_node_result r JOIN ops.lc_evaluation_plan p ON p.id=r.plan_id WHERE p.id=:plan")
                .param("plan",fixture.id("planOne")).query(Boolean.class).single()).isTrue();
    }

    @Test
    void frozenFixedTrafficOutcomeRunsThroughSignedHttpAndRevisesOnlyForNewQualifiedFacts() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        qualifiedFormalOutcome(true,false);
    }

    @Test
    void equivalentOfficialSummaryRunsTheSameFrozenFormalOutcomeWithoutVisitDetails() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        FormalOutcome outcome=qualifiedFormalOutcome(false,true);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM core.lc_visit_fact v WHERE v.platform_listing_id=:listing
                  AND v.visited_at>=(SELECT min(period_start) FROM core.lc_official_summary_observation
                    WHERE platform_listing_id=:listing)
                  AND v.visited_at<(SELECT max(period_end) FROM core.lc_official_summary_observation
                    WHERE platform_listing_id=:listing)
                """).param("listing",fixture.id("listing")).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(DISTINCT m.evidence_path) FROM ops.lc_node_result r
                  JOIN mart.lc_conversion_measurement m ON m.id=r.measurement_id
                 WHERE r.id=:result AND m.evidence_path='OFFICIAL_SUMMARY'
                """).param("result",outcome.resultId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT p.formal_nodes#>>'{0,comparisonReference,evidencePath}'='OFFICIAL_SUMMARY'
                  AND p.formal_nodes#>>'{0,comparisonReference,state}'='FROZEN_REFERENCE'
                  AND r.evaluation_evidence#>>'{formalTrafficComparison,state}'='QUALIFIED_FORMAL_COMPARISON'
                  AND r.evaluation_evidence#>>'{formalTrafficComparison,criticalGroups,CORE,state}'=
                      'QUALIFIED_INDEPENDENT_COMPARISON'
                FROM ops.lc_node_result r JOIN ops.lc_evaluation_plan p ON p.id=r.plan_id WHERE r.id=:result
                """).param("result",outcome.resultId()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void summaryMissingARequiredCriticalGroupCannotClaimThatProtection() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        FormalOutcome outcome=qualifiedFormalOutcome(false,true,false);

        assertThat(jdbc.sql("""
                SELECT verdict='MET' AND protection_verdict='UNDETERMINED'
                  AND evaluation_evidence#>>'{formalTrafficComparison,state}'='QUALIFIED_FORMAL_COMPARISON'
                  AND evaluation_evidence#>>'{formalTrafficComparison,criticalGroups,CORE,state}'='UNQUALIFIED'
                  AND evaluation_evidence#>'{formalTrafficComparison,qualificationGaps}'
                      @> '["CRITICAL_GROUP_CORE:CRITICAL_GROUP_TARGET_UNQUALIFIED"]'::jsonb
                FROM ops.lc_node_result WHERE id=:result
                """).param("result",outcome.resultId()).query(Boolean.class).single()).isTrue();
    }

    private record FormalOutcome(UUID actionId,UUID resultId) { }

    /** One shared material evaluator journey; unit tests retain the arithmetic boundary matrix. */
    private FormalOutcome qualifiedFormalOutcome(boolean exerciseRevisions) throws Exception {
        return qualifiedFormalOutcome(exerciseRevisions,false);
    }

    private FormalOutcome qualifiedFormalOutcome(boolean exerciseRevisions,boolean officialSummary) throws Exception {
        return qualifiedFormalOutcome(exerciseRevisions,officialSummary,true);
    }

    private FormalOutcome qualifiedFormalOutcome(boolean exerciseRevisions,boolean officialSummary,
                                                   boolean includeTargetCriticalGroup) throws Exception {
        for(var scope:List.of(ActionScopeCode.INTERNAL_FACT_INTAKE,ActionScopeCode.LISTING_ACTION_PREPARE,
                ActionScopeCode.LISTING_MANUAL_VERIFY,ActionScopeCode.LISTING_OUTCOME_EVALUATE))
            users.grantScope(OPERATOR,userId,scope,ResourceScopeType.STORE,fixture.id("store"),null);
        fixture.seed.sql("UPDATE core.store SET timezone='UTC' WHERE id=:id")
                .param("id",fixture.id("store")).update();
        listingIntake.ensureResponsibilityTask(fixture.id("organization"),fixture.id("recommendationOne"),
                "Synthetic qualified frozen Outcome",Instant.now().plusSeconds(86400),Instant.now());

        var json=new tools.jackson.databind.ObjectMapper();
        Instant now=jdbc.sql("SELECT clock_timestamp()").query(java.time.OffsetDateTime.class).single().toInstant();
        Instant frozen=now.minusSeconds(30L*86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant referenceStart=frozen.minusSeconds(29L*86400),referenceEnd=frozen.minusSeconds(15L*86400);
        Instant targetStart=frozen.plusSeconds(86400),targetEnd=frozen.plusSeconds(15L*86400);
        UUID referenceSource=seedFormalProvenance("reference",frozen.minusSeconds(2L*86400));
        UUID targetSource=seedFormalProvenance("target",now.minusSeconds(30));
        if(officialSummary) seedSummaryProfile(true);
        String currentText=jdbc.sql("SELECT description_text FROM core.lc_description_observation WHERE id=:id")
                .param("id",fixture.id("observationOne")).query(String.class).single();
        String targetText=jdbc.sql("SELECT target_text FROM ops.lc_action WHERE id=:id")
                .param("id",fixture.id("actionOne")).query(String.class).single();
        postListing("/facts/display",Map.of("displayState","DISPLAYED","displayedText",currentText,
                "observedAt",referenceStart.minusSeconds(2L*86400).toString(),
                "evidenceReference","evidence://synthetic/formal-reference-display"));
        postListing("/facts/display",Map.of("displayState","DISPLAYED","displayedText",targetText,
                "observedAt",targetStart.minusSeconds(2L*86400).toString(),
                "evidenceReference","evidence://synthetic/formal-target-display"));
        tools.jackson.databind.JsonNode reference;
        if(officialSummary) {
            reference=measureSummaryWindow(referenceStart,referenceEnd,200,56,
                    Map.of("ADVERTISING",Map.of("visits",180,"retained",36),
                            "ORGANIC",Map.of("visits",20,"retained",20)),
                    formalCriticalGroupCounts(),"reference",true);
        } else {
            String referencePrefix="formal-r-"+UUID.randomUUID();
            seedTrafficCohort(referenceSource,referencePrefix,referenceStart,frozen.minusSeconds(2L*86400),
                    180,36,20,20);
            markCriticalGroup(referencePrefix,36);
            reference=measureDetailWindow(referenceStart,referenceEnd,56,200);
        }
        UUID referenceMeasurement=UUID.fromString(reference.path("id").asText());
        Instant referenceAcquired=frozen.minusSeconds(12L*3600),referenceComputed=frozen.minusSeconds(6L*3600);
        fixture.seed.sql("""
                UPDATE mart.lc_conversion_measurement SET acquisition_time=:acquired,computed_at=:computed
                 WHERE id=:id
                """).param("acquired",java.sql.Timestamp.from(referenceAcquired))
                .param("computed",java.sql.Timestamp.from(referenceComputed)).param("id",referenceMeasurement).update();
        fixture.seed.sql("UPDATE mart.lc_measurement_lineage SET recorded_at=:at WHERE measurement_id=:id")
                .param("at",java.sql.Timestamp.from(referenceComputed)).param("id",referenceMeasurement).update();

        Instant accountingEnd=frozen.minusSeconds(10),accountingStart=accountingEnd.minusSeconds(14L*86400);
        UUID historicalMetricSource=seedFormalProvenance("accounting-reference",accountingEnd);
        seedOperationalProtectionPeriod(historicalMetricSource,"formal-reference",accountingStart,accountingEnd,
                frozen.minusSeconds(5),new BigDecimal("1000"),new BigDecimal("2"));
        seedOperationalProtectionPeriod(targetSource,"formal-target",targetStart,targetEnd,now.minusSeconds(5),
                new BigDecimal("1100"),BigDecimal.ONE);
        seedCurrentBusinessProtectionSources(currentBusinessProtectionBasis(),"formal-"+UUID.randomUUID());
        configureFormalOutcomeFixture(json,referenceMeasurement,referenceStart,referenceEnd,referenceAcquired,
                referenceComputed,frozen,accountingStart,accountingEnd);

        int initialAdvertising=exerciseRevisions?20:400,initialAdvertisingRetained=exerciseRevisions?4:364;
        int initialOrganic=exerciseRevisions?180:400,initialOrganicRetained=exerciseRevisions?180:380;
        tools.jackson.databind.JsonNode target;
        if(officialSummary) {
            target=measureSummaryWindow(targetStart,targetEnd,initialAdvertising+initialOrganic,
                    initialAdvertisingRetained+initialOrganicRetained,
                    Map.of("ADVERTISING",Map.of("visits",initialAdvertising,"retained",initialAdvertisingRetained),
                            "ORGANIC",Map.of("visits",initialOrganic,"retained",initialOrganicRetained)),
                    includeTargetCriticalGroup?formalCriticalGroupCounts():Map.of(),"target",
                    includeTargetCriticalGroup);
        } else {
            String initialTargetPrefix="formal-t0-"+UUID.randomUUID();
            seedTrafficCohort(targetSource,initialTargetPrefix,targetStart,now.minusSeconds(20),
                    initialAdvertising,initialAdvertisingRetained,initialOrganic,initialOrganicRetained);
            markCriticalGroup(initialTargetPrefix,initialAdvertisingRetained);
            target=measureDetailWindow(targetStart,targetEnd,initialAdvertisingRetained+initialOrganicRetained,
                    initialAdvertising+initialOrganic);
        }
        if(exerciseRevisions) {
            mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("nodeCode","D14","stage","OPERATIONAL",
                            "measurementId",referenceMeasurement))))
                    .andExpect(status().isBadRequest());
            evaluateFormal(target,null);
            assertLatestFormal("NOT_MET","PASS",false,"QUALIFIED_NOT_TRIGGERED","0.000000");

            seedTrafficCohort(targetSource,"formal-t1-"+UUID.randomUUID(),targetStart,now.minusSeconds(15),380,0,220,0);
            target=measureDetailWindow(targetStart,targetEnd,184,800);
            evaluateFormal(target,"evidence://synthetic/qualified-futility");
            assertLatestFormal("NOT_MET","PASS",true,"TRIGGERED",null);

            seedTrafficCohort(targetSource,"formal-t2-"+UUID.randomUUID(),targetStart,now.minusSeconds(10),360,360,200,200);
            target=measureDetailWindow(targetStart,targetEnd,744,1360);
            evaluateFormal(target,"evidence://synthetic/late-retained-correction");
        } else evaluateFormal(target,null);
        if(!officialSummary || includeTargetCriticalGroup)
            assertLatestFormal("MET","PASS",false,"QUALIFIED_NOT_TRIGGERED",
                    exerciseRevisions?null:"0.634");
        if(exerciseRevisions) {
            assertThat(jdbc.sql("SELECT revision_no FROM ops.lc_node_result WHERE plan_id=:plan AND stage='OPERATIONAL' ORDER BY revision_no")
                    .param("plan",fixture.id("planOne")).query(Integer.class).list()).containsExactly(0,1,2);
            assertThat(jdbc.sql("SELECT late_fact_reference FROM ops.lc_outcome_revision WHERE plan_id=:plan ORDER BY recorded_at")
                    .param("plan",fixture.id("planOne")).query(String.class).list())
                    .containsExactly("evidence://synthetic/qualified-futility","evidence://synthetic/late-retained-correction");
            evaluateFormal(target,"SETTLED",null);
            assertThat(jdbc.sql("SELECT protection_verdict FROM ops.lc_node_result WHERE plan_id=:plan AND stage='SETTLED'")
                    .param("plan",fixture.id("planOne")).query(String.class).single()).isEqualTo("UNDETERMINED");
        }
        UUID result=jdbc.sql("""
                SELECT id FROM ops.lc_node_result WHERE plan_id=:plan AND stage='OPERATIONAL'
                 ORDER BY revision_no DESC LIMIT 1
                """).param("plan",fixture.id("planOne")).query(UUID.class).single();
        return new FormalOutcome(fixture.id("actionOne"),result);
    }

    private UUID seedFormalProvenance(String label,Instant at) {
        UUID id=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,
                    recorded_by_user_id,evidence_note)
                VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:actor,:note)
                """).param("id",id).param("org",fixture.id("organization")).param("at",java.sql.Timestamp.from(at))
                .param("actor",userId).param("note","Qualified formal "+label+" fixture").update();
        return id;
    }

    private void seedTrafficCohort(UUID provenance,String prefix,Instant windowStart,Instant acquired,
                                   int advertising,int advertisingRetained,int organic,int organicRetained) {
        fixture.seed.sql("""
                WITH rows AS (
                  SELECT 'ADVERTISING'::text channel,n FROM generate_series(1,:advertising) n
                  UNION ALL SELECT 'ORGANIC',n FROM generate_series(1,:organic) n)
                INSERT INTO core.lc_visit_fact(id,organization_id,provenance_id,store_id,platform_listing_id,
                    platform_listing_variant_id,source_fact_key,visit_key,visited_at,acquired_at,
                    sellable_at_visit,source_channel)
                SELECT gen_random_uuid(),:org,:source,:store,:listing,:variant,:prefix||'-visit-'||channel||'-'||n,
                    :prefix||'-'||channel||'-'||n,:visited,:acquired,'YES',channel FROM rows
                """).param("advertising",advertising).param("organic",organic).param("org",fixture.id("organization"))
                .param("source",provenance).param("store",fixture.id("store")).param("listing",fixture.id("listing"))
                .param("variant",fixture.id("listingVariant")).param("prefix",prefix)
                .param("visited",java.sql.Timestamp.from(windowStart.plusSeconds(3600)))
                .param("acquired",java.sql.Timestamp.from(acquired)).update();
        fixture.seed.sql("""
                WITH rows AS (
                  SELECT 'ADVERTISING'::text channel,n FROM generate_series(1,:advertising) n
                  UNION ALL SELECT 'ORGANIC',n FROM generate_series(1,:organic) n)
                INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,store_id,platform_listing_variant_id,
                    source_fact_key,native_order_key,occurred_at,sale_stage,retention_window_days,quantity,
                    currency_code,gross_amount,net_amount)
                SELECT gen_random_uuid(),:org,:source,:store,:variant,:prefix||'-sale-'||channel||'-'||n,
                    :prefix||'-order-'||channel||'-'||n,:occurred,'RETAINED',14,1,'RUB',100,100 FROM rows
                """).param("advertising",advertisingRetained).param("organic",organicRetained)
                .param("org",fixture.id("organization")).param("source",provenance).param("store",fixture.id("store"))
                .param("variant",fixture.id("listingVariant")).param("prefix",prefix)
                .param("occurred",java.sql.Timestamp.from(windowStart.plusSeconds(3600))).update();
        fixture.seed.sql("""
                INSERT INTO core.lc_visit_purchase_link(id,organization_id,provenance_id,visit_fact_id,sales_fact_id,
                    link_basis,linked_at)
                SELECT gen_random_uuid(),:org,:source,v.id,s.id,'MANUAL_ENTRY',:linked
                  FROM core.lc_visit_fact v JOIN ledger.sales_fact s ON s.organization_id=v.organization_id
                   AND s.source_fact_key=replace(v.source_fact_key,'-visit-','-sale-')
                 WHERE v.source_fact_key LIKE :prefix||'-visit-%'
                """).param("org",fixture.id("organization")).param("source",provenance)
                .param("linked",java.sql.Timestamp.from(acquired)).param("prefix",prefix).update();
    }

    /** A fixed 30-visit subgroup with the same 4/20 and 10/10 source rates in reference and target. */
    private void markCriticalGroup(String prefix,int advertisingRetained) {
        fixture.seed.sql("""
                UPDATE core.lc_visit_fact SET key_group_code='CORE'
                 WHERE source_fact_key IN (
                   SELECT :prefix||'-visit-ADVERTISING-'||n FROM generate_series(1,4) n
                   UNION ALL SELECT :prefix||'-visit-ADVERTISING-'||n
                     FROM generate_series(:firstNonretained,:lastNonretained) n
                   UNION ALL SELECT :prefix||'-visit-ORGANIC-'||n FROM generate_series(1,10) n)
                """).param("prefix",prefix).param("firstNonretained",advertisingRetained+1)
                .param("lastNonretained",advertisingRetained+16).update();
    }

    private tools.jackson.databind.JsonNode measureDetailWindow(Instant from,Instant to,long links,long visits) throws Exception {
        postListing("/facts/measurement-coverage",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",14,"evidencePath","DETAIL","sourceCompleteThrough",to.plusSeconds(14L*86400).toString(),
                "sourceReference","evidence://synthetic/formal-detail-"+UUID.randomUUID(),
                "expectedVisitRows",visits,"expectedLinkRows",links));
        return postListing("/measurements",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",14,"evidencePath","DETAIL"));
    }

    private tools.jackson.databind.JsonNode measureSummaryWindow(Instant from,Instant to,long visits,long retained,
            Map<String,?> sourceStrata,Map<String,?> criticalGroups,String label,
            boolean criticalGroupsQualified) throws Exception {
        Instant completeThrough=to.plusSeconds(14L*86400);
        var request=new java.util.LinkedHashMap<String,Object>();
        request.put("periodStart",from.toString());request.put("periodEnd",to.toString());
        request.put("visits",visits);request.put("retainedPurchases",retained);request.put("retentionDays",14);
        request.put("label","Synthetic equivalent formal "+label);request.put("observedAt",completeThrough.toString());
        request.put("sourceMethodInputVersion",1);request.put("sourceStrata",sourceStrata);
        request.put("criticalGroupSourceStrata",criticalGroups);
        var summary=postListing("/facts/official-summary",request);
        postListing("/facts/measurement-coverage",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",14,"evidencePath","OFFICIAL_SUMMARY","sourceCompleteThrough",completeThrough.toString(),
                "sourceReference","evidence://synthetic/formal-summary-"+label,
                "summaryObservationId",summary.path("observationId").asText()));
        var measured=postListing("/measurements",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",14,"evidencePath","OFFICIAL_SUMMARY"));
        assertThat(measured.path("sourceStratified").asBoolean()).isTrue();
        assertThat(measured.path("ratioState").asText()).isEqualTo("DEFINED");
        assertThat(jdbc.sql("""
                SELECT inputs->>'sourceStrataQualified'='true'
                  AND (inputs->>'criticalGroupSourceStrataQualified')::boolean=:groupsQualified
                FROM mart.lc_measurement_lineage WHERE measurement_id=:id
                """).param("groupsQualified",criticalGroupsQualified)
                .param("id",UUID.fromString(measured.path("id").asText()))
                .query(Boolean.class).single()).isTrue();
        return measured;
    }

    private static Map<String,?> formalCriticalGroupCounts() {
        return Map.of("CORE",Map.of(
                "ADVERTISING",Map.of("visits",20,"retained",4),
                "ORGANIC",Map.of("visits",10,"retained",10)));
    }

    private void seedOperationalProtectionPeriod(UUID provenance,String namespace,Instant from,Instant to,
                                                   Instant computed,BigDecimal profit,BigDecimal returns) {
        seedProtectionPeriod(provenance,namespace,"D14",from,to,computed,profit,returns);
    }

    private void seedProtectionPeriod(UUID provenance,String namespace,String window,Instant from,Instant to,
                                        Instant computed,BigDecimal profit,BigDecimal returns) {
        UUID run=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO mart.calculation_run(id,organization_id,trigger_kind,scope_kind,store_ref_id,
                    window_code,period_start,period_end,definition_set_digest,state,subject_count,value_count,
                    correlation_id,started_at,completed_at,requested_by_user_id)
                VALUES(:id,:org,'MANUAL','STORE',:store,:window,:from,:to,:digest,'SUCCEEDED',1,4,:correlation,
                    :started,:completed,:actor)
                """).param("id",run).param("org",fixture.id("organization")).param("store",fixture.id("store"))
                .param("window",window)
                .param("from",java.sql.Timestamp.from(from)).param("to",java.sql.Timestamp.from(to))
                .param("digest",com.mimococo.marketops.shared.Digest.ofText(namespace))
                .param("correlation",namespace).param("started",java.sql.Timestamp.from(computed.minusSeconds(1)))
                .param("completed",java.sql.Timestamp.from(computed)).param("actor",userId).update();
        var values=new java.util.LinkedHashMap<String,BigDecimal>();
        values.put("OPERATIONAL_CONTRIBUTION_PROFIT",profit);values.put("RETURN_UNITS",returns);
        values.put("COMPLETED_UNITS",new BigDecimal("100"));values.put("REQUIRED_PROFIT_PER_UNIT",new BigDecimal("5"));
        values.forEach((code,amount)->{
            UUID value=UUID.randomUUID();
            fixture.seed.sql("""
                    INSERT INTO mart.metric_value(id,organization_id,calculation_run_id,metric_code,
                        definition_version,subject_kind,subject_id,window_code,period_start,period_end,value_state,
                        numeric_value,currency_code,confidence_state,estimated,oldest_source_time,freshness_seconds,
                        input_digest,computed_at)
                    SELECT :id,:org,:run,:code,max(definition_version),'PLATFORM_LISTING_VARIANT',:subject,:window,
                        :from,:to,'AVAILABLE',:amount,:currency,'CANONICAL_CONFIRMED',false,:from,0,:digest,:computed
                      FROM mart.metric_definition WHERE metric_code=:code
                    """).param("id",value).param("org",fixture.id("organization")).param("run",run).param("code",code)
                    .param("window",window)
                    .param("subject",fixture.id("listingVariant")).param("from",java.sql.Timestamp.from(from))
                    .param("to",java.sql.Timestamp.from(to)).param("amount",amount)
                    .param("currency",code.contains("PROFIT")?"RUB":null)
                    .param("digest",com.mimococo.marketops.shared.Digest.ofText(namespace+":"+code))
                    .param("computed",java.sql.Timestamp.from(computed)).update();
            fixture.seed.sql("INSERT INTO mart.metric_input_reference(id,metric_value_id,reference_kind,reference_id) VALUES(:id,:value,'FACT_PROVENANCE',:source)")
                    .param("id",UUID.randomUUID()).param("value",value).param("source",provenance).update();
        });
    }

    private void configureFormalOutcomeFixture(tools.jackson.databind.ObjectMapper json,UUID referenceMeasurement,
            Instant referenceStart,Instant referenceEnd,Instant referenceAcquired,Instant referenceComputed,
            Instant frozen,Instant accountingStart,Instant accountingEnd) throws Exception {
        String digest=jdbc.sql("SELECT canonical_input_digest FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",referenceMeasurement).query(String.class).single();
        var reference=json.createObjectNode();
        reference.put("windowDurationDays",14);reference.put("retentionDays",14);
        reference.put("referenceMeasurementId",referenceMeasurement.toString());
        reference.put("referenceWindowStart",referenceStart.toString());reference.put("referenceWindowEnd",referenceEnd.toString());
        String evidencePath=jdbc.sql("SELECT evidence_path FROM mart.lc_conversion_measurement WHERE id=:id")
                .param("id",referenceMeasurement).query(String.class).single();
        reference.put("definitionVersion",1);reference.put("evidencePath",evidencePath);reference.put("canonicalInputDigest",digest);
        reference.put("sourceTime",referenceEnd.plusSeconds(14L*86400).toString());
        reference.put("acquisitionTime",referenceAcquired.toString());reference.put("computedAt",referenceComputed.toString());
        reference.set("sourceWeights",json.valueToTree(Map.of("ADVERTISING",new BigDecimal("0.9"),"ORGANIC",new BigDecimal("0.1"))));
        var frozenCriticalGroup=json.createObjectNode();
        frozenCriticalGroup.put("state","FROZEN_GROUP_REFERENCE");
        frozenCriticalGroup.set("sourceWeights",json.valueToTree(Map.of(
                "ADVERTISING",new BigDecimal("0.666666666666666666666667"),
                "ORGANIC",new BigDecimal("0.333333333333333333333333"))));
        reference.putObject("criticalGroups").set("CORE",frozenCriticalGroup);
        reference.put("state","FROZEN_REFERENCE");
        reference.set("qualificationGaps",json.createArrayNode());
        var node=json.createObjectNode();node.put("nodeCode","D14");node.put("maturityDays",14);
        node.put("method","EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1");node.put("threshold","0.05");
        node.set("methodParameters",json.valueToTree(Map.of("familyAlpha","0.05","nodeAlpha","0.05",
                "samplingModel","INDEPENDENT_BERNOULLI_VISITS","qualificationRef","fixture://formal-fixed-traffic")));
        node.set("schedule",json.valueToTree(Map.of("windowStartOffsetDays",1,"windowEndOffsetDays",15,
                "notBeforeOffsetDays",29,"lastOffsetDays",43)));
        node.set("protectionComparison",json.valueToTree(Map.of("method","CANONICAL_ACCOUNTING_CHANGE_V1",
                "qualificationRef","fixture://formal-accounting","referencePeriodStart",accountingStart.toString(),
                "referencePeriodEnd",accountingEnd.toString())));node.set("comparisonReference",reference);
        var nodes=json.createArrayNode().add(node);
        var stop=json.valueToTree(Map.of("nodeCode","D14","trigger","QUALIFIED_FUTILITY","method",Map.of(
                "code","EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1","upperBoundRequired",true,
                "minimumEffect","0.03","qualificationRef","fixture://formal-futility")));
        var dependencies=json.createObjectNode();dependencies.put("model","LC_ACTION_RULE_DEPENDENCIES_1");
        dependencies.put("purpose","LISTING_CONVERSION");dependencies.put("actionKind","LISTING_DESCRIPTION_CHANGE");
        var values=dependencies.putObject("values");
        values.set("NON_WORSENING_PROFIT_BOUND",json.valueToTree(Map.of("numeric",BigDecimal.ZERO,"unit","RATIO")));
        values.set("NON_WORSENING_RETURN_BOUND",json.valueToTree(Map.of("numeric",new BigDecimal("0.02"),"unit","RATIO")));
        dependencies.set("protectionScopeBasis",json.valueToTree(Map.of("evidenceReference","fixture://formal-protection-scope",
                "linkedProfitScopes",List.of(),"criticalReturnVariantIds",List.of(fixture.id("listingVariant").toString()))));
        dependencies.set("supplyScenarios",json.valueToTree(List.of(Map.of("code","FINITE_CURRENT_SUPPLY",
                "productVariantId",fixture.id("productVariant").toString(),"companyDailyFulfillmentUnits",100,
                "coverageDays",7,"evidenceReference","fixture://accepted-current-supply"))));
        try(var connection=fixture.migration.getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                statement.execute("ALTER TABLE ops.lc_action DISABLE TRIGGER lc_action_captures_calibration_dependencies");
                try(var update=connection.prepareStatement("UPDATE ops.lc_action SET calibration_dependencies=?::jsonb WHERE id=?")) {
                    update.setString(1,dependencies.toString());update.setObject(2,fixture.id("actionOne"));update.executeUpdate();
                }
                statement.execute("ALTER TABLE ops.lc_action ENABLE TRIGGER lc_action_captures_calibration_dependencies");
                connection.commit();
            } catch(Exception failure) { connection.rollback();throw failure; }
        }
        fixture.seed.sql("""
                UPDATE ops.lc_evaluation_plan SET formal_nodes=CAST(:nodes AS jsonb),stop_rule=CAST(:stop AS jsonb),
                    critical_groups=CAST(:groups AS jsonb),cross_period_window_days=0,frozen_at=:frozen,latest_boundary=:boundary
                 WHERE id=:id
                """).param("nodes",nodes.toString()).param("stop",stop.toString())
                .param("groups",json.writeValueAsString(List.of(Map.of("code","CORE","bound",new BigDecimal("0.50")))))
                .param("frozen",java.sql.Timestamp.from(frozen)).param("boundary",java.sql.Timestamp.from(frozen.plusSeconds(43L*86400)))
                .param("id",fixture.id("planOne")).update();
        UUID launchId=UUID.randomUUID();
        assertThat(fixture.launch(launchId,"actionOne",fixture.id("ownerUser"))
                .path("launched").asBoolean()).isTrue();
        fixture.seed.sql("UPDATE ops.lc_launch SET launched_at=:at WHERE id=:id")
                .param("at",java.sql.Timestamp.from(frozen.plusSeconds(12L*3600)))
                .param("id",launchId).update();
    }

    private void evaluateFormal(tools.jackson.databind.JsonNode measurement,String lateReference) throws Exception {
        evaluateFormal(measurement,"OPERATIONAL",lateReference);
    }

    private void evaluateFormal(tools.jackson.databind.JsonNode measurement,String stage,String lateReference) throws Exception {
        var request=new java.util.LinkedHashMap<String,Object>();request.put("nodeCode","D14");request.put("stage",stage);
        request.put("measurementId",measurement.path("id").asText());if(lateReference!=null) request.put("lateFactReference",lateReference);
        mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(new tools.jackson.databind.ObjectMapper().writeValueAsString(request)))
                .andDo(result->{ if(result.getResolvedException()!=null) throw result.getResolvedException(); })
                .andExpect(status().isOk());
    }

    private void assertLatestFormal(String verdict,String protection,boolean stopped,String futility,String difference) {
        String evidence=jdbc.sql("""
                SELECT evaluation_evidence::text FROM ops.lc_node_result WHERE plan_id=:plan AND stage='OPERATIONAL'
                 ORDER BY revision_no DESC LIMIT 1
                """).param("plan",fixture.id("planOne")).query(String.class).single();
        var row=jdbc.sql("""
                SELECT verdict||':'||protection_verdict||':'||stop_triggered FROM ops.lc_node_result
                 WHERE plan_id=:plan AND stage='OPERATIONAL' ORDER BY revision_no DESC LIMIT 1
                """).param("plan",fixture.id("planOne")).query(String.class).single();
        String protectionVector=jdbc.sql("""
                SELECT protection_vector::text FROM ops.lc_node_result
                 WHERE plan_id=:plan AND stage='OPERATIONAL' ORDER BY revision_no DESC LIMIT 1
                """).param("plan",fixture.id("planOne")).query(String.class).single();
        assertThat(row).withFailMessage("Unexpected formal outcome %s; protection vector: %s; evaluation evidence: %s",
                        row,protectionVector,evidence)
                .isEqualTo(verdict+":"+protection+":"+stopped);
        var parsed=new tools.jackson.databind.ObjectMapper().readTree(evidence);
        assertThat(parsed.at("/formalTrafficComparison/state").asText()).isEqualTo("QUALIFIED_FORMAL_COMPARISON");
        assertThat(parsed.at("/formalTrafficComparison/criticalGroups/CORE/state").asText())
                .isEqualTo("QUALIFIED_INDEPENDENT_COMPARISON");
        assertThat(parsed.at("/formalTrafficComparison/criticalGroups/CORE/observedDifference").decimalValue())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(parsed.at("/formalTrafficComparison/criticalGroups/CORE/verdict").asText()).isEqualTo("PASS");
        String criticalVariant=fixture.id("listingVariant").toString();
        assertThat(parsed.at("/canonicalProtectionInputs/criticalReturnInputs/"+criticalVariant).isMissingNode()).isFalse();
        assertThat(parsed.at("/canonicalAccountingComparison/referenceInputs/criticalReturnInputs/"+criticalVariant).isMissingNode()).isFalse();
        assertThat(jdbc.sql("""
                SELECT protection_vector->>'CRITICAL_GROUP_CORE'='PASS'
                  AND protection_vector->>'CRITICAL_VARIANT_RETURN'='PASS'
                  AND protection_vector->> :variantKey='PASS'
                FROM ops.lc_node_result WHERE plan_id=:plan AND stage='OPERATIONAL'
                 ORDER BY revision_no DESC LIMIT 1
                """).param("variantKey","CRITICAL_VARIANT_RETURN:"+criticalVariant)
                .param("plan",fixture.id("planOne")).query(Boolean.class).single()).isTrue();
        assertThat(parsed.at("/futility/state").asText()).isEqualTo(futility);
        assertThat(parsed.at("/canonicalAccountingComparison/state").asText()).isEqualTo("ACCOUNTING_COMPARISON_COMPUTED");
        if(difference!=null) assertThat(parsed.at("/formalTrafficComparison/observedDifference").decimalValue())
                .isEqualByComparingTo(difference);
    }

    @Test
    void concurrentIdenticalOutcomeRequestsRetainOneResultWithoutDuplicateSideEffects() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_OUTCOME_EVALUATE,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        listingIntake.ensureResponsibilityTask(fixture.id("organization"),fixture.id("recommendationOne"),
                "Synthetic concurrent Outcome responsibility",Instant.now().plusSeconds(86400),Instant.now());
        var before=businessCounts();
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
                .param("plan",fixture.id("planOne")).query(Integer.class).list()).containsExactly(0);
        assertThat(count("ops.lc_outcome_revision")).isEqualTo(before.get("revisions"));
        assertThat(count("mart.calculation_run")).isEqualTo(before.get("runs")+1);
        assertThat(count("ops.work_task_event")).isEqualTo(before.get("taskEvents")+1);
        var afterConcurrent=businessCounts();
        mvc.perform(post(route).header(HttpHeaders.AUTHORIZATION,token)
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk());
        assertThat(businessCounts()).isEqualTo(afterConcurrent);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"SYNC","ASYNC","CRASH_AFTER_APPLY"})
    void oneSignedLaunchRunsRealWorkerAdapterCustodyReadbackAndReturnsExecutionToConsole(String scenario) throws Exception {
        exerciseConnectedDescriptionLaunch(scenario);
    }

    private record ConnectedDescriptionLaunch(UUID actionId,UUID recommendationId,UUID approvalId,UUID commandId) { }

    private ConnectedDescriptionLaunch exerciseConnectedDescriptionLaunch(String scenario) throws Exception {
        var json=new tools.jackson.databind.ObjectMapper();
        fixture.seed.sql("""
                UPDATE platform.capability_operation SET description_response_binding='{
                    "schema":"DESCRIPTION_RESPONSE_IDENTITY_V1","evidenceRef":"fixture://protocol",
                    "mode":"EXACT_OBJECT","selection":"ITEMS","payloadPointer":"/items",
                    "listingKeyPointer":"/offer_id","listingKeyType":"string"}'::jsonb
                 WHERE capability_id=:id
                """).param("id",fixture.id("capability")).update();
        fixture.seed.sql("""
                UPDATE platform.capability_operation SET description_request_guard='{
                    "schema":"DESCRIPTION_REQUEST_V1","evidenceRef":"fixture://protocol",
                    "mutationSemantics":"PARTIAL_ATTRIBUTE","markingPolicy":"NOT_APPLICABLE",
                    "body":{"offer_id":{"$bind":"LISTING_KEY","$type":"string"},
                      "attributes":[{"id":{"$bind":"ATTRIBUTE_KEY","$type":"string"},
                        "values":[{"value":{"$bind":"DESCRIPTION_TEXT","$type":"string"}}]}],"kizMarked":false}}
                    '::jsonb WHERE capability_id=:id AND operation IN ('APPLY','RESTORE')
                """).param("id",fixture.id("capability")).update();
        fixture.seed.sql("""
                INSERT INTO platform.platform_api_profile(platform_code,base_url,request_timeout_ms,max_response_bytes,
                    verification_state,last_verified_at,evidence_ref,verified_source_title,owner_label,status,created_at,updated_at)
                SELECT platform_code,'https://example.invalid',5000,8192,'VERIFIED',now(),'fixture://protocol',
                    'Synthetic connected protocol','fixture','ACTIVE',now(),now() FROM platform.platform_capability WHERE id=:id
                """).param("id",fixture.id("capability")).update();
        UUID header=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO platform.platform_auth_header(id,platform_code,header_name,value_source,value_template,credential_purpose,
                    ordinal,verification_state,last_verified_at,evidence_ref,verified_source_title,owner_label,status,created_at,updated_at)
                SELECT :id,platform_code,'X-Fixture-Description','LITERAL','synthetic','CONTENT_WRITE',99,'VERIFIED',now(),
                    'fixture://protocol','Synthetic connected protocol','fixture','ACTIVE',now(),now()
                 FROM platform.platform_capability WHERE id=:capability
                """).param("id",header).param("capability",fixture.id("capability")).update();
        if (scenario.equals("ASYNC")) configureConnectedAsyncProtocol();
        // This is an isolated fabricated attestation of a fictional protocol, never real account evidence.
        fixture.seed.sql("""
                INSERT INTO platform.registry_verification_case(id,organization_id,marketplace_account_id,capability_id,
                    endpoint_ids,auth_header_ids,official_source_url,official_source_sha256,account_evidence_ref,
                    account_evidence_sha256,evidence_class,tested_at,valid_until,submitted_by_user_id,reviewed_by_user_id,
                    reviewed_at,state,configuration_snapshot,submitted_configuration_snapshot)
                VALUES(gen_random_uuid(),:org,:account,:capability,ARRAY(SELECT endpoint_id FROM platform.capability_operation WHERE capability_id=:capability),
                    ARRAY[CAST(:header AS uuid)],'https://example.invalid/synthetic',:digest,'evidence://synthetic/never-a-real-account',
                    :digest,'REAL_ACCOUNT',now()-interval '1 minute',now()+interval '1 day',:author,:reviewer,now(),'APPROVED',
                    platform.registry_configuration_snapshot(:capability),platform.registry_configuration_snapshot(:capability))
                """).param("org",fixture.id("organization")).param("account",fixture.id("account")).param("capability",fixture.id("capability"))
                .param("header",header).param("digest","a".repeat(64)).param("author",fixture.id("executorUser"))
                .param("reviewer",fixture.id("ownerUser")).update();
        String nativeKey=jdbc.sql("SELECT native_listing_key FROM core.platform_listing WHERE id=:id")
                .param("id",fixture.id("listing")).query(String.class).single();
        loopback.open(nativeKey,ListingConversionFixture.TARGET_TEXT_ONE,scenario);
        var approved=prepareApprovedApiCorrection(ListingConversionFixture.TARGET_TEXT_ONE,
                BigDecimal.ZERO,true);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_LAUNCH,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        String route="/api/v1/console/listing/actions/"+approved.actionId()+"/launch";
        var response=mvc.perform(post(route).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"axes\":{}}" )).andExpect(status().isOk()).andExpect(jsonPath("$.launched").value(true)).andReturn();
        UUID command=UUID.fromString(json.readTree(response.getResponse().getContentAsString()).path("commandId").asText());
        assertThat(fixture.gateReasons(command)).contains("PRODUCTION_WRITE_DISABLED");
        assertThat(loopback.received).isEmpty();
        // Only this disposable database's fictional envelope is open, and the transport can reach only its own server.
        fixture.seed.sql("UPDATE ops.lc_gate_authority SET production_write_enabled=true WHERE id=:id")
                .param("id",fixture.id("gateAuthority")).update();
        assertThat(fixture.gateReasons(command)).isEmpty();
        var worker=applicationContext.getBean("listingDescriptionCommandWorker");
        applicationContext.getBean(com.mimococo.marketops.marketplaceintegration.internal.config.ListingDescriptionWriteProperties.class)
                .setRetryDelaySeconds(1);
        if (scenario.equals("CRASH_AFTER_APPLY")) {
            assertThatThrownBy(()->runConnectedWorker(applicationContext.getBean("listingDescriptionCommandWorker"))).isInstanceOf(ListingDescriptionLoopback.SimulatedProcessLoss.class);
            assertThat(jdbc.sql("SELECT outcome_class FROM ops.lc_description_command_attempt WHERE command_id=:id")
                    .param("id",command).query(String.class).single()).isEqualTo("IN_FLIGHT");
            fixture.seed.sql("UPDATE ops.lc_description_command SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                    .param("id",command).update();
            // A newly constructed worker uses the committed database; no in-memory command state is carried over.
            worker=applicationContext.getAutowireCapableBeanFactory().createBean(Class.forName(
                    "com.mimococo.marketops.marketplaceintegration.internal.application.ListingDescriptionCommandWorker"));
            assertThat(runConnectedWorker(worker)).isZero();
            mvc.perform(get("/api/v1/console/listing-description-commands/"+command).header(HttpHeaders.AUTHORIZATION,bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("UNKNOWN_REQUIRES_READBACK"));
            assertThat(loopback.received).hasSize(1);
            users.grantScope(OPERATOR,userId,ActionScopeCode.COMMAND_RESOLVE,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
            mvc.perform(post("/api/v1/console/listing-description-commands/"+command+"/readback")
                    .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Read back the interrupted fictional command without resubmitting it\"}"))
                    .andExpect(status().isOk());
            assertThat(runConnectedWorker(worker)).isEqualTo(1);
        } else {
            assertThat(runConnectedWorker(worker)).isEqualTo(1);
            if (scenario.equals("ASYNC")) {
                assertThat(loopback.received).hasSize(1);
                mvc.perform(get("/api/v1/console/listing-description-commands/"+command).header(HttpHeaders.AUTHORIZATION,bearer()))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PLATFORM_PENDING"))
                        .andExpect(jsonPath("$.executionReceipts").isEmpty());
                fixture.seed.sql("UPDATE ops.approval_decision SET scope_expires_at=clock_timestamp()-interval '1 microsecond' WHERE id=:id")
                        .param("id",approved.approvalId()).update();
                for (int poll=0;poll<2;poll++) {
                    advanceConnectedCommandDue(command);
                    assertThat(jdbc.sql("SELECT clock_timestamp()>scope_expires_at FROM ops.approval_decision WHERE id=:id")
                            .param("id",approved.approvalId()).query(Boolean.class).single()).isTrue();
                    assertThat(runConnectedWorker(worker)).isEqualTo(1);
                }
            }
        }
        boolean proven=!scenario.equals("CRASH_AFTER_APPLY");
        mvc.perform(get("/api/v1/console/listing-description-commands/"+command).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("READBACK_MATCHED"))
                .andExpect(jsonPath("$.executionReceipts[0].executionState").value(proven?"MANAGEMENT_VERIFIED":"NATIVE_COMPLETION_UNPROVEN"));
        applicationContext.getBean(com.mimococo.marketops.operationsworkflow.ListingExecutionJournal.class).deliverPending(10);
        mvc.perform(get("/api/v1/console/listing/actions/"+approved.actionId()).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value(proven?"VERIFIED":"LAUNCHED"));
        mvc.perform(get("/api/v1/console/listing-description-commands/"+command).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.executionReceipts[0].taskEventId").isString());
        if (scenario.equals("ASYNC")) assertThat(loopback.received).containsExactly("POST /fixture/descriptions",
                "POST /fixture/task-info","POST /fixture/task-info","GET /fixture/descriptions/"+nativeKey);
        else assertThat(loopback.received).containsExactly("POST /fixture/descriptions","GET /fixture/descriptions/"+nativeKey);
        var wire=json.readTree(new String(loopback.bodies.getFirst(),java.nio.charset.StandardCharsets.UTF_8));
        assertThat(wire.path("offer_id").asString()).isEqualTo(nativeKey);
        assertThat(wire.path("attributes").get(0).path("values").get(0).path("value").asString()).isEqualTo(ListingConversionFixture.TARGET_TEXT_ONE);
        var custody=applicationContext.getBean(com.mimococo.marketops.marketplaceintegration.RawCustody.class);
        for (UUID content:jdbc.sql("SELECT raw_content_id FROM raw.lc_description_response_observation WHERE command_id=:id")
                .param("id",command).query(UUID.class).list()) assertThat(custody.readById(content)).isPresent();
        mvc.perform(post(route).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"axes\":{}}" )).andExpect(status().isConflict());
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_description_command WHERE action_id=:id")
                .param("id",approved.actionId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(loopback.received).hasSize(scenario.equals("ASYNC")?4:2);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_evaluation_plan WHERE action_id=:id")
                .param("id",approved.actionId()).query(Integer.class).single()).isZero();
        loopback.stop();
        return new ConnectedDescriptionLaunch(approved.actionId(),approved.recommendationId(),approved.approvalId(),command);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"SYNC","ASYNC","CRASH","LATER_CHANGE","MISSING_VERSION","EXPIRED_APPROVAL","VERSION_CONFLICT"})
    void exactRestorationUsesNewPreparationIndependentReviewApprovalAndConditionalWorker(String scenario) throws Exception {
        // This positive fixture budgets for both the original residual occupation and restoration.
        // Neither obligation is released merely to make an opposite action fit.
        fixture.seed.sql("UPDATE ops.lc_exposure_allowance SET limit_value=2 WHERE id=:id")
                .param("id",fixture.id("allowanceConcurrent")).update();
        // Establish the real isolated source execution through the same connected chain.
        var sourceLaunch=exerciseConnectedDescriptionLaunch("SYNC");
        fixture.seed.sql("UPDATE ops.approval_decision SET scope_expires_at=clock_timestamp()-interval '1 microsecond' WHERE id=:id")
                .param("id",sourceLaunch.approvalId()).update();
        var json=new tools.jackson.databind.ObjectMapper();
        UUID source=sourceLaunch.commandId();
        var restorationBasis=exactRestorationPurposeBasis();
        for (var scope:List.of(ActionScopeCode.LISTING_ACTION_PREPARE,ActionScopeCode.COMMAND_RESOLVE))
            users.grantScope(OPERATOR,userId,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        mvc.perform(post("/api/v1/console/listing-description-commands/"+source+"/compensation")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"An opposite old approval cannot authorize restoration\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/console/listing/health/listings/"+fixture.id("listing")+"/facts/description")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text",ListingConversionFixture.TARGET_TEXT_ONE,"languageCode","ru",
                    "kizMarkedDeclared",false,"note","Synthetic operator observation, not official provider provenance"))))
                .andExpect(status().isOk());
        var candidateResponse=mvc.perform(post("/api/v1/console/listing/actions/candidates")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("listingId",fixture.id("listing"),"candidateKind","CONTENT_DESCRIPTION",
                    "roundKey","exact-restoration","evidenceReferences",List.of("evidence://synthetic/restoration")))))
                .andExpect(status().isOk()).andReturn();
        String candidate=json.readTree(candidateResponse.getResponse().getContentAsString()).path("id").asText();
        for (Map<String,Object> invalid:List.<Map<String,Object>>of(
                Map.of("executionPath","API","restoresCommandId",source,"kizMarkedDeclared",false),
                Map.of("executionPath","API","restoresCommandId",UUID.randomUUID(),"kizMarkedDeclared",false,
                        "purpose","DESCRIPTION_CORRECTION","purposeBasis",restorationBasis),
                Map.of("executionPath","API","restoresCommandId",source,"targetText","","kizMarkedDeclared",false,
                        "purpose","DESCRIPTION_CORRECTION","purposeBasis",restorationBasis))) {
            mvc.perform(post("/api/v1/console/listing/actions/candidates/"+candidate+"/prepare")
                    .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(invalid))).andExpect(status().isConflict());
        }
        var prepared=mvc.perform(post("/api/v1/console/listing/actions/candidates/"+candidate+"/prepare")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("executionPath","API","restoresCommandId",source,
                    "kizMarkedDeclared",false,"exposureShare",new java.math.BigDecimal("0.01"),
                    "purpose","DESCRIPTION_CORRECTION","purposeBasis",restorationBasis))))
                .andExpect(result->assertThat(result.getResponse().getStatus()).withFailMessage("Restoration preparation: %s",result.getResolvedException()).isEqualTo(200))
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.targetText").value(ListingConversionFixture.PRIOR_TEXT_ONE))
                .andExpect(jsonPath("$.restoresCommandId").value(source.toString())).andReturn();
        UUID action=UUID.fromString(json.readTree(prepared.getResponse().getContentAsString()).path("id").asText());
        UUID recommendation=jdbc.sql("SELECT recommendation_id FROM ops.lc_action WHERE id=:id")
                .param("id",action).query(UUID.class).single();
        mvc.perform(post("/api/v1/console/listing/actions/"+action+"/launch")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"axes\":{}}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_description_command WHERE action_id=:id")
                .param("id",action).query(Integer.class).single()).isZero();
        String authorToken=bearer();
        subject="restoration-reviewer-"+UUID.randomUUID();
        UUID reviewer=users.provision(OPERATOR,fixture.id("organization"),providerId,subject,null,"Restoration reviewer",null).id();
        jdbc.sql("UPDATE iam.user_account SET credentials_valid_from=now()-interval '1 hour' WHERE id=:id").param("id",reviewer).update();
        users.assignRole(OPERATOR,reviewer,BusinessRoleCode.OWNER,null);
        for(var scope:List.of(ActionScopeCode.LISTING_ACTION_REVIEW,ActionScopeCode.LISTING_ACTION_APPROVE_MATERIAL,
                ActionScopeCode.LISTING_ACTION_APPROVE_ORDINARY,ActionScopeCode.LISTING_CONVERSION_VIEW,
                ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW))
            users.grantScope(OPERATOR,reviewer,scope,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        mvc.perform(post("/api/v1/console/listing/actions/"+action+"/review")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(meaningReviewRequest(action,true)))
                .andExpect(status().isOk());
        long version=jdbc.sql("SELECT version FROM ops.recommendation WHERE id=:id").param("id",recommendation).query(Long.class).single();
        mvc.perform(post("/api/v1/console/workflow/recommendations/"+recommendation+"/approval")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion",version,"reason","New exact restoration business approval"))))
                .andExpect(result->assertThat(result.getResponse().getStatus()).withFailMessage("Restoration approval: %s",result.getResolvedException()).isEqualTo(200));
        if (scenario.equals("ASYNC")) configureConnectedAsyncProtocol();
        fixture.seed.sql("UPDATE platform.capability_operation SET conditional_write_header='If-Match' WHERE capability_id=:id AND operation='RESTORE'")
                .param("id",fixture.id("capability")).update();
        fixture.seed.sql("UPDATE platform.capability_operation SET version_token_header='etag' WHERE capability_id=:id AND operation='READBACK'")
                .param("id",fixture.id("capability")).update();
        fixture.seed.sql("UPDATE platform.registry_verification_case SET endpoint_ids=ARRAY(SELECT endpoint_id FROM platform.capability_operation WHERE capability_id=:id),configuration_snapshot=platform.registry_configuration_snapshot(capability_id),submitted_configuration_snapshot=platform.registry_configuration_snapshot(capability_id) WHERE capability_id=:id")
                .param("id",fixture.id("capability")).update();
        var launched=mvc.perform(post("/api/v1/console/listing/actions/"+action+"/launch")
                .header(HttpHeaders.AUTHORIZATION,authorToken).contentType(MediaType.APPLICATION_JSON).content("{\"axes\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.launched").value(true)).andReturn();
        UUID restored=UUID.fromString(json.readTree(launched.getResponse().getContentAsString()).path("commandId").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_exposure_occupation WHERE allowance_id=:id AND state<>'RELEASED'")
                .param("id",fixture.id("allowanceConcurrent")).query(Integer.class).single()).isEqualTo(2);
        assertThat(restored).isNotEqualTo(source);
        String nativeKey=jdbc.sql("SELECT native_listing_key FROM core.platform_listing WHERE id=:id")
                .param("id",fixture.id("listing")).query(String.class).single();
        loopback.openRestoration(nativeKey,ListingConversionFixture.TARGET_TEXT_ONE,ListingConversionFixture.PRIOR_TEXT_ONE,scenario);
        assertThat(jdbc.sql("SELECT scope_expires_at<clock_timestamp() FROM ops.approval_decision WHERE id=:id")
                .param("id",sourceLaunch.approvalId()).query(Boolean.class).single()).isTrue();
        UUID newApproval=jdbc.sql("SELECT approval_decision_id FROM ops.lc_description_command WHERE id=:id")
                .param("id",restored).query(UUID.class).single();
        assertThat(newApproval).isNotEqualTo(sourceLaunch.approvalId());
        assertThatThrownBy(()->jdbc.sql("UPDATE ops.lc_description_command SET approval_decision_id=:old WHERE id=:id")
                .param("old",sourceLaunch.approvalId()).param("id",restored).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        Object worker=applicationContext.getBean("listingDescriptionCommandWorker");
        if(scenario.equals("EXPIRED_APPROVAL")) {
            fixture.seed.sql("UPDATE ops.approval_decision SET scope_expires_at=clock_timestamp()-interval '1 microsecond' WHERE id=:id")
                    .param("id",newApproval).update();
            assertThat(fixture.gateReasons(restored)).contains("AUTHORIZATION_INVALID_OR_EXPIRED");
            assertThat(runConnectedWorker(worker)).isZero();
            assertThat(loopback.received).isEmpty();
            return;
        }
        assertThat(fixture.gateReasons(restored)).isEmpty();
        if(scenario.equals("CRASH")) {
            assertThatThrownBy(()->runConnectedWorker(applicationContext.getBean("listingDescriptionCommandWorker")))
                    .isInstanceOf(ListingDescriptionLoopback.SimulatedProcessLoss.class);
            fixture.seed.sql("UPDATE ops.lc_description_command SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                    .param("id",restored).update();
            worker=applicationContext.getAutowireCapableBeanFactory().createBean(Class.forName(
                    "com.mimococo.marketops.marketplaceintegration.internal.application.ListingDescriptionCommandWorker"));
            assertThat(runConnectedWorker(worker)).isZero();
            assertThat(loopback.received).hasSize(2);
            mvc.perform(post("/api/v1/console/listing-description-commands/"+restored+"/readback")
                    .header(HttpHeaders.AUTHORIZATION,authorToken).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Observe interrupted restoration without another mutation\"}"))
                    .andExpect(status().isOk());
            assertThat(runConnectedWorker(worker)).isEqualTo(1);
        } else {
            assertThat(runConnectedWorker(worker)).isEqualTo(1);
            if(scenario.equals("ASYNC")) {
                for(int poll=0;poll<2;poll++) {
                    advanceConnectedCommandDue(restored);
                    assertThat(runConnectedWorker(worker)).isEqualTo(1);
                }
            }
        }
        if(scenario.equals("VERSION_CONFLICT")) {
            mvc.perform(get("/api/v1/console/listing-description-commands/"+restored).header(HttpHeaders.AUTHORIZATION,authorToken))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("UNKNOWN_REQUIRES_READBACK"))
                    .andExpect(jsonPath("$.executionReceipts").isEmpty());
            assertThat(loopback.received).containsExactly("GET /fixture/descriptions/"+nativeKey,"POST /fixture/descriptions");
            assertThat(runConnectedWorker(worker)).isZero();
            assertThat(loopback.received).hasSize(2);
            return;
        }
        if(scenario.equals("LATER_CHANGE") || scenario.equals("MISSING_VERSION")) {
            String expected=scenario.equals("LATER_CHANGE")?"LATER_CHANGE_OR_MISMATCH_INVESTIGATION":"READBACK_MISMATCH";
            mvc.perform(get("/api/v1/console/listing-description-commands/"+restored).header(HttpHeaders.AUTHORIZATION,authorToken))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.state").value(expected))
                    .andExpect(jsonPath("$.executionReceipts").isEmpty());
            assertThat(loopback.received).containsExactly("GET /fixture/descriptions/"+nativeKey);
            return;
        }
        assertThat(jdbc.sql("SELECT state FROM ops.lc_description_command WHERE id=:id").param("id",restored).query(String.class).single())
                .withFailMessage("Restoration did not settle: wire=%s, attempts=%s",loopback.received,
                    jdbc.sql("SELECT purpose,outcome_class,error_code FROM ops.lc_description_command_attempt WHERE command_id=:id ORDER BY attempt_no")
                        .param("id",restored).query().listOfRows()).isEqualTo("READBACK_MATCHED");
        mvc.perform(get("/api/v1/console/listing-description-commands/"+restored).header(HttpHeaders.AUTHORIZATION,authorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("READBACK_MATCHED"))
                .andExpect(jsonPath("$.executionReceipts[0].executionState").value(
                    scenario.equals("CRASH")?"NATIVE_COMPLETION_UNPROVEN":"MANAGEMENT_VERIFIED"));
        var purposes=jdbc.sql("SELECT purpose FROM ops.lc_description_command_attempt WHERE command_id=:id ORDER BY attempt_no")
                .param("id",restored).query(String.class).list();
        assertThat(purposes.stream().filter("RESTORE"::equals).count()).isEqualTo(1);
        assertThat(purposes).doesNotContain("APPLY");
        if(scenario.equals("ASYNC")) assertThat(purposes).containsExactly("READBACK","RESTORE","STATUS_ENQUIRY","STATUS_ENQUIRY","READBACK");
        else assertThat(purposes).containsExactly("READBACK","RESTORE","READBACK");
        assertThat(loopback.received.stream().filter("POST /fixture/descriptions"::equals).count()).isEqualTo(1);
    }

    @Test
    void restorationRejectsACompletedSourceWhoseCapturedPriorIsMissing() throws Exception {
        var sourceLaunch=exerciseConnectedDescriptionLaunch("EMPTY_SOURCE");
        UUID source=sourceLaunch.commandId();
        assertThat(jdbc.sql("SELECT prior_text IS NULL FROM ops.lc_description_command WHERE id=:id")
                .param("id",source).query(Boolean.class).single()).isTrue();
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_PREPARE,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        var json=new tools.jackson.databind.ObjectMapper();
        mvc.perform(post("/api/v1/console/listing/health/listings/"+fixture.id("listing")+"/facts/description")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text",ListingConversionFixture.TARGET_TEXT_ONE,"languageCode","ru",
                    "kizMarkedDeclared",false,"note","Synthetic operator observation after empty prior was changed"))))
                .andExpect(status().isOk());
        var proposed=mvc.perform(post("/api/v1/console/listing/actions/candidates")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("listingId",fixture.id("listing"),"candidateKind","CONTENT_DESCRIPTION",
                    "roundKey","missing-captured-prior","evidenceReferences",List.of("evidence://synthetic/empty-prior")))))
                .andExpect(status().isOk()).andReturn();
        String candidate=json.readTree(proposed.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(post("/api/v1/console/listing/actions/candidates/"+candidate+"/prepare")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("executionPath","API","restoresCommandId",source,"kizMarkedDeclared",false,
                        "purpose","DESCRIPTION_CORRECTION","purposeBasis",exactRestorationPurposeBasis()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("RESTORE_UNSUPPORTED"));
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_action WHERE candidate_id=:id").param("id",UUID.fromString(candidate))
                .query(Integer.class).single()).isZero();
        assertThat(loopback.received).hasSize(2);
    }

    private int runConnectedWorker(Object worker) {
        Integer advanced=org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker,"runOnce",Instant.now(),10);
        return advanced==null?0:advanced;
    }

    private void advanceConnectedCommandDue(UUID command) {
        // Advance the persisted test deadline; the worker still exercises its real due-row query.
        assertThat(fixture.seed.sql("""
                UPDATE ops.lc_description_command SET next_attempt_at=clock_timestamp()-interval '1 microsecond'
                 WHERE id=:id AND next_attempt_at IS NOT NULL
                """).param("id",command).update()).isEqualTo(1);
    }

    private void configureConnectedAsyncProtocol() {
        fixture.seed.sql("UPDATE platform.platform_capability SET write_result_model='ASYNCHRONOUS_TASK' WHERE id=:id")
                .param("id",fixture.id("capability")).update();
        fixture.seed.sql("""
                UPDATE platform.capability_operation SET task_key_pointer='/task_id',description_response_binding=
                    '{"schema":"DESCRIPTION_RESPONSE_IDENTITY_V1","evidenceRef":"fixture://protocol","mode":"TASK_ACCEPTANCE_ONLY"}'
                 WHERE capability_id=:id AND operation IN ('APPLY','RESTORE')
                """).param("id",fixture.id("capability")).update();
        UUID endpoint=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO platform.platform_endpoint SELECT (jsonb_populate_record(NULL::platform.platform_endpoint,
                    to_jsonb(e)||jsonb_build_object('id',:id,'endpoint_code',:code,'operation_function','DESCRIPTION_STATUS',
                        'path_template','/fixture/task-info','http_method','POST'))).*
                  FROM platform.platform_endpoint e WHERE id=:original
                """).param("id",endpoint).param("code","synthetic.connected.status."+endpoint)
                .param("original",fixture.id("endpointReadback")).update();
        fixture.seed.sql("""
                INSERT INTO platform.capability_operation SELECT (jsonb_populate_record(NULL::platform.capability_operation,
                    to_jsonb(o)||jsonb_build_object('id',:id,'endpoint_id',:endpoint,'operation','STATUS_ENQUIRY',
                        'request_template','{"task_id":"{nativeTaskKey}"}',
                        'task_status_pointer','/status','task_success_value','done','task_failure_value','error',
                        'task_pending_values',jsonb_build_array('working'),
                        'description_response_binding',description_response_binding||'{"statusValueType":"string",
                            "taskBindingMethod":"REQUEST_UNIQUE","taskRequestPointer":"/task_id","taskRequestValueType":"string"}'::jsonb))).*
                  FROM platform.capability_operation o WHERE capability_id=:capability AND operation='READBACK'
                """).param("id",UUID.randomUUID()).param("endpoint",endpoint).param("capability",fixture.id("capability")).update();
    }

    @org.junit.jupiter.api.AfterEach
    void stopConnectedServer() {
        loopback.stop();
        // Each scenario owns one fictional organization. Retained pending evidence must
        // not become runnable work for the next scenario's differently bound server.
        if (fixture!=null) fixture.seed.sql("UPDATE ops.lc_description_command SET next_attempt_at='infinity' WHERE organization_id=:org")
                .param("org",fixture.id("organization")).update();
    }

    @Test
    void oneSignedApiLaunchCreatesAndReturnsItsOnlyCommandBeforeCommit() throws Exception {
        var approved=prepareApprovedApiCorrection(ListingConversionFixture.TARGET_TEXT_ONE,
                BigDecimal.ZERO,true);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_LAUNCH,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        String route="/api/v1/console/listing/actions/"+approved.actionId()+"/launch";
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
                .param("action",approved.actionId()).query(Integer.class).single()).isEqualTo(1);
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
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_MANUAL_VERIFY,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        fixture.seed.sql("UPDATE core.store SET timezone='UTC' WHERE id=:id")
                .param("id",fixture.id("store")).update();
        postListing("/facts/display",Map.of("displayState","DISPLAYED","displayedText","Synthetic prior summary text",
                "observedAt",from.minusSeconds(3600).toString(),"evidenceReference","evidence://synthetic/summary-prior"));
        postListing("/facts/display",Map.of("displayState","DISPLAYED","displayedText","Synthetic target summary text",
                "observedAt",from.plusSeconds(12L*3600).toString(),"evidenceReference","evidence://synthetic/summary-transition"));
        var unsplittable=postListing("/measurements",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY"));
        assertThat(unsplittable.path("ratioState").asText()).isEqualTo("NOT_AVAILABLE");
        assertThat(unsplittable.path("visitCount").asLong()).isEqualTo(100);
        assertThat(unsplittable.path("retainedPurchaseVisitCount").asLong()).isEqualTo(10);
        assertThat(unsplittable.path("primaryRatio").isNull()).isTrue();
        assertThat(unsplittable.path("qualificationReasonCodes").toString())
                .contains("SUMMARY_TRANSITION_WINDOW_UNSPLITTABLE");
        assertThat(unsplittable.path("excludedTransitionDays").size()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT inputs->'sourceInputs'->>'reported_visits'='100'
                  AND inputs->'sourceInputs'->>'reported_retained_purchases'='10'
                FROM mart.lc_measurement_lineage WHERE measurement_id=:id
                """).param("id",UUID.fromString(unsplittable.path("id").asText()))
                .query(Boolean.class).single()).isTrue();
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
    void nonEquivalentSummaryMethodInputsCannotBorrowFormalQualificationButKeepTheProvenTotal() throws Exception {
        measurementGrants();
        seedSummaryProfile(true);
        Instant to=Instant.now().minusSeconds(40L*86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant from=to.minusSeconds(86400),complete=to.plusSeconds(31L*86400);
        var malformed=new tools.jackson.databind.ObjectMapper().valueToTree(Map.of(
                "periodStart",from.toString(),"periodEnd",to.toString(),"visits",100,"retainedPurchases",10,
                "retentionDays",30,"observedAt",complete.toString(),"sourceMethodInputVersion",1,
                "sourceStrata",List.of("not-a-count-map")));
        mvc.perform(post("/api/v1/console/listing/health/listings/"+fixture.id("listing")+"/facts/official-summary")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(malformed.toString())).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM core.lc_official_summary_observation WHERE platform_listing_id=:id")
                .param("id",fixture.id("listing")).query(Long.class).single()).isZero();
        var body=new java.util.LinkedHashMap<String,Object>();
        body.put("periodStart",from.toString());body.put("periodEnd",to.toString());body.put("visits",100);
        body.put("retainedPurchases",10);body.put("retentionDays",30);body.put("observedAt",complete.toString());
        body.put("sourceMethodInputVersion",1);
        body.put("sourceStrata",Map.of("ADVERTISING",Map.of("visits",80,"retained",9),
                "ORGANIC",Map.of("visits",20,"retained",2)));
        body.put("criticalGroupSourceStrata",formalCriticalGroupCounts());
        var summary=postListing("/facts/official-summary",body);
        postListing("/facts/measurement-coverage",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY","sourceCompleteThrough",complete.toString(),
                "sourceReference","evidence://synthetic/non-equivalent-summary-method-inputs",
                "summaryObservationId",summary.path("observationId").asText()));
        var measured=postListing("/measurements",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY"));

        assertThat(measured.path("pathQualified").asBoolean()).isTrue();
        assertThat(measured.path("primaryRatio").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(measured.path("sourceStratified").asBoolean()).isFalse();
        assertThat(jdbc.sql("""
                SELECT inputs->>'sourceStrataQualified'='false'
                  AND inputs->'summaryMethodInputQualificationReasonCodes'
                      @> '["SUMMARY_SOURCE_STRATA_TOTAL_MISMATCH"]'::jsonb
                FROM mart.lc_measurement_lineage WHERE measurement_id=:id
                """).param("id",UUID.fromString(measured.path("id").asText()))
                .query(Boolean.class).single()).isTrue();
        assertThat(measurementEvidence.measuredSourceStrata(UUID.fromString(measured.path("id").asText()),
                fixture.id("listing")).orElseThrow().qualified()).isFalse();
    }

    @Test
    void expiredSummaryProfileCannotQualifyALaterMeasurement() throws Exception {
        measurementGrants();
        seedSummaryProfile(true);
        Instant to=Instant.now().minusSeconds(40L*86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant from=to.minusSeconds(86400),complete=to.plusSeconds(31L*86400);
        var body=new java.util.LinkedHashMap<String,Object>();
        body.put("periodStart",from.toString());body.put("periodEnd",to.toString());body.put("visits",100);
        body.put("retainedPurchases",10);body.put("retentionDays",30);body.put("observedAt",complete.toString());
        body.put("sourceMethodInputVersion",1);
        body.put("sourceStrata",Map.of("ADVERTISING",Map.of("visits",80,"retained",8),
                "ORGANIC",Map.of("visits",20,"retained",2)));
        body.put("criticalGroupSourceStrata",formalCriticalGroupCounts());
        var summary=postListing("/facts/official-summary",body);
        postListing("/facts/measurement-coverage",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY","sourceCompleteThrough",complete.toString(),
                "sourceReference","evidence://synthetic/profile-expiry",
                "summaryObservationId",summary.path("observationId").asText()));
        fixture.seed.sql("""
                UPDATE core.lc_summary_equivalence_profile SET status='RETIRED',effective_to=clock_timestamp()
                 WHERE organization_id=:org
                """).param("org",fixture.id("organization")).update();
        var measured=postListing("/measurements",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","OFFICIAL_SUMMARY"));

        assertThat(measured.path("ratioState").asText()).isEqualTo("NOT_AVAILABLE");
        assertThat(measured.path("visitCount").asLong()).isEqualTo(100);
        assertThat(measured.path("retainedPurchaseVisitCount").asLong()).isEqualTo(10);
        assertThat(measured.path("qualificationReasonCodes").toString()).contains("EQUIVALENCE_PROFILE_ABSENT");
        assertThat(measured.path("sourceStratified").asBoolean()).isFalse();
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
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_MANUAL_VERIFY,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_OUTCOME_EVALUATE,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        fixture.seed.sql("UPDATE core.store SET timezone='UTC' WHERE id=:id")
                .param("id",fixture.id("store")).update();
        Instant from=Instant.now().minusSeconds(42L*86400).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant to=from.plusSeconds(2L*86400);
        String currentText=jdbc.sql("SELECT description_text FROM core.lc_description_observation WHERE id=:id")
                .param("id",fixture.id("observationOne")).query(String.class).single();
        String targetText=jdbc.sql("SELECT target_text FROM ops.lc_action WHERE id=:id")
                .param("id",fixture.id("actionOne")).query(String.class).single();
        postListing("/facts/display",Map.of("displayState","DISPLAYED","displayedText",currentText,
                "observedAt",from.minusSeconds(3600).toString(),"evidenceReference","evidence://synthetic/late-sale-prior"));
        postListing("/facts/display",Map.of("displayState","DISPLAYED","displayedText",targetText,
                "observedAt",from.plusSeconds(6L*3600).toString(),"evidenceReference","evidence://synthetic/late-sale-transition"));
        postListing("/facts/visit",Map.of("visitKey","transition-sale-visit","visitedAt",from.plusSeconds(8L*3600).toString(),
                "sellable","YES","channel","ORGANIC"));
        UUID transitionSale=seedSale(from.plusSeconds(8L*3600+100),null,false);
        postListing("/facts/purchase-link",Map.of("visitKey","transition-sale-visit","salesFactId",transitionSale.toString(),
                "basis","MANUAL_ENTRY"));
        postListing("/facts/visit",Map.of("visitKey","late-sale-visit","visitedAt",from.plusSeconds(86400+3600).toString(),
                "sellable","YES","channel","ORGANIC"));
        UUID sale=seedSale(from.plusSeconds(86400+3700),null,false);
        postListing("/facts/purchase-link",Map.of("visitKey","late-sale-visit","salesFactId",sale.toString(),"basis","MANUAL_ENTRY"));
        postListing("/facts/measurement-coverage",Map.of("windowStart",from.toString(),"windowEnd",to.toString(),
                "retentionDays",30,"evidencePath","DETAIL","sourceCompleteThrough",to.plusSeconds(31L*86400).toString(),
                "sourceReference","evidence://synthetic/complete-sale-window","expectedVisitRows",2,"expectedLinkRows",2));
        var request=Map.of("windowStart",from.toString(),"windowEnd",to.toString(),"retentionDays",30,"evidencePath","DETAIL");
        var original=postListing("/measurements",request);
        UUID originalMeasurement=UUID.fromString(original.path("id").asText());
        assertThat(original.path("primaryRatio").decimalValue()).isEqualByComparingTo("1");
        assertThat(original.path("visitCount").asLong()).isEqualTo(1);
        assertThat(original.path("retainedPurchaseVisitCount").asLong()).isEqualTo(1);
        assertThat(original.path("excludedTransitionDays").size()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT inputs->'sourceStrata'->'ORGANIC'->>'retained' FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",originalMeasurement).query(String.class).single()).isEqualTo("1");
        assertThat(jdbc.sql("""
                SELECT inputs->>'wholeWindowVisitCount'='2' AND inputs->>'wholeWindowRetainedCount'='2'
                  AND jsonb_array_length(inputs#>'{versionCoverage,excludedTransitionDays}')=1
                FROM mart.lc_measurement_lineage WHERE measurement_id=:id
                """).param("id",originalMeasurement).query(Boolean.class).single()).isTrue();

        UUID protectionSource=seedFormalProvenance("transition-protection",from.minusSeconds(3600));
        seedOperationalProtectionPeriod(protectionSource,"transition-profit-risk",from,to,
                Instant.now().minusSeconds(5),new BigDecimal("1000"),BigDecimal.ONE);
        fixture.seed.sql("""
                INSERT INTO core.listing_stock_observation(id,organization_id,provenance_id,
                    platform_listing_variant_id,fulfillment_mode_code,source_fact_key,observed_at,
                    available_quantity,reserved_quantity)
                VALUES(:id,:org,:source,:variant,'MARKETPLACE_FULFILLED',:key,:at,25,0)
                """).param("id",UUID.randomUUID()).param("org",fixture.id("organization"))
                .param("source",protectionSource).param("variant",fixture.id("listingVariant"))
                .param("key","transition-stock-"+UUID.randomUUID()).param("at",java.sql.Timestamp.from(to)).update();
        String retainedProtection=jdbc.sql("""
                SELECT jsonb_build_object(
                  'metrics',(SELECT jsonb_agg(jsonb_build_array(metric_code,numeric_value) ORDER BY metric_code)
                     FROM mart.metric_value WHERE calculation_run_id=(SELECT id FROM mart.calculation_run
                       WHERE correlation_id='transition-profit-risk')),
                  'stock',(SELECT jsonb_agg(jsonb_build_array(source_fact_key,available_quantity) ORDER BY source_fact_key)
                     FROM core.listing_stock_observation WHERE platform_listing_variant_id=:variant))::text
                """).param("variant",fixture.id("listingVariant")).query(String.class).single();

        var json=new tools.jackson.databind.ObjectMapper();
        Instant frozen=from.minusSeconds(86400);
        var queueNode=json.valueToTree(Map.of(
                "nodeCode","D14","maturityDays",30,"method","EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1",
                "threshold","0.05","methodParameters",Map.of("familyAlpha","0.05","nodeAlpha","0.05",
                        "samplingModel","INDEPENDENT_BERNOULLI_VISITS","qualificationRef","fixture://queue-late-fact"),
                "schedule",Map.of("windowStartOffsetDays",1,"windowEndOffsetDays",3,
                        "notBeforeOffsetDays",33,"lastOffsetDays",60),
                "comparisonReference",Map.of(),"protectionComparison",Map.of()));
        fixture.seed.sql("""
                UPDATE ops.lc_evaluation_plan SET formal_nodes=CAST(:nodes AS jsonb),stop_rule='{}'::jsonb,
                    critical_groups='[]'::jsonb,cross_period_window_days=0,frozen_at=:frozen,latest_boundary=:boundary
                 WHERE id=:id
                """).param("nodes",json.writeValueAsString(List.of(queueNode)))
                .param("frozen",java.sql.Timestamp.from(frozen))
                .param("boundary",java.sql.Timestamp.from(frozen.plusSeconds(60L*86400)))
                .param("id",fixture.id("planOne")).update();
        listingIntake.ensureResponsibilityTask(fixture.id("organization"),fixture.id("recommendationOne"),
                "Synthetic late-sale reversal Outcome responsibility",Instant.now().plusSeconds(86400),Instant.now());
        UUID launchId=UUID.randomUUID();
        assertThat(fixture.launch(launchId,"actionOne",fixture.id("ownerUser")).path("launched").asBoolean()).isTrue();
        fixture.seed.sql("UPDATE ops.lc_launch SET launched_at=:at WHERE id=:id")
                .param("at",java.sql.Timestamp.from(from.minusSeconds(1))).param("id",launchId).update();
        mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("nodeCode","D14","stage","OPERATIONAL",
                        "measurementId",originalMeasurement))))
                .andExpect(status().isOk());
        UUID originalOutcome=jdbc.sql("""
                SELECT id FROM ops.lc_node_result WHERE plan_id=:plan AND stage='OPERATIONAL' AND revision_no=0
                """).param("plan",fixture.id("planOne")).query(UUID.class).single();

        fixture.seed.sql("DELETE FROM ops.lc_recalculation_queue WHERE organization_id=:org")
                .param("org",fixture.id("organization")).update();
        UUID reversal=seedSale(from.plusSeconds(86400+3700),sale,true);
        String triggerReference="ledger.sales_fact:"+reversal;
        UUID queue=jdbc.sql("""
                SELECT id FROM ops.lc_recalculation_queue
                 WHERE platform_listing_id=:listing AND trigger_reference=:reference
                """).param("listing",fixture.id("listing")).param("reference",triggerReference)
                .query(UUID.class).single();
        assertThat(jdbc.sql("SELECT ops.lc_enqueue_source_recalculation(:org,:listing,'ORDINARY',:reference,:source)")
                .param("org",fixture.id("organization")).param("listing",fixture.id("listing"))
                .param("reference",triggerReference).param("source",java.sql.Timestamp.from(from.plusSeconds(86400+3700)))
                .query(UUID.class).single()).isEqualTo(queue);
        var recalculation=applicationContext.getBean(
                com.mimococo.marketops.listingconversion.internal.application.RecalculationService.class);
        try(var isolation=fixture.migration.getConnection()) {
            isolation.setAutoCommit(false);
            try(var lock=isolation.prepareStatement("""
                    SELECT id FROM ops.lc_recalculation_queue
                     WHERE id<>? AND accepted_at<=clock_timestamp()
                       AND (state='QUEUED' OR (state='RUNNING'
                         AND (leased_until IS NULL OR leased_until<=clock_timestamp())))
                     FOR UPDATE
                    """)) {
                lock.setObject(1,queue);
                try(var rows=lock.executeQuery()) {
                    while(rows.next()) { /* Hold every unrelated eligible row for this bounded claim. */ }
                    assertThat(recalculation.runOnce(1)).isEqualTo(1);
                }
            } finally {
                isolation.rollback();
            }
        }
        assertThat(jdbc.sql("SELECT state FROM ops.lc_recalculation_queue WHERE id=:id")
                .param("id",queue).query(String.class).single()).isEqualTo("FINISHED");
        UUID revisedMeasurement=jdbc.sql("SELECT measurement_result_ids[1] FROM ops.lc_recalculation_queue WHERE id=:id")
                .param("id",queue).query(UUID.class).single();
        UUID revisedOutcome=jdbc.sql("SELECT outcome_result_ids[1] FROM ops.lc_recalculation_queue WHERE id=:id")
                .param("id",queue).query(UUID.class).single();
        assertThat(revisedMeasurement).isNotEqualTo(originalMeasurement);
        assertThat(revisedOutcome).isNotEqualTo(originalOutcome);
        assertThat(jdbc.sql("SELECT primary_ratio FROM mart.lc_conversion_measurement WHERE id=:id")
                .param("id",revisedMeasurement).query(BigDecimal.class).single()).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT primary_ratio FROM mart.lc_conversion_measurement WHERE id=:id")
                .param("id",originalMeasurement).query(BigDecimal.class).single()).isEqualByComparingTo("1");
        String lineage=jdbc.sql("SELECT inputs::text FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",revisedMeasurement).query(String.class).single();
        assertThat(lineage).contains(sale.toString(),reversal.toString());
        assertThat(jdbc.sql("SELECT inputs->'sourceStrata'->'ORGANIC'->>'retained' FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",revisedMeasurement).query(String.class).single()).isEqualTo("0");
        assertThat(jdbc.sql("SELECT inputs->>'sourceStrataQualified' FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",revisedMeasurement).query(String.class).single()).isEqualTo("true");
        assertThat(jdbc.sql("SELECT canonical_input_digest=encode(sha256(convert_to(inputs::text,'UTF8')),'hex') FROM mart.lc_measurement_lineage WHERE measurement_id=:id")
                .param("id",revisedMeasurement).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT inputs->>'wholeWindowVisitCount'='2' AND inputs->>'wholeWindowRetainedCount'='1'
                  AND jsonb_array_length(inputs#>'{versionCoverage,excludedTransitionDays}')=1
                FROM mart.lc_measurement_lineage WHERE measurement_id=:id
                """).param("id",revisedMeasurement).query(Boolean.class).single()).isTrue();
        var source=measurementEvidence.measuredSourceStrata(revisedMeasurement,fixture.id("listing")).orElseThrow();
        assertThat(source.qualified()).isTrue();
        assertThat(source.counts().path("ORGANIC").path("visits").longValue()).isEqualTo(1);
        assertThat(source.counts().path("ORGANIC").path("retained").longValue()).isZero();
        assertThat(measurementEvidence.measuredSourceStrata(revisedMeasurement,fixture.id("listingTwo"))).isEmpty();
        assertThat(jdbc.sql("""
                SELECT state='FINISHED' AND cardinality(measurement_result_ids)=1
                  AND outcome_assessed_count=1 AND cardinality(outcome_result_ids)=1
                FROM ops.lc_recalculation_queue WHERE id=:id
                """).param("id",queue).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.lc_outcome_revision
                 WHERE plan_id=:plan AND original_result_id=:original AND revised_result_id=:revised
                   AND revision_reason='LATE_FACT' AND late_fact_reference=:reference
                """).param("plan",fixture.id("planOne")).param("original",originalOutcome)
                .param("revised",revisedOutcome).param("reference","recalculation-queue:"+queue)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT jsonb_build_object(
                  'metrics',(SELECT jsonb_agg(jsonb_build_array(metric_code,numeric_value) ORDER BY metric_code)
                     FROM mart.metric_value WHERE calculation_run_id=(SELECT id FROM mart.calculation_run
                       WHERE correlation_id='transition-profit-risk')),
                  'stock',(SELECT jsonb_agg(jsonb_build_array(source_fact_key,available_quantity) ORDER BY source_fact_key)
                     FROM core.listing_stock_observation WHERE platform_listing_variant_id=:variant))::text
                """).param("variant",fixture.id("listingVariant")).query(String.class).single()).isEqualTo(retainedProtection);
        long outcomeEvents=jdbc.sql("SELECT count(*) FROM ops.work_task_event WHERE outcome_reference=:reference")
                .param("reference","lc-node-result:"+revisedOutcome).query(Long.class).single();
        assertThat(jdbc.sql("SELECT ops.lc_enqueue_source_recalculation(:org,:listing,'ORDINARY',:reference,:source)")
                .param("org",fixture.id("organization")).param("listing",fixture.id("listing"))
                .param("reference",triggerReference).param("source",java.sql.Timestamp.from(from.plusSeconds(86400+3700)))
                .query(UUID.class).single()).isEqualTo(queue);
        assertThat(jdbc.sql("SELECT state FROM ops.lc_recalculation_queue WHERE id=:id")
                .param("id",queue).query(String.class).single()).isEqualTo("FINISHED");
        assertThat(jdbc.sql("SELECT count(*) FROM ops.work_task_event WHERE outcome_reference=:reference")
                .param("reference","lc-node-result:"+revisedOutcome).query(Long.class).single()).isEqualTo(outcomeEvents);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_outcome_revision WHERE plan_id=:plan")
                .param("plan",fixture.id("planOne")).query(Long.class).single()).isEqualTo(1);
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
        for (var value:draft.path("values")) {
            if (value.path("categoryCode").asText().equals("ALLOWANCE_AXES")) {
                ((tools.jackson.databind.node.ObjectNode)value).set("json",json.readTree(
                        "{\"axes\":[\"CONCURRENT_LISTINGS\",\"AFFECTED_VARIANTS\"],\"scopeComposition\":\"ALL_APPLICABLE\"}"));
            }
        }
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
        assertThat(jdbc.sql("SELECT value_json->>'scopeComposition' FROM core.lc_calibration_value WHERE package_id=:id AND category_code='ALLOWANCE_AXES'")
                .param("id",UUID.fromString(id)).query(String.class).single()).isEqualTo("ALL_APPLICABLE");
        assertThat(jdbc.sql("SELECT package_id FROM core.lc_resolve_calibration_for(:org,:platform,:store,clock_timestamp(),'DESCRIPTION_CORRECTION')")
                .param("org",fixture.id("organization")).param("platform",fixture.graph.platform()).param("store",fixture.id("store"))
                .query(UUID.class).single()).isEqualTo(UUID.fromString(id));
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_calibration_event WHERE package_id=:id")
                .param("id",UUID.fromString(id)).query(Long.class).single()).isEqualTo(4);
        assertThat(calibration.resolve(fixture.id("organization"),fixture.graph.platform(),fixture.id("store"),Instant.now())
                .resolved().packageId()).isEqualTo(fixture.id("calibrationPackage"));
    }

    @Test
    void preparedProposalCannotAddOrSwapItsSimulationReferenceLater() {
        var repository=applicationContext.getBean(com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository.class);
        assertThat(repository.selectedSimulation(fixture.id("actionOne")).declared()).isFalse();
        for (String field:List.of("simulationId","simulationInputsDigest")) {
            assertThatThrownBy(()->jdbc.sql("""
                    UPDATE ops.recommendation SET proposed_parameters=proposed_parameters||jsonb_build_object(:field,:value)
                     WHERE id=:id
                    """).param("field",field).param("value",field.equals("simulationId")?UUID.randomUUID().toString():"a".repeat(64))
                    .param("id",fixture.id("recommendationOne")).update())
                    .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        }
        assertThat(repository.selectedSimulation(fixture.id("actionOne")).declared()).isFalse();
    }

    @Test
    void expiredPromotionScenarioDoesNotPreventOtherCalibrationConsumersFromActivating() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        var draft=calibrationDraft("synthetic-expired-demand-independent-consumers");
        draft.put("purposeCode","LISTING_CONVERSION");
        draft.put("version",2);
        draft.put("replacesPackageId",fixture.id("calibrationPackage").toString());
        var mapper=new tools.jackson.databind.ObjectMapper();
        Instant ended=Instant.now().minusSeconds(3600);
        var basis=Map.of("periodStart",ended.minusSeconds(86400).toString(),"periodEnd",ended.toString(),
                "evidenceReference","fixture://past-demand","minimumContributionProfit",new BigDecimal("100"),
                "currencyCode","RUB","profitEvidenceReference","fixture://past-profit-line","necessaryScenarios",List.of(
                    Map.of("code","DOWNSIDE","quantity",4,"conservative",true,"evidenceReference","fixture://downside")));
        for(var value:draft.path("values")) {
            if(value.path("categoryCode").asText().equals("DEMAND_SCENARIO_SET"))
                ((tools.jackson.databind.node.ObjectNode)value).set("json",mapper.valueToTree(
                        Map.of("economicScenarioBases",Map.of(fixture.id("listing").toString(),basis))));
        }
        UUID active=UUID.fromString(activateSyntheticCalibrationWithIndependentOwner(draft));
        assertThat(jdbc.sql("SELECT status FROM core.lc_calibration_package WHERE id=:id")
                .param("id",active).query(String.class).single()).isEqualTo("ACTIVE");
        var checked=calibration.recheckAction(fixture.id("actionOne"),Instant.now());
        assertThat(checked.outcome().state()).isEqualTo("UNCHANGED_DEPENDENCIES");
        var period=new com.mimococo.marketops.listingconversion.SimulationAssumptions(
                ended.minusSeconds(86400),ended,Map.of(),"Past period cannot provide prospective admission");
        var evidence=com.mimococo.marketops.listingconversion.internal.application.CalibrationService.demandEvidence(
                checked.outcome(),fixture.id("listing"),period,List.of(
                    new com.mimococo.marketops.listingconversion.internal.domain.PromotionSimulator.Scenario(
                        "DOWNSIDE",new java.math.BigDecimal("4"),true,true)),Instant.now());
        assertThat(evidence.get("state")).isEqualTo("UNQUALIFIED");
        assertThat(evidence.get("gaps")).isEqualTo(List.of("DEMAND_NOT_ACCEPTED_EX_ANTE"));
    }

    @Test
    void governedReplacementContinuesUnchangedDependenciesAndStopsChangedOnes() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        var decisions=applicationContext.getBean(com.mimococo.marketops.operationsworkflow.ListingActionDecisionAuthority.class);
        var json=new tools.jackson.databind.ObjectMapper();
        UUID old=fixture.id("calibrationPackage");
        String originalPlan=jdbc.sql("SELECT to_jsonb(p)::text FROM ops.lc_evaluation_plan p WHERE action_id=:id")
                .param("id",fixture.id("actionOne")).query(String.class).single();
        String originalDependencies=jdbc.sql("SELECT calibration_dependencies::text FROM ops.lc_action WHERE id=:id")
                .param("id",fixture.id("actionOne")).query(String.class).single();
        assertThat(originalDependencies).doesNotContain("DEMAND_SCENARIO_SET");
        var replacement=calibrationDraft("synthetic-unchanged-action-rules");
        replacement.put("purposeCode","LISTING_CONVERSION");replacement.put("version",2);
        replacement.put("replacesPackageId",old.toString());
        for(var value:replacement.path("values")) {
            if(value.path("categoryCode").asText().equals("DEMAND_SCENARIO_SET")) {
                ((tools.jackson.databind.node.ObjectNode)value).set("json",json.readTree(
                        "{\"scenarios\":[{\"code\":\"CHANGED_PROMOTION_ONLY\",\"quantity\":20,\"necessary\":true,\"conservative\":true}]}"));
            }
        }
        String current=activateSyntheticCalibrationWithIndependentOwner(replacement);
        var check=calibration.recheckAction(fixture.id("actionOne"),Instant.now());
        assertThat(check.outcome().state()).isEqualTo("UNCHANGED_DEPENDENCIES");
        assertThat(check.outcome().resolved().packageId()).isEqualTo(UUID.fromString(current));
        assertThat(decisions.unresolvedReasons(fixture.id("recommendationOne"))).doesNotContain("ENTITY_VERSION_CHANGED");
        assertThat(bindingGaps("actionOne")).isEmpty();
        assertThatThrownBy(()->jdbc.sql("UPDATE ops.lc_action SET calibration_dependencies='{}' WHERE id=:id")
                .param("id",fixture.id("actionOne")).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        listingIntake.ensureResponsibilityTask(fixture.id("organization"),fixture.id("recommendationOne"),
                "Synthetic continued approved action",Instant.now().plusSeconds(86400),Instant.now());
        assertThat(fixture.launch(UUID.randomUUID(),"actionOne",fixture.id("ownerUser"))
                .path("launched").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT to_jsonb(p)::text FROM ops.lc_evaluation_plan p WHERE action_id=:id")
                .param("id",fixture.id("actionOne")).query(String.class).single()).isEqualTo(originalPlan);
        assertThat(jdbc.sql("SELECT calibration_dependencies::text FROM ops.lc_action WHERE id=:id")
                .param("id",fixture.id("actionOne")).query(String.class).single()).isEqualTo(originalDependencies);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_OUTCOME_EVALUATE,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        mvc.perform(post(endpoint()).header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body())).andExpect(status().isOk());
        assertThat(jdbc.sql("""
                SELECT p.calibration_package_id=:v1 AND p.calibration_version=1
                  AND r.evaluation_evidence->>'planDigest'=p.plan_digest
                  AND r.evaluation_evidence->>'method'=p.formal_nodes->0->>'method'
                FROM ops.lc_node_result r JOIN ops.lc_evaluation_plan p ON p.id=r.plan_id
                 WHERE p.action_id=:action ORDER BY r.revision_no DESC LIMIT 1
                """).param("v1",old).param("action",fixture.id("actionOne"))
                .query(Boolean.class).single()).isTrue();
        UUID actionUnderV2=replaceDescriptionAction(fixture.id("listingTwo"),fixture.id("actionTwo"),"calibration-v2-action");
        assertThat(jdbc.sql("""
                SELECT a.calibration_package_id=:package AND a.calibration_version=2
                  AND p.calibration_package_id=a.calibration_package_id AND p.calibration_version=a.calibration_version
                FROM ops.lc_action a JOIN ops.lc_evaluation_plan p ON p.action_id=a.id WHERE a.id=:id
                """).param("package",UUID.fromString(current)).param("id",actionUnderV2)
                .query(Boolean.class).single()).isTrue();
        assertThat(decisions.decisionScope(fixture.id("recommendationTwo")).orElseThrow().calibrationRecheck())
                .containsEntry("currentPackageId",current).containsEntry("state","UNCHANGED_DEPENDENCIES");

        var changed=calibrationDraft("synthetic-changed-action-rules",UUID.fromString(current));
        changed.put("purposeCode","LISTING_CONVERSION");changed.put("version",3);changed.put("replacesPackageId",current);
        for(var value:changed.path("values")) {
            var rule=(tools.jackson.databind.node.ObjectNode)value;
            switch(value.path("categoryCode").asText()) {
                case "APPROVAL_VALIDITY" -> rule.put("numeric",47);
                case "STOP_RULE" -> rule.set("json",json.createObjectNode());
                case "CROSS_PERIOD_WINDOW" -> rule.put("numeric",0);
                default -> { }
            }
        }
        String versionThree=activateSyntheticCalibrationWithIndependentOwner(changed);
        assertThat(calibration.recheckAction(actionUnderV2,Instant.now()).outcome().state())
                .isEqualTo("CALIBRATION_DEPENDENCIES_CHANGED");
        assertThat(calibration.recheckAction(fixture.id("actionTwo"),Instant.now()).outcome().state())
                .isEqualTo("CALIBRATION_DEPENDENCIES_CHANGED");
        assertThat(bindingGaps("actionTwo")).contains("CALIBRATION_NOT_CURRENT");
        assertThat(bindingGaps("actionOne")).contains("CALIBRATION_NOT_CURRENT");
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_LAUNCH,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        mvc.perform(post("/api/v1/console/listing/actions/"+fixture.id("actionTwo")+"/launch")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"axes\":{}}"))
                .andExpect(status().is4xxClientError());
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_description_command WHERE action_id=:id")
                .param("id",fixture.id("actionTwo")).query(Integer.class).single()).isZero();
        UUID actionUnderV3=replaceDescriptionAction(fixture.id("listingTwo"),actionUnderV2,"calibration-v3-no-tail");
        assertThat(jdbc.sql("""
                SELECT a.calibration_package_id=:package AND a.calibration_version=3
                  AND p.stop_rule='{}'::jsonb AND p.cross_period_window_days=0
                  AND p.latest_boundary=p.frozen_at+
                    ((SELECT max((node#>>'{schedule,lastOffsetDays}')::integer)
                        FROM jsonb_array_elements(p.formal_nodes) node)*interval '1 day')
                FROM ops.lc_action a JOIN ops.lc_evaluation_plan p ON p.action_id=a.id WHERE a.id=:id
                """).param("package",UUID.fromString(versionThree)).param("id",actionUnderV3)
                .query(Boolean.class).single()).isTrue();
        assertThat(calibration.recheckAction(fixture.id("actionTwo"),Instant.now().plusSeconds(2*86400)).outcome().state())
                .isEqualTo("CALIBRATION_UNRESOLVED");
    }

    @Test
    void correctionCalibrationDoesNotRequireGrowthClaimsButRetainsSafetyRules() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        var draft=calibrationDraft("synthetic-correction-without-growth-evidence");
        var values=(tools.jackson.databind.node.ArrayNode)draft.path("values");
        var unused=java.util.Set.of("MATERIAL_IMPROVEMENT_BOUND","DEMAND_SCENARIO_SET","FORMAL_NODES","STOP_RULE");
        for(int index=values.size()-1;index>=0;index--) if(unused.contains(values.get(index).path("categoryCode").asText())) values.remove(index);
        String id=activateSyntheticCalibrationWithIndependentOwner(draft);
        var resolved=calibration.resolve(fixture.id("organization"),fixture.graph.platform(),fixture.id("store"),Instant.now(),"DESCRIPTION_CORRECTION");
        assertThat(resolved.ok()).isTrue();assertThat(resolved.resolved().packageId()).isEqualTo(UUID.fromString(id));
        assertThat(resolved.resolved().values()).doesNotContainKeys(unused.toArray(String[]::new));
        assertThat(resolved.resolved().values()).containsKeys("NON_WORSENING_PROFIT_BOUND","NON_WORSENING_RETURN_BOUND",
                "CRITICAL_GROUP_RULE","FRESHNESS_RULE","RESPONSIBILITY_COVERAGE","APPROVAL_VALIDITY","ALLOWANCE_AXES");
        assertThat(calibration.resolve(fixture.id("organization"),fixture.graph.platform(),fixture.id("store"),Instant.now(),"PROMOTION").ok()).isFalse();
        assertThat(calibration.resolve(fixture.id("organization"),fixture.graph.platform(),fixture.id("store"),Instant.now())
                .resolved().packageId()).isEqualTo(fixture.id("calibrationPackage"));
        assertThat(calibration.resolve(fixture.id("organization"),fixture.graph.platform(),fixture.id("store"),Instant.now().plusSeconds(2*86400),"DESCRIPTION_CORRECTION").ok()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PROMOTION","BOUNDED_EXPLORATION"})
    void declaredPurposeCannotUseAnIncompatibleDescriptionPath(String purpose) throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_PREPARE,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        var json=new tools.jackson.databind.ObjectMapper();
        var response=mvc.perform(post("/api/v1/console/listing/actions/candidates")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("listingId",fixture.id("listing"),"candidateKind","CONTENT_DESCRIPTION",
                        "roundKey","purpose-refusal-"+UUID.randomUUID(),"evidenceReferences",List.of("evidence://synthetic/purpose")))))
                .andExpect(status().isOk()).andReturn();
        String candidate=json.readTree(response.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(post("/api/v1/console/listing/actions/candidates/"+candidate+"/prepare")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("executionPath","API","purpose",purpose,
                        "targetText",ListingConversionFixture.PRIOR_TEXT_ONE+".","kizMarkedDeclared",false))))
                .andExpect(result->assertThat(result.getResolvedException()).isInstanceOfSatisfying(
                        com.mimococo.marketops.shared.OperationRejectedException.class,
                        failure->assertThat(failure.errorCode()).isEqualTo(com.mimococo.marketops.shared.ErrorCode.EXECUTION_PATH_MISMATCH)));
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_action WHERE candidate_id=:id")
                .param("id",UUID.fromString(candidate)).query(Long.class).single()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"NO_BASIS","MISSING_END","MISSING_UNTIL","EXPIRED"})
    void boundedExplorationRequiresItsExplicitFiniteUseBasis(String gap) throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_PREPARE,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        var json=new tools.jackson.databind.ObjectMapper();
        var candidateResult=mvc.perform(post("/api/v1/console/listing/actions/candidates")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("listingId",fixture.id("listing"),"candidateKind","CONTENT_DESCRIPTION",
                        "roundKey","finite-purpose-"+UUID.randomUUID(),"evidenceReferences",List.of("evidence://synthetic/purpose")))))
                .andExpect(status().isOk()).andReturn();
        UUID candidate=UUID.fromString(json.readTree(candidateResult.getResponse().getContentAsString()).path("id").asText());
        var request=new java.util.LinkedHashMap<String,Object>(Map.of("executionPath","MANUAL","purpose","BOUNDED_EXPLORATION",
                "targetText",ListingConversionFixture.PRIOR_TEXT_ONE+".","kizMarkedDeclared",false));
        var basis=new java.util.LinkedHashMap<String,Object>(Map.of("evidenceReference","evidence://synthetic/finite-use",
                "useConditions",List.of("Use only while the exact purpose remains supported"),
                "endConditions",gap.equals("MISSING_END")?List.of():List.of("Reassess at the fixed end without claiming improvement")));
        if(!gap.equals("MISSING_UNTIL")) basis.put("useUntil",Instant.now().plusSeconds(gap.equals("EXPIRED")?-60:3600).toString());
        if(!gap.equals("NO_BASIS")) request.put("purposeBasis",basis);
        mvc.perform(post("/api/v1/console/listing/actions/candidates/"+candidate+"/prepare")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(result->assertThat(result.getResolvedException()).isInstanceOfSatisfying(
                        com.mimococo.marketops.shared.OperationRejectedException.class,
                        failure->assertThat(failure.errorCode()).isEqualTo(com.mimococo.marketops.shared.ErrorCode.VALIDATION_FAILED)));
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_action WHERE candidate_id=:id")
                .param("id",candidate).query(Long.class).single()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"DESCRIPTION_CORRECTION,false","BOUNDED_EXPLORATION,false",
            "BOUNDED_EXPLORATION,true"})
    void declaredNonformalApprovalBindsReviewedUseAndCannotOutliveIt(String purpose,
                                                                     boolean launchBeforeUseExpiry) throws Exception {
        var json=new tools.jackson.databind.ObjectMapper();
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        var draft=calibrationDraft("synthetic-nonformal-action-purpose");
        draft.put("purposeCode",purpose);
        var values=(tools.jackson.databind.node.ArrayNode)draft.path("values");
        var unused=purpose.equals("DESCRIPTION_CORRECTION")?java.util.Set.of("MATERIAL_IMPROVEMENT_BOUND","DEMAND_SCENARIO_SET","FORMAL_NODES","STOP_RULE")
                :java.util.Set.of("MATERIAL_IMPROVEMENT_BOUND","FORMAL_NODES");
        for(int index=values.size()-1;index>=0;index--) if(unused.contains(values.get(index).path("categoryCode").asText())) values.remove(index);
        CurrentBusinessProtectionBasis protection=null;
        if(purpose.equals("BOUNDED_EXPLORATION")) {
            protection=currentBusinessProtectionBasis();
            configureCurrentBusinessProtectionRules(json,draft,protection,Map.of());
        }
        UUID packageId=UUID.fromString(activateSyntheticCalibrationWithIndependentOwner(draft));
        if(protection!=null) seedCurrentBusinessProtectionSources(protection,"bounded-"+UUID.randomUUID());
        UUID action=prepareDescriptionForPurpose(java.math.BigDecimal.ZERO,ListingConversionFixture.PRIOR_TEXT_ONE+".",
                purpose,300);
        mvc.perform(get("/api/v1/console/listing/actions/"+action).header(HttpHeaders.AUTHORIZATION,bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.purposeCode").value(purpose))
                .andExpect(jsonPath("$.calibrationPackageId").value(packageId.toString()));
        assertThat(jdbc.sql("""
                SELECT a.calibration_dependencies->>'purpose'=:purpose
                  AND r.proposed_parameters->>'purposeCode'=:purpose
                  AND EXISTS (SELECT 1 FROM ops.lc_task_responsibility b
                    WHERE b.recommendation_id=r.id AND b.calibration_package_id=a.calibration_package_id)
                FROM ops.lc_action a JOIN ops.recommendation r ON r.id=a.recommendation_id WHERE a.id=:id
                """).param("id",action).param("purpose",purpose).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_evaluation_plan WHERE action_id=:id")
                .param("id",action).query(Long.class).single()).isZero();
        assertThatThrownBy(()->jdbc.sql("""
                UPDATE ops.recommendation SET proposed_parameters=jsonb_set(proposed_parameters,'{purposeCode}','"LISTING_CONVERSION"')
                WHERE id=(SELECT recommendation_id FROM ops.lc_action WHERE id=:id)
                """).param("id",action).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(calibration.recheckAction(action,Instant.now()).outcome().resolved().packageId()).isEqualTo(packageId);
        assertThatThrownBy(()->jdbc.sql("UPDATE ops.lc_action SET purpose_basis='{}' WHERE id=:id")
                .param("id",action).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        userId=independentMeaningReviewer();
        mvc.perform(get("/api/v1/console/listing/actions/"+action+"/review-basis")
                .header(HttpHeaders.AUTHORIZATION,bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.purposeBasis.useConditions[0]").value("Retain only while the corrected product fact remains accurate"));
        mvc.perform(post("/api/v1/console/listing/actions/"+action+"/review").header(HttpHeaders.AUTHORIZATION,bearer())
                .contentType(MediaType.APPLICATION_JSON).content(meaningReviewRequest(action,false))).andExpect(status().isOk());
        assertThat(jdbc.sql("""
                SELECT r.evaluation_plan_digest IS NULL AND r.purpose_basis_digest=ops.lc_purpose_basis_digest(:purpose,a.purpose_basis)
                  AND r.meaning_assessment->>'basisDigest'=ops.lc_meaning_review_basis_digest(a.id)
                FROM ops.lc_action_review r JOIN ops.lc_action a ON a.id=r.action_id WHERE a.id=:id AND r.verdict='ATTESTED'
                """).param("id",action).param("purpose",purpose).query(Boolean.class).single()).isTrue();
        var recommendation=jdbc.sql("SELECT recommendation_id FROM ops.lc_action WHERE id=:id")
                .param("id",action).query(UUID.class).single();
        long proposalVersion=jdbc.sql("SELECT version FROM ops.recommendation WHERE id=:id")
                .param("id",recommendation).query(Long.class).single();
        mvc.perform(post("/api/v1/console/workflow/recommendations/"+recommendation+"/approval")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion",proposalVersion,"reason","Approve the exact synthetic independently reviewed use basis"))))
                .andExpect(result->assertThat(result.getResponse().getStatus())
                        .withFailMessage("Purpose approval failed: %s",result.getResolvedException()).isEqualTo(200));
        assertThat(jdbc.sql("""
                SELECT b.evaluation_plan_digest IS NULL AND b.purpose_basis_digest=ops.lc_purpose_basis_digest(:purpose,a.purpose_basis)
                   AND b.evidence_versions->>'purposeBasisDigest'=b.purpose_basis_digest
                   AND g.detail->>'purposeBasisDigest'=b.purpose_basis_digest
                   AND b.expires_at=d.scope_expires_at AND a.state='APPROVED'
                FROM ops.lc_action_binding b JOIN ops.lc_action a ON a.id=b.action_id
                  JOIN ops.approval_decision d ON d.id=b.approval_decision_id
                  JOIN ops.guardrail_evaluation g ON g.id=b.guardrail_evaluation_id WHERE a.id=:id
                """).param("id",action).param("purpose",purpose).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT unnest(ops.lc_binding_gaps(:id))").param("id",action).query(String.class).list()).isEmpty();
        if(purpose.equals("BOUNDED_EXPLORATION")) {
            assertThat(jdbc.sql("""
                    SELECT b.expires_at=(a.purpose_basis->>'useUntil')::timestamptz
                    FROM ops.lc_action_binding b JOIN ops.lc_action a ON a.id=b.action_id WHERE a.id=:id
                    """).param("id",action).query(Boolean.class).single()).isTrue();
            if(launchBeforeUseExpiry) {
                assertThat(jdbc.sql("""
                        SELECT (purpose_basis->>'useUntil')::timestamptz>clock_timestamp()
                        FROM ops.lc_action WHERE id=:id
                        """).param("id",action).query(Boolean.class).single()).isTrue();
                var authority=applicationContext.getBean(
                        com.mimococo.marketops.operationsworkflow.ListingActionDecisionAuthority.class);
                var current=authority.recheckedDecisionScope(recommendation).orElseThrow().protectionRecheck();
                assertThat(current).containsEntry("financialInputState","CANONICAL_INPUT_AVAILABLE")
                        .containsEntry("currentProfitVerdict","PASS")
                        .containsEntry("currentReturnVerdict","PASS")
                        .containsEntry("unitProfitFloorVerdict","PASS")
                        .containsEntry("supplyVerdict","PASS");
                String actionPath="/api/v1/console/listing/actions/"+action;
                mvc.perform(post(actionPath+"/allowance-preview").header(HttpHeaders.AUTHORIZATION,bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"axes\":{}}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.resolved").value(true))
                        .andExpect(jsonPath("$.gaps").isEmpty()).andExpect(jsonPath("$.axes.length()").value(2));
                users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_LAUNCH,
                        ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
                mvc.perform(post(actionPath+"/launch").header(HttpHeaders.AUTHORIZATION,bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"axes\":{}}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.launched").value(true))
                        .andExpect(jsonPath("$.occupationIds.length()").value(2))
                        .andExpect(jsonPath("$.insufficientAxes").isEmpty()).andExpect(jsonPath("$.commandId").isEmpty());
                assertThat(jdbc.sql("""
                        SELECT count(*)=2 AND bool_and(outcome='PASS'
                          AND detail->>'purposeCode'='BOUNDED_EXPLORATION'
                          AND detail->>'protectionRecheck.financialInputState'='CANONICAL_INPUT_AVAILABLE'
                          AND detail->>'protectionRecheck.currentProfitVerdict'='PASS'
                          AND detail->>'protectionRecheck.currentReturnVerdict'='PASS'
                          AND detail->>'protectionRecheck.unitProfitFloorVerdict'='PASS'
                          AND detail->>'protectionRecheck.supplyVerdict'='PASS')
                        FROM ops.guardrail_evaluation
                        WHERE recommendation_id=:id AND purpose IN ('APPROVAL','EXECUTION')
                        """).param("id",recommendation).query(Boolean.class).single()).isTrue();
                assertThat(jdbc.sql("SELECT state FROM ops.lc_action WHERE id=:id")
                        .param("id",action).query(String.class).single()).isEqualTo("LAUNCHED");
            } else {
                String originalPurpose=jdbc.sql("SELECT purpose_basis::text FROM ops.lc_action WHERE id=:id")
                        .param("id",action).query(String.class).single();
                String originalPurposeDigest=jdbc.sql("SELECT ops.lc_purpose_basis_digest(:purpose,purpose_basis) FROM ops.lc_action WHERE id=:id")
                        .param("purpose",purpose).param("id",action).query(String.class).single();
                Instant afterUse=jdbc.sql("SELECT (purpose_basis->>'useUntil')::timestamptz+interval '1 millisecond' FROM ops.lc_action WHERE id=:id")
                        .param("id",action).query(java.sql.Timestamp.class).single().toInstant();
                assertThat(jdbc.sql("""
                        SELECT CAST(:afterUse AS timestamptz)>(a.purpose_basis->>'useUntil')::timestamptz
                          AND CAST(:afterUse AS timestamptz)>b.expires_at
                        FROM ops.lc_action a JOIN ops.lc_action_binding b ON b.action_id=a.id WHERE a.id=:id
                        """).param("afterUse",java.sql.Timestamp.from(afterUse)).param("id",action)
                        .query(Boolean.class).single()).isTrue();
                // Advance only the mutable binding deadline. The reviewed use basis remains byte-for-byte frozen.
                assertThat(fixture.seed.sql("""
                        UPDATE ops.lc_action_binding SET expires_at=bound_at+interval '1 microsecond'
                         WHERE action_id=:id AND bound_at+interval '1 microsecond'<clock_timestamp()
                        """).param("id",action).update()).isEqualTo(1);
                assertThat(jdbc.sql("SELECT purpose_basis::text FROM ops.lc_action WHERE id=:id")
                        .param("id",action).query(String.class).single()).isEqualTo(originalPurpose);
                assertThat(jdbc.sql("SELECT ops.lc_purpose_basis_digest(:purpose,purpose_basis) FROM ops.lc_action WHERE id=:id")
                        .param("purpose",purpose).param("id",action).query(String.class).single()).isEqualTo(originalPurposeDigest);
                assertThat(jdbc.sql("SELECT unnest(ops.lc_binding_gaps(:id))").param("id",action).query(String.class).list())
                        .contains("BINDING_EXPIRED");
                users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_ACTION_LAUNCH,
                        ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
                mvc.perform(post("/api/v1/console/listing/actions/"+action+"/launch")
                        .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"axes\":{}}"))
                        .andExpect(result->assertThat(result.getResolvedException()).isInstanceOfSatisfying(
                                com.mimococo.marketops.shared.OperationRejectedException.class,
                                failure->assertThat(failure.errorCode()).isEqualTo(com.mimococo.marketops.shared.ErrorCode.BINDING_INAPPLICABLE)));
            }
        }
        if(purpose.equals("DESCRIPTION_CORRECTION")) {
            fixture.seed.sql("UPDATE ops.lc_action_binding SET purpose_basis_digest=NULL WHERE action_id=:id")
                    .param("id",action).update();
            assertThat(jdbc.sql("SELECT unnest(ops.lc_binding_gaps(:id))").param("id",action).query(String.class).list())
                    .contains("PURPOSE_USE_BASIS_MISSING_OR_CHANGED").doesNotContain("EVALUATION_PLAN_BINDING_MISSING_OR_CHANGED");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_launch WHERE action_id=:id")
                .param("id",action).query(Long.class).single()).isEqualTo(launchBeforeUseExpiry?1:0);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_description_command WHERE action_id=:id")
                .param("id",action).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ops.lc_exposure_occupation WHERE action_id=:id")
                .param("id",action).query(Long.class).single()).isEqualTo(launchBeforeUseExpiry?2:0);
    }

    private String activateSyntheticCalibrationWithIndependentOwner(tools.jackson.databind.node.ObjectNode draft) throws Exception {
        Instant effectiveFrom=Instant.now().minusSeconds(60);
        for(var action:List.of(ActionScopeCode.LISTING_CALIBRATION_PREPARE,ActionScopeCode.LISTING_CALIBRATION_VALIDATE))
            users.grantScope(OPERATOR,userId,action,ResourceScopeType.ORGANIZATION,fixture.id("organization"),effectiveFrom);
        var created=postCalibration("",draft);
        String id=created.path("package").path("id").asText();
        Map<String,String> decision=Map.of("digest",created.path("governance").path("draft_digest").asText(),
                "evidenceReference","fixture:exact-synthetic-replacement");
        postCalibration("/"+id+"/validate",decision);
        subject="calibration-replacement-owner-"+UUID.randomUUID();
        userId=users.provision(OPERATOR,fixture.id("organization"),providerId,subject,null,"Synthetic replacement Owner",null).id();
        jdbc.sql("UPDATE iam.user_account SET credentials_valid_from=now()-interval '1 hour' WHERE id=:id").param("id",userId).update();
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,effectiveFrom);
        users.grantScope(OPERATOR,userId,ActionScopeCode.LISTING_CALIBRATION_ACCEPT,
                ResourceScopeType.ORGANIZATION,fixture.id("organization"),effectiveFrom);
        postCalibration("/"+id+"/accept",decision);postCalibration("/"+id+"/activate",decision);
        return id;
    }

    private void ensureSyntheticDescriptionCorrection() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,Instant.now().minusSeconds(60));
        Instant at=jdbc.sql("SELECT clock_timestamp()").query(java.time.OffsetDateTime.class).single().toInstant();
        if(calibration.resolve(fixture.id("organization"),fixture.graph.platform(),fixture.id("store"),at,
                "DESCRIPTION_CORRECTION").ok()) return;
        var draft=calibrationDraft("description-correction-"+UUID.randomUUID().toString().substring(0,8));
        var values=(tools.jackson.databind.node.ArrayNode)draft.path("values");
        var unused=java.util.Set.of("MATERIAL_IMPROVEMENT_BOUND","DEMAND_SCENARIO_SET","FORMAL_NODES","STOP_RULE");
        for(int index=values.size()-1;index>=0;index--)
            if(unused.contains(values.get(index).path("categoryCode").asText())) values.remove(index);
        activateSyntheticCalibrationWithIndependentOwner(draft);
    }

    private static Map<String,Object> exactRestorationPurposeBasis() {
        return Map.of("evidenceReference","evidence://synthetic/exact-restoration",
                "useConditions",List.of("Restore only the exact captured prior description"),
                "endConditions",List.of("End after exact readback and management verification"));
    }

    private tools.jackson.databind.node.ObjectNode calibrationDraft(String code) {
        return calibrationDraft(code,fixture.id("calibrationPackage"));
    }

    private tools.jackson.databind.node.ObjectNode calibrationDraft(String code,UUID sourcePackage) {
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
                """).param("id",sourcePackage).query(String.class).single();
        draft.set("values",json.readTree(values));
        return draft;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"FOREIGN_LISTING","REVERSED_PERIOD","LOCAL_TIME"})
    void currentAccountingReferenceCannotAcquireAuthorityWithInvalidScopeOrPeriod(String defect) throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        for (var permission:List.of(ActionScopeCode.LISTING_CALIBRATION_PREPARE,ActionScopeCode.LISTING_CALIBRATION_VALIDATE))
            users.grantScope(OPERATOR,userId,permission,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        var draft=calibrationDraft("synthetic-invalid-accounting-reference");
        var mapper=new tools.jackson.databind.ObjectMapper();
        String from=defect.equals("LOCAL_TIME")?"2026-07-01T00:00:00":"2026-07-01T00:00:00Z";
        String to=defect.equals("REVERSED_PERIOD")?"2026-06-01T00:00:00Z":"2026-07-08T00:00:00Z";
        String listing=defect.equals("FOREIGN_LISTING")?UUID.randomUUID().toString():fixture.id("listing").toString();
        for (var value:draft.path("values")) if(value.path("categoryCode").asText().equals("NON_WORSENING_PROFIT_BOUND"))
            ((tools.jackson.databind.node.ObjectNode)value).set("json",mapper.valueToTree(Map.of(
                    "currentAccountingComparisons",Map.of(listing,Map.of("periodStart",from,"periodEnd",to,
                            "evidenceReference","fixture://invalid-reference")))));
        var created=postCalibration("",draft);
        assertThat(created.path("combinationFailures").toString()).contains("CURRENT_ACCOUNTING_REFERENCE");
        mvc.perform(post("/api/v1/console/listing/calibrations/"+created.path("package").path("id").asText()+"/validate")
                .header(HttpHeaders.AUTHORIZATION,bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("digest",created.path("governance").path("draft_digest").asText(),
                        "evidenceReference","fixture://invalid-reference-review")))).andExpect(status().isBadRequest());
    }

    @Test
    void calibrationValidationRejectsMissingComponentsAndIncorrectExactDigest() throws Exception {
        users.assignRole(OPERATOR,userId,BusinessRoleCode.OWNER,null);
        for (var action:List.of(ActionScopeCode.LISTING_CALIBRATION_PREPARE,ActionScopeCode.LISTING_CALIBRATION_VALIDATE)) {
            users.grantScope(OPERATOR,userId,action,ResourceScopeType.ORGANIZATION,fixture.id("organization"),null);
        }
        var json=new tools.jackson.databind.ObjectMapper();
        var draft=calibrationDraft("synthetic-incomplete-calibration");
        var values=(tools.jackson.databind.node.ArrayNode)draft.path("values");
        for(int index=values.size()-1;index>=0;index--) if(values.get(index).path("categoryCode").asText().equals("APPROVAL_VALIDITY")) values.remove(index);
        var created=postCalibration("",draft);
        String id=created.path("package").path("id").asText();
        assertThat(created.path("combinationFailures").toString()).contains("APPROVAL_VALIDITY");
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
        seedSummaryProfile(false);
    }

    private void seedSummaryProfile(boolean formalMethodInputs) {
        fixture.seed.sql("""
                INSERT INTO core.lc_summary_equivalence_profile (id,organization_id,platform_code,summary_kind,
                  profile_version,proof_state,covers_numerator,covers_denominator,covers_time_attribution,
                  covers_maturity,covers_revision,evidence_reference,published_by_user_id,published_at,effective_from,status,
                  source_method_input_version,covers_source_strata,covers_critical_groups)
                VALUES (:id,:org,:platform,'VISITS_AND_RETAINED_PURCHASES',1,'PROVEN',true,true,true,true,true,
                  'evidence://synthetic/summary-equivalence',:owner,now()-interval '1 year',
                  now()-interval '1 year','ACTIVE',
                  :methodVersion,:coversSource,:coversGroups)
                """).param("id", UUID.randomUUID()).param("org", fixture.id("organization"))
                .param("platform", fixture.graph.platform()).param("owner",fixture.id("ownerUser"))
                .param("methodVersion",formalMethodInputs?1:null).param("coversSource",formalMethodInputs)
                .param("coversGroups",formalMethodInputs).update();
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
        @Bean @Primary ListingDescriptionLoopback descriptionLoopback() { return new ListingDescriptionLoopback(); }
        @Bean @Primary com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort connectedObjects() {
            return new com.mimococo.marketops.marketplaceintegration.port.InMemoryObjectStoragePort();
        }

        @Bean
        @Primary
        JwtDecoder localDecoder() throws JOSEException {
            return NimbusJwtDecoder.withPublicKey(SIGNING_KEY.toRSAPublicKey()).build();
        }
    }
}
