package com.mimococo.marketops;

import com.mimococo.marketops.identityaccess.*;
import com.mimococo.marketops.identityaccess.internal.application.UserAdministrationService;
import com.mimococo.marketops.identityaccess.internal.web.BrowserSigningFixture;
import com.mimococo.marketops.operationsworkflow.*;
import com.mimococo.marketops.operationsworkflow.internal.application.*;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.marketplaceintegration.internal.application.PriceCommandWorker;
import com.mimococo.marketops.marketplaceintegration.port.PriceWritePort;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteResult;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** Test-classpath-only server: real servlet/JWT/DB/business flow, synthetic issuer and marketplace. */
public final class BrowserFixtureApplication {
    private static final UUID STORE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private BrowserFixtureApplication() { }

    public static void main(String[] args) throws Exception {
        if (!"ISOLATED_SYNTHETIC_DATABASE".equals(System.getenv("MARKETOPS_BROWSER_FIXTURE"))) {
            throw new IllegalStateException("Explicit isolated browser fixture required");
        }
        var application = new SpringApplication(MarketOpsServerApplication.class,
                BrowserSigningFixture.class, SyntheticMarketplace.class);
        application.addInitializers(initial -> {
            var env = initial.getEnvironment();
            String url = env.getRequiredProperty("spring.datasource.url");
            if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/marketops")) {
                throw new IllegalStateException("Browser fixture requires the loopback compose database");
            }
            var preflight = JdbcClient.create(new DriverManagerDataSource(url,
                    env.getRequiredProperty("spring.datasource.username"), env.getRequiredProperty("spring.datasource.password")));
            boolean exists = preflight.sql("SELECT to_regclass('core.organization') IS NOT NULL").query(Boolean.class).single();
            if (exists && preflight.sql("SELECT count(*) FROM core.organization").query(Long.class).single() != 0) {
                throw new IllegalStateException("Browser fixture refuses nonempty data before migrations");
            }
        });
        var context = application.run("--spring.profiles.active=local",
                "--marketops.identity.oidc.issuer-uri=" + BrowserSigningFixture.ISSUER,
                "--marketops.identity.oidc.jwk-set-uri=" + BrowserSigningFixture.ISSUER + "/jwks",
                "--marketops.identity.oidc.audience=" + BrowserSigningFixture.AUDIENCE,
                "--marketops.price-write.worker-enabled=false",
                "--marketops.acquisition.scheduler-enabled=false",
                "--marketops.diagnostic-export.worker-enabled=true",
                "--marketops.object-storage.root-directory=" + Files.createTempDirectory("marketops-browser-custody-"),
                "--logging.level.com.mimococo.marketops=INFO");
        try {
            var env = context.getEnvironment();
            String url = env.getRequiredProperty("spring.datasource.url");
            if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/marketops")) {
                throw new IllegalStateException("Browser fixture requires the loopback compose database");
            }
            var fixture = JdbcClient.create(new DriverManagerDataSource(url,
                    env.getRequiredProperty("spring.flyway.user"), env.getRequiredProperty("spring.flyway.password")));
            if (fixture.sql("SELECT count(*) FROM core.organization").query(Integer.class).single() != 0) {
                throw new IllegalStateException("Browser fixture refuses a nonempty database");
            }
            var graph = PriceCommandFixture.seedReviewGraph(fixture, "browser-" + UUID.randomUUID(), STORE);
            fixture.sql("UPDATE iam.identity_provider SET issuer=:issuer WHERE id=:id")
                    .param("issuer", BrowserSigningFixture.ISSUER).param("id", graph.providerId()).update();
            var users = context.getBean(UserAdministrationService.class);
            users.assignRole("synthetic-browser", graph.userId(), BusinessRoleCode.OWNER, null);
            Instant validFrom = Instant.now().minusSeconds(60);
            for (var action : List.of(ActionScopeCode.DIAGNOSTIC_VIEW, ActionScopeCode.EVIDENCE_VIEW,
                    ActionScopeCode.PRICE_CHANGE_APPROVE, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                    ActionScopeCode.RECOMMENDATION_MANAGE, ActionScopeCode.COMMAND_RESOLVE)) {
                users.grantScope("synthetic-browser", graph.userId(), action,
                        ResourceScopeType.ORGANIZATION, graph.organizationId(), validFrom);
            }
            seedMetrics(fixture, graph);
            var actor = new AuthenticatedActor(graph.userId(), graph.organizationId(), graph.providerId(),
                    BrowserSigningFixture.ISSUER, "Synthetic Browser Operator", "synthetic-subject", "synthetic-session",
                    Instant.now(), Instant.now().plusSeconds(600), true, Set.of(BusinessRoleCode.OWNER));
            context.getBean(CommercialPolicyService.class).publish(actor, new CommercialPolicyService.PolicyDraft(
                    "browser-policy", 1, "ORGANIZATION", null, null, null, "GROWTH", "RUB", List.of(
                    rate("MIN_DATA_COMPLETENESS", "0.7"), rate("MIN_CONTRIBUTION_MARGIN", "0.1"),
                    rate("MAX_SINGLE_CHANGE_RATE", "0.15"), rate("MAX_DAILY_CHANGE_RATE", "0.2"),
                    new CommercialPolicyService.LimitDraft("MIN_UNIT_CONTRIBUTION_PROFIT", null, new BigDecimal("1"), null, null),
                    new CommercialPolicyService.LimitDraft("MIN_AVAILABLE_UNITS", null, null, 1, null),
                    new CommercialPolicyService.LimitDraft("MAX_INPUT_AGE_SECONDS", null, null, null, 7200L),
                    new CommercialPolicyService.LimitDraft("COOLDOWN_SECONDS", null, null, null, 60L)), "Synthetic browser policy"));
            var recommendations = context.getBean(RecommendationService.class);
            UUID recommendation = recommendations.propose(graph.userId().toString(), graph.organizationId(), STORE,
                    graph.subjectId(), ActionKind.PRICE_CHANGE, "DETERMINISTIC", null, graph.calculationRunId(),
                    MetricWindow.D30, new BigDecimal("500"), Map.of("targetPrice", "105.0000"), Map.of(), "LOW", 14, List.of());
            for (var state : List.of(RecommendationState.VALIDATED, RecommendationState.READY_FOR_REVIEW)) {
                recommendations.transition(graph.userId().toString(), recommendation, state, null,
                        recommendations.require(recommendation).version());
            }
            context.getBean(PilotAllowlistService.class).grant(actor, "OZON", STORE, graph.subjectId(),
                    validFrom, Instant.now().plusSeconds(3600), "Synthetic browser fixture only");
            String subject = fixture.sql("SELECT external_subject FROM iam.user_account WHERE id=:id")
                    .param("id", graph.userId()).query(String.class).single();
            var mapper = context.getBean(ObjectMapper.class);
            // Loopback test driver only; these handlers never enter a production artifact.
            var driver = HttpServer.create(new InetSocketAddress("127.0.0.1", 8082), 0);
            driver.createContext("/fixture", exchange -> {
                try {
                    if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
                    byte[] bytes = mapper.writeValueAsBytes(Map.of("accessToken", BrowserSigningFixture.token(subject),
                            "storeId", STORE, "subjectId", graph.subjectId(), "recommendationId", recommendation,
                            "provenanceId", graph.provenanceId()));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Exception failed) { exchange.sendResponseHeaders(500, -1); }
                finally { exchange.close(); }
            });
            driver.createContext("/advance", exchange -> {
                try {
                    if (!"POST".equals(exchange.getRequestMethod()) || !"browser-test".equals(exchange.getRequestHeaders().getFirst("X-Fixture-Driver"))) {
                        exchange.sendResponseHeaders(405, -1); return;
                    }
                    UUID command = fixture.sql("SELECT id FROM ops.price_command WHERE recommendation_id=:id")
                            .param("id", recommendation).query(UUID.class).single();
                    if (!context.getBean(PriceCommandWorker.class).advance(command)) throw new IllegalStateException("Not advanced");
                    byte[] bytes = mapper.writeValueAsBytes(Map.of("commandId", command));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Exception failed) { exchange.sendResponseHeaders(500, -1); }
                finally { exchange.close(); }
            });
            driver.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> driver.stop(0)));
        } catch (Exception failed) {
            context.close();
            throw failed;
        }
    }

    private static CommercialPolicyService.LimitDraft rate(String code, String value) {
        return new CommercialPolicyService.LimitDraft(code, new BigDecimal(value), null, null, null);
    }

    private static void seedMetrics(JdbcClient jdbc, PriceCommandFixture.SeedIds graph) {
        for (var item : Map.of("UNIT_COST", "50", "PLATFORM_FEES", "10", "MINIMUM_PRICE", "60",
                "DATA_COMPLETENESS", "1", "PLATFORM_AVAILABLE_UNITS", "30").entrySet()) {
            jdbc.sql("""
                    INSERT INTO mart.metric_value(id,organization_id,calculation_run_id,metric_code,definition_version,
                        subject_kind,subject_id,window_code,period_start,period_end,value_state,numeric_value,
                        currency_code,confidence_state,estimated,input_digest,computed_at,oldest_source_time,freshness_seconds)
                    VALUES(gen_random_uuid(),:org,:run,:code,1,'PLATFORM_LISTING_VARIANT',:subject,'D30',now()-interval '30 days',
                        now(),'AVAILABLE',:value,'RUB','CANONICAL_CONFIRMED',false,repeat('1',64),now(),now()-interval '1 hour',3600)
                    """).param("org", graph.organizationId()).param("run", graph.calculationRunId())
                    .param("code", item.getKey()).param("subject", graph.subjectId()).param("value", new BigDecimal(item.getValue())).update();
        }
        jdbc.sql("""
                INSERT INTO mart.metric_input_reference(id,metric_value_id,reference_kind,reference_id)
                SELECT gen_random_uuid(),id,'FACT_PROVENANCE',:provenance FROM mart.metric_value WHERE subject_id=:subject
                """).param("provenance", graph.provenanceId()).param("subject", graph.subjectId()).update();
        jdbc.sql("""
                INSERT INTO mart.diagnosis_finding(id,organization_id,calculation_run_id,subject_kind,subject_id,window_code,
                    rule_code,rule_version,outcome,severity,detail,evaluated_at,period_start,period_end,input_digest)
                VALUES(gen_random_uuid(),:org,:run,'PLATFORM_LISTING_VARIANT',:subject,'D30','LOW_CLICK_THROUGH',1,
                    'TRIGGERED','WARNING','{}'::jsonb,now(),now()-interval '30 days',now(),repeat('1',64))
                """).param("org", graph.organizationId()).param("run", graph.calculationRunId()).param("subject", graph.subjectId()).update();
    }

    /** All outbound execution is replaced at the port; requests still use the real worker and custody. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SyntheticMarketplace {
        @Bean @Primary
        PriceWritePort browserMarketplace() {
            return request -> {
                boolean readback = request.operation().name().equals("READBACK");
                byte[] body = (readback ? "{\"price\":\"105.0000\",\"currency\":\"RUB\"}" : "{\"accepted\":true}")
                        .getBytes(StandardCharsets.UTF_8);
                return new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, "HTTP 200", null,
                        readback ? new BigDecimal("105.0000") : null, readback ? "RUB" : null,
                        body, Instant.now(), null).withResponse(body, new PriceWriteResult.Response(
                                200, Map.of("etag", "synthetic-browser-version"), request.digest(), "PROTOCOL_FIXTURE"));
            };
        }
    }
}
