package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.mimococo.marketops.AdvertisingGraphFixture;
import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Actual repository SQL on PostgreSQL: missing values, gaps, overlap and late restatement. */
@SpringBootTest @ActiveProfiles("ci")
class AdvertisingCanonicalFactsIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static final Instant FROM=Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant MID=FROM.plusSeconds(86400), TO=MID.plusSeconds(86400), READ=TO.plusSeconds(3600);
    @Autowired AdvertisingEvidenceRepository facts;
    @Autowired com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository policies;
    @Autowired AdvertisingEvidenceGatherer gatherer;
    JdbcClient seed;
    AdvertisingGraphFixture.Graph graph;
    UUID provenance;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",TestDatabase::applicationRole);
        registry.add("spring.datasource.password",TestDatabase::applicationPassword);
        registry.add("spring.flyway.user",TestDatabase::migrationRole);
        registry.add("spring.flyway.password",TestDatabase::migrationPassword);
    }
    @BeforeEach void fixture() {
        seed=JdbcClient.create(new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword()));
        graph=AdvertisingGraphFixture.seed(seed);
        provenance=seed.sql("SELECT provenance_id FROM core.ad_object_configuration_observation WHERE id=:id")
                .param("id",graph.configurationId()).query(UUID.class).single();
    }
    UUID fact(Instant from,Instant to,String spend,String clicks,String currency,Instant accepted,UUID supersedes) {
        UUID id=UUID.randomUUID();
        seed.sql("""
                INSERT INTO ledger.ad_object_fact(id,organization_id,provenance_id,ad_native_object_id,store_id,source_fact_key,
                    period_start,period_end,currency_code,spend_amount,clicks,report_window_complete,correction_window_open,
                    source_time,recorded_at,supersedes_fact_id,adjustment_kind)
                VALUES(:id,:org,:provenance,:object,:store,:key,:from,:to,:currency,:spend,:clicks,true,false,:to,:accepted,:supersedes,:adjustment)
                """).param("id",id).param("org",graph.organizationId()).param("provenance",provenance).param("object",graph.objectId())
                .param("store",graph.storeId()).param("key","canonical-facts:"+id).param("from",Timestamp.from(from)).param("to",Timestamp.from(to))
                .param("currency",currency).param("spend",spend==null?null:new BigDecimal(spend)).param("clicks",clicks==null?null:Long.valueOf(clicks))
                .param("accepted",Timestamp.from(accepted)).param("supersedes",supersedes).param("adjustment",supersedes==null?null:"CORRECTION").update();
        return id;
    }
    AdvertisingEvidenceRepository.ObjectFactAggregate read(Instant at) { return facts.objectFacts(graph.organizationId(),graph.objectId(),FROM,TO,at).orElseThrow(); }
    @Test void partialMissingMoneyAndTrafficNeverDisappearInsideSqlSum() {
        fact(FROM,MID,"100","10","RUB",READ,null);fact(MID,TO,null,null,"RUB",READ,null);
        assertThat(read(READ).spendAmount()).isNull();assertThat(read(READ).clicks()).isNull();
        assertThat(read(READ).coverageRatio()).isEqualByComparingTo("1");
    }
    @Test void completeSubreportsDoNotInventCoverageOfMissingDays() {
        fact(FROM,MID,"100","10","RUB",READ,null);
        assertThat(read(READ).coverageRatio()).isEqualByComparingTo("0.5");
    }
    @Test void overlapIsUnqualifiedEvenWhenUnionCoversTheWholeWindow() {
        fact(FROM,TO,"100","10","RUB",READ,null);fact(MID,TO,"50","5","RUB",READ,null);
        assertThat(read(READ).everyWindowComplete()).isFalse();
        assertThat(read(READ).coverageRatio()).isEqualByComparingTo("1");
    }
    @Test void laterCorrectionDoesNotRewriteWhatWasAvailableAtActionTime() {
        UUID original=fact(FROM,TO,"100","10","RUB",READ,null);
        fact(FROM,TO,"70","7","RUB",READ.plusSeconds(60),original);
        assertThat(read(READ).spendAmount()).isEqualByComparingTo("100");
        assertThat(read(READ.plusSeconds(60)).spendAmount()).isEqualByComparingTo("70");
        assertThat(read(READ.plusSeconds(60)).everyWindowComplete()).isTrue();
    }
    @Test void differentCurrenciesNeverBecomeOneMonetaryTotal() {
        fact(FROM,MID,"100","10","RUB",READ,null);fact(MID,TO,"20","2","USD",READ,null);
        assertThat(read(READ).currencyCode()).isNull();
    }

    UUID listing(UUID variant, boolean mapped) {
        UUID listing=UUID.randomUUID(), unit=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.platform_listing(id,organization_id,store_id,marketplace_account_id,platform_code,native_listing_key,
                    first_seen_at,last_seen_at,status,created_at,updated_at)
                VALUES(:id,:org,:store,:account,'OZON',:key,now(),now(),'OBSERVED',now(),now())
                """).param("id",listing).param("org",graph.organizationId()).param("store",graph.storeId())
                .param("account",graph.accountId()).param("key",listing.toString()).update();
        seed.sql("""
                INSERT INTO core.platform_listing_variant(id,organization_id,platform_listing_id,native_variant_key,first_seen_at,last_seen_at,status,created_at,updated_at)
                VALUES(:id,:org,:listing,:key,now(),now(),'OBSERVED',now(),now())
                """).param("id",unit).param("org",graph.organizationId()).param("listing",listing).param("key",unit.toString()).update();
        if(mapped) seed.sql("""
                INSERT INTO core.listing_mapping(id,organization_id,platform_listing_variant_id,product_variant_id,effective_from,status,
                    confirmed_by_user_id,reason,created_at,updated_at)
                VALUES(gen_random_uuid(),:org,:listing,:variant,:from,'ACTIVE',:actor,'canonical test mapping',now(),now())
                """).param("org",graph.organizationId()).param("listing",unit).param("variant",variant)
                .param("from",Timestamp.from(FROM)).param("actor",graph.executorUserId()).update();
        return unit;
    }
    UUID conversion() {
        UUID id=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_conversion_definition(id,organization_id,definition_version,scope_kind,sale_stage,traffic_denominator_kind,
                    linkage_basis,minimum_linkage_coverage_ratio,minimum_affected_set_coverage_ratio,minimum_sample_events,
                    maximum_attribution_gap_ratio,observation_window_days,owner_user_id,reason,evidence_reference,effective_from,status,created_at)
                VALUES(:id,:org,1,'ORGANIZATION','CANONICAL_AD_LINKED_COMPLETED_SALE','CLICKS','DETERMINISTIC_OBJECT_LINKAGE',1,1,1,1,30,
                    :actor,'canonical test definition','fixture:canonical',:from,'ACTIVE',now())
                """).param("id",id).param("org",graph.organizationId()).param("actor",graph.executorUserId()).param("from",Timestamp.from(FROM)).update();
        return id;
    }
    void sale(UUID listing,UUID conversion,int quantity,String money) {
        seed.sql("""
                INSERT INTO ledger.ad_linked_sale_event(id,organization_id,provenance_id,ad_native_object_id,affected_set_id,platform_listing_variant_id,
                    conversion_definition_id,sale_stage,linkage_basis,linkage_evidence_ref,event_count,net_sales_amount,currency_code,
                    occurred_at,period_start,period_end,source_time,recorded_at)
                VALUES(gen_random_uuid(),:org,:provenance,:object,:affected,:listing,:definition,'CANONICAL_AD_LINKED_COMPLETED_SALE',
                    'DETERMINISTIC_OBJECT_LINKAGE','fixture:exact-linked-sale',:quantity,:money,'RUB',:from,:from,:to,:to,:read)
                """).param("org",graph.organizationId()).param("provenance",provenance).param("object",graph.objectId())
                .param("affected",graph.affectedSetId()).param("listing",listing).param("definition",conversion).param("quantity",quantity)
                .param("money",new BigDecimal(money)).param("from",Timestamp.from(FROM)).param("to",Timestamp.from(TO)).param("read",Timestamp.from(READ)).update();
    }
    @Test void sqlLineageAndUnequalVariantQuantitiesReconcileWithoutMeanOrFirstPolicy() {
        UUID second=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.product_variant(id,organization_id,product_id,sku_code,display_name,status,created_at,updated_at)
                SELECT :id,organization_id,product_id,:sku,'Second canonical unit','ACTIVE',now(),now() FROM core.product_variant WHERE id=:first
                """).param("id",second).param("sku",second.toString()).param("first",graph.productVariantId()).update();
        UUID listingA=listing(graph.productVariantId(),true), listingB=listing(second,true), definition=conversion();
        sale(listingA,definition,10,"5000");sale(listingB,definition,1,"1200");
        var linked=facts.linkedSales(graph.organizationId(),graph.objectId(),"CANONICAL_AD_LINKED_COMPLETED_SALE",FROM,TO,READ).orElseThrow();
        assertThat(linked.eventCount()).isEqualTo(11);assertThat(linked.distinctVariants()).isEqualTo(2);
        var canonical=com.mimococo.marketops.advertisingefficiency.AdEvidenceState.CANONICAL_CONFIRMED;
        java.util.function.Function<String,com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure> amount=
                value->com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure.available(new BigDecimal(value),canonical);
        var costs=java.util.Map.of(listingA,new AdvertisingEvidenceGatherer.VariantEconomics(amount.apply("100"),amount.apply("25"),amount.apply("10"),amount.apply("5"),"RUB"),
                listingB,new AdvertisingEvidenceGatherer.VariantEconomics(amount.apply("900"),amount.apply("50"),amount.apply("20"),amount.apply("10"),"RUB"));
        var cpaA=new com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository.AllowableCpaDefinition(UUID.randomUUID(),1,
                "CANONICAL_AD_LINKED_COMPLETED_SALE","RUB","OPERATIONAL_CONTRIBUTION",new BigDecimal("0.5"),"APPLIED_ONCE_ON_TOP");
        var cpaB=new com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository.AllowableCpaDefinition(UUID.randomUUID(),2,
                "CANONICAL_AD_LINKED_COMPLETED_SALE","RUB","OPERATIONAL_CONTRIBUTION",new BigDecimal("0.25"),"APPLIED_ONCE_ON_TOP");
        var result=AdvertisingAttributedEconomics.calculate(linked,costs,java.util.Map.of(graph.productVariantId(),cpaA,second,cpaB),amount.apply("100"),"RUB");
        assertThat(result.profit().absoluteProfit().value()).isEqualByComparingTo("3720");
        assertThat(result.allowableSpend().value()).isEqualByComparingTo("1855");
    }
    @Test void unmappedLineIsRetainedAsUnknownInsteadOfDisappearingFromCoverageDenominator() {
        UUID unit=listing(graph.productVariantId(),false), definition=conversion();sale(unit,definition,9,"900");
        var linked=facts.linkedSales(graph.organizationId(),graph.objectId(),"CANONICAL_AD_LINKED_COMPLETED_SALE",FROM,TO,READ).orElseThrow();
        assertThat(linked.eventCount()).isEqualTo(9);assertThat(linked.lines().getFirst().productVariantId()).isNull();
        assertThat(linked.netSalesAmount()).isNull();
    }
    UUID companySale(UUID listing,String stage,String order,int quantity,String money,Instant occurred) {
        UUID id=UUID.randomUUID(), source=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note)
                VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:actor,'synthetic financial cohort')
                """).param("id",source).param("org",graph.organizationId()).param("actor",graph.executorUserId()).param("at",Timestamp.from(READ)).update();
        seed.sql("""
                INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,sale_stage,retention_window_days,
                    source_fact_key,native_order_key,native_line_key,occurred_at,quantity,currency_code,gross_amount,net_amount)
                VALUES(:id,:org,:source,:listing,:store,:stage,:retention,:key,:order,'line',:at,:quantity,'RUB',:money,:money)
                """).param("id",id).param("org",graph.organizationId()).param("source",source).param("listing",listing).param("store",graph.storeId())
                .param("stage",stage).param("retention",stage.equals("RETAINED")?30:null).param("key",id.toString()).param("order",order)
                .param("at",Timestamp.from(occurred)).param("quantity",quantity).param("money",new BigDecimal(money)).update();
        return id;
    }
    @Test void retainedCohortCannotBeLabeledSettledWithoutActualFinancialFacts() {
        UUID unit=listing(graph.productVariantId(),true);
        companySale(unit,"RETAINED","order",10,"1000",FROM);
        assertThat(facts.settledCompanySales(graph.organizationId(),unit,FROM,TO,READ).complete()).isFalse();
        companySale(unit,"SETTLED","order",4,"300",READ);
        assertThat(facts.settledCompanySales(graph.organizationId(),unit,FROM,TO,READ).netAmount()).isNull();
        companySale(unit,"SETTLED","order",6,"450",READ);
        var financial=facts.settledCompanySales(graph.organizationId(),unit,FROM,TO,READ);
        assertThat(financial.complete()).isTrue();assertThat(financial.netAmount()).isEqualByComparingTo("750");
    }
    @Test void aFinancialLineCannotBeCountedTwiceThroughAmbiguousRetainedCohorts() {
        UUID unit=listing(graph.productVariantId(),true);
        companySale(unit,"RETAINED","ambiguous",10,"1000",FROM);
        companySale(unit,"RETAINED","ambiguous",10,"1000",MID);
        companySale(unit,"SETTLED","ambiguous",10,"700",READ);
        assertThat(facts.settledCompanySales(graph.organizationId(),unit,FROM,TO,READ).complete()).isFalse();
    }

    UUID cpaPolicy(int version,boolean productScope,String status) {
        UUID id=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_allowable_cpa_definition(id,organization_id,definition_version,scope_kind,product_variant_ref_id,sale_stage,
                    currency_code,contribution_basis,target_contribution_retention_ratio,return_loss_treatment,owner_user_id,reason,evidence_reference,effective_from,status,created_at)
                VALUES(:id,:org,:version,:scope,:product,'CANONICAL_AD_LINKED_RETAINED_SALE','RUB','SETTLED_CONTRIBUTION',0.5,'INCLUDED_IN_STAGE_CONTRIBUTION',
                    :owner,'synthetic exact policy scope','fixture://policy-resolution',:from,:status,now())
                """).param("id",id).param("org",graph.organizationId()).param("version",version).param("scope",productScope?"PRODUCT_VARIANT":"ORGANIZATION")
                .param("product",productScope?graph.productVariantId():null).param("owner",graph.executorUserId()).param("from",Timestamp.from(FROM)).param("status",status).update();
        return id;
    }
    @Test void conflictedSpecificPolicyNeverFallsBackToBroaderPolicy() {
        cpaPolicy(1,false,"ACTIVE");UUID specific=cpaPolicy(2,true,"ACTIVE");
        var chosen=policies.resolveAllowableCpa(graph.organizationId(),"OZON",graph.storeId(),graph.productVariantId(),"CANONICAL_AD_LINKED_RETAINED_SALE",READ);
        assertThat(chosen.orElseThrow().id()).isEqualTo(specific);
        UUID conflict=cpaPolicy(3,true,"RETIRED");
        assertThat(policies.resolveAllowableCpa(graph.organizationId(),"OZON",graph.storeId(),graph.productVariantId(),"CANONICAL_AD_LINKED_RETAINED_SALE",READ)).isEmpty();
        seed.sql("UPDATE core.ad_allowable_cpa_definition SET effective_to=:end WHERE id=:id").param("id",conflict).param("end",Timestamp.from(MID)).update();
        assertThat(policies.resolveAllowableCpa(graph.organizationId(),"OZON",graph.storeId(),graph.productVariantId(),"CANONICAL_AD_LINKED_RETAINED_SALE",READ).orElseThrow().id()).isEqualTo(specific);
    }


    @Test void aLaterMetricNeverErasesTheEconomicsVisibleAtAnEarlierDecisionInstant() {
        UUID unit=listing(graph.productVariantId(),true),definition=conversion();sale(unit,definition,10,"10000");
        metricVersion(unit,READ.minusSeconds(10),"100");
        metricVersion(unit,READ.plusSeconds(10),"900");
        var sales=facts.linkedSales(graph.organizationId(),graph.objectId(),"CANONICAL_AD_LINKED_COMPLETED_SALE",FROM,TO,READ);
        assertThat(gatherer.economicsForSales(sales,FROM,TO,READ).get(unit).unitCost().value()).isEqualByComparingTo("100");
        assertThat(gatherer.economicsForSales(sales,FROM,TO,READ.plusSeconds(20)).get(unit).unitCost().value()).isEqualByComparingTo("900");
    }
    void metricVersion(UUID unit,Instant at,String cost) {
        UUID run=UUID.randomUUID();
        seed.sql("""
            INSERT INTO mart.calculation_run(id,organization_id,trigger_kind,scope_kind,window_code,period_start,period_end,
                definition_set_digest,state,subject_count,value_count,started_at,completed_at,correlation_id)
            VALUES(:id,:org,'BACKFILL','ORGANIZATION','D30',:from,:to,repeat('a',64),'SUCCEEDED',1,4,:at,:at,'canonical-metric-history')
            """).param("id",run).param("org",graph.organizationId()).param("from",java.sql.Timestamp.from(TO.minusSeconds(30*86400L)))
                .param("to",java.sql.Timestamp.from(TO)).param("at",java.sql.Timestamp.from(at)).update();
        for(String code:java.util.List.of("UNIT_COST","PLATFORM_FEES_PER_UNIT","RETURN_LOSS_PER_UNIT","VARIABLE_TAX_PER_UNIT")) {
            UUID id=UUID.randomUUID();
            seed.sql("""
                INSERT INTO mart.metric_value(id,organization_id,calculation_run_id,metric_code,definition_version,subject_kind,subject_id,
                    window_code,period_start,period_end,value_state,numeric_value,currency_code,confidence_state,estimated,oldest_source_time,
                    freshness_seconds,input_digest,computed_at)
                VALUES(:id,:org,:run,:code,2,'PLATFORM_LISTING_VARIANT',:listing,'D30',:from,:to,'AVAILABLE',:amount,'RUB',
                    'CANONICAL_CONFIRMED',false,:at,0,:digest,:at)
                """).param("id",id).param("org",graph.organizationId()).param("run",run).param("code",code)
                .param("listing",unit).param("from",java.sql.Timestamp.from(TO.minusSeconds(30*86400L))).param("to",java.sql.Timestamp.from(TO))
                .param("amount",code.equals("UNIT_COST")?new java.math.BigDecimal(cost):java.math.BigDecimal.ZERO)
                .param("at",java.sql.Timestamp.from(at)).param("digest",com.mimococo.marketops.shared.Digest.ofText(id.toString())).update();
            seed.sql("INSERT INTO mart.metric_input_reference VALUES(gen_random_uuid(),:id,'FACT_PROVENANCE',:source)")
                .param("id",id).param("source",provenance).update();
        }
    }
}
