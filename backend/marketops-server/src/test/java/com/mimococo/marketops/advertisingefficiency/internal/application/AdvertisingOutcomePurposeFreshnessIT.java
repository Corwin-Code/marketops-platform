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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** CV-A/C: application-created fixture commands, immutable baselines and real PostgreSQL readers. */
@SpringBootTest @ActiveProfiles("ci")
class AdvertisingOutcomePurposeFreshnessIT {
    @Autowired AutowireCapableBeanFactory beans;
    @Autowired ObjectMapper json;
    AdvertisingFrozenOutcomeIT fixture;
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",AdvertisingFrozenOutcomeIT.DATABASE::getJdbcUrl);
        r.add("spring.datasource.username",TestDatabase::applicationRole);
        r.add("spring.datasource.password",TestDatabase::applicationPassword);
        r.add("spring.flyway.user",TestDatabase::migrationRole);
        r.add("spring.flyway.password",TestDatabase::migrationPassword);
    }
    @BeforeEach void setup(TestInfo info) throws Exception {
        String method=info.getTestMethod().orElseThrow().getName();
        fixture=new AdvertisingFrozenOutcomeIT() {
            @Override String transform(String sql) {
                String transformed=super.transform(sql);
                if(method.contains("SplitAges")) transformed=transformed.replace("\nINSERT INTO ops.ad_candidate_selection(",
                    "\nUPDATE core.ad_freshness_profile SET source_max_age_minutes=1440,accepted_fact_max_age_minutes=1 WHERE organization_id='8689c119-8fa0-50b7-8ba2-f9bf3039d336' AND evidence_kind='COMPANY_COMPLETED_SALE' AND decision_purpose='EARLY_COMPLETED_SALES_OUTCOME';\nINSERT INTO ops.ad_candidate_selection(");
                if(method.contains("PermissiveCoverage")) transformed=transformed.replace("\nINSERT INTO ops.ad_candidate_selection(",
                    "\nUPDATE core.ad_freshness_profile SET minimum_coverage_ratio=0.8 WHERE organization_id='8689c119-8fa0-50b7-8ba2-f9bf3039d336' AND evidence_kind='OFFICIAL_AD_SPEND' AND decision_purpose='EARLY_COMPLETED_SALES_OUTCOME';\nINSERT INTO ops.ad_candidate_selection(");
                if(method.contains("LaxProfiles")) transformed=transformed.replace("\nINSERT INTO ops.ad_candidate_selection(",
                    "\nUPDATE core.ad_freshness_profile SET minimum_confidence_state='UNKNOWN',requires_window_complete=false,requires_correction_window_closed=false,minimum_coverage_ratio=NULL WHERE organization_id='8689c119-8fa0-50b7-8ba2-f9bf3039d336' AND decision_purpose IN('EARLY_COMPLETED_SALES_OUTCOME','FINAL_RETAINED_SALES_OUTCOME') AND evidence_kind IN('OFFICIAL_AD_SPEND','OFFICIAL_AD_TRAFFIC','AD_LINKED_SALE_EVENT','COST_AND_FEE','AD_OBJECT_CONFIGURATION','AFFECTED_SET','SELLABILITY','AVAILABILITY','PRICE_AND_PROMOTION');\nINSERT INTO ops.ad_candidate_selection(");
                if(method.contains("Sellability")) return transformed.replace("PROVEN_ADVERTISING_LOSS","PROMOTED_VARIANT_NOT_SELLABLE");
                if(method.contains("Availability")) return transformed.replace("PROVEN_ADVERTISING_LOSS","PROMOTED_VARIANT_UNAVAILABLE");
                return transformed;
            }
        };
        beans.autowireBean(fixture);fixture.fixture(info);
        if(method.contains("Sellability") || method.contains("Availability")) fixture.responsibilities.ensureResponsibility(fixture.graph.id("caseId"),fixture.graph.id("calculationRun"),"MARKETPLACE_OPERATOR");
        fixture.observedSales("1000",null);
        // The linked-member test starts with a mature observation. Its helper
        // supplies the complete mature report; an additional independent early
        // report would overlap it and correctly remain a stale consumed input.
        if(!method.equals("outcomeLaxProfilesFutureLinkedMemberCannotBecomeCanonicalProfit")) fixture.coverage();
    }
    UUID report(Instant source,boolean complete,boolean correctionOpen,boolean wholeWindow,String spend) {
        UUID id=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO ledger.ad_object_fact(id,organization_id,provenance_id,ad_native_object_id,store_id,source_fact_key,
                  period_start,period_end,currency_code,spend_amount,clicks,report_window_complete,correction_window_open,
                  source_time,recorded_at,supersedes_fact_id,adjustment_kind)
                SELECT :id,organization_id,provenance_id,ad_native_object_id,store_id,:key,
                  period_start,CASE WHEN :whole THEN period_end ELSE period_end-interval '1 hour' END,
                  currency_code,CAST(:spend AS numeric),clicks,:complete,:open,:source,:at,id,'CORRECTION'
                FROM ledger.ad_object_fact f WHERE f.ad_native_object_id=:object AND f.period_start=:from
                  AND NOT EXISTS(SELECT 1 FROM ledger.ad_object_fact n WHERE n.supersedes_fact_id=f.id)
                """).param("id",id).param("key",id.toString()).param("whole",wholeWindow).param("spend",spend)
                .param("complete",complete).param("open",correctionOpen).param("source",Timestamp.from(source))
                .param("at",Timestamp.from(fixture.read)).param("object",fixture.graph.id("object"))
                .param("from",Timestamp.from(fixture.from)).update();
        return id;
    }
    JsonNode observation() {
        var result=fixture.service.evaluate(fixture.due(),fixture.read).orElseThrow();
        return json.readTree(fixture.seed.sql("SELECT input_snapshot::text FROM ops.ad_outcome_axes WHERE observation_id=:id")
                .param("id",result.observationId()).query(String.class).single()).path("observation");
    }
    String business() {
        return fixture.seed.sql("""
                SELECT a.business_outcome FROM ops.ad_outcome_axes a JOIN ops.ad_outcome_observation o ON o.id=a.observation_id
                WHERE o.command_id=:command ORDER BY o.evaluated_at DESC,o.revision_no DESC LIMIT 1
                """).param("command",fixture.command).query(String.class).single();
    }
    void assertSpendBlocked(JsonNode observed) {
        assertThat(observed.path("officialSpend").path("valueState").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(observed.path("officialSpend").path("value").isNull()).isTrue();
        assertThat(business()).isNotIn("VERIFIED_AD_EXPOSURE_STOPPED","VERIFIED_EFFICIENCY_SUCCESS");
    }
    @Test void freshClosedExactZeroWindowProvesExposureStoppedWithUnresolvedProfit() {
        report(fixture.read.minusSeconds(1),true,false,true,"0");
        var observed=observation();
        assertThat(observed.path("officialSpend").path("evidenceState").asString()).isEqualTo("CANONICAL_CONFIRMED");
        assertThat(observed.path("profit").path("absoluteProfit").path("valueState").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(business()).isEqualTo("VERIFIED_AD_EXPOSURE_STOPPED");
    }
    @Test void oldSourceWithNewAcceptanceCannotConfirmEvenClosedZeroSpend() {
        report(fixture.read.minusSeconds(172800),true,false,true,"0");
        var observed=observation();assertSpendBlocked(observed);
        assertThat(observed.path("blockers").toString()).contains("FRESHNESS_BOUND_UNMET:EARLY_COMPLETED_SALES_OUTCOME:OFFICIAL_AD_SPEND");
        // The independent fresh company guard remains usable despite stale ad spend.
        assertThat(observed.path("companySales").path("evidenceState").asString()).isEqualTo("CANONICAL_CONFIRMED");
    }
    @Test void partialSourceWindowDoesNotProveZeroNewExposure() {
        report(fixture.read.minusSeconds(1),true,false,false,"0");assertSpendBlocked(observation());
    }
    @Test void outcomePermissiveCoverageCannotConvertPartialZeroSpendIntoExposureStopped() {
        report(fixture.read.minusSeconds(1),true,false,false,"0");
        var observed=observation();
        assertThat(observed.path("officialSpend").path("evidenceState").asString()).isEqualTo("CANONICAL_CONFIRMED");
        assertThat(observed.path("coverage").decimalValue()).isLessThan(java.math.BigDecimal.ONE).isGreaterThan(new java.math.BigDecimal("0.8"));
        assertThat(business()).isNotEqualTo("VERIFIED_AD_EXPOSURE_STOPPED");
    }
    @Test void incompleteReportDoesNotProveZeroNewExposure() {
        report(fixture.read.minusSeconds(1),false,false,true,"0");assertSpendBlocked(observation());
    }
    @Test void openCorrectionWindowDoesNotProveZeroNewExposure() {
        report(fixture.read.minusSeconds(1),true,true,true,"0");assertSpendBlocked(observation());
    }
    @Test void revocationOfExactSpendProfileDoesNotRebindToAnotherVersion() {
        report(fixture.read.minusSeconds(1),true,false,true,"0");
        fixture.seed.sql("UPDATE core.ad_freshness_profile SET status='CANCELLED' WHERE organization_id=:org AND evidence_kind='OFFICIAL_AD_SPEND' AND decision_purpose='EARLY_COMPLETED_SALES_OUTCOME'")
                .param("org",fixture.graph.id("organization")).update();
        var observed=observation();assertSpendBlocked(observed);
        assertThat(observed.path("blockers").toString()).contains("FROZEN_FRESHNESS_VERSION_INVALID");
    }
    @Test void independentSpendAndTrafficProfilesApplyDifferentBounds() {
        // Bounds are changed before a separate application snapshot; this also
        // proves that the already-frozen command does not accept the changed version.
        report(fixture.read.minusSeconds(1),true,false,true,"0");
        fixture.seed.sql("UPDATE core.ad_freshness_profile SET source_max_age_minutes=1 WHERE organization_id=:org AND evidence_kind='OFFICIAL_AD_TRAFFIC' AND decision_purpose='EARLY_COMPLETED_SALES_OUTCOME'")
                .param("org",fixture.graph.id("organization")).update();
        var observed=observation();
        assertThat(observed.path("officialSpend").path("evidenceState").asString()).isEqualTo("CANONICAL_CONFIRMED");
        assertThat(observed.path("traffic").isNull()).isTrue();
        assertThat(observed.path("blockers").toString()).contains("FROZEN_FRESHNESS_VERSION_INVALID:EARLY_COMPLETED_SALES_OUTCOME:OFFICIAL_AD_TRAFFIC");
    }
    @Test void providerIncidentBlocksEveryAffectedPurposeWithoutInventingZero() {
        report(fixture.read.minusSeconds(1),true,false,true,"0");
        fixture.seed.sql("INSERT INTO platform.ad_provider_incident VALUES(gen_random_uuid(),:org,:platform,:store,:source,true,:at,:until,'fixture://cv-a-provider-incident')")
                .param("org",fixture.graph.id("organization")).param("platform",fixture.graph.platform()).param("store",fixture.graph.id("store"))
                .param("source",fixture.graph.id("provenance")).param("at",Timestamp.from(fixture.read.minusSeconds(1)))
                .param("until",Timestamp.from(fixture.read.plusSeconds(3600))).update();
        assertSpendBlocked(observation());
    }
    @Test void staleZeroReplacedByFreshPositiveSpendRevisesSameActionWithoutFalseClosure() {
        report(fixture.read.minusSeconds(172800),true,false,true,"0");assertSpendBlocked(observation());
        fixture.read=fixture.read.plusSeconds(1);report(fixture.read,true,false,true,"100");
        var observed=observation();
        assertThat(observed.path("officialSpend").path("value").decimalValue()).isEqualByComparingTo("100");
        assertThat(business()).isNotIn("VERIFIED_AD_EXPOSURE_STOPPED","VERIFIED_EFFICIENCY_SUCCESS");
        assertThat(fixture.seed.sql("SELECT count(*) FROM ops.ad_outcome_observation WHERE command_id=:id")
                .param("id",fixture.command).query(Integer.class).single()).isEqualTo(2);
    }
    @Test void companySplitAgesDoesNotApplyAcceptedAgeLimitToAnOtherwiseFreshSource() {
        fixture.read=fixture.read.plusSeconds(1);
        fixture.seed.sql("""
            INSERT INTO ledger.return_quality_evidence_snapshot(id,organization_id,platform_listing_variant_id,report_window_start,report_window_end,
                completed_coverage,retained_coverage,return_coverage,qc_coverage,completed_source_updated_at,retained_source_updated_at,
                return_source_updated_at,qc_source_updated_at,evidence_reference,accepted_at,correlation_id,supersedes_snapshot_id)
            SELECT gen_random_uuid(),organization_id,platform_listing_variant_id,report_window_start,report_window_end,
                'COMPLETE','INCOMPLETE','INCOMPLETE','CONFLICTED',:source,:source,:source,:source,'fixture://independent-completed-source',:at,'cv-a-split-age',id
            FROM ledger.return_quality_evidence_snapshot r WHERE r.organization_id=:org AND r.report_window_start=:from
                AND NOT EXISTS(SELECT 1 FROM ledger.return_quality_evidence_snapshot n WHERE n.supersedes_snapshot_id=r.id)
            """).param("source",Timestamp.from(fixture.read.minusSeconds(1800))).param("at",Timestamp.from(fixture.read))
            .param("org",fixture.graph.id("organization")).param("from",Timestamp.from(fixture.from)).update();
        var observed=observation();
        assertThat(observed.path("companySales").path("evidenceState").asString()).isEqualTo("CANONICAL_CONFIRMED");
        assertThat(observed.path("companySales").path("value").decimalValue()).isEqualByComparingTo("1000");
    }

    void physicalReport(String kind,String state,Instant effective,Instant sourceTime) {
        UUID provenance=UUID.randomUUID();
        fixture.seed.sql("INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note) VALUES(:id,:org,'MANUAL_ENTRY',:source,:at,:actor,'synthetic complete retrospective source report; no provider access')")
                .param("id",provenance).param("org",fixture.graph.id("organization")).param("source",Timestamp.from(sourceTime))
                .param("at",Timestamp.from(fixture.read)).param("actor",fixture.graph.id("ownerUser")).update();
        if(kind.equals("SELLABILITY")) fixture.seed.sql("INSERT INTO core.listing_health_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,sellable) VALUES(gen_random_uuid(),:org,:source,:listing,:key,:at,:state)")
                .param("org",fixture.graph.id("organization")).param("source",provenance).param("listing",fixture.graph.id("listingVariant"))
                .param("key",UUID.randomUUID().toString()).param("at",Timestamp.from(effective)).param("state",state).update();
        else fixture.seed.sql("INSERT INTO core.listing_stock_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,fulfillment_mode_code,available_quantity) VALUES(gen_random_uuid(),:org,:source,:listing,:key,:at,'SELLER_FULFILLED',:quantity)")
                .param("org",fixture.graph.id("organization")).param("source",provenance).param("listing",fixture.graph.id("listingVariant"))
                .param("key",UUID.randomUUID().toString()).param("at",Timestamp.from(effective)).param("quantity",Integer.valueOf(state)).update();
    }
    @Test void physicalSellabilityCauseClearsAcrossWindowWhileProfitStaysUnknown() {
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        var observed=observation();
        assertThat(observed.path("profit").path("absoluteProfit").path("valueState").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(business()).isEqualTo("VERIFIED_AD_RISK_CLEARED");
        assertThat(observed.path("officialSpend").path("value").decimalValue()).isEqualByComparingTo("100");
    }
    @Test void physicalSellabilityRebuiltCurrentObjectCannotCloseTheOriginalActionDanger() {
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        fixture.seed.sql("UPDATE core.ad_native_object SET lineage_generation=lineage_generation+1 WHERE id=:id")
                .param("id",fixture.graph.id("object")).update();
        var observed=observation();
        assertThat(observed.path("blockers").toString()).contains("OUTCOME_ORIGINAL_ACTION_IDENTITY_UNRESOLVED");
        assertThat(business()).isEqualTo("PROTECTION_IN_PROGRESS");
    }
    @Test void physicalSellabilityDifferentHistoricalConfigurationGenerationCannotCloseTheOriginalDanger() {
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        fixture.seed.sql("""
            INSERT INTO core.ad_object_configuration_observation
            SELECT (jsonb_populate_record(NULL::core.ad_object_configuration_observation,to_jsonb(c)||jsonb_build_object(
              'id',gen_random_uuid(),'source_fact_key',gen_random_uuid()::text,'lineage_generation',c.lineage_generation+1,
              'observed_at',CAST(:at AS timestamptz),'source_time',CAST(:source AS timestamptz)))).*
            FROM core.ad_object_configuration_observation c WHERE c.ad_native_object_id=:object ORDER BY c.observed_at DESC LIMIT 1
            """).param("at",Timestamp.from(fixture.from)).param("source",Timestamp.from(fixture.read))
                .param("object",fixture.graph.id("object")).update();
        var observed=observation();
        assertThat(observed.path("blockers").toString()).contains("OUTCOME_ORIGINAL_ACTION_IDENTITY_UNRESOLVED");
        assertThat(business()).isEqualTo("PROTECTION_IN_PROGRESS");
    }
    @Test void physicalAvailabilityCauseClearsIndependentlyOfUnresolvedProfit() {
        physicalReport("AVAILABILITY","100",fixture.from.minusSeconds(1),fixture.read);
        observation();assertThat(business()).isEqualTo("VERIFIED_AD_RISK_CLEARED");
    }
    @Test void physicalSellabilityRestoredOnlyAfterWindowCannotClaimContinuousRiskClearance() {
        physicalReport("SELLABILITY","NO",fixture.from.minusSeconds(1),fixture.read);
        physicalReport("SELLABILITY","YES",fixture.to.plusSeconds(1),fixture.read);
        observation();assertThat(business()).isEqualTo("PROTECTION_IN_PROGRESS");
    }
    @Test void physicalAvailabilityContinuingHarmRemainsOpenDespitePositiveSpendReport() {
        physicalReport("AVAILABILITY","0",fixture.from.minusSeconds(1),fixture.read);
        observation();assertThat(business()).isEqualTo("PROTECTION_IN_PROGRESS");
    }
    @Test void physicalSellabilityOldSourceCannotCloseEvenWhenNewlyAcceptedAndSafe() {
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read.minusSeconds(172800));
        observation();assertThat(business()).isEqualTo("PROTECTION_IN_PROGRESS");
    }
    @Test void physicalSellabilityUnknownCannotCloseOriginalDanger() {
        physicalReport("SELLABILITY","UNKNOWN",fixture.from.minusSeconds(1),fixture.read);
        observation();assertThat(business()).isEqualTo("PROTECTION_IN_PROGRESS");
    }
    @Test void physicalSellabilityLateInvalidationReopensSameFrozenActionAndPreservesEarlierClaim() {
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        observation();assertThat(business()).isEqualTo("VERIFIED_AD_RISK_CLEARED");
        fixture.read=fixture.read.plusSeconds(2);
        physicalReport("SELLABILITY","NO",fixture.to.minusSeconds(1),fixture.read);
        observation();assertThat(business()).isEqualTo("PROTECTION_IN_PROGRESS");
        assertThat(fixture.seed.sql("SELECT count(*) FROM ops.ad_containment WHERE ad_native_object_id=:id AND containment_kind='ACTION_OUTCOME_QUARANTINE' AND state='ACTIVE'")
                .param("id",fixture.graph.id("object")).query(Integer.class).single()).isEqualTo(1);
        assertThat(fixture.seed.sql("SELECT count(*) FROM ops.work_task_event e JOIN ops.ad_case_responsibility r ON r.task_id=e.task_id WHERE r.case_id=:id AND e.event_kind='ESCALATED'")
                .param("id",fixture.graph.id("caseId")).query(Integer.class).single()).isGreaterThanOrEqualTo(1);
        assertThat(fixture.seed.sql("SELECT count(*) FROM ops.ad_outcome_axes a JOIN ops.ad_outcome_observation o ON o.id=a.observation_id WHERE o.command_id=:id AND a.business_outcome='VERIFIED_AD_RISK_CLEARED'")
                .param("id",fixture.command).query(Integer.class).single()).isEqualTo(1);
    }

    @Test void physicalSellabilityRecurrenceAtTheNextMatureStageReopensOnceAndPreservesTheEarlyWindow() {
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        var early=fixture.service.evaluate(fixture.due(),fixture.read).orElseThrow();
        assertThat(business()).isEqualTo("VERIFIED_AD_RISK_CLEARED");
        String earlySnapshot=fixture.seed.sql("SELECT input_snapshot::text FROM ops.ad_outcome_axes WHERE observation_id=:id")
                .param("id",early.observationId()).query(String.class).single();
        Instant earlyEnd=fixture.to;
        fixture.to=fixture.from.plusSeconds(720*3600L);fixture.read=fixture.to.plusSeconds(60);
        fixture.companyStage("RETAINED","1000",fixture.from);
        fixture.coverage();
        // The same accepted source restates the initial state and separately
        // reports real recurrence inside the later window, after early closure.
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        physicalReport("SELLABILITY","NO",earlyEnd.plusSeconds(1),fixture.read);
        var retained=fixture.service.evaluate(fixture.dueStage("RETAINED"),fixture.read).orElseThrow();
        assertThat(retained.stage()).isEqualTo("RETAINED");
        assertThat(retained.reopenedContainmentId()).isNotNull();
        assertThat(business()).isEqualTo("PROTECTION_IN_PROGRESS");
        var recurrence=json.readTree(fixture.seed.sql("SELECT input_snapshot::text FROM ops.ad_outcome_axes WHERE observation_id=:id")
                .param("id",retained.observationId()).query(String.class).single()).path("observation");
        assertThat(recurrence.path("protectionEvidence").path("exactAffectedScope").asBoolean()).isTrue();
        assertThat(recurrence.path("protectionEvidence").path("sellabilityCleared").asBoolean()).isFalse();
        boolean sellabilityQualified=false;
        for(JsonNode proof:recurrence.path("purposeEvidence")) if(proof.path("kind").asString().equals("SELLABILITY")) {
            sellabilityQualified=proof.path("eligible").asBoolean();
        }
        assertThat(sellabilityQualified).isTrue();
        assertThat(fixture.seed.sql("SELECT supersedes_observation_id IS NULL FROM ops.ad_outcome_observation WHERE id=:id")
                .param("id",retained.observationId()).query(Boolean.class).single()).isTrue();
        assertThat(fixture.seed.sql("SELECT input_snapshot::text FROM ops.ad_outcome_axes WHERE observation_id=:id")
                .param("id",early.observationId()).query(String.class).single()).isEqualTo(earlySnapshot);
        assertThat(fixture.seed.sql("SELECT count(*) FROM ops.ad_containment WHERE ad_native_object_id=:id AND containment_kind='ACTION_OUTCOME_QUARANTINE'")
                .param("id",fixture.graph.id("object")).query(Integer.class).single()).isEqualTo(1);
        assertThat(fixture.seed.sql("SELECT count(*) FROM ops.work_task_event e JOIN ops.ad_case_responsibility r ON r.task_id=e.task_id WHERE r.case_id=:id AND e.event_kind='OUTCOME_OBSERVED' AND e.outcome_reference=:reference")
                .param("id",fixture.graph.id("caseId")).param("reference","ad-outcome:"+retained.observationId()).query(Integer.class).single()).isEqualTo(1);
        fixture.read=fixture.read.plusSeconds(1);
        physicalReport("SELLABILITY","NO",fixture.to.minusSeconds(1),fixture.read);
        var revised=fixture.service.evaluate(fixture.dueStage("RETAINED_REVISED"),fixture.read).orElseThrow();
        assertThat(revised.reopenedContainmentId()).isNull();
        assertThat(fixture.seed.sql("SELECT count(*) FROM ops.ad_containment WHERE ad_native_object_id=:id AND containment_kind='ACTION_OUTCOME_QUARANTINE'")
                .param("id",fixture.graph.id("object")).query(Integer.class).single()).isEqualTo(1);
    }

    @Test void outcomeLaxProfilesCompleteFreshZeroStillProvesExposureStopped() {
        report(fixture.read.minusSeconds(1),true,false,true,"0");
        var observed=observation();
        assertLaxQualified(observed,"OFFICIAL_AD_SPEND");
        assertThat(observed.path("officialSpend").path("evidenceState").asString()).isEqualTo("CANONICAL_CONFIRMED");
        assertThat(business()).isEqualTo("VERIFIED_AD_EXPOSURE_STOPPED");
    }
    @Test void outcomeLaxProfilesIncompleteWholeWindowZeroCannotProveExposureStopped() {
        report(fixture.read.minusSeconds(1),false,false,true,"0");
        var observed=observation();assertLaxQualified(observed,"OFFICIAL_AD_SPEND");
        assertThat(observed.path("coverage").decimalValue()).isEqualByComparingTo("1");
        assertSpendBlocked(observed);
        assertThat(observed.path("officialSpend").path("evidenceState").asString()).isEqualTo("INCOMPLETE");
        assertThat(observed.path("blockers").toString()).contains("OFFICIAL_AD_SPEND_NOT_CANONICAL_COMPLETE_CLOSED");
    }
    @Test void outcomeLaxProfilesOpenCorrectionZeroCannotProveExposureStopped() {
        report(fixture.read.minusSeconds(1),true,true,true,"0");
        var observed=observation();assertLaxQualified(observed,"OFFICIAL_AD_SPEND");
        assertSpendBlocked(observed);
        assertThat(observed.path("officialSpend").path("evidenceState").asString()).isEqualTo("INCOMPLETE");
        assertThat(observed.path("blockers").toString()).contains("OFFICIAL_AD_SPEND_NOT_CANONICAL_COMPLETE_CLOSED");
        assertThat(observed.path("traffic").isNull()).isTrue();
    }
    @Test void physicalSellabilityLaxProfilesCompleteWindowStillClearsRisk() {
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        var observed=observation();assertLaxQualified(observed,"SELLABILITY");
        assertThat(observed.path("protectionEvidence").path("sellabilityCleared").asBoolean()).isTrue();
        assertThat(business()).isEqualTo("VERIFIED_AD_RISK_CLEARED");
    }
    @Test void physicalSellabilityLaxProfilesConflictingSameInstantReportsCannotClearRisk() {
        physicalReport("SELLABILITY","NO",fixture.from.minusSeconds(1),fixture.read);
        fixture.read=fixture.read.plusSeconds(1);
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        var observed=observation();assertLaxQualified(observed,"SELLABILITY");
        assertThat(observed.path("protectionEvidence").path("sellabilityCleared").asBoolean()).isFalse();
        assertThat(business()).isEqualTo("PROTECTION_IN_PROGRESS");
    }
    @Test void physicalSellabilityLaxProfilesFutureConsumedSourceCannotClearRisk() {
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        physicalReport("SELLABILITY","YES",fixture.to.minusSeconds(1),fixture.read.plusSeconds(30));
        var observed=observation();assertLaxQualified(observed,"SELLABILITY");
        assertThat(observed.path("protectionEvidence").path("sellabilityCleared").asBoolean()).isFalse();
        assertThat(business()).isEqualTo("PROTECTION_IN_PROGRESS");
    }
    @Test void physicalSellabilityLaxProfilesConflictingAffectedSetCannotClearRisk() {
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        fixture.seed.sql("""
            INSERT INTO core.ad_affected_set(id,organization_id,ad_native_object_id,affected_set_digest,product_variant_ids,
              platform_listing_variant_ids,resolution_state,unresolved_reason_codes,resolved_at,created_at)
            SELECT gen_random_uuid(),organization_id,ad_native_object_id,repeat('e',64),product_variant_ids,
              platform_listing_variant_ids,'CONFLICTED',ARRAY['CONFLICTING_SOURCE'],resolved_at,created_at
            FROM core.ad_affected_set WHERE id=:id
            """).param("id",fixture.graph.id("affectedSet")).update();
        var observed=observation();assertLaxQualified(observed,"AFFECTED_SET");
        assertThat(observed.path("protectionEvidence").path("exactAffectedScope").asBoolean()).isFalse();
        assertThat(business()).isEqualTo("PROTECTION_IN_PROGRESS");
    }
    @Test void outcomeLaxProfilesFutureLinkedMemberCannotBecomeCanonicalProfit() {
        var valid=fixture.retainedGolden("20");
        var before=json.readTree(fixture.seed.sql("SELECT input_snapshot::text FROM ops.ad_outcome_axes WHERE observation_id=:id")
                .param("id",valid.observationId()).query(String.class).single()).path("observation");
        assertThat(before.path("profit").path("absoluteProfit").path("valueState").asString())
                .as("actual retained precondition: %s",before).isEqualTo("AVAILABLE");
        fixture.read=fixture.read.plusSeconds(1);
        fixture.seed.sql("""
            INSERT INTO ledger.ad_linked_sale_event
            SELECT (jsonb_populate_record(NULL::ledger.ad_linked_sale_event,to_jsonb(e)||jsonb_build_object(
              'id',gen_random_uuid(),'source_time',CAST(:future AS timestamptz),'recorded_at',CAST(:at AS timestamptz)))).*
            FROM ledger.ad_linked_sale_event e WHERE e.id=:event
            """).param("future",Timestamp.from(fixture.read.plusSeconds(30))).param("at",Timestamp.from(fixture.read))
                .param("event",fixture.retainedEvent).update();
        var revised=fixture.service.evaluate(fixture.dueStage("RETAINED_REVISED"),fixture.read).orElseThrow();
        var observed=json.readTree(fixture.seed.sql("SELECT input_snapshot::text FROM ops.ad_outcome_axes WHERE observation_id=:id")
                .param("id",revised.observationId()).query(String.class).single()).path("observation");
        assertLaxQualified(observed,"AD_LINKED_SALE_EVENT");
        assertThat(observed.path("profit").path("absoluteProfit").path("valueState").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(observed.path("blockers").toString()).contains("OUTCOME_ECONOMIC_INPUT_INCOMPLETE");
        assertThat(business()).isNotIn("VERIFIED_AD_RISK_CLEARED","VERIFIED_EFFICIENCY_SUCCESS");
    }
    void assertLaxQualified(JsonNode observed,String kind) {
        assertThat(observed.path("freshnessProfiles").path(kind).path("minimumConfidenceState").asString()).isEqualTo("UNKNOWN");
        boolean eligible=false;
        for(JsonNode proof:observed.path("purposeEvidence")) if(proof.path("kind").asString().equals(kind)) eligible=proof.path("eligible").asBoolean();
        assertThat(eligible).as("ordinary frozen Profile remains eligible for %s",kind).isTrue();
    }

    @Test void physicalSellabilityLaxProfilesUnknownLaterStageDoesNotInvalidateEarlierWindow() {
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        var early=fixture.service.evaluate(fixture.due(),fixture.read).orElseThrow();
        assertThat(business()).isEqualTo("VERIFIED_AD_RISK_CLEARED");
        String earlySnapshot=fixture.seed.sql("SELECT input_snapshot::text FROM ops.ad_outcome_axes WHERE observation_id=:id")
                .param("id",early.observationId()).query(String.class).single();
        Instant earlyEnd=fixture.to;
        fixture.to=fixture.from.plusSeconds(720*3600L);fixture.read=fixture.to.plusSeconds(60);
        fixture.companyStage("RETAINED","1000",fixture.from);fixture.coverage();
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        physicalReport("SELLABILITY","UNKNOWN",earlyEnd.plusSeconds(1),fixture.read);
        var retained=fixture.service.evaluate(fixture.dueStage("RETAINED"),fixture.read).orElseThrow();
        var observed=json.readTree(fixture.seed.sql("SELECT input_snapshot::text FROM ops.ad_outcome_axes WHERE observation_id=:id")
                .param("id",retained.observationId()).query(String.class).single()).path("observation");
        assertLaxQualified(observed,"SELLABILITY");
        assertThat(observed.path("protectionEvidence").path("sellabilityWindowComplete").asBoolean()).isFalse();
        assertThat(business()).isEqualTo("PROTECTION_IN_PROGRESS");
        assertThat(retained.reopenedContainmentId()).isNull();
        fixture.read=fixture.read.plusSeconds(1);
        physicalReport("SELLABILITY","UNKNOWN",fixture.to.minusSeconds(1),fixture.read);
        var revised=fixture.service.evaluate(fixture.dueStage("RETAINED_REVISED"),fixture.read).orElseThrow();
        assertThat(revised.reopenedContainmentId()).isNull();
        assertThat(fixture.seed.sql("SELECT count(*) FROM ops.ad_containment WHERE ad_native_object_id=:id AND containment_kind='ACTION_OUTCOME_QUARANTINE'")
                .param("id",fixture.graph.id("object")).query(Integer.class).single()).isZero();
        assertThat(fixture.seed.sql("SELECT input_snapshot::text FROM ops.ad_outcome_axes WHERE observation_id=:id")
                .param("id",early.observationId()).query(String.class).single()).isEqualTo(earlySnapshot);
    }
    @Test void physicalSellabilityLaxProfilesUnknownSameWindowRevisionStillReopensResponsibility() {
        physicalReport("SELLABILITY","YES",fixture.from.minusSeconds(1),fixture.read);
        var early=fixture.service.evaluate(fixture.due(),fixture.read).orElseThrow();
        assertThat(business()).isEqualTo("VERIFIED_AD_RISK_CLEARED");
        fixture.read=fixture.read.plusSeconds(1);
        physicalReport("SELLABILITY","UNKNOWN",fixture.to.minusSeconds(1),fixture.read);
        var revised=fixture.service.evaluate(fixture.due(),fixture.read).orElseThrow();
        assertThat(revised.reopenedContainmentId()).isNotNull();
        assertThat(business()).isEqualTo("PROTECTION_IN_PROGRESS");
        assertThat(fixture.seed.sql("SELECT supersedes_observation_id FROM ops.ad_outcome_observation WHERE id=:id")
                .param("id",revised.observationId()).query(UUID.class).single()).isEqualTo(early.observationId());
        assertThat(fixture.seed.sql("SELECT count(*) FROM ops.ad_containment WHERE ad_native_object_id=:id AND containment_kind='ACTION_OUTCOME_QUARANTINE'")
                .param("id",fixture.graph.id("object")).query(Integer.class).single()).isEqualTo(1);
    }

}
