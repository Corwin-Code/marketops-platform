package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.TestDatabase;
import java.sql.Timestamp;
import java.time.Instant;
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

/** Same-class CV-A/C boundaries through canonical PostgreSQL and actual Outcome evaluation. */
@SpringBootTest @ActiveProfiles("ci")
class AdvertisingOutcomeInputIntegrityIT {
    @Autowired AutowireCapableBeanFactory beans;
    @Autowired AdvertisingOutcomeFreshness freshness;
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
        helper=new AdvertisingOutcomePurposeFreshnessIT();beans.autowireBean(helper);helper.setup(info);fixture=helper.fixture;
    }

    @Test void physicalSellabilityIdenticalRetrospectiveRefreshKeepsHistoricalWindowUsable() {
        helper.physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read.minusSeconds(172800));
        fixture.read=fixture.read.plusSeconds(1);
        helper.physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        helper.observation();assertThat(helper.business()).isEqualTo("VERIFIED_AD_RISK_CLEARED");
    }
    @Test void physicalSellabilityConflictingSameInstantReportsCannotBePickedByUuid() {
        helper.physicalReport("SELLABILITY","NO",fixture.from.minusSeconds(1),fixture.read);
        fixture.read=fixture.read.plusSeconds(1);
        helper.physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        helper.observation();assertThat(helper.business()).isEqualTo("PROTECTION_IN_PROGRESS");
    }
    @Test void physicalSellabilityFutureSecondSourceCannotHideBehindFreshMinimumAndRevisesWhenAdmissible() {
        helper.physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        Instant future=fixture.read.plusSeconds(30);
        helper.physicalReport("SELLABILITY","YES",fixture.to.minusSeconds(1),future);
        helper.observation();assertThat(helper.business()).isEqualTo("PROTECTION_IN_PROGRESS");
        fixture.read=future.plusSeconds(1);
        helper.observation();assertThat(helper.business()).isEqualTo("VERIFIED_AD_RISK_CLEARED");
        assertThat(fixture.seed.sql("SELECT count(*) FROM ops.ad_outcome_observation WHERE command_id=:command")
                .param("command",fixture.command).query(Integer.class).single()).isEqualTo(2);
    }
    @Test void physicalSellabilityMappingConflictReopensOriginalScopeWithoutReplacingItsMapping() {
        helper.physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        helper.observation();assertThat(helper.business()).isEqualTo("VERIFIED_AD_RISK_CLEARED");
        fixture.seed.sql("""
            INSERT INTO core.mapping_conflict(id,organization_id,platform_listing_variant_id,conflict_kind,detail,state,detected_at,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:listing,'CONFLICTING_CONFIRMATION','synthetic conflicting mapping evidence','OPEN',:during,:at,:at)
            """).param("org",fixture.graph.id("organization")).param("listing",fixture.graph.id("listingVariant"))
            .param("during",Timestamp.from(fixture.to.minusSeconds(1))).param("at",Timestamp.from(fixture.read)).update();
        fixture.read=fixture.read.plusSeconds(1);
        var observation=helper.observation();
        assertThat(observation.path("protectionEvidence").path("exactAffectedScope").asBoolean()).isFalse();
        assertThat(helper.business()).isEqualTo("PROTECTION_IN_PROGRESS");
    }
    @Test void physicalSellabilitySameTimestampConflictingAffectedSetIsUnresolved() {
        helper.physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        fixture.seed.sql("""
            INSERT INTO core.ad_affected_set(id,organization_id,ad_native_object_id,affected_set_digest,product_variant_ids,
              platform_listing_variant_ids,resolution_state,unresolved_reason_codes,resolved_at,created_at)
            SELECT gen_random_uuid(),organization_id,ad_native_object_id,repeat('e',64),product_variant_ids,
              platform_listing_variant_ids,'CONFLICTED',ARRAY['CONFLICTING_SOURCE'],resolved_at,created_at
            FROM core.ad_affected_set WHERE id=:id
            """).param("id",fixture.graph.id("affectedSet")).update();
        assertThat(helper.observation().path("protectionEvidence").path("exactAffectedScope").asBoolean()).isFalse();
        assertThat(helper.business()).isEqualTo("PROTECTION_IN_PROGRESS");
    }
    @Test void retainedCompleteObservedReturnsUseTheExistingCanonicalCoverageEnum() {
        coverage("COMPLETE",fixture.read,fixture.read,fixture.read);
        assertThat(coverage("RETAINED").complete()).isTrue();
    }
    @Test void futureConsumedQcCannotHideBehindFreshRetainedAndReturnSources() {
        coverage("COMPLETE",fixture.read,fixture.read,fixture.read.plusSeconds(60));
        assertThat(coverage("RETAINED").complete()).isTrue();
        assertThat(coverage("SETTLED").complete()).isFalse();
    }
    @Test void missingUnconsumedSourcesDoNotCrashOrBecomeComplete() {
        coverage("INCOMPLETE",null,null,null);
        assertThat(coverage("OPERATIONAL").complete()).isFalse();
        assertThat(coverage("OPERATIONAL").source()).isNull();
    }
    AdvertisingOutcomeFreshness.CompanyCoverage coverage(String stage) {
        return freshness.companyCoverage(fixture.graph.id("organization"),fixture.graph.id("listingVariant"),stage,fixture.from,fixture.to,fixture.read);
    }
    void coverage(String state,Instant retained,Instant returns,Instant qc) {
        fixture.read=fixture.read.plusSeconds(1);
        fixture.seed.sql("""
            INSERT INTO ledger.return_quality_evidence_snapshot(id,organization_id,platform_listing_variant_id,report_window_start,report_window_end,
              completed_coverage,retained_coverage,return_coverage,qc_coverage,completed_source_updated_at,retained_source_updated_at,
              return_source_updated_at,qc_source_updated_at,evidence_reference,accepted_at,correlation_id,supersedes_snapshot_id)
            SELECT gen_random_uuid(),organization_id,platform_listing_variant_id,report_window_start,report_window_end,
              :state,:state,CASE WHEN :state='COMPLETE' THEN 'COMPLETE_OBSERVED' ELSE 'INCOMPLETE' END,:state,:retained,:retained,
              :returns,:qc,'fixture://cv-a-component-proof',:at,:key,id FROM ledger.return_quality_evidence_snapshot r
            WHERE r.organization_id=:org AND r.report_window_start=:from
              AND NOT EXISTS(SELECT 1 FROM ledger.return_quality_evidence_snapshot n WHERE n.supersedes_snapshot_id=r.id)
            """).param("state",state).param("retained",retained==null?null:Timestamp.from(retained),java.sql.Types.TIMESTAMP)
            .param("returns",returns==null?null:Timestamp.from(returns),java.sql.Types.TIMESTAMP)
            .param("qc",qc==null?null:Timestamp.from(qc),java.sql.Types.TIMESTAMP).param("at",Timestamp.from(fixture.read))
            .param("key",UUID.randomUUID().toString()).param("org",fixture.graph.id("organization")).param("from",Timestamp.from(fixture.from)).update();
    }
}
