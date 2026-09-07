package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** One policy/fact fault per fresh ordinary-classification fixture, through the app SQL role. */
class AdvertisingMaterialityIT {
    static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    static DataSource migration;
    static JdbcClient seed,application;
    AdvertisingR1Fixture.Graph graph;
    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        seed=JdbcClient.create(migration);
        application=JdbcClient.create(new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword()));
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }
    @BeforeEach void fixture() throws Exception {
        graph=AdvertisingMaterialityFixture.seedUnapproved(migration);
        assertThat(assessment().path("route").asText()).as("complete low-risk classification precondition").isEqualTo("ORDINARY_IMPACT");
    }
    @Test void completeOrdinaryAssessmentExposesEveryIndependentAxisButGrantsNoExecution() {
        var evidence=assessment();
        assertThat(evidence.path("axes").properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder(
                "absoluteBidChange","relativeBidChange","officialSpendExposure","affectedVariants","criticalSalesExposure",
                "cumulativeBidChange","lifecycleAndCohort","direction");
        assertThat(evidence.path("axes").path("absoluteBidChange").path("value").decimalValue()).isEqualByComparingTo("10");
        assertThat(evidence.path("reasons").isEmpty()).isTrue();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_action_authorization WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Integer.class).single()).isZero();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Integer.class).single()).isZero();
    }
    @ParameterizedTest
    @ValueSource(strings={"ABSOLUTE_BID_CHANGE","RELATIVE_BID_CHANGE","OFFICIAL_SPEND_EXPOSURE","AFFECTED_VARIANT_EXPOSURE",
            "CUMULATIVE_BID_CHANGE","LIFECYCLE_OR_GOVERNED_COHORT_UNRESOLVED","FIXED_UNKNOWN_DECISION_EVIDENCE",
            "FIXED_REGRESSION_OR_UNKNOWN_EXECUTION","FIXED_CRITICAL_PROTECTED_SALES_EXPOSURE","EXACT_ORDINARY_PROMOTION_ABSENT"})
    void aSingleHardAxisCannotBeCompensatedByTheRemainingOrdinaryEvidence(String fault) {
        switch(fault) {
            case "ABSOLUTE_BID_CHANGE" -> policy("ordinary_nonzero_envelope_amount=8,material_absolute_change_amount=9");
            case "RELATIVE_BID_CHANGE" -> policy("ordinary_relative_envelope_ratio=0.2,material_relative_change_ratio=0.3");
            case "OFFICIAL_SPEND_EXPOSURE" -> policy("material_spend_exposure_amount=1");
            case "AFFECTED_VARIANT_EXPOSURE" -> policy("material_affected_variant_count=1");
            case "CUMULATIVE_BID_CHANGE" -> policy("material_cumulative_change_amount=9");
            case "LIFECYCLE_OR_GOVERNED_COHORT_UNRESOLVED" -> seed.sql("UPDATE ops.commercial_policy SET status='ENDED' WHERE organization_id=:org")
                    .param("org",graph.id("organization")).update();
            case "FIXED_UNKNOWN_DECISION_EVIDENCE" -> seed.sql("UPDATE mart.ad_case SET evidence_state='UNKNOWN' WHERE id=:id")
                    .param("id",graph.id("caseId")).update();
            case "FIXED_REGRESSION_OR_UNKNOWN_EXECUTION" -> seed.sql("UPDATE mart.ad_case SET cause_code='ACTION_OUTCOME_REGRESSION',protection_tier='P0' WHERE id=:id")
                    .param("id",graph.id("caseId")).update();
            case "FIXED_CRITICAL_PROTECTED_SALES_EXPOSURE" -> seed.sql("""
                    INSERT INTO core.ad_outcome_critical_unit_rule(id,outcome_policy_id,organization_id,product_variant_id,store_id,reason,evidence_reference)
                    VALUES(gen_random_uuid(),:policy,:org,:product,:store,'Explicit critical sales fixture','fixture://critical-unit')
                    """).param("policy",graph.id("outcome")).param("org",graph.id("organization"))
                    .param("product",graph.id("productVariant")).param("store",graph.id("store")).update();
            case "EXACT_ORDINARY_PROMOTION_ABSENT" -> seed.sql("UPDATE ops.ad_ordinary_promotion SET status='REVOKED' WHERE bundle_id=:id")
                    .param("id",graph.id("bundle")).update();
            default -> throw new AssertionError(fault);
        }
        var result=assessment();
        assertThat(result.path("route").asText()).isEqualTo("MATERIAL_IMPACT");
        assertThat(result.path("reasons").toString()).contains(fault);
    }
    @Test void expiredMaterialityAuthorityIsUnresolvedRatherThanAPromotedApproval() {
        policy("status='RETIRED'");
        assertThat(assessment().path("route").asText()).isEqualTo("MATERIALITY_UNRESOLVED");
    }
    @Test void missingSpendKeepsItsUnknownValueAndCannotRetainAnOrdinaryRoute() {
        seed.sql("UPDATE mart.ad_case SET official_spend_state='NOT_AVAILABLE',official_spend_amount=NULL WHERE id=:id")
                .param("id",graph.id("caseId")).update();
        var result=assessment();
        assertThat(result.path("route").asText()).isEqualTo("MATERIAL_IMPACT");
        assertThat(result.path("axes").path("officialSpendExposure").path("value").isNull()).isTrue();
        assertThat(result.path("reasons").toString()).contains("OFFICIAL_SPEND_UNKNOWN");
    }
    private void policy(String assignment) {
        seed.sql("UPDATE core.ad_materiality_policy SET "+assignment+" WHERE id=:id")
                .param("id",graph.id("materiality")).update();
    }
    private JsonNode assessment() {
        return new ObjectMapper().readTree(application.sql("SELECT ops.ad_materiality_assessment(:bundle,:candidate)::text")
                .param("bundle",graph.id("bundle")).param("candidate",graph.id("candidate")).query(String.class).single());
    }
}
