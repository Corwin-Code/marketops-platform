package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.shared.Digest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** CV-C: real application workers, isolated PostgreSQL and immutable revision history. */
@SpringBootTest @ActiveProfiles("ci")
class AdvertisingOutcomeRevisionFreshnessIT {
    @Autowired AutowireCapableBeanFactory beans;
    @Autowired ObjectMapper json;
    AdvertisingOutcomePurposeFreshnessIT helper;
    AdvertisingFrozenOutcomeIT fixture;

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",AdvertisingFrozenOutcomeIT.DATABASE::getJdbcUrl);
        r.add("spring.datasource.username",TestDatabase::applicationRole);
        r.add("spring.datasource.password",TestDatabase::applicationPassword);
        r.add("spring.flyway.user",TestDatabase::migrationRole);
        r.add("spring.flyway.password",TestDatabase::migrationPassword);
    }

    @BeforeEach void setup(TestInfo info) throws Exception {
        helper=new AdvertisingOutcomePurposeFreshnessIT();
        beans.autowireBean(helper);helper.setup(info);fixture=helper.fixture;
    }

    @Test void unchangedInputsAndClockAdvancementWithinQualificationDoNotAppendRevision() {
        zeroSpend();run(1);String original=snapshotText();
        run(0);fixture.read=fixture.read.plusSeconds(10);run(0);
        assertThat(snapshotText()).isEqualTo(original);assertCount(1);
    }

    @Test void futureAcceptedCorrectionIsIgnoredThenRevisesExactlyOnceWhenAccepted() {
        zeroSpend();run(1);UUID first=latestId();String original=snapshotText();
        Instant before=fixture.read,accepted=before.plusSeconds(30);
        fixture.read=accepted;helper.report(accepted,true,false,true,"100");fixture.read=before;
        run(0);assertThat(observation().path("officialSpend").path("value").decimalValue()).isZero();
        fixture.read=accepted.plusSeconds(1);run(1);
        assertThat(observation().path("officialSpend").path("value").decimalValue()).isEqualByComparingTo("100");
        assertThat(helper.business()).isNotEqualTo("VERIFIED_AD_EXPOSURE_STOPPED");
        run(0);assertCount(2);assertThat(snapshotText(first)).isEqualTo(original);
    }

    @Test void sourceExpiryCrossingDowngradesFormerlyEligibleProofOnlyOnce() {
        zeroSpend();run(1);UUID first=latestId();String original=snapshotText();
        JsonNode proof=proof("OFFICIAL_AD_SPEND");assertThat(proof.path("eligible").asBoolean()).isTrue();
        fixture.read=Instant.parse(proof.path("expiresAt").asString()).plusSeconds(1);run(1);
        helper.assertSpendBlocked(observation());assertThat(proof("OFFICIAL_AD_SPEND").path("eligible").asBoolean()).isFalse();
        run(0);fixture.read=fixture.read.plusSeconds(1);run(0);
        assertCount(2);assertThat(snapshotText(first)).isEqualTo(original);
    }

    @Test void exactProfileRevocationDowngradesFormerlyEligibleProofOnlyOnce() {
        zeroSpend();run(1);UUID first=latestId();String original=snapshotText();
        UUID frozen=profile("OFFICIAL_AD_SPEND");assertThat(proof("OFFICIAL_AD_SPEND").path("eligible").asBoolean()).isTrue();
        fixture.seed.sql("UPDATE core.ad_freshness_profile SET status='CANCELLED' WHERE id=:id").param("id",frozen).update();
        fixture.read=fixture.read.plusSeconds(1);run(1);helper.assertSpendBlocked(observation());
        assertThat(proof("OFFICIAL_AD_SPEND").path("reasonCodes").toString()).contains("FROZEN_FRESHNESS_VERSION_INVALID");
        assertThat(profile("OFFICIAL_AD_SPEND")).isEqualTo(frozen);run(0);
        assertCount(2);assertThat(snapshotText(first)).isEqualTo(original);
    }

    @Test void laterMorePermissiveProfileNeverReplacesStillValidFrozenAuthority() {
        zeroSpend();run(1);UUID frozen=profile("OFFICIAL_AD_SPEND");
        fixture.read=fixture.read.plusSeconds(1);
        fixture.seed.sql("UPDATE core.ad_freshness_profile SET status='RETIRED' WHERE id=:id").param("id",frozen).update();
        UUID later=UUID.randomUUID();
        fixture.seed.sql("""
            INSERT INTO core.ad_freshness_profile
            SELECT (jsonb_populate_record(NULL::core.ad_freshness_profile,to_jsonb(f)||jsonb_build_object(
                'id',CAST(:later AS uuid),'profile_version',f.profile_version+100,'status','ACTIVE',
                'source_max_age_minutes',525600,'accepted_fact_max_age_minutes',525600,
                'effective_from',CAST(:at AS timestamptz),'created_at',CAST(:at AS timestamptz)))).*
            FROM core.ad_freshness_profile f WHERE f.id=:frozen
            """).param("later",later).param("at",Timestamp.from(fixture.read)).param("frozen",frozen).update();
        // Registry changes alone do not rewrite a still-valid frozen version.
        run(0);
        helper.report(fixture.read.minusSeconds(172800),true,false,true,"0");run(1);
        helper.assertSpendBlocked(observation());assertThat(profile("OFFICIAL_AD_SPEND")).isEqualTo(frozen).isNotEqualTo(later);
        assertThat(proof("OFFICIAL_AD_SPEND").path("reasonCodes").toString()).contains("FRESHNESS_BOUND_UNMET");
        run(0);assertCount(2);
    }

    @Test void frozenProfileScopeMutationInvalidatesAuthorityAndSchedulesOnlyOneRevision() {
        zeroSpend();run(1);UUID frozen=profile("OFFICIAL_AD_SPEND");
        fixture.seed.sql("UPDATE core.ad_freshness_profile SET scope_kind='PLATFORM',platform_code=:platform WHERE id=:id")
                .param("platform",fixture.graph.platform()).param("id",frozen).update();
        fixture.read=fixture.read.plusSeconds(1);run(1);helper.assertSpendBlocked(observation());
        assertThat(proof("OFFICIAL_AD_SPEND").path("reasonCodes").toString()).contains("FROZEN_FRESHNESS_VERSION_INVALID");
        run(0);assertCount(2);
    }

    @Test void companyCoverageCorrectionAloneRevisesSafetyAndThenStaysIdle() {
        zeroSpend();run(1);
        assertThat(observation().path("companySales").path("valueState").asString()).isEqualTo("AVAILABLE");
        fixture.read=fixture.read.plusSeconds(1);coverageCorrection("INCOMPLETE");run(1);
        assertThat(observation().path("companySales").path("valueState").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(observation().path("officialSpend").path("value").decimalValue()).isZero();
        assertThat(proof("COMPANY_COMPLETED_SALE").path("eligible").asBoolean()).isFalse();
        run(0);assertCount(2);
    }

    @Test void costMetricRefreshAloneRevisesTheConsumedCanonicalCostAndThenStaysIdle() {
        zeroSpend();linkedSale();costs("10");run(1);
        assertThat(proof("COST_AND_FEE").path("eligible").asBoolean()).isTrue();
        BigDecimal original=observation().path("profit").path("absoluteProfit").path("value").decimalValue();
        fixture.read=fixture.read.plusSeconds(1);costs("20");run(1);
        assertThat(proof("COST_AND_FEE").path("eligible").asBoolean()).isTrue();
        assertThat(observation().path("profit").path("absoluteProfit").path("value").decimalValue())
                .isEqualByComparingTo(original.subtract(new BigDecimal("100")));
        run(0);assertCount(2);
    }

    @Test void newerNonCoveringMetricCannotMaskTheConsumedCohortCostRefresh() {
        zeroSpend();linkedSale();costs("10");run(1);
        assertThat(proof("COST_AND_FEE").path("eligible").asBoolean()).isTrue();
        BigDecimal original=observation().path("profit").path("absoluteProfit").path("value").decimalValue();
        fixture.read=fixture.read.plusSeconds(1);costs("90",fixture.from.minusSeconds(1));run(0);
        assertThat(observation().path("profit").path("absoluteProfit").path("value").decimalValue()).isEqualByComparingTo(original);
        fixture.read=fixture.read.plusSeconds(1);costs("20");run(1);
        assertThat(observation().path("profit").path("absoluteProfit").path("value").decimalValue())
                .isEqualByComparingTo(original.subtract(new BigDecimal("100")));
        run(0);assertCount(2);
    }

    @Test void controlConfigurationAloneRevisesItsQualificationAndThenStaysIdle() {
        zeroSpend();run(1);assertThat(proof("AD_OBJECT_CONFIGURATION").path("eligible").asBoolean()).isTrue();
        fixture.read=fixture.read.plusSeconds(1);configuration("EXECUTOR_SELF_REPORT");run(1);
        assertThat(proof("AD_OBJECT_CONFIGURATION").path("eligible").asBoolean()).isFalse();
        assertThat(observation().path("protectionEvidence").path("configurationVerified").asBoolean()).isFalse();
        run(0);assertCount(2);
    }

    @Test void physicalSellabilityWindowInvalidationAloneReopensThenStaysIdle() {
        helper.physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);run(1);
        assertThat(helper.business()).isEqualTo("VERIFIED_AD_RISK_CLEARED");UUID first=latestId();String original=snapshotText();
        fixture.read=fixture.read.plusSeconds(1);
        helper.physicalReport("SELLABILITY","NO",fixture.to.minusSeconds(1),fixture.read);run(1);
        assertThat(helper.business()).isEqualTo("PROTECTION_IN_PROGRESS");
        assertThat(observation().path("protectionEvidence").path("sellabilityCleared").asBoolean()).isFalse();
        run(0);assertCount(2);assertThat(snapshotText(first)).isEqualTo(original);
    }

    @Test void physicalAvailabilityWindowInvalidationAloneReopensThenStaysIdle() {
        helper.physicalReport("AVAILABILITY","100",fixture.from.minusSeconds(1),fixture.read);run(1);
        assertThat(helper.business()).isEqualTo("VERIFIED_AD_RISK_CLEARED");
        fixture.read=fixture.read.plusSeconds(1);
        helper.physicalReport("AVAILABILITY","0",fixture.to.minusSeconds(1),fixture.read);run(1);
        assertThat(helper.business()).isEqualTo("PROTECTION_IN_PROGRESS");
        assertThat(observation().path("protectionEvidence").path("availabilityCleared").asBoolean()).isFalse();
        run(0);assertCount(2);
    }

    @Test void priceOnlySourceChangeRevisesTheConfounderSnapshotAndThenStaysIdle() {
        fixture.context();run(1);String original=observation().path("confounderDigest").asString();
        fixture.read=fixture.read.plusSeconds(1);UUID provenance=provenance(fixture.read);
        fixture.seed.sql("""
            INSERT INTO core.listing_price_observation(id,organization_id,provenance_id,platform_listing_variant_id,
                source_fact_key,observed_at,currency_code,selling_price,promotion_active)
            VALUES(gen_random_uuid(),:org,:source,:listing,:key,:at,'RUB',120,'NO')
            """).param("org",fixture.graph.id("organization")).param("source",provenance)
            .param("listing",fixture.graph.id("listingVariant")).param("key",UUID.randomUUID().toString())
            .param("at",Timestamp.from(fixture.to.minusSeconds(1))).update();
        run(1);assertThat(observation().path("confounderDigest").asString()).isNotEqualTo(original);
        run(0);assertCount(2);
    }

    private void zeroSpend() { helper.report(fixture.read.minusSeconds(1),true,false,true,"0"); }
    private void run(int expected) {
        var batch=fixture.worker.runForObject(fixture.graph.id("organization"),fixture.graph.id("object"),fixture.read,10);
        assertThat(batch.evaluated()).isEqualTo(expected);assertThat(batch.recorded()).isEqualTo(expected);
        assertThat(batch.remaining()).isFalse();
    }
    private UUID latestId() {
        return fixture.seed.sql("SELECT id FROM ops.ad_outcome_observation WHERE command_id=:id ORDER BY evaluated_at DESC,revision_no DESC LIMIT 1")
                .param("id",fixture.command).query(UUID.class).single();
    }
    private String snapshotText() { return snapshotText(latestId()); }
    private String snapshotText(UUID id) {
        return fixture.seed.sql("SELECT input_snapshot::text FROM ops.ad_outcome_axes WHERE observation_id=:id")
                .param("id",id).query(String.class).single();
    }
    private JsonNode observation() { return json.readTree(snapshotText()).path("observation"); }
    private JsonNode proof(String kind) {
        for(JsonNode proof:observation().path("purposeEvidence")) if(kind.equals(proof.path("kind").asString())) return proof;
        throw new AssertionError("Missing frozen purpose proof: "+kind);
    }
    private UUID profile(String kind) { return UUID.fromString(observation().path("freshnessProfiles").path(kind).path("id").asString()); }
    private void assertCount(int count) {
        assertThat(fixture.seed.sql("SELECT count(*) FROM ops.ad_outcome_observation WHERE command_id=:id")
                .param("id",fixture.command).query(Integer.class).single()).isEqualTo(count);
    }
    private UUID provenance(Instant accepted) {
        UUID id=UUID.randomUUID();
        fixture.seed.sql("""
            INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note)
            VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:actor,'synthetic revision source; logical clock; no provider access')
            """).param("id",id).param("org",fixture.graph.id("organization")).param("at",Timestamp.from(accepted))
            .param("actor",fixture.graph.id("ownerUser")).update();return id;
    }
    private void coverageCorrection(String completed) {
        fixture.seed.sql("""
            INSERT INTO ledger.return_quality_evidence_snapshot(id,organization_id,platform_listing_variant_id,report_window_start,report_window_end,
                completed_coverage,retained_coverage,return_coverage,qc_coverage,completed_source_updated_at,retained_source_updated_at,
                return_source_updated_at,qc_source_updated_at,evidence_reference,accepted_at,correlation_id,supersedes_snapshot_id)
            SELECT gen_random_uuid(),organization_id,platform_listing_variant_id,report_window_start,report_window_end,
                :completed,retained_coverage,return_coverage,qc_coverage,:at,retained_source_updated_at,return_source_updated_at,
                qc_source_updated_at,'fixture://cv-c-coverage-only',:at,'cv-c-coverage-only',id
            FROM ledger.return_quality_evidence_snapshot r WHERE r.organization_id=:org AND r.report_window_start=:from
                AND NOT EXISTS(SELECT 1 FROM ledger.return_quality_evidence_snapshot n WHERE n.supersedes_snapshot_id=r.id)
            """).param("completed",completed).param("at",Timestamp.from(fixture.read)).param("org",fixture.graph.id("organization"))
            .param("from",Timestamp.from(fixture.from)).update();
    }
    private void configuration(String grade) {
        fixture.seed.sql("""
            INSERT INTO core.ad_object_configuration_observation
            SELECT (jsonb_populate_record(NULL::core.ad_object_configuration_observation,to_jsonb(c)||jsonb_build_object(
                'id',gen_random_uuid(),'provenance_id',CAST(:source AS uuid),'observed_at',CAST(:at AS timestamptz),
                'source_time',CAST(:at AS timestamptz),'created_at',CAST(:at AS timestamptz),
                'evidence_grade',CAST(:grade AS text),'supersedes_observation_id',c.id))).*
            FROM core.ad_object_configuration_observation c WHERE c.organization_id=:org AND c.ad_native_object_id=:object
            ORDER BY c.observed_at DESC,c.id DESC LIMIT 1
            """).param("source",provenance(fixture.read)).param("at",Timestamp.from(fixture.read)).param("grade",grade)
            .param("org",fixture.graph.id("organization")).param("object",fixture.graph.id("object")).update();
    }
    private void linkedSale() {
        fixture.seed.sql("""
            INSERT INTO ledger.ad_linked_sale_event(id,organization_id,provenance_id,ad_native_object_id,affected_set_id,platform_listing_variant_id,
                conversion_definition_id,sale_stage,linkage_basis,linkage_evidence_ref,event_count,net_sales_amount,currency_code,
                occurred_at,period_start,period_end,source_time,recorded_at)
            VALUES(gen_random_uuid(),:org,:source,:object,:set,:listing,:definition,'CANONICAL_AD_LINKED_COMPLETED_SALE',
                'DETERMINISTIC_OBJECT_LINKAGE','fixture://cv-c-consumed-cost',10,1000,'RUB',:from,:from,:to,:at,:at)
            """).param("org",fixture.graph.id("organization")).param("source",fixture.graph.id("provenance"))
            .param("object",fixture.graph.id("object")).param("set",fixture.graph.id("affectedSet")).param("listing",fixture.graph.id("listingVariant"))
            .param("definition",fixture.graph.id("conversion")).param("from",Timestamp.from(fixture.from)).param("to",Timestamp.from(fixture.to))
            .param("at",Timestamp.from(fixture.read)).update();
    }
    private void costs(String unitCost) { costs(unitCost,fixture.to); }
    private void costs(String unitCost,Instant metricTo) {
        Instant metricFrom=metricTo.minusSeconds(30*86400L);
        UUID run=UUID.randomUUID();
        fixture.seed.sql("""
            INSERT INTO mart.calculation_run(id,organization_id,trigger_kind,scope_kind,window_code,period_start,period_end,
                definition_set_digest,state,subject_count,value_count,started_at,completed_at,correlation_id)
            VALUES(:id,:org,'BACKFILL','ORGANIZATION','D30',:from,:to,repeat('a',64),'SUCCEEDED',1,4,:at,:at,'cv-c-canonical-cost-refresh')
            """).param("id",run).param("org",fixture.graph.id("organization")).param("from",Timestamp.from(metricFrom))
            .param("to",Timestamp.from(metricTo)).param("at",Timestamp.from(fixture.read)).update();
        for(String code:List.of("UNIT_COST","PLATFORM_FEES_PER_UNIT","RETURN_LOSS_PER_UNIT","VARIABLE_TAX_PER_UNIT")) {
            UUID id=UUID.randomUUID();
            fixture.seed.sql("""
                INSERT INTO mart.metric_value(id,organization_id,calculation_run_id,metric_code,definition_version,subject_kind,subject_id,
                    window_code,period_start,period_end,value_state,numeric_value,currency_code,confidence_state,estimated,oldest_source_time,
                    freshness_seconds,input_digest,computed_at)
                VALUES(:id,:org,:run,:code,2,'PLATFORM_LISTING_VARIANT',:listing,'D30',:from,:to,'AVAILABLE',:amount,'RUB',
                    'CANONICAL_CONFIRMED',false,:at,0,:digest,:at)
                """).param("id",id).param("org",fixture.graph.id("organization")).param("run",run).param("code",code)
                .param("listing",fixture.graph.id("listingVariant")).param("from",Timestamp.from(metricFrom)).param("to",Timestamp.from(metricTo))
                .param("amount",code.equals("UNIT_COST")?new BigDecimal(unitCost):BigDecimal.ZERO).param("at",Timestamp.from(fixture.read))
                .param("digest",Digest.ofText(id.toString())).update();
            fixture.seed.sql("INSERT INTO mart.metric_input_reference VALUES(gen_random_uuid(),:id,'FACT_PROVENANCE',:source)")
                    .param("id",id).param("source",fixture.graph.id("provenance")).update();
        }
    }
}
