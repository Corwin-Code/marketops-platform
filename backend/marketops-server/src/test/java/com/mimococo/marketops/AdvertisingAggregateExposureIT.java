package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual six-axis SQL and serialized app admission; isolated history injections are explicit. */
class AdvertisingAggregateExposureIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration,application,admin;
    private static JdbcClient seed,read;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private AdvertisingR1Fixture.Graph graph;
    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        seed=JdbcClient.create(migration);read=JdbcClient.create(application);
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }
    @BeforeEach void fixture() throws Exception { graph=AdvertisingR1Fixture.seed(migration); }

    @Test void canonicalPositiveControlAdmitsOneActionWithEveryAggregateAxisAvailable() throws Exception {
        seal(graph);reserve(graph);
        assertThat(reasons("PROTECTION_DECREASE")).isEmpty();
        JsonNode axes=snapshot("PROTECTION_DECREASE").path("envelopes").get(0).path("axes");
        assertThat(axes.properties()).hasSize(6);
        for(var axis:axes.properties()) assertThat(axis.getValue().path("state").asText()).as(axis.getKey()).isEqualTo("AVAILABLE");
        assertThat(axes.path("activeInterventions").path("usage").asInt()).isEqualTo(1);
        assertThat(axes.path("affectedRetainedSalesShare").path("usage").decimalValue()).isEqualByComparingTo("1");
        assertThat(axes.path("cumulativeBidChangeMajor").path("usage").decimalValue()).isEqualByComparingTo("10");
        assertThat(countReservations()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"max_associated_spend_amount,0,ASSOCIATED_SPEND", "max_affected_retained_sales_share,0.5,AFFECTED_RETAINED_SALES_SHARE",
            "max_cumulative_bid_change_amount,9,CUMULATIVE_BID_CHANGE"})
    void eachMeasuredAmountAxisFailsWithoutNettingTheOtherAxes(String column,String limit,String reason) throws Exception {
        seal(graph);reserve(graph);assertThat(reasons("PROTECTION_DECREASE")).isEmpty();
        policy(column+"="+limit);
        assertThat(reasons("PROTECTION_DECREASE")).containsExactlyInAnyOrder(reason,"AGGREGATE_ENVELOPE_BLOCKED");
    }

    @ParameterizedTest
    @CsvSource({"max_associated_spend_amount,0,ASSOCIATED_SPEND", "max_affected_retained_sales_share,0.5,AFFECTED_RETAINED_SALES_SHARE",
            "max_cumulative_bid_change_amount,9,CUMULATIVE_BID_CHANGE"})
    void actualControlledAdmissionRollsBackForEachExhaustedMeasuredAxis(String column,String limit,String reason) throws Exception {
        graph=AdvertisingR1Fixture.seedOutcome(migration,sql->sql.replace(
                "retained_window_days=30,measurement_window_hours=720,max_affected_retained_sales_share=1",
                "retained_window_days=30,measurement_window_hours=720,"+(column.equals("max_affected_retained_sales_share")?"":"max_affected_retained_sales_share=1,")+column+"="+limit));
        seal(graph);
        assertThatThrownBy(()->reserve(graph)).isInstanceOf(SQLException.class).hasMessageContaining(reason);
        assertThat(countReservations()).isZero();
    }

    @Test void activeCountStillBoundsRecoveryAndCannotBeOffsetByLowSpend() throws Exception {
        seal(graph);reserve(graph);assertThat(reasons("EXACT_PRIOR_BID_COMPENSATION")).isEmpty();
        policy("max_active_interventions=2,reserved_recovery_headroom_count=1");
        historicalHolder();historicalHolder();
        assertThat(reasons("EXACT_PRIOR_BID_COMPENSATION")).containsExactlyInAnyOrder("ACTIVE_INTERVENTIONS","AGGREGATE_ENVELOPE_BLOCKED");
    }
    @Test void ordinaryAdmissionCannotBorrowTheReservedRecoverySlot() throws Exception {
        seal(graph);reserve(graph);policy("max_active_interventions=2,reserved_recovery_headroom_count=1");
        assertThat(reasons("PROTECTION_DECREASE")).isEmpty();historicalHolder();
        assertThat(reasons("PROTECTION_DECREASE")).containsExactlyInAnyOrder("RECOVERY_HEADROOM","AGGREGATE_ENVELOPE_BLOCKED");
        assertThat(reasons("EXACT_PRIOR_BID_COMPENSATION")).isEmpty();
    }
    @Test void unresolvedWriteConsumesItsOwnAxisAlongsideTheActiveSlot() throws Exception {
        seal(graph);reserve(graph);policy("max_unresolved_transmitted_writes=0");
        assertThat(reasons("PROTECTION_DECREASE")).isEmpty();
        seed.sql("UPDATE ops.ad_action_reservation SET unknown_or_mismatch_open=true WHERE id=:id").param("id",graph.id("reservation")).update();
        assertThat(reasons("PROTECTION_DECREASE")).containsExactlyInAnyOrder("UNRESOLVED_TRANSMITTED_WRITES","AGGREGATE_ENVELOPE_BLOCKED");
        assertThat(axis("activeInterventions").path("usage").asInt()).isEqualTo(1);
    }
    @ParameterizedTest
    @CsvSource({"max_active_interventions", "max_associated_spend_amount", "max_affected_retained_sales_share",
            "max_cumulative_bid_change_amount", "max_unresolved_transmitted_writes", "reserved_recovery_headroom_count"})
    void noAxisCanAcquireAnUnknownLimitThroughANullSchemaValue(String column) {
        assertThatThrownBy(()->policy(column+"=NULL")).hasRootCauseInstanceOf(SQLException.class)
            .satisfies(error->assertThat(sqlState(error)).isEqualTo("23502"));
    }

    @Test void missingAffectedCoverageIsUnknownAndActualAdmissionRollsBack() throws Exception {
        seal(graph);coverage(graph.id("listingVariant"),"INCOMPLETE",false,false);
        assertThatThrownBy(()->reserve(graph)).isInstanceOf(SQLException.class).hasMessageContaining("RETAINED_SALES_SHARE_UNRESOLVED");
        assertThat(countReservations()).isZero();
        assertThat(axis("affectedRetainedSalesShare").path("state").asText()).isEqualTo("UNKNOWN");
        assertThat(axis("affectedRetainedSalesShare").path("usage").isNull()).isTrue();
    }
    @Test void anotherStoreWithoutCoverageCannotDisappearFromOrganizationExposure() throws Exception {
        seal(graph);reserve(graph);assertThat(reasons("PROTECTION_DECREASE")).isEmpty();
        companyUnit(true,false);
        assertThat(reasons("PROTECTION_DECREASE")).contains("RETAINED_SALES_SHARE_UNRESOLVED");
        assertThat(axis("affectedRetainedSalesShare").path("usage").isNull()).isTrue();
    }
    @Test void aMissingAffectedListingUnitIsNotDeduplicatedOutOfCoverage() throws Exception {
        seal(graph);reserve(graph);companyUnit(false,true);
        assertThat(reasons("PROTECTION_DECREASE")).contains("RETAINED_SALES_SHARE_UNRESOLVED");
        assertThat(axis("affectedRetainedSalesShare").path("usage").isNull()).isTrue();
    }
    @Test void mixedRetainedCurrencyDoesNotProduceAKnownShare() throws Exception {
        seal(graph);reserve(graph);assertThat(reasons("PROTECTION_DECREASE")).isEmpty();
        retained(graph.id("listingVariant"),"USD",false,false);
        assertThat(reasons("PROTECTION_DECREASE")).contains("RETAINED_SALES_SHARE_UNRESOLVED");
    }
    @Test void duplicateRetainedCohortsCannotInflateTheCompanyDenominator() throws Exception {
        seal(graph);reserve(graph);assertThat(reasons("PROTECTION_DECREASE")).isEmpty();
        retained(graph.id("listingVariant"),"RUB",true,false);
        assertThat(reasons("PROTECTION_DECREASE")).contains("RETAINED_SALES_SHARE_UNRESOLVED");
    }
    @Test void aFutureCoverageAcceptanceCannotRepairACurrentlyIncompleteUnit() throws Exception {
        seal(graph);reserve(graph);coverage(graph.id("listingVariant"),"INCOMPLETE",false,false);
        coverage(graph.id("listingVariant"),"COMPLETE",true,false);
        assertThat(reasons("PROTECTION_DECREASE")).contains("RETAINED_SALES_SHARE_UNRESOLVED");
    }
    @Test void futureSourceUpdatesCannotCertifyTheCurrentCoveragePrefix() throws Exception {
        seal(graph);reserve(graph);coverage(graph.id("listingVariant"),"COMPLETE",false,true);
        assertThat(reasons("PROTECTION_DECREASE")).contains("RETAINED_SALES_SHARE_UNRESOLVED");
    }
    @Test void futureIngestedRetainedFactsCannotChangeTheCurrentExposure() throws Exception {
        seal(graph);reserve(graph);JsonNode before=axis("affectedRetainedSalesShare");
        UUID other=companyUnit(false,false);coverage(other,"COMPLETE",false,false);
        retained(other,"RUB",false,true);
        assertThat(axis("affectedRetainedSalesShare").path("companySales")).isEqualTo(before.path("companySales"));
        assertThat(axis("affectedRetainedSalesShare").path("usage")).isEqualTo(before.path("usage"));
    }
    @Test void futureProvenanceSourceCannotChangeTheCurrentExposure() throws Exception {
        seal(graph);reserve(graph);JsonNode before=axis("affectedRetainedSalesShare");
        UUID other=companyUnit(false,false);coverage(other,"COMPLETE",false,false);
        retained(other,"RUB",false,false,true);
        assertThat(axis("affectedRetainedSalesShare").path("companySales")).isEqualTo(before.path("companySales"));
        assertThat(axis("affectedRetainedSalesShare").path("usage")).isEqualTo(before.path("usage"));
    }
    @Test void whollyFutureOfficialPeriodCannotAddCurrentAssociatedSpend() throws Exception {
        seal(graph);reserve(graph);JsonNode before=axis("associatedOfficialSpend");
        officialPeriod("now()+interval '1 day'","now()+interval '2 days'");
        assertThat(axis("associatedOfficialSpend")).isEqualTo(before);
    }
    @Test void officialAmountStraddlingAsOfCannotBeProratedIntoKnownSpend() throws Exception {
        seal(graph);reserve(graph);officialPeriod("now()-interval '1 hour'","now()+interval '1 day'");
        assertThat(axis("associatedOfficialSpend").path("state").asText()).isEqualTo("UNKNOWN");
        assertThat(axis("associatedOfficialSpend").path("usage").isNull()).isTrue();
        assertThat(reasons("PROTECTION_DECREASE")).contains("ASSOCIATED_SPEND_UNRESOLVED");
    }
    @Test void wholeOfficialReportCrossingOwnerWindowRemainsAConservativeAmount() throws Exception {
        seal(graph);reserve(graph);officialPeriod("now()-interval '31 days'","now()-interval '29 days'");
        assertThat(axis("associatedOfficialSpend").path("state").asText()).isEqualTo("AVAILABLE");
        assertThat(axis("associatedOfficialSpend").path("usage").decimalValue()).isEqualByComparingTo("200");
        assertThat(axis("associatedOfficialSpend").path("conservativeBoundaryReportCount").asInt()).isEqualTo(1);
        assertThat(axis("associatedOfficialSpend").path("aggregationBasis").asText()).isEqualTo("COMPLETE_INTERSECTING_OFFICIAL_REPORT_AMOUNTS");
    }
    @Test void completeAcceptedPrefixCanProveZeroForAListingWithNoRetainedRows() throws Exception {
        seal(graph);reserve(graph);UUID other=companyUnit(false,true);
        assertThat(reasons("PROTECTION_DECREASE")).contains("RETAINED_SALES_SHARE_UNRESOLVED");
        coverage(other,"COMPLETE",false,false);
        assertThat(reasons("PROTECTION_DECREASE")).isEmpty();
        assertThat(axis("affectedRetainedSalesShare").path("companySales").decimalValue()).isEqualByComparingTo("1000");
    }
    @Test void completeZeroCompanyDenominatorIsKnownZeroButNeverAKnownRatio() throws Exception {
        graph=AdvertisingR1Fixture.seedOutcome(migration,sql->sql.replaceAll("(?s)INSERT INTO ledger\\.sales_fact[^;]+?fictional-retained[^;]+;", ""));
        seal(graph);
        assertThatThrownBy(()->reserve(graph)).hasMessageContaining("RETAINED_SALES_SHARE_UNRESOLVED");
        assertThat(axis("affectedRetainedSalesShare").path("companySales").decimalValue()).isEqualByComparingTo("0");
        assertThat(axis("affectedRetainedSalesShare").path("usage").isNull()).isTrue();
    }

    @Test void twoRealApplicationTransactionsCannotAdmitOverlappingSealedCandidates() throws Exception {
        var second=secondReviewedCandidate();seal(graph);seal(second);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<java.util.concurrent.Future<String>>();
            for(var item:List.of(graph,second)) results.add(workers.submit(()->{
                ready.countDown();if(!start.await(10,TimeUnit.SECONDS)) throw new AssertionError("bounded test start");
                try {reserve(item);return "ADMITTED";}catch(SQLException refusal){return refusal.getSQLState();}
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("ADMITTED","MO097");
        }
        assertThat(countReservations()).isEqualTo(1);
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_action_authorization WHERE organization_id=:org")
            .param("org",graph.id("organization")).query(Integer.class).single()).isEqualTo(2);
    }
    @ParameterizedTest
    @CsvSource({"3,2", "2,1"})
    void disjointHistoricalAuthoritiesUseRealSerializedAggregateAdmission(int limit,int admitted) throws Exception {
        graph=AdvertisingR1Fixture.seedOutcome(migration,sql->sql.replace(
            "retained_window_days=30,measurement_window_hours=720,max_affected_retained_sales_share=1",
            "retained_window_days=30,measurement_window_hours=720,max_affected_retained_sales_share=1,max_active_interventions="+limit+",reserved_recovery_headroom_count=1"));
        seal(graph);
        var second=secondReviewedCandidate(true);
        // Migration-role history oracle supplies an already-authorized intervention.
        // This test targets app admission, not the human or Provider execution path.
        seed.sql("INSERT INTO ops.ad_action_authorization SELECT (jsonb_populate_record(NULL::ops.ad_action_authorization,to_jsonb(original)||jsonb_build_object('id',gen_random_uuid(),'recommendation_id',:rec,'approval_decision_id',:approval,'candidate_id',:candidate,'outcome_baseline_id',:baseline))).* FROM ops.ad_action_authorization original WHERE recommendation_id=:original")
            .param("rec",second.id("recommendation")).param("approval",second.id("approval")).param("candidate",second.id("candidate")).param("baseline",second.id("baseline")).param("original",graph.id("recommendation")).update();
        assertThat(second.id("object")).isNotEqualTo(graph.id("object"));
        assertThat(second.id("productVariant")).isNotEqualTo(graph.id("productVariant"));
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<java.util.concurrent.Future<String>>();
            for(var item:List.of(graph,second)) results.add(workers.submit(()->{
                ready.countDown();if(!start.await(10,TimeUnit.SECONDS)) throw new AssertionError("bounded test start");
                try {reserve(item);return "ADMITTED";} catch(SQLException refusal) {
                    assertThat(refusal.getSQLState()).isEqualTo("MO097");
                    assertThat(refusal.getMessage()).contains("RECOVERY_HEADROOM").doesNotContain("already holds");
                    return "AGGREGATE_REFUSED";
                }
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            List<String> outcomes=List.of(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("ADMITTED"::equals).count()).isEqualTo(admitted);
            assertThat(outcomes.stream().filter("AGGREGATE_REFUSED"::equals).count()).isEqualTo(2-admitted);
        }
        assertThat(countReservations()).isEqualTo(admitted);
    }
    private void seal(AdvertisingR1Fixture.Graph item) throws Exception {
        try(Connection app=application.getConnection()) {app.setAutoCommit(false);
            String proof=AdvertisingR1Fixture.proof(admin,app,item,item.id("ownerUser"),null,item.id("recommendation"),item.id("approval"));
            AdvertisingR1Fixture.seal(app,item,proof);app.commit();}
    }
    private void reserve(AdvertisingR1Fixture.Graph item) throws SQLException {
        try(Connection app=application.getConnection()) {app.setAutoCommit(false);AdvertisingR1Fixture.reserve(app,item);app.commit();}
    }
    private long countReservations(){return seed.sql("SELECT count(*) FROM ops.ad_action_reservation WHERE organization_id=:org AND state='ACTIVE'")
        .param("org",graph.id("organization")).query(Long.class).single();}
    private JsonNode snapshot(String direction){return JSON.readTree(read.sql("SELECT ops.ad_exposure_snapshot(:org,:store,:direction)::text")
        .param("org",graph.id("organization")).param("store",graph.id("store")).param("direction",direction).query(String.class).single());}
    private JsonNode axis(String name){return snapshot("PROTECTION_DECREASE").path("envelopes").get(0).path("axes").path(name);}
    private List<String> reasons(String direction){var result=new ArrayList<String>();snapshot(direction).path("reasons").forEach(n->result.add(n.asText()));return result;}
    private void policy(String set){seed.sql("UPDATE core.ad_exposure_envelope SET "+set+" WHERE id=:id").param("id",graph.id("exposure")).update();}
    private static String sqlState(Throwable error){while(error!=null){if(error instanceof SQLException sql)return sql.getSQLState();error=error.getCause();}return null;}

    /** A second already-reviewed synthetic candidate uses real independent issuer/seal and app admission. */
    private AdvertisingR1Fixture.Graph secondReviewedCandidate() {return secondReviewedCandidate(false);}
    private AdvertisingR1Fixture.Graph secondReviewedCandidate(boolean disjoint) {
        Map<String,UUID> ids=new HashMap<>(graph.ids());
        for(String key:List.of("candidate","recommendation","approval","selection","endorsement","baseline","reservation"))ids.put(key,UUID.randomUUID());
        if(disjoint) {
            UUID unit=companyUnit(false,false);coverage(unit,"COMPLETE",false,false);
            ids.put("listingVariant",unit);
            ids.put("listing",seed.sql("SELECT platform_listing_id FROM core.platform_listing_variant WHERE id=:id").param("id",unit).query(UUID.class).single());
            ids.put("productVariant",seed.sql("SELECT product_variant_id FROM core.listing_mapping WHERE platform_listing_variant_id=:id").param("id",unit).query(UUID.class).single());
            for(String key:List.of("object","affectedSet","configuration","caseId"))ids.put(key,UUID.randomUUID());
        }
        String replace="to_jsonb(original)::text";
        for(String key:List.of("candidate","recommendation","approval","selection","endorsement","baseline","object","affectedSet","configuration","caseId","productVariant","listingVariant","listing"))
            replace="replace("+replace+",'"+graph.id(key)+"','"+ids.get(key)+"')";
        String changed="("+replace+")::jsonb";
        if(disjoint) {
            cloneReviewed("core.ad_native_object","id",graph.id("object"),changed+"||jsonb_build_object('native_object_key','disjoint-"+ids.get("object")+"')");
            cloneReviewed("core.ad_affected_set","id",graph.id("affectedSet"),changed);
            cloneReviewed("core.ad_object_configuration_observation","id",graph.id("configuration"),changed);
            cloneReviewed("mart.ad_case","id",graph.id("caseId"),changed+"||jsonb_build_object('case_key','disjoint-"+ids.get("caseId")+"')");
            cloneReviewed("ledger.ad_object_fact","ad_native_object_id",graph.id("object"),changed+"||jsonb_build_object('id',gen_random_uuid(),'source_fact_key','disjoint-spend')");
        }
        cloneReviewed("ops.ad_bid_candidate","id",graph.id("candidate"),changed+"||jsonb_build_object('ordinal',2)");
        cloneReviewed("ops.recommendation","id",graph.id("recommendation"),changed+"||jsonb_build_object('entity_version_digest',ops.ad_entity_version_digest('"+ids.get("object")+"','"+ids.get("candidate")+"'))");
        cloneReviewed("ops.ad_outcome_baseline","id",graph.id("baseline"),changed);
        cloneReviewed("ops.ad_outcome_stage_baseline","outcome_baseline_id",graph.id("baseline"),changed);
        cloneReviewed("ops.ad_outcome_critical_unit","outcome_baseline_id",graph.id("baseline"),changed);
        seed.sql("INSERT INTO ops.ad_outcome_baseline_attestation SELECT :id,:org,ops.ad_outcome_stored_payload_digest(:id),now(),'CANONICAL_OUTCOME_PLANNER_V1'")
            .param("id",ids.get("baseline")).param("org",ids.get("organization")).update();
        String snapshot="jsonb_build_object('bid',ops.ad_bid_authority_snapshot('"+ids.get("recommendation")+"'),'bundle',ops.ad_bundle_authority_snapshot('"+ids.get("bundle")+"'))";
        cloneReviewed("ops.ad_candidate_selection","id",graph.id("selection"),changed+"||jsonb_build_object('authority_snapshot',"+snapshot+")");
        cloneReviewed("ops.ad_candidate_endorsement","id",graph.id("endorsement"),changed+"||jsonb_build_object('authority_snapshot',"+snapshot+")");
        cloneReviewed("ops.guardrail_evaluation","recommendation_id",graph.id("recommendation"),changed+"||jsonb_build_object('id',gen_random_uuid(),'authority_snapshot',ops.ad_bid_authority_snapshot('"+ids.get("recommendation")+"'))");
        cloneReviewed("ops.approval_decision","id",graph.id("approval"),changed+"||jsonb_build_object('entity_version_digest',ops.ad_entity_version_digest('"+ids.get("object")+"','"+ids.get("candidate")+"'))");
        return new AdvertisingR1Fixture.Graph(Map.copyOf(ids),graph.platform());
    }
    private void cloneReviewed(String table,String key,UUID id,String json){seed.sql("INSERT INTO "+table+" SELECT (jsonb_populate_record(NULL::"+table+","+json+")).* FROM "+table+" original WHERE "+key+"=:id").param("id",id).update();}

    /** Migration-only history oracle adds another held action to measure count, without granting app DML. */
    private void historicalHolder() {
        UUID object=UUID.randomUUID(),affected=UUID.randomUUID(),candidate=UUID.randomUUID();
        cloneReviewed("core.ad_native_object","id",graph.id("object"),"to_jsonb(original)||jsonb_build_object('id','"+object+"','native_object_key','aggregate-"+object+"')");
        cloneReviewed("core.ad_affected_set","id",graph.id("affectedSet"),"to_jsonb(original)||jsonb_build_object('id','"+affected+"','ad_native_object_id','"+object+"')");
        cloneReviewed("ops.ad_bid_candidate","id",graph.id("candidate"),"to_jsonb(original)||jsonb_build_object('id','"+candidate+"','ad_native_object_id','"+object+"','ordinal',"+(int)(3+countReservations())+")");
        cloneReviewed("ops.ad_action_reservation","id",graph.id("reservation"),"to_jsonb(original)||jsonb_build_object('id',gen_random_uuid(),'ad_native_object_id','"+object+"','affected_set_id','"+affected+"','intervention_reference_id','"+candidate+"')");
        seed.sql("INSERT INTO ledger.ad_object_fact SELECT (jsonb_populate_record(NULL::ledger.ad_object_fact,to_jsonb(original)||jsonb_build_object('id',gen_random_uuid(),'ad_native_object_id',:object,'source_fact_key',:key))).* FROM ledger.ad_object_fact original WHERE ad_native_object_id=:original")
            .param("object",object).param("key",object.toString()).param("original",graph.id("object")).update();
    }
    private UUID companyUnit(boolean otherStore,boolean affected) {
        UUID store=graph.id("store");
        if(otherStore){store=UUID.randomUUID();cloneReviewed("core.store","id",graph.id("store"),"to_jsonb(original)||jsonb_build_object('id','"+store+"','code','s"+store+"')");}
        UUID listing=UUID.randomUUID(),unit=UUID.randomUUID(),product=graph.id("productVariant");
        if(!affected){product=UUID.randomUUID();cloneReviewed("core.product_variant","id",graph.id("productVariant"),"to_jsonb(original)||jsonb_build_object('id','"+product+"','sku_code','p"+product+"')");}
        cloneReviewed("core.platform_listing","id",graph.id("listing"),"to_jsonb(original)||jsonb_build_object('id','"+listing+"','store_id','"+store+"','native_listing_key','l"+listing+"')");
        cloneReviewed("core.platform_listing_variant","id",graph.id("listingVariant"),"to_jsonb(original)||jsonb_build_object('id','"+unit+"','platform_listing_id','"+listing+"','native_variant_key','v"+unit+"')");
        cloneReviewed("core.listing_mapping","platform_listing_variant_id",graph.id("listingVariant"),"to_jsonb(original)||jsonb_build_object('id',gen_random_uuid(),'platform_listing_variant_id','"+unit+"','product_variant_id','"+product+"')");
        return unit;
    }
    private void coverage(UUID unit,String state,boolean futureAccepted,boolean futureSource){
        seed.sql("""
          INSERT INTO ledger.return_quality_evidence_snapshot(id,organization_id,platform_listing_variant_id,
            report_window_start,report_window_end,completed_coverage,retained_coverage,return_coverage,qc_coverage,
            completed_source_updated_at,retained_source_updated_at,return_source_updated_at,qc_source_updated_at,
            evidence_reference,accepted_at,correlation_id,supersedes_snapshot_id)
          SELECT gen_random_uuid(),:org,:unit,coalesce(prior.report_window_start,now()-interval '60 days'),
            coalesce(prior.report_window_end,now()+interval '5 minutes'),
            'COMPLETE',:state,'COMPLETE_ZERO','COMPLETE',now(),
            now()+CASE WHEN :futureSource THEN interval '1 day' ELSE interval '0' END,now(),now(),
            'fixture://accepted-covering-prefix',now()+CASE WHEN :futureAccepted THEN interval '1 day' ELSE interval '0' END,
            'aggregate-coverage', prior.id
          FROM (SELECT 1) singleton LEFT JOIN LATERAL (
            SELECT old.* FROM ledger.return_quality_evidence_snapshot old WHERE old.platform_listing_variant_id=:unit
              AND NOT EXISTS(SELECT 1 FROM ledger.return_quality_evidence_snapshot newer WHERE newer.supersedes_snapshot_id=old.id)
              ORDER BY old.accepted_at DESC LIMIT 1) prior ON true
          """).param("org",graph.id("organization")).param("unit",unit).param("state",state).param("futureAccepted",futureAccepted).param("futureSource",futureSource).update();
    }
    private void officialPeriod(String from,String to) {
        seed.sql("INSERT INTO ledger.ad_object_fact SELECT (jsonb_populate_record(NULL::ledger.ad_object_fact,to_jsonb(original)||jsonb_build_object('id',gen_random_uuid(),'source_fact_key',:key,'period_start',"+from+",'period_end',"+to+"))).* FROM ledger.ad_object_fact original WHERE ad_native_object_id=:id AND source_fact_key='fictional-spend'")
            .param("key",UUID.randomUUID().toString()).param("id",graph.id("object")).update();
    }
    private void retained(UUID unit,String currency,boolean duplicate,boolean futureAccepted){retained(unit,currency,duplicate,futureAccepted,false);}
    private void retained(UUID unit,String currency,boolean duplicate,boolean futureAccepted,boolean futureSource){
        UUID source=graph.id("provenance");
        if(futureAccepted||futureSource){source=UUID.randomUUID();cloneReviewed("core.fact_provenance","id",graph.id("provenance"),"to_jsonb(original)||jsonb_build_object('id','"+source+"','ingestion_time',now()+interval '"+(futureAccepted?"1 day":"0")+"','source_time',now()+interval '"+(futureSource?"1 day":"0")+"')");}
        seed.sql("""
          INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,
            sale_stage,retention_window_days,source_fact_key,native_order_key,native_line_key,occurred_at,quantity,currency_code,gross_amount,net_amount)
          SELECT gen_random_uuid(),:org,:source,:unit,listing.store_id,'RETAINED',30,:key,
            CASE WHEN :duplicate THEN 'fictional-order' ELSE :key END,NULL,now()-interval '1 hour',1,:currency,1000,1000
          FROM core.platform_listing_variant variant JOIN core.platform_listing listing ON listing.id=variant.platform_listing_id WHERE variant.id=:unit
          """).param("org",graph.id("organization")).param("source",source).param("unit",unit).param("key",UUID.randomUUID().toString()).param("duplicate",duplicate).param("currency",currency).update();
    }
}
