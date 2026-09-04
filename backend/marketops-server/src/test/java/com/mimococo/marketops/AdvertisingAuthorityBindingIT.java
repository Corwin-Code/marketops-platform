package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * An advertising decision is bound to the advertising authority.
 *
 * <p>This test exists because it was not. ops.approval_decision and
 * ops.guardrail_evaluation are shared by both write-capable actions and both
 * carried an unconditional trigger that called the price authority. No branch on
 * the action, no WHEN clause. Every advertising guardrail insert was refused
 * with MO032, so no execution PASS could exist, so no advertising command could
 * ever be created — the entire controlled-write path was unreachable and
 * nothing said so.
 *
 * <p>The price snapshot did not fail loudly for an advertising subject, which is
 * why it survived: it LEFT JOINs the listing variant, so an advertising subject
 * produces a document that is structurally valid, carries the right
 * organization, and describes nothing.
 */
@SpringBootTest
@ActiveProfiles("ci")
class AdvertisingAuthorityBindingIT {

    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE =
            TestDatabase.isolatedContainer();

    private static JdbcClient seed;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @BeforeAll
    static void openSeedConnection() {
        seed = JdbcClient.create(new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                TestDatabase.migrationRole(), TestDatabase.migrationPassword()));
    }

    @Test
    @DisplayName("TC-AD-AUTH-001 an advertising guardrail binds to the advertising authority")
    void advertisingGuardrailIsRecordable() {
        var graph = AdvertisingGraphFixture.seed(seed);
        var decision = AdvertisingGraphFixture.seedDecision(seed, graph,
                "PROTECTION_DECREASE", "MAX_CPC_BOUNDED");

        String snapshot = adAuthority(decision.recommendationId());
        assertThat(snapshot).isNotNull();

        // The insert the whole write path depends on, and which used to be
        // refused unconditionally by the binder before any constraint was even
        // reached. A BLOCK verdict, because a PASS additionally has to name the
        // policy bundle that let it pass and no bundle can be active while no
        // advertising capability is verified — which is the next test.
        assertThatCode(() -> insertGuardrail(graph.organizationId(),
                decision.recommendationId(), "EXECUTION", snapshot, false, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TC-AD-AUTH-006 a passing verdict must name the bundle that let it pass")
    void aPassingVerdictMustNameItsAuthority() {
        var graph = AdvertisingGraphFixture.seed(seed);
        var decision = AdvertisingGraphFixture.seedDecision(seed, graph,
                "PROTECTION_DECREASE", "MAX_CPC_BOUNDED");
        String snapshot = adAuthority(decision.recommendationId());

        // policy_id is a foreign key into the price commercial policy, which an
        // advertising decision does not have. Before V0052 a PASS could not be
        // recorded at all; now it can, and only by naming the decision policy
        // bundle. No bundle is active here — none can be, while no advertising
        // capability is verified — so this is the structural reason no
        // advertising command exists yet, stated as a refusal rather than a
        // silence.
        assertThatThrownBy(() -> insertGuardrail(graph.organizationId(),
                decision.recommendationId(), "EXECUTION", snapshot, true, null))
                .hasMessageContaining("guardrail_evaluation_policy_presence_ck");
    }

    @Test
    @DisplayName("TC-AD-AUTH-002 the price authority cannot stand in for the advertising one")
    void priceAuthorityIsRefusedForAnAdvertisingDecision() {
        var graph = AdvertisingGraphFixture.seed(seed);
        var decision = AdvertisingGraphFixture.seedDecision(seed, graph,
                "PROTECTION_DECREASE", "MAX_CPC_BOUNDED");

        String priceSnapshot = seed.sql(
                        "SELECT ops.price_authority_snapshot(:id)::text")
                .param("id", decision.recommendationId()).query(String.class).single();

        // Structurally valid and describes nothing: this is exactly the document
        // the old trigger stamped on every advertising decision.
        assertThat(priceSnapshot).contains("\"actionKind\": \"AD_BID_CHANGE\"");
        assertThatThrownBy(() -> insertGuardrail(graph.organizationId(),
                decision.recommendationId(), "EXECUTION", priceSnapshot, false, null))
                .hasMessageContaining("guardrail inputs changed");
    }

    @Test
    @DisplayName("TC-AD-AUTH-003 the advertising identity moves when the advertising facts move")
    void identityMovesWithTheFacts() {
        var graph = AdvertisingGraphFixture.seed(seed);
        var decision = AdvertisingGraphFixture.seedDecision(seed, graph,
                "PROTECTION_DECREASE", "MAX_CPC_BOUNDED");
        String before = digest(graph.objectId(), decision.candidateId());

        // A new configuration observation superseding the old one is precisely
        // the fact an approval must not survive: the bid it was given for is no
        // longer the bid the platform holds.
        seed.sql("""
                INSERT INTO core.ad_object_configuration_observation (
                        id, organization_id, ad_native_object_id, provenance_id,
                        semantic_profile_id, lineage_generation, observed_bid_amount,
                        bid_currency_code, bid_unit_code, observed_status,
                        observed_bidding_mode, evidence_grade, observed_at, source_time,
                        supersedes_observation_id, created_at)
                SELECT gen_random_uuid(), :organization, :object, c.provenance_id,
                       c.semantic_profile_id, 1, 41.0000, 'RUB', 'CURRENCY_MAJOR', 'RUNNING',
                       'MANUAL_BID', 'OFFICIAL_API_READBACK', now(), now(), :configuration,
                       now()
                  FROM core.ad_object_configuration_observation c WHERE c.id = :configuration
                """).param("organization", graph.organizationId())
                .param("object", graph.objectId())
                .param("configuration", graph.configurationId()).update();

        assertThat(digest(graph.objectId(), decision.candidateId())).isNotEqualTo(before);
    }

    @Test
    @DisplayName("TC-AD-AUTH-004 the identity is not the same constant for every object")
    void identityDistinguishesObjects() {
        // The failure this rules out is the one the metric-derived digest had:
        // an advertising subject has no canonical metric values, so digesting
        // them produced one constant for every object in the product.
        var first = AdvertisingGraphFixture.seed(seed);
        var firstDecision = AdvertisingGraphFixture.seedDecision(seed, first,
                "PROTECTION_DECREASE", "MAX_CPC_BOUNDED");
        var second = AdvertisingGraphFixture.seed(seed);
        var secondDecision = AdvertisingGraphFixture.seedDecision(seed, second,
                "PROTECTION_DECREASE", "MAX_CPC_BOUNDED");

        assertThat(firstDecision.entityVersionDigest())
                .isNotEqualTo(secondDecision.entityVersionDigest())
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("TC-AD-AUTH-005 the dispatching binder kept everything the price path had")
    void priceBindingIsUnchanged() {
        // This is the guard for the mistake that produced it. The binder had
        // already been replaced once, by V0029, which added the as-of
        // comparison, the staleness predicate and the fulfilment-mode
        // assignment. Rebuilding it from the original V0020 body silently
        // reverted all three, and thirty-nine price cases failed with an
        // authority mismatch that named none of them.
        //
        // So the assertion is on the definition actually installed: it
        // dispatches, and it still contains every marker V0029 introduced.
        String source = seed.sql("""
                SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'ops' AND p.proname = 'bind_price_authority_snapshot'
                """).query(String.class).single();

        assertThat(source)
                .describedAs("dispatches on the action the recommendation names")
                .contains("ops.ad_bid_authority_snapshot(NEW.recommendation_id)")
                .contains("ops.price_authority_snapshot(NEW.recommendation_id)")
                .describedAs("keeps the as-of comparison V0029 added")
                .contains("ops.price_authority_snapshot(NEW.recommendation_id, NEW.evaluated_at)")
                .describedAs("keeps the staleness predicate V0029 added")
                .contains("ops.r2_price_authority_is_current")
                .describedAs("keeps the fulfilment mode V0029 made durable command authority")
                .contains("NEW.fulfillment_mode_code");

        // And exactly one binder, on all three tables, so there is nothing to
        // keep in step.
        assertThat(seed.sql("""
                SELECT count(DISTINCT p.proname) FROM pg_trigger t
                  JOIN pg_proc p ON p.oid = t.tgfoid
                  JOIN pg_class c ON c.oid = t.tgrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE NOT t.tgisinternal AND n.nspname = 'ops'
                   AND c.relname IN ('approval_decision', 'guardrail_evaluation',
                                     'price_command')
                   AND p.proname LIKE 'bind_%'
                """).query(Integer.class).single()).isEqualTo(1);
    }

    private String adAuthority(UUID recommendationId) {
        return seed.sql("SELECT ops.ad_bid_authority_snapshot(:id)::text")
                .param("id", recommendationId).query(String.class).single();
    }

    private String digest(UUID objectId, UUID candidateId) {
        return seed.sql("SELECT ops.ad_entity_version_digest(:object, :candidate)")
                .param("object", objectId).param("candidate", candidateId)
                .query(String.class).single();
    }

    private void insertGuardrail(UUID organizationId, UUID recommendationId, String purpose,
                                 String authoritySnapshot, boolean passed, UUID bundleId) {
        jdbc.sql("""
                INSERT INTO ops.guardrail_evaluation (id, organization_id, recommendation_id,
                        purpose, outcome, reason_codes, detail, input_digest, evaluated_at,
                        correlation_id, authority_snapshot, ad_decision_bundle_id,
                        ad_bundle_version)
                VALUES (:id, :organization, :recommendation, :purpose, :outcome,
                        CAST(:reasons AS text[]), '{}'::jsonb, :inputDigest, now(),
                        'authority-fixture', CAST(:snapshot AS jsonb), :bundle, :bundleVersion)
                """)
                .param("id", UUID.randomUUID())
                .param("organization", organizationId)
                .param("recommendation", recommendationId)
                .param("purpose", purpose)
                .param("outcome", passed ? "PASS" : "BLOCK")
                .param("reasons", passed ? "{}" : "{AD_POLICY_BUNDLE_UNRESOLVED}")
                .param("inputDigest", "e".repeat(64))
                .param("snapshot", authoritySnapshot)
                .param("bundle", bundleId)
                .param("bundleVersion", bundleId == null ? null : 1)
                .update();
    }
}
