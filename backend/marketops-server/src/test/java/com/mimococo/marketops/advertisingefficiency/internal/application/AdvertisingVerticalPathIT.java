package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.*;
import com.mimococo.marketops.AdvertisingR1Fixture;
import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.analyticsdecision.*;
import com.mimococo.marketops.analyticsdecision.internal.application.AnalyticsCalculationService;
import com.mimococo.marketops.identityaccess.*;
import com.mimococo.marketops.marketplaceintegration.port.*;
import com.mimococo.marketops.operationsworkflow.*;
import com.mimococo.marketops.operationsworkflow.internal.application.*;
import com.mimococo.marketops.advertisingefficiency.internal.domain.OutcomeEvaluation;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingOutcomeRepository;
import com.mimococo.marketops.shared.internal.config.ProductionWriteProperties;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Canonical facts, actual Metric engine and advertising calculator, actual three-person
 * authorization, actual command worker with a socket-free fixture port, then actual
 * mature Outcome readers. No Case, candidate, recommendation, Metric or frozen baseline
 * is seeded. All topology/Owner facts are synthetic and production_write_enabled stays false.
 */
@SpringBootTest @ActiveProfiles("ci") @Import(AdvertisingVerticalPathIT.Runtime.class)
class AdvertisingVerticalPathIT {
    static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    static final String ISSUER_PASSWORD=UUID.randomUUID().toString();
    @Autowired JdbcClient jdbc;
    @Autowired ApplicationContext context;
    @Autowired AnalyticsCalculationService analytics;
    @Autowired MetricQuery metrics;
    @Autowired AdvertisingCaseRefreshService refresh;
    @Autowired AdvertisingHumanDecisionService humans;
    @Autowired RecommendationService recommendations;
    @Autowired ApprovalService approvals;
    @Autowired ExecutionService execution;
    @Autowired GuardrailService guardrails;
    @Autowired AdvertisingOutcomeService outcomeService;
    @Autowired AdvertisingOutcomeRepository outcomes;
    @Autowired ProductionWriteProperties productionWrites;
    @Autowired MutableClock clock;
    @Autowired FixturePort provider;
    JdbcClient seed;
    AdvertisingR1Fixture.Graph graph;
    Instant start;
    UUID caseId,recommendation,command,reservation;
    AuthenticatedActor maker,ops,owner;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DATABASE::getJdbcUrl);
        r.add("spring.datasource.username",TestDatabase::applicationRole);
        r.add("spring.datasource.password",TestDatabase::applicationPassword);
        r.add("spring.flyway.user",TestDatabase::migrationRole);
        r.add("spring.flyway.password",TestDatabase::migrationPassword);
        r.add("marketops.identity.invocation.jdbc-url",DATABASE::getJdbcUrl);
        r.add("marketops.identity.invocation.username",()->"marketops_identity_issuer");
        r.add("marketops.identity.invocation.password",()->ISSUER_PASSWORD);
    }
    @BeforeEach void topologyAndAuthorityOnly() throws Exception {
        start=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).minusSeconds(2);clock.at=start;provider.calls.clear();
        var migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        seed=JdbcClient.create(migration);graph=AdvertisingR1Fixture.seedUnapproved(migration,this::authorityOnly);
        try(var admin=DATABASE.createConnection("");var s=admin.createStatement()) { s.execute("ALTER ROLE marketops_identity_issuer LOGIN PASSWORD '"+ISSUER_PASSWORD+"'"); }
        role("executorUser","MARKETPLACE_OPERATOR");
        for(String user:List.of("executorUser","verifierUser","ownerUser"))
            for(String action:List.of("ADVERTISING_VIEW","ADVERTISING_TASK_ACT")) scope(user,action);
        for(String user:List.of("verifierUser","ownerUser")) scope(user,"ADVERTISING_DECISION_EVIDENCE_VIEW");
        maker=actor("executorUser",BusinessRoleCode.MARKETPLACE_OPERATOR);
        ops=actor("verifierUser",BusinessRoleCode.OPS_LEAD);owner=actor("ownerUser",BusinessRoleCode.OWNER);
        seed.sql("UPDATE core.ad_human_slo_profile SET staffed_coverage_enabled=true,staffed_coverage_timezone='Etc/UTC',staffed_coverage_start_minute=0,staffed_coverage_end_minute=1439 WHERE id=:id").param("id",graph.id("humanSlo")).update();
        sql("""
          INSERT INTO core.ad_reporting_calendar(id,organization_id,policy_version,scope_kind,reporting_timezone,daily_cut_minute,
            operating_days,weekly_cut_weekday,weekly_cut_minute,late_revision_horizon_hours,owner_user_id,reason,evidence_reference,effective_from,status,created_at)
          VALUES(gen_random_uuid(),:org,1,'ORGANIZATION','Etc/UTC',0,ARRAY[1,2,3,4,5,6,7]::smallint[],1,0,24,:owner,
            'Synthetic full calendar','fixture://vertical/calendar',CAST(:at AS timestamptz)-interval '70 days','ACTIVE',:at)
          """).update();
        for(String purpose:List.of("PROTECTION_RECOMMENDATION","TASK_ACTIVATION","QUEUE_OBSERVATION")) sql("""
          INSERT INTO core.ad_freshness_profile(id,organization_id,profile_version,evidence_kind,decision_purpose,scope_kind,
            source_max_age_minutes,accepted_fact_max_age_minutes,expected_publication_lag_minutes,correction_window_minutes,
            requires_window_complete,requires_correction_window_closed,minimum_coverage_ratio,minimum_confidence_state,provider_incident_blocks,
            owner_user_id,reason,evidence_reference,effective_from,effective_to,status,created_at)
          SELECT gen_random_uuid(),organization_id,1,evidence_kind,:purpose,'ORGANIZATION',60,60,0,0,true,false,1,
            'CANONICAL_CONFIRMED',true,:owner,'Synthetic complete exact purpose','fixture://vertical/purpose',CAST(:at AS timestamptz)-interval '1 day',
            CAST(:at AS timestamptz)+interval '1 day','ACTIVE',:at FROM core.ad_freshness_profile
          WHERE organization_id=:org AND decision_purpose='PROTECTION_BID_WRITE'
          """).param("purpose",purpose).update();
        sql("INSERT INTO core.ad_outcome_critical_unit_rule(id,organization_id,outcome_policy_id,product_variant_id,store_id,reason,evidence_reference) VALUES(gen_random_uuid(),:org,:outcome,:variant,:store,'Synthetic exact unit','fixture://vertical/critical')").update();
        assertEmptyDerivedState();
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    /** Strip derived state before executing the shared topology/typed-authority template. */
    String authorityOnly(String source) {
        StringBuilder selected=new StringBuilder();
        for(String part:source.replaceAll("(?m)^\\s*--.*$", "").split(";")) {
            String s=part.strip();
            if(s.isEmpty() || s.matches("(?s).*\\b(?:INSERT INTO|UPDATE|FROM) mart\\..*") || s.startsWith("INSERT INTO ledger.")) continue;
            if((s.contains("INSERT INTO ops.") || s.startsWith("UPDATE ops.") || s.startsWith("SELECT ops."))
                    && !s.contains("ops.ad_decision_policy_bundle") && !s.contains("ops.ad_gate_authority")) continue;
            selected.append(s).append(';');
        }
        return selected.toString().replace("now()","TIMESTAMPTZ '"+start.minusSeconds(60)+"'")
            .replace("ARRAY[]::uuid[], 'COMPLETE'","ARRAY['7d693f80-2ad3-570d-8f47-e589af7b5598']::uuid[], 'COMPLETE'")
            .replace("now()-interval '1 day','ACTIVE'", "now()-interval '70 days','ACTIVE'")
            .replace("720, 1440, 336, 0.80000","720, 1440, 24, 0.80000")
            .replace("0.80000, 0.80000, 30, 0.20000, 30", "1.00000, 1.00000, 10, 0.20000, 30");
    }
    void assertEmptyDerivedState() {
        for(String table:List.of("mart.ad_case","mart.metric_value","ops.ad_bid_candidate","ops.recommendation","ops.ad_outcome_baseline"))
            assertThat(seed.sql("SELECT count(*) FROM "+table+" WHERE organization_id=:id").param("id",graph.id("organization")).query(Integer.class).single()).as(table+" must start empty").isZero();
    }

    @Test void missingEconomicsCreatesResponsibilityAndNeverAFabricatedCandidate() {
        acceptPreActionFacts(false);
        var result=refresh.refresh(graph.id("organization"),graph.id("object"),start,"TARGETED",null,"vertical-negative").orElseThrow();
        assertThat(result.calculation().cases()).isNotEmpty();
        assertThat(result.proposed()).isEmpty();
        assertThat(count("ops.ad_case_responsibility")).isPositive();
        assertThat(count("ops.ad_bid_candidate")).isZero();
        assertThat(count("ops.ad_outcome_baseline")).isZero();
        assertThat(count("ops.ad_bid_command")).isZero();
        assertThat(provider.calls).isEmpty();
        assertThat(productionWrites.getEnabled()).isFalse();
    }

    @Test void concurrentCanonicalRefreshCreatesOneCaseAndOneResponsibilityTask() throws Exception {
        acceptPreActionFacts(true);
        var begin=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var calls=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int index=0;index<4;index++) calls.add(workers.submit(() -> {
                begin.await();
                return refresh.refresh(graph.id("organization"),graph.id("object"),start,
                        "TARGETED",null,"vertical-concurrent").orElseThrow();
            }));
            begin.countDown();
            for(var call:calls) assertThat(call.get(30,java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
        }
        assertThat(count("mart.ad_case")).isEqualTo(1);
        assertThat(sql("SELECT cause_code FROM mart.ad_case WHERE organization_id=:org").query(String.class).single())
                .isEqualTo("PROVEN_ADVERTISING_LOSS");
        assertThat(count("ops.ad_case_responsibility")).isEqualTo(1);
        assertThat(sql("SELECT count(*) FROM ops.work_task task JOIN ops.ad_case_responsibility responsibility ON responsibility.task_id=task.id WHERE responsibility.organization_id=:org")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(count("ops.ad_bid_candidate")).isEqualTo(1);
        assertThat(count("ops.ad_bid_command")).isZero();
        assertThat(provider.calls).isEmpty();
    }

    @Test void concurrentDifferentAsOfWithoutQualificationPoliciesKeepsOneTaskPerActualCause() throws Exception {
        acceptPreActionFacts(false);
        sql("UPDATE core.ad_optimization_qualification_policy SET status='CANCELLED' WHERE organization_id=:org").update();
        var begin=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var calls=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int index=0;index<4;index++) {
                Instant asOf=start.plusNanos(index*1000L);
                calls.add(workers.submit(() -> {
                    begin.await();
                    return refresh.refresh(graph.id("organization"),graph.id("object"),asOf,
                            "TARGETED",null,"vertical-policy-missing-concurrent").orElseThrow();
                }));
            }
            begin.countDown();
            for(var call:calls) assertThat(call.get(30,java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
        }
        assertThat(count("mart.ad_qualification_period")).isZero();
        // At start the complete 30-day report is visible, so policy is the
        // unresolved cause. Advancing the left window edge excludes that
        // indivisible report and independently raises an official-fact gap.
        assertThat(count("mart.ad_case")).isEqualTo(2);
        assertThat(sql("SELECT cause_code FROM mart.ad_case WHERE organization_id=:org").query(String.class).list())
                .containsExactlyInAnyOrder("DECISION_POLICY_UNRESOLVED","OFFICIAL_AD_FACT_DEFECT");
        assertThat(count("ops.ad_case_responsibility")).isEqualTo(2);
        assertThat(sql("SELECT count(*) FROM ops.work_task task JOIN ops.ad_case_responsibility responsibility ON responsibility.task_id=task.id WHERE responsibility.organization_id=:org")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(sql("SELECT count(*) FROM ops.ad_case_responsibility responsibility JOIN mart.ad_case kase ON kase.id=responsibility.case_id WHERE responsibility.organization_id=:org GROUP BY kase.cause_code")
                .query(Integer.class).list()).containsExactlyInAnyOrder(1,1);
        assertThat(count("ops.ad_bid_candidate")).isZero();
        assertThat(count("ops.ad_bid_command")).isZero();
        assertThat(provider.calls).isEmpty();
    }

    @Test void acceptedFactsDriveRealHumanCommandFixtureReadbackAndMatureOutcomeHistory() {
        acceptPreActionFacts(true);
        var canonical=metrics.currentValues(SubjectKind.PLATFORM_LISTING_VARIANT,graph.id("listingVariant"),MetricWindow.D30);
        for(MetricCode code:List.of(MetricCode.UNIT_COST,MetricCode.PLATFORM_FEES_PER_UNIT,MetricCode.RETURN_LOSS_PER_UNIT,MetricCode.VARIABLE_TAX_PER_UNIT))
        {
            assertThat(canonical.get(code).valueState().name()).as(code.name()).isEqualTo("AVAILABLE");
            assertThat(canonical.get(code).confidenceState().name()).as(code.name()+" must carry canonical fresh evidence").isEqualTo("CANONICAL_CONFIRMED");
        }
        assertThat(canonical.get(MetricCode.PLATFORM_FEES_PER_UNIT).numericValue()).isEqualByComparingTo("0");
        var calculated=refresh.refresh(graph.id("organization"),graph.id("object"),start,"TARGETED",null,"vertical-positive").orElseThrow();
        assertThat(calculated.calculation().cases().stream().map(c -> c.identity().cause().name()).toList())
            .as("Actual canonical cases %s; purpose failures %s", calculated.calculation().cases(),
                calculated.calculation().purposeEvidence().stream().filter(e -> !e.eligible()).toList())
            .contains("PROVEN_ADVERTISING_LOSS");
        var protection=calculated.calculation().cases().stream().filter(c->c.identity().cause().name().equals("PROVEN_ADVERTISING_LOSS")).findFirst().orElseThrow();
        assertThat(protection.contributionProfit().value()).isEqualByComparingTo("-1000");
        assertThat(protection.maxCpc().ceiling().amount()).isEqualByComparingTo("25");
        assertThat(calculated.proposed()).hasSize(1);
        recommendation=calculated.proposed().getFirst();
        UUID candidate=UUID.fromString(recommendations.require(recommendation).proposedParameters().get("candidateId"));
        caseId=seed.sql("SELECT case_id FROM ops.ad_bid_candidate WHERE id=:id").param("id",candidate).query(UUID.class).single();
        assertThat(count("ops.ad_case_responsibility")).isPositive();
        assertThat(count("ops.ad_outcome_baseline")).isZero();
        fictionalDispatchControls(candidate);
        var selected=humans.select(maker,caseId,candidate,0,"Choose actual generated loss protection");
        assertThat(selected.state()).isEqualTo(RecommendationState.VALIDATED);
        assertThat(count("ops.ad_outcome_baseline")).isEqualTo(1);
        var endorsed=humans.endorse(ops,recommendation,selected.version(),"Independent operational review");
        humans.preparePreview(owner,recommendation);
        var preview=guardrails.previewAdBidChange(recommendations.require(recommendation),GuardrailPurpose.IMPACT_PREVIEW);
        assertThat(preview.verdict().passed()).as(preview.toString()).isTrue();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner,null,List.of()));
        var approved=approvals.approve(owner,recommendation,"Owner approves exact synthetic target",endorsed.version());
        assertThat(approved.state()).isEqualTo(RecommendationState.APPROVED);
        command=execution.createCommand(owner,recommendation,recommendations.require(recommendation).version()).commandId();
        reservation=seed.sql("SELECT reservation_id FROM ops.ad_bid_command WHERE id=:id").param("id",command).query(UUID.class).single();
        assertThat(seed.sql("SELECT b.state FROM ops.ad_outcome_baseline b JOIN ops.ad_bid_command c ON c.outcome_baseline_id=b.id WHERE c.id=:id").param("id",command).query(String.class).single()).isEqualTo("COMPLETE");
        assertThat(jdbc.sql("SELECT unnest(ops.evaluate_ad_bid_write_gate(:id))").param("id",command).query(String.class).list()).isEmpty();
        Object worker=context.getBean("adBidCommandWorker");
        ReflectionTestUtils.invokeMethod(worker,"runOnce",Instant.now(),10);
        assertThat(seed.sql("SELECT state FROM ops.ad_bid_command WHERE id=:id").param("id",command).query(String.class).single()).isEqualTo("READBACK_MATCHED");
        assertThat(provider.calls).containsExactly(AdBidWriteRequest.Operation.APPLY,AdBidWriteRequest.Operation.READBACK);
        ReflectionTestUtils.invokeMethod(worker,"runOnce",Instant.now(),10);
        assertThat(provider.calls).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM raw.ad_bid_response_observation WHERE command_id=:id AND evidence_class='PROTOCOL_FIXTURE'").param("id",command).query(Integer.class).single()).isEqualTo(2);
        observeMaturityAndRevision();
        assertThat(productionWrites.getEnabled()).isFalse();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_command WHERE platform_code IN ('OZON','WILDBERRIES')").query(Integer.class).single()).isZero();
    }

    void acceptPreActionFacts(boolean economicsAvailable) {
        Instant occurred=start.minusSeconds(7200),from=start.minus(Duration.ofDays(30));
        company("COMPLETED","10000",10,occurred,"before",null);
        company("RETAINED","10000",10,occurred,"before",null);
        UUID financial=company("SETTLED","10000",10,occurred,"before",null);
        UUID linked=linked("10000",10,from,start,occurred);
        sql("INSERT INTO ledger.ad_settlement_attribution VALUES(gen_random_uuid(),:org,:event,:financial,'fixture://vertical/settlement',:at)")
            .param("event",linked).param("financial",financial).update();
        report(from,start,"6000",100);
        coverage(start.minus(Duration.ofDays(61)),start.plusSeconds(300),economicsAvailable);context(start.minusSeconds(1));
        if(economicsAvailable) {
            economicsAuthority();
            economicFacts(occurred);
        }
        var run=analytics.run(graph.id("store"),MetricWindow.D30,"SCHEDULED",null);
        assertThat(run.subjectCount()).isEqualTo(1);
    }
    JdbcClient.StatementSpec sql(String text) {
        return seed.sql(text).param("org",graph.id("organization")).param("store",graph.id("store"))
          .param("account",graph.id("account")).param("listing",graph.id("listingVariant")).param("variant",graph.id("productVariant"))
          .param("source",graph.id("provenance")).param("object",graph.id("object")).param("owner",graph.id("ownerUser"))
          .param("outcome",graph.id("outcome")).param("set",graph.id("affectedSet")).param("conversion",graph.id("conversion"))
          .param("at",Timestamp.from(clock.instant()));
    }
    int count(String table) { return seed.sql("SELECT count(*) FROM "+table+" WHERE organization_id=:id").param("id",graph.id("organization")).query(Integer.class).single(); }
    UUID company(String stage,String amount,int quantity,Instant occurred,String order,UUID supersedes) {
        UUID id=UUID.randomUUID(),source=UUID.randomUUID();
        sql("INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note) VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:owner,'Synthetic accepted vertical company fact')").param("id",source).update();
        sql("""
          INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,sale_stage,retention_window_days,
            source_fact_key,native_order_key,native_line_key,occurred_at,quantity,currency_code,gross_amount,net_amount,supersedes_fact_id,adjustment_kind)
          VALUES(:id,:org,:provenance,:listing,:store,:stage,:retention,:key,:order,'line',:occurred,:quantity,'RUB',:money,:money,:supersedes,:adjustment)
          """).param("id",id).param("provenance",source).param("stage",stage).param("retention",stage.equals("RETAINED")?30:null)
            .param("key",id.toString()).param("order",order).param("occurred",Timestamp.from(occurred)).param("quantity",quantity)
            .param("money",new BigDecimal(amount)).param("supersedes",supersedes).param("adjustment",supersedes==null?null:"CORRECTION").update();
        return id;
    }
    UUID linked(String amount,int quantity,Instant from,Instant to,Instant occurred) {
        UUID id=UUID.randomUUID();
        sql("""
          INSERT INTO ledger.ad_linked_sale_event(id,organization_id,provenance_id,ad_native_object_id,affected_set_id,platform_listing_variant_id,
            conversion_definition_id,sale_stage,linkage_basis,linkage_evidence_ref,event_count,net_sales_amount,currency_code,
            occurred_at,period_start,period_end,source_time,recorded_at)
          VALUES(:id,:org,:source,:object,:set,:listing,:conversion,'CANONICAL_AD_LINKED_RETAINED_SALE','DETERMINISTIC_OBJECT_LINKAGE',
            'fixture://vertical/exact-link',:quantity,:money,'RUB',:occurred,:from,:to,:at,:at)
          """).param("id",id).param("quantity",quantity).param("money",new BigDecimal(amount)).param("occurred",Timestamp.from(occurred))
            .param("from",Timestamp.from(from)).param("to",Timestamp.from(to)).update();return id;
    }
    void report(Instant from,Instant to,String amount,int clicks) {
        sql("""
          INSERT INTO ledger.ad_object_fact(id,organization_id,provenance_id,ad_native_object_id,store_id,source_fact_key,period_start,period_end,
            currency_code,spend_amount,clicks,report_window_complete,correction_window_open,source_time,recorded_at)
          VALUES(gen_random_uuid(),:org,:source,:object,:store,:key,:from,:to,'RUB',:money,:clicks,true,false,:at,:at)
          """).param("key",UUID.randomUUID().toString()).param("from",Timestamp.from(from)).param("to",Timestamp.from(to))
            .param("money",new BigDecimal(amount)).param("clicks",clicks).update();
    }
    void coverage(Instant from,Instant to) {
        coverage(from,to,false);
    }
    void coverage(Instant from,Instant to,boolean returnsObserved) {
        sql("""
          INSERT INTO ledger.return_quality_evidence_snapshot(id,organization_id,platform_listing_variant_id,report_window_start,report_window_end,
            completed_coverage,retained_coverage,return_coverage,qc_coverage,completed_source_updated_at,retained_source_updated_at,
            return_source_updated_at,qc_source_updated_at,evidence_reference,accepted_at,correlation_id)
          VALUES(gen_random_uuid(),:org,:listing,:from,:to,'COMPLETE','COMPLETE',:returns,'COMPLETE',:at,:at,:at,:at,
            'fixture://vertical/whole-window',:at,'vertical')
          """).param("from",Timestamp.from(from)).param("to",Timestamp.from(to)).param("returns",returnsObserved?"COMPLETE_OBSERVED":"COMPLETE_ZERO").update();
    }
    void context(Instant at) {
        sql("INSERT INTO core.listing_price_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,currency_code,selling_price,promotion_active) VALUES(gen_random_uuid(),:org,:source,:listing,:key,:observed,'RUB',1000,'NO')").param("key",UUID.randomUUID().toString()).param("observed",Timestamp.from(at)).update();
        sql("INSERT INTO core.listing_health_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,sellable) VALUES(gen_random_uuid(),:org,:source,:listing,:key,:observed,'YES')").param("key",UUID.randomUUID().toString()).param("observed",Timestamp.from(at)).update();
        sql("INSERT INTO core.listing_stock_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,fulfillment_mode_code,available_quantity) VALUES(gen_random_uuid(),:org,:source,:listing,:key,:observed,'SELLER_FULFILLED',100)").param("key",UUID.randomUUID().toString()).param("observed",Timestamp.from(at)).update();
    }
    void economicsAuthority() {
        UUID profile=UUID.randomUUID();
        sql("INSERT INTO core.cost_version(id,organization_id,product_variant_id,cost_kind,currency_code,unit_cost,provenance_id,effective_from,status,created_at,updated_at) VALUES(gen_random_uuid(),:org,:variant,'PURCHASE','RUB',500,:source,CAST(:at AS timestamptz)-interval '6 hours','ACTIVE',:at,:at)").update();
        sql("INSERT INTO core.store_fulfillment_declaration(id,organization_id,store_id,fulfillment_mode_code,effective_from,status,created_at,updated_at) VALUES(gen_random_uuid(),:org,:store,'SELLER_FULFILLED',CAST(:at AS timestamptz)-interval '70 days','ACTIVE',:at,:at)").update();
        sql("""
          INSERT INTO core.economics_projection_profile(id,profile_version,organization_id,platform_code,marketplace_account_id,store_id,
            fulfillment_mode_code,currency_code,effective_from,effective_to,verification_state,verified_at,verification_expires_at,evidence_reference,
            minimum_supported_price,maximum_supported_price,status,created_at)
          VALUES(:id,1,:org,:platform,:account,:store,'SELLER_FULFILLED','RUB',CAST(:at AS timestamptz)-interval '70 days',CAST(:at AS timestamptz)+interval '100 days',
            'ENGINEERING_VERIFIED',CAST(:at AS timestamptz)-interval '1 hour',CAST(:at AS timestamptz)+interval '100 days','fixture://vertical/fee-authority',1,100000,'ACTIVE',:at)
          """).param("id",profile).param("platform",graph.platform()).update();
        sql("""
          INSERT INTO core.economics_projection_family(profile_id,family_code,applicability_state,evidence_reference)
          SELECT :profile,family,'REQUIRED','fixture://vertical/fee/'||family FROM unnest(ARRAY['COMMISSION','FULFILLMENT_DELIVERY',
            'STORAGE','PROMOTION','OTHER_VARIABLE','RETURN_LOSS','ADVERTISING','VARIABLE_TAX']) family
          """).param("profile",profile).update();
        sql("""
          INSERT INTO core.economics_projection_component(id,profile_id,component_code,family_code,component_kind,fixed_amount,evidence_reference)
          SELECT gen_random_uuid(),:profile,family,family,'FIXED',0,'fixture://vertical/zero-component/'||family
          FROM unnest(ARRAY['COMMISSION','FULFILLMENT_DELIVERY','STORAGE','PROMOTION','OTHER_VARIABLE','RETURN_LOSS','ADVERTISING','VARIABLE_TAX']) family
          """).param("profile",profile).update();
    }
    void economicFacts(Instant occurred) {
        UUID source=UUID.randomUUID();
        sql("INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note) VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:owner,'Synthetic current publication of complete settled fee and return facts')").param("id",source).update();
        for(String category:List.of("COMMISSION","FULFILLMENT","STORAGE","PROMOTION","OTHER_VARIABLE","VARIABLE_TAX"))
            sql("""
              INSERT INTO ledger.finance_fee_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,source_fact_key,
                fee_category,settlement_state,occurred_at,currency_code,amount)
              VALUES(gen_random_uuid(),:org,:source,:listing,:store,:key,:category,'SETTLED',:occurred,'RUB',0)
              """).param("source",source).param("key",UUID.randomUUID().toString()).param("category",category).param("occurred",Timestamp.from(occurred)).update();
        sql("""
          INSERT INTO ledger.return_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,source_fact_key,native_return_key,
            return_kind,reason_category,occurred_at,quantity,currency_code,refund_amount,loss_amount)
          VALUES(gen_random_uuid(),:org,:source,:listing,:store,:key,:key,'CANCELLATION','CUSTOMER_CHANGED_MIND',:occurred,1,'RUB',0,0)
          """).param("source",source).param("key",UUID.randomUUID().toString()).param("occurred",Timestamp.from(occurred)).update();
    }
    void fictionalDispatchControls(UUID candidateId) {
        // The synthetic Owner envelope names the target the real calculator
        // generated, before any human selection or approval is recorded.
        sql("""
          UPDATE ops.ad_gate_authority gate SET exact_object_values=jsonb_build_object(:object::text,
            jsonb_build_object('currentBid',candidate.current_bid_amount,'targetBid',candidate.provider_normalized_amount,
              'currencyCode',candidate.currency_code,'bidUnitCode',candidate.bid_unit_code))
          FROM ops.ad_bid_candidate candidate WHERE candidate.id=:candidate AND gate.organization_id=:org
          """).param("candidate",candidateId).update();
        UUID capability=seed.sql("SELECT id FROM platform.platform_capability WHERE platform_code=:platform AND capability_code='ad-bid-change'").param("platform",graph.platform()).query(UUID.class).single();
        sql("""
          INSERT INTO platform.capability_subject_status(id,organization_id,platform_code,capability_id,store_id,
            availability,last_verified_at,evidence_ref,verified_source_title,created_at,updated_at)
          VALUES(gen_random_uuid(),:org,:platform,:cap,:store,'AVAILABLE',:at,'fixture://vertical/protocol','Synthetic protocol',:at,:at)
          """).param("platform",graph.platform()).param("cap",capability).update();
        sql("""
          INSERT INTO platform.feature_flag(id,flag_code,flag_kind,scope_kind,state,status,reason,created_at,updated_at)
          SELECT gen_random_uuid(),'ad-bid-change-write','WRITE_CAPABILITY','GLOBAL','ENABLED','ACTIVE','Isolated fixture port only',:at,:at
          WHERE NOT EXISTS(SELECT 1 FROM platform.feature_flag WHERE flag_code='ad-bid-change-write' AND scope_kind='GLOBAL')
          """).update();
        seed.sql("UPDATE platform.feature_flag SET state='ENABLED' WHERE flag_code='ad-bid-change-write' AND scope_kind='GLOBAL'").update();
        sql("""
          INSERT INTO platform.feature_flag(id,flag_code,flag_kind,scope_kind,capability_id,state,status,reason,created_at,updated_at)
          VALUES(gen_random_uuid(),'ad-bid-change-write','WRITE_CAPABILITY','CAPABILITY',:cap,'ENABLED','ACTIVE','Synthetic capability only',:at,:at)
          """).param("cap",capability).update();
        sql("""
          INSERT INTO ops.pilot_allowlist_entry(id,organization_id,action_kind,platform_code,store_id,ad_native_object_id,
            valid_from,valid_until,status,granted_by_user_id,reason,created_at,updated_at)
          VALUES(gen_random_uuid(),:org,'AD_BID_CHANGE',:platform,:store,:object,CAST(:at AS timestamptz)-interval '1 hour',CAST(:at AS timestamptz)+interval '1 hour',
            'ACTIVE',:owner,'Synthetic exact object only',:at,:at)
          """).param("platform",graph.platform()).update();
        for(String operation:List.of("APPLY","READBACK")) {
            UUID endpoint=UUID.randomUUID();boolean writing=operation.equals("APPLY");
            sql("""
              INSERT INTO platform.platform_endpoint(id,platform_code,endpoint_code,api_version,http_method,path_template,query_template,
                operation_function,capability_id,read_write_class,pagination_model,idempotency_support,verification_state,last_verified_at,
                evidence_ref,verified_source_title,owner_label,contract_test_status,status,created_at,updated_at)
              VALUES(:id,:platform,:code,'v1',:method,:path,:query,:function,:cap,:kind,'NONE','YES','VERIFIED',:at,
                'fixture://vertical/protocol','Synthetic port protocol','test','PASSING','ACTIVE',:at,:at)
              """).param("id",endpoint).param("platform",graph.platform()).param("code","vertical-"+operation.toLowerCase())
                .param("method",writing?"POST":"GET").param("path","/fixture/ad-bid/"+operation.toLowerCase())
                .param("query",writing?null:"object={nativeObjectKey}").param("function","AD_BID_"+operation)
                .param("cap",capability).param("kind",writing?"WRITE":"READ").update();
            sql("""
              INSERT INTO platform.capability_operation(id,capability_id,platform_code,operation,endpoint_id,request_template,
                accepted_pointer,accepted_value,observed_price_pointer,observed_currency_pointer,ad_observed_unit_pointer,version_token_header,
                verification_state,last_verified_at,evidence_ref,verified_source_title,owner_label,status,created_at,updated_at)
              VALUES(gen_random_uuid(),:cap,:platform,:operation,:endpoint,:template,'/accepted','true'::jsonb,'/bid','/currencyCode','/bidUnitCode',:versionHeader,
                'VERIFIED',:at,'fixture://vertical/protocol','Synthetic port protocol','test','ACTIVE',:at,:at)
              """).param("cap",capability).param("platform",graph.platform()).param("operation",operation).param("endpoint",endpoint)
                .param("versionHeader",writing?null:"etag")
                .param("template",writing?"{\"bid\":\"{targetBid}\",\"currencyCode\":\"{currencyCode}\",\"bidUnitCode\":\"{bidUnitCode}\",\"object\":\"{nativeObjectKey}\"}":"").update();
        }
        // All actual gates see current authority; no gate result or attempt is fabricated.

    }
    void observeMaturityAndRevision() {
        Instant landed=seed.sql("SELECT observed_at FROM ops.ad_bid_command_readback WHERE command_id=:id").param("id",command).query(Timestamp.class).single().toInstant();
        Instant from=landed.plusSeconds(1800),earlyTo=from.plusSeconds(86400),retainedTo=from.plus(Duration.ofDays(30)),settledTo=from.plus(Duration.ofDays(60));
        clock.at=earlyTo.plusSeconds(60);
        UUID completed=company("COMPLETED","10000",10,from.plusSeconds(7200),"after",null);
        coverage(from,earlyTo);report(from,earlyTo,"50",10);context(clock.instant().minusSeconds(1));
        var early=outcomeService.evaluate(due("OPERATIONAL"),clock.instant()).orElseThrow();
        assertThat(early.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.UNCHANGED);
        assertThat(seed.sql("SELECT state FROM ops.ad_action_reservation WHERE id=:id").param("id",reservation).query(String.class).single()).isEqualTo("RELEASED");
        clock.at=retainedTo.plusSeconds(60);
        // A current official publication restates the same Completed cohort;
        // old source timestamps cannot become fresh merely because we recalculate.
        company("COMPLETED","10000",10,from.plusSeconds(7200),"after",completed);
        company("RETAINED","10000",10,from.plus(Duration.ofDays(1)),"after",null);
        UUID retainedEvent=linked("10000",10,from,retainedTo,from.plus(Duration.ofDays(1)));
        coverage(from,retainedTo,true);report(earlyTo,retainedTo,"950",90);context(clock.instant().minusSeconds(1));
        UUID costSource=UUID.randomUUID();
        sql("INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note) VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:owner,'Synthetic current publication of unchanged unit cost')").param("id",costSource).update();
        sql("UPDATE core.cost_version SET effective_to=CAST(:at AS timestamptz)-interval '6 hours',updated_at=:at WHERE organization_id=:org AND effective_to IS NULL").update();
        sql("INSERT INTO core.cost_version(id,organization_id,product_variant_id,cost_kind,currency_code,unit_cost,provenance_id,effective_from,status,created_at,updated_at) VALUES(gen_random_uuid(),:org,:variant,'PURCHASE','RUB',500,:source,CAST(:at AS timestamptz)-interval '6 hours','ACTIVE',:at,:at)").param("source",costSource).update();
        economicFacts(from.plus(Duration.ofDays(1)));
        analytics.run(graph.id("store"),MetricWindow.D30,"SCHEDULED",null);
        for(MetricCode code:List.of(MetricCode.UNIT_COST,MetricCode.PLATFORM_FEES_PER_UNIT,MetricCode.RETURN_LOSS_PER_UNIT,MetricCode.VARIABLE_TAX_PER_UNIT))
            assertThat(metrics.currentValues(SubjectKind.PLATFORM_LISTING_VARIANT,graph.id("listingVariant"),MetricWindow.D30).get(code).confidenceState().name())
                .as("Mature canonical "+code).isEqualTo("CANONICAL_CONFIRMED");
        var retained=outcomeService.evaluate(due("RETAINED"),clock.instant()).orElseThrow();
        assertThat(retained.evaluation().verdict()).as(retained.toString()).isEqualTo(OutcomeEvaluation.Verdict.IMPROVED);
        clock.at=settledTo.plusSeconds(60);
        UUID settled=company("SETTLED","10000",10,settledTo.minusSeconds(1),"after",null);
        sql("INSERT INTO ledger.ad_settlement_attribution VALUES(gen_random_uuid(),:org,:event,:financial,'fixture://vertical/actual-financial',:at)")
          .param("event",retainedEvent).param("financial",settled).update();
        coverage(from,settledTo,true);report(retainedTo,settledTo,"0",0);context(clock.instant().minusSeconds(1));
        var financial=outcomeService.evaluate(due("SETTLED"),clock.instant()).orElseThrow();
        assertThat(financial.evaluation().verdict()).as(financial.toString()).isEqualTo(OutcomeEvaluation.Verdict.IMPROVED);
        clock.at=clock.instant().plusSeconds(60);
        UUID corrected=company("SETTLED","8000",10,settledTo.minusSeconds(1),"after",settled);
        sql("INSERT INTO ledger.ad_settlement_attribution VALUES(gen_random_uuid(),:org,:event,:financial,'fixture://vertical/late-correction',:at)")
          .param("event",retainedEvent).param("financial",corrected).update();
        var revised=outcomeService.evaluate(due("SETTLED_REVISED"),clock.instant()).orElseThrow();
        assertThat(revised.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.REGRESSED);
        assertThat(revised.reopenedContainmentId()).isNotNull();
        assertThat(seed.sql("SELECT verdict FROM ops.ad_outcome_observation WHERE id=:id").param("id",financial.observationId()).query(String.class).single()).isEqualTo("IMPROVED");
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_outcome_review_responsibility WHERE action_id=:id AND required_role_code='FINANCE_ANALYST'").param("id",command).query(Integer.class).single()).isEqualTo(1);
        assertThat(outcomes.due(clock.instant(),100)).noneMatch(row->command.equals(row.commandId()) && row.nextStage().equals("SETTLED_REVISED"));
    }
    AdvertisingOutcomeRepository.DueRow due(String stage) {
        return outcomes.due(clock.instant(),100).stream().filter(row->command.equals(row.commandId()) && row.nextStage().equals(stage)).findFirst().orElseThrow();
    }
    AuthenticatedActor actor(String user,BusinessRoleCode role) {
        String issuer=seed.sql("SELECT issuer FROM iam.identity_provider WHERE id=:id").param("id",graph.id("provider")).query(String.class).single();
        return new AuthenticatedActor(graph.id(user),graph.id("organization"),graph.id("provider"),issuer,"Synthetic role",
            "a".repeat(64),"b".repeat(64),start,start.plusSeconds(1800),true,Set.of(role));
    }
    void role(String user,String role) {
        sql("INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,status,effective_from,reason,created_at,updated_at) VALUES(gen_random_uuid(),:org,:user,:role,'ACTIVE',CAST(:at AS timestamptz)-interval '1 hour','Synthetic role',:at,:at) ON CONFLICT DO NOTHING").param("user",graph.id(user)).param("role",role).update();
    }
    void scope(String user,String action) {
        sql("INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,status,effective_from,reason,created_at,updated_at) VALUES(gen_random_uuid(),:org,:user,:action,:org,'ACTIVE',CAST(:at AS timestamptz)-interval '1 hour','Synthetic scope',:at,:at) ON CONFLICT DO NOTHING").param("user",graph.id(user)).param("action",action).update();
    }
    static final class MutableClock extends Clock {
        Instant at=Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at; }
    }
    static final class FixturePort implements AdBidWritePort {
        final List<AdBidWriteRequest.Operation> calls=new ArrayList<>();
        @Override public AdBidWriteResult perform(AdBidWriteRequest request) {
            calls.add(request.operation());
            byte[] body=(request.operation()==AdBidWriteRequest.Operation.READBACK?
                "{\"bid\":"+request.targetBid().amount()+",\"currencyCode\":\"RUB\",\"bidUnitCode\":\"CURRENCY_MAJOR\"}":
                "{\"accepted\":true}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return new AdBidWriteResult(AdBidWriteResult.Outcome.ACCEPTED,"200",null,null,null,null,body,Instant.now(),null,
                new AdBidWriteResult.Response(200,Map.of("etag","fixture-v1"),request.digest(),"PROTOCOL_FIXTURE",true));
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Runtime {
        @Bean @Primary MutableClock fixedLogicalClock() { return new MutableClock(); }
        @Bean @Primary FixturePort fixtureAdBidPort() { return new FixturePort(); }
        @Bean @Primary ObjectStoragePort fixtureObjects() { return new InMemoryObjectStoragePort(); }
    }
}
