package com.mimococo.marketops.identityaccess.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
import com.mimococo.marketops.identityaccess.internal.application.IdentityProviderService;
import com.mimococo.marketops.identityaccess.internal.application.UserAdministrationService;
import com.mimococo.marketops.identityaccess.internal.config.IdentityProperties;
import com.mimococo.marketops.identityaccess.internal.domain.RoleAssignment;
import com.mimococo.marketops.identityaccess.internal.domain.UserScopeGrantRecord;
import com.mimococo.marketops.shared.Digest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Real signatures, servlet filters and migrated PostgreSQL; no external issuer or JWKS calls. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
@Import(SignedBearerIdentityIT.LocalSigningKey.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignedBearerIdentityIT {
    private static final String ISSUER = "https://identity.example.invalid/synthetic";
    private static final String AUDIENCE = "marketops-synthetic-console";
    private static final String OPERATOR = "synthetic-identity-fixture";
    private static final String POLICIES = "/api/v1/console/policy/policies";
    private static final RSAKey SIGNING_KEY = signingKey();
    private static final RSAKey OTHER_KEY = signingKey();
    private static final String POLICY_BODY = """
            {"policyCode":"signed-bearer-fixture","policyVersion":1,"scopeKind":"ORGANIZATION",
             "lifecycleObjective":"GROWTH","currencyCode":"RUB","reason":"synthetic boundary check",
             "limits":[{"limitCode":"MIN_DATA_COMPLETENESS","rateValue":0.3},
                       {"limitCode":"MIN_CONTRIBUTION_MARGIN","rateValue":0.01},
                       {"limitCode":"MAX_SINGLE_CHANGE_RATE","rateValue":0.15},
                       {"limitCode":"MAX_DAILY_CHANGE_RATE","rateValue":0.2},
                       {"limitCode":"MIN_UNIT_CONTRIBUTION_PROFIT","amountValue":0.5},
                       {"limitCode":"MIN_AVAILABLE_UNITS","countValue":1},
                       {"limitCode":"MAX_INPUT_AGE_SECONDS","durationSeconds":2592000},
                       {"limitCode":"COOLDOWN_SECONDS","durationSeconds":60}]}
            """;

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired IdentityProviderService providers;
    @Autowired UserAdministrationService users;
    @Autowired JwtDecoder decoder;
    @Autowired MarketOpsJwtAuthenticationConverter converter;
    private UUID organizationId;
    private UUID providerId;
    private UUID userId;
    private String subject;
    private String session;
    private RoleAssignment role;
    private UserScopeGrantRecord scope;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        var database = TestDatabase.isolatedContainer();
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
    void provisionSyntheticAuthority() {
        organizationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO core.organization (id,code,display_name,status,created_at,updated_at)
                VALUES (:id,'signed-bearer-org','Synthetic Identity Organization','ACTIVE',now(),now())
                """).param("id", organizationId).update();
        var provider = providers.register(OPERATOR, "signed-bearer-provider", "Synthetic OIDC",
                ISSUER, 900, "synthetic-platform");
        providerId = providers.verifyAndActivate(OPERATOR, provider.id(), "amr", "mfa",
                "evidence://synthetic/signed-bearer", "Local signed token fixture only", provider.version()).id();
    }

    @BeforeEach
    void provisionIndependentHuman() {
        subject = "synthetic-subject-" + UUID.randomUUID();
        session = "synthetic-session-" + UUID.randomUUID();
        userId = users.provision(OPERATOR, organizationId, providerId, subject,
                null, "Synthetic Operator", null).id();
        // The fixture represents an existing provisioned user. NumericDate iat
        // is second precision and must not precede a new profile's microseconds.
        jdbc.sql("UPDATE iam.user_account SET credentials_valid_from=now()-interval '1 hour' WHERE id=:id")
                .param("id", userId).update();
        role = users.assignRole(OPERATOR, userId, BusinessRoleCode.OWNER, null);
        scope = users.grantScope(OPERATOR, userId, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScopeType.ORGANIZATION, organizationId, null);
        users.grantScope(OPERATOR, userId, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                ResourceScopeType.ORGANIZATION, organizationId, null);
    }

    @Test
    void signedBearerReachesDatabaseAndRetainsOnlyDigestsInTheSessionJournal() throws Exception {
        String bearer = sign(claims());
        var result = mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                .andExpect(status().isOk()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        var event = jdbc.sql("""
                SELECT subject_digest,session_digest,decision FROM iam.identity_decision_event
                WHERE user_id=:id AND decision='AUTHENTICATED'
                """).param("id", userId).query().singleRow();
        assertThat(event.get("subject_digest")).isEqualTo(Digest.ofComponents(List.of(ISSUER, subject)));
        assertThat(event.get("session_digest")).isEqualTo(Digest.ofComponents(List.of(ISSUER, session)));
        assertThat(event.toString()).doesNotContain(bearer, subject, session);
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "expired", "not-yet-valid", "missing-expiry",
            "missing-issued-at", "future-issued-at", "future-authentication", "fractional-authentication"})
    void invalidSignedClaimsCannotAuthenticate(String invalid) throws Exception {
        var claims = claims();
        switch (invalid) {
            case "issuer" -> claims.issuer("https://other.example.invalid");
            case "audience" -> claims.audience("other-application");
            case "expired" -> claims.expirationTime(Date.from(Instant.now().minusSeconds(300)));
            case "not-yet-valid" -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(300)));
            case "missing-expiry" -> claims.expirationTime(null);
            case "missing-issued-at" -> claims.issueTime(null);
            case "future-issued-at" -> claims.issueTime(Date.from(Instant.now().plusSeconds(300)));
            case "future-authentication" -> claims.claim("auth_time", Instant.now().plusSeconds(300).getEpochSecond());
            case "fractional-authentication" -> claims.claim("auth_time", 1.25);
            default -> throw new AssertionError(invalid);
        }
        assertUnauthenticated(sign(claims));
    }

    @Test
    void signatureFromAnotherKeyIsRefused() throws Exception {
        assertUnauthenticated(sign(claims(), OTHER_KEY));
    }

    @Test
    void freshBearerAuthorizesAnAuditedMutationWithoutAnAuthCookieOrCsrfToken() throws Exception {
        String token = sign(claims());
        var result = mvc.perform(post(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON).content(POLICY_BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isString())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.commercial_policy
                WHERE organization_id=:org AND published_by_user_id=:user AND status='ACTIVE'
                """).param("org", organizationId).param("user", userId).query(Integer.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.metadata_audit_event
                WHERE actor_id=:actor AND action='POLICY_CHANGE' AND entity_type='commercial-policy'
                """).param("actor", userId.toString()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void credentialBoundaryInvalidatesAnExistingTokenWithoutDisablingTheUser() throws Exception {
        String token = sign(claims());
        mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
        jdbc.sql("UPDATE iam.user_account SET credentials_valid_from=:boundary WHERE id=:id")
                .param("boundary", java.sql.Timestamp.from(Instant.now().minusSeconds(1)))
                .param("id", userId).update();
        mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("USER_INACTIVE"));
        String fresh = sign(claims().issueTime(Date.from(Instant.now())));
        mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + fresh)).andExpect(status().isOk());
    }

    @Test
    void unverifiedIssuerCannotBePromotedByItsCorrectSignature() throws Exception {
        providers.retire(OPERATOR, providerId, "synthetic retirement", providers.require(providerId).version());
        try {
            mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(claims())))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.title").value("IDENTITY_PROVIDER_NOT_ACCEPTED"));
        } finally {
            providers.verifyAndActivate(OPERATOR, providerId, "amr", "mfa", "evidence://synthetic/signed-bearer",
                    "Local signed token fixture only", providers.require(providerId).version());
        }
    }

    @Test
    void similarlyNamedMfaClaimIsNotTheConfiguredClaim() throws Exception {
        String token = sign(claims().claim("amr", List.of("pwd")).claim("acr", "mfa"));
        mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.title").value("MULTI_FACTOR_REQUIRED"));
    }

    @Test
    void disabledUserIsRefusedWithTheSamePreviouslyAcceptedToken() throws Exception {
        String token = sign(claims());
        mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
        users.disable(OPERATOR, userId, "synthetic revocation", users.require(userId).version());
        mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("USER_INACTIVE"));
    }

    @Test
    void scopeRevocationIsImmediateWithinTheSessionRecordingInterval() throws Exception {
        String token = sign(claims());
        mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
        users.revokeScope(OPERATOR, scope.id(), "synthetic revocation", scope.version());
        mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("RESOURCE_SCOPE_DENIED"));
    }

    @Test
    void tokenRoleClaimCannotReplaceRevokedLiveDatabaseRole() throws Exception {
        String token = sign(claims().claim("roles", List.of("OWNER")).claim("scope", "*"));
        mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
        users.revokeRole(OPERATOR, role.id(), "synthetic revocation", role.version());
        mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("ACTION_NOT_PERMITTED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "stale"})
    void tokenRenewalDoesNotRenewHumanStepUp(String age) throws Exception {
        String token = sign(claims().claim("auth_time", age.equals("missing") ? null : Instant.now().minusSeconds(1800).getEpochSecond()));
        mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(POLICY_BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("STEP_UP_REQUIRED"));
        assertNoPolicyPublished();
    }

    @Test
    void cookiesQueryParametersAndFormBodiesDoNotAuthenticate() throws Exception {
        String token = sign(claims());
        mvc.perform(get(POLICIES).cookie(new Cookie("access_token", token), new Cookie("JSESSIONID", "synthetic-session")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(POLICIES).param("access_token", token)).andExpect(status().isUnauthorized());
        mvc.perform(post(POLICIES).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("access_token", token)).andExpect(status().isUnauthorized());
        mvc.perform(post(POLICIES).header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .cookie(new Cookie("access_token", token)).contentType(MediaType.APPLICATION_JSON).content(POLICY_BODY))
                .andExpect(status().isUnauthorized());
        assertNoPolicyPublished();
    }

    @Test
    void existingServletSessionCannotSupplyAmbientAuthentication() throws Exception {
        String token = sign(claims());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(converter.convert(decoder.decode(token))));
        mvc.perform(post(POLICIES).session(session).contentType(MediaType.APPLICATION_JSON).content(POLICY_BODY))
                .andExpect(status().isUnauthorized());
        assertNoPolicyPublished();
    }

    @Test
    void untrustedCrossOriginMutationIsRefusedEvenWithAValidBearer() throws Exception {
        mvc.perform(post(POLICIES).header(HttpHeaders.ORIGIN, "https://evil.example.invalid")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(claims()))
                        .contentType(MediaType.APPLICATION_JSON).content(POLICY_BODY))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertNoPolicyPublished();
    }

    @Test
    void operationalSnapshotTrustsOnlyTheSocketPeerAndNeverBearerOrForwardedHeaders() throws Exception {
        for (String peer : List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")) {
            mvc.perform(get("/actuator/operations").with(request -> { request.setRemoteAddr(peer); return request; }))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value(1))
                    .andExpect(jsonPath("$.signals.database_readiness_failed").value(0))
                    .andExpect(jsonPath("$.signals.*").value(org.hamcrest.Matchers.hasSize(6)));
        }
        for (boolean bearer : List.of(false, true)) {
            var request = get("/actuator/operations").with(r -> { r.setRemoteAddr("192.0.2.10"); return r; })
                    .header("X-Forwarded-For", "127.0.0.1").header("Forwarded", "for=127.0.0.1;proto=https");
            if (bearer) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(claims()));
            mvc.perform(request).andExpect(bearer ? status().isForbidden() : status().isUnauthorized())
                    .andExpect(jsonPath("$.signals").doesNotExist());
        }
        mvc.perform(post("/actuator/operations")).andExpect(status().isUnauthorized());
    }

    private void assertNoPolicyPublished() {
        assertThat(jdbc.sql("SELECT count(*) FROM ops.commercial_policy WHERE published_by_user_id=:user")
                .param("user", userId).query(Integer.class).single()).isZero();
    }

    private void assertUnauthenticated(String token) throws Exception {
        var result = mvc.perform(get(POLICIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.title").value("AUTHENTICATION_REQUIRED"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token, subject, session, ISSUER);
    }

    private JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(2))).expirationTime(Date.from(now.plusSeconds(600)))
                .claim("auth_time", now.minusSeconds(5).getEpochSecond())
                .claim("amr", List.of("pwd", "mfa")).claim("sid", session);
    }

    private static String sign(JWTClaimsSet.Builder claims) throws JOSEException {
        return sign(claims, SIGNING_KEY);
    }

    private static String sign(JWTClaimsSet.Builder claims, RSAKey key) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("synthetic-key").build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey signingKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("synthetic-key").generate();
        } catch (JOSEException failed) {
            throw new ExceptionInInitializerError(failed);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalSigningKey {
        @Bean @Primary
        JwtDecoder localDecoder(IdentityProperties properties) throws JOSEException {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(SIGNING_KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(IdentitySecurityConfig.tokenValidator(properties));
            return decoder;
        }
    }
}
