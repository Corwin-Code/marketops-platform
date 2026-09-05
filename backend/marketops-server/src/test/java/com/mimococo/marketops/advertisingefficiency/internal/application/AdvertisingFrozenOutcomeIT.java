package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.*;
import com.mimococo.marketops.AdvertisingR1Fixture;
import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.internal.domain.*;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;

/** Real PostgreSQL readers, frozen JSON, service evaluation and application-role release. */
@SpringBootTest @ActiveProfiles("ci")
class AdvertisingFrozenOutcomeIT {
    static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    static final UUID POLICY=UUID.fromString("4f30ccee-8886-5c20-9e40-0dbce9c14962");
    static final UUID PRODUCT=UUID.fromString("1484c926-777f-5205-8893-941965dbb38a");
    static final UUID LISTING=UUID.fromString("7d693f80-2ad3-570d-8f47-e589af7b5598");
    static final UUID STORE=UUID.fromString("f5eced9a-7d0a-5d65-8942-8d1efeabf41a");
    static final UUID RULE=UUID.fromString("75757575-7575-4757-8757-757575757575");
    @Autowired AdvertisingOutcomeService service;
    @Autowired AdvertisingOutcomeWorker worker;
    @Autowired AdvertisingOutcomeRepository outcomes;
    @Autowired com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake responsibilities;
    @Autowired DataSource application;
    DataSource migration, admin;
    JdbcClient seed;
    AdvertisingR1Fixture.Graph graph;
    UUID command;
    Instant landed,from,to,read;
    boolean matureGolden;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",TestDatabase::applicationRole);
        registry.add("spring.datasource.password",TestDatabase::applicationPassword);
        registry.add("spring.flyway.user",TestDatabase::migrationRole);
        registry.add("spring.flyway.password",TestDatabase::migrationPassword);
    }
    static AdMeasure amount(String value) { return AdMeasure.available(new BigDecimal(value),AdEvidenceState.CANONICAL_CONFIRMED); }
    String transform(String sql) {
        if(matureGolden) sql=matureGolden(sql);
        sql=sql.replace("720, 1440, 336, 0.80000","720, 1440, 24, 0.80000");
        String rule="""
            INSERT INTO core.ad_outcome_critical_unit_rule(id,organization_id,outcome_policy_id,product_variant_id,store_id,reason,evidence_reference)
            VALUES('75757575-7575-4757-8757-757575757575','8689c119-8fa0-50b7-8ba2-f9bf3039d336','4f30ccee-8886-5c20-9e40-0dbce9c14962',
              '1484c926-777f-5205-8893-941965dbb38a','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','exact fictional critical unit','fixture://critical-unit');
            """;
        sql=sql.replace("INSERT INTO ops.ad_outcome_baseline(",rule+"INSERT INTO ops.ad_outcome_baseline(");
        sql=sql.replace("'ruleId',NULL","'ruleId','75757575-7575-4757-8757-757575757575'::uuid");
        String unit="""
            INSERT INTO ops.ad_outcome_critical_unit VALUES('d0fa7daf-0724-5272-a691-bc0400c23766','1484c926-777f-5205-8893-941965dbb38a',
              '7d693f80-2ad3-570d-8f47-e589af7b5598','75757575-7575-4757-8757-757575757575');
            """;
        return sql.replace("-- Synthetic migration-role trusted Planner attestation",unit+"-- Synthetic migration-role trusted Planner attestation");
    }
    @BeforeEach void fixture(TestInfo testInfo) throws Exception {
        matureGolden=testInfo.getTestMethod().orElseThrow().getName().startsWith("mature");
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        seed=JdbcClient.create(migration);graph=AdvertisingR1Fixture.seedOutcome(migration,this::transform);
        try(var app=application.getConnection()) {
            app.setAutoCommit(false);
            AdvertisingR1Fixture.seal(app,graph,AdvertisingR1Fixture.proof(admin,app,graph,graph.id("ownerUser"),null,graph.id("recommendation"),graph.id("approval")));
            command=AdvertisingR1Fixture.createCommand(app,graph);app.commit();
        }
        landed=seed.sql("SELECT clock_timestamp()+interval '1 second'").query(Timestamp.class).single().toInstant();
        from=landed.plusSeconds(1800);to=from.plusSeconds(86400);read=to.plusSeconds(60);
        landedReadback();
        if(matureGolden) responsibilities.ensureResponsibility(graph.id("caseId"),graph.id("calculationRun"),"MARKETPLACE_OPERATOR");
    }
    void landedReadback() {
        UUID attempt=UUID.randomUUID(), raw=UUID.randomUUID(), content=UUID.randomUUID();
        seed.sql("""
                INSERT INTO ops.ad_bid_command_attempt(id,command_id,attempt_no,purpose,fence_token,lease_owner,started_at,completed_at,
                    outcome_class,correlation_id,request_digest,operation_snapshot)
                VALUES(:id,:command,1,'READBACK',1,'fictional-outcome',:at,:at,'ACCEPTED','fictional-outcome',repeat('a',64),'{}')
                """).param("id",attempt).param("command",command).param("at",Timestamp.from(landed)).update();
        seed.sql("INSERT INTO raw.raw_content(id,hash_algorithm,hash_value,byte_length,object_ref) VALUES(:id,'SHA256',:hash,2,'object-ref://fictional/outcome')")
                .param("id",content).param("hash",com.mimococo.marketops.shared.Digest.ofText(content.toString())).update();
        seed.sql("""
                INSERT INTO raw.ad_bid_response_observation(id,command_id,attempt_id,raw_content_id,request_digest,http_status,
                    response_headers,evidence_class,response_complete,observed_bid,observed_currency,observed_unit,observed_at,correlation_id)
                VALUES(:id,:command,:attempt,:content,repeat('a',64),200,'{}','PROTOCOL_FIXTURE',true,20,'RUB','CURRENCY_MAJOR',:at,'fictional-outcome')
                """).param("id",raw).param("command",command).param("attempt",attempt).param("content",content).param("at",Timestamp.from(landed)).update();
        seed.sql("""
                INSERT INTO ops.ad_bid_command_readback VALUES(gen_random_uuid(),:command,:attempt,:at,20,'RUB','CURRENCY_MAJOR','MATCHES_TARGET',:raw,'fictional-outcome')
                """).param("command",command).param("attempt",attempt).param("at",Timestamp.from(landed)).param("raw",raw).update();
        seed.sql("UPDATE ops.ad_bid_command SET state='READBACK_MATCHED',terminal_at=:at WHERE id=:id").param("id",command).param("at",Timestamp.from(landed)).update();
    }
    UUID observedSales(String amount,UUID supersedes) {
        UUID id=UUID.randomUUID(), provenance=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note)
                VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:actor,'fictional source accepted at logical test clock')
                """).param("id",provenance).param("org",graph.id("organization")).param("actor",graph.id("ownerUser")).param("at",Timestamp.from(read)).update();
        seed.sql("""
                INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,sale_stage,
                    source_fact_key,native_order_key,occurred_at,quantity,currency_code,gross_amount,net_amount,supersedes_fact_id,adjustment_kind)
                VALUES(:id,:org,:source,:listing,:store,'COMPLETED',:key,'fictional-completed-order',:at,10,'RUB',:amount,:amount,:supersedes,:adjustment)
                """).param("id",id).param("org",graph.id("organization")).param("source",provenance).param("listing",graph.id("listingVariant"))
                .param("store",graph.id("store")).param("key",id.toString()).param("at",Timestamp.from(from)).param("amount",new BigDecimal(amount))
                .param("supersedes",supersedes).param("adjustment",supersedes==null?null:"CORRECTION").update();
        return id;
    }
    void coverage() {
        seed.sql("""
                INSERT INTO ledger.return_quality_evidence_snapshot(id,organization_id,platform_listing_variant_id,report_window_start,report_window_end,
                    completed_coverage,retained_coverage,return_coverage,qc_coverage,completed_source_updated_at,retained_source_updated_at,
                    return_source_updated_at,qc_source_updated_at,evidence_reference,accepted_at,correlation_id)
                VALUES(gen_random_uuid(),:org,:listing,:from,:to,'COMPLETE','COMPLETE','COMPLETE_ZERO','COMPLETE',:read,:read,:read,:read,
                    'fixture://complete-window',:read,'fictional-outcome')
                """).param("org",graph.id("organization")).param("listing",graph.id("listingVariant")).param("from",Timestamp.from(from))
                .param("to",Timestamp.from(to)).param("read",Timestamp.from(read)).update();
        seed.sql("""
                INSERT INTO ledger.ad_object_fact(id,organization_id,provenance_id,ad_native_object_id,store_id,source_fact_key,period_start,period_end,
                    currency_code,spend_amount,clicks,report_window_complete,correction_window_open,source_time,recorded_at)
                VALUES(gen_random_uuid(),:org,:source,:object,:store,:key,:from,:to,'RUB',100,100,true,false,:read,:read)
                """).param("org",graph.id("organization")).param("source",graph.id("provenance")).param("object",graph.id("object"))
                .param("store",graph.id("store")).param("key",UUID.randomUUID().toString()).param("from",Timestamp.from(from))
                .param("to",Timestamp.from(to)).param("read",Timestamp.from(read)).update();
    }
    AdvertisingOutcomeRepository.DueRow due() {
        return outcomes.due(graph.id("organization"),graph.id("object"),read,100).stream().filter(value->command.equals(value.commandId())).findFirst().orElseThrow();
    }
    String reservationState() { return seed.sql("SELECT state FROM ops.ad_action_reservation WHERE id=:id").param("id",graph.id("reservation")).query(String.class).single(); }
    @Test void actualCompanyAndEveryCriticalUnitReleaseOnlyEarlySafetyWithoutProfitSuccess() {
        observedSales("1000",null);coverage();
        var result=service.evaluate(due(),read).orElseThrow();
        assertThat(result.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.UNCHANGED);
        assertThat(reservationState()).isEqualTo("RELEASED");
        assertThat(seed.sql("SELECT dual_axis_verdict FROM ops.ad_outcome_axes WHERE observation_id=:id").param("id",result.observationId()).query(String.class).single())
                .isEqualTo("UNRESOLVED");
        assertThat(seed.sql("SELECT guard_state FROM ops.ad_outcome_critical_guard WHERE observation_id=:id").param("id",result.observationId()).query(String.class).single()).isEqualTo("PASS");
    }
    @Test void targetedObjectScopeEvaluatesActualDueOutcomeAndReplayDoesNotAppendAnotherRevision() {
        observedSales("1000",null);coverage();
        assertThat(worker.runForObject(UUID.randomUUID(),graph.id("object"),read,10).evaluated()).isZero();
        assertThat(worker.runForObject(graph.id("organization"),UUID.randomUUID(),read,10).evaluated()).isZero();
        var batch=worker.runForObject(graph.id("organization"),graph.id("object"),read,10);
        assertThat(batch.evaluated()).isEqualTo(1);assertThat(batch.recorded()).isEqualTo(1);assertThat(batch.remaining()).isFalse();
        assertThat(reservationState()).isEqualTo("RELEASED");
        assertThat(worker.runForObject(graph.id("organization"),graph.id("object"),read,10).evaluated()).isZero();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_outcome_observation WHERE command_id=:id").param("id",command).query(Integer.class).single()).isEqualTo(1);
    }
    @Test void absentActualCompanySalesKeepsCriticalUnitUnknownAndReservationHeld() {
        var result=service.evaluate(due(),read).orElseThrow();
        assertThat(result.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.INDETERMINATE);
        assertThat(reservationState()).isNotEqualTo("RELEASED");
        assertThat(outcomes.tryReleaseReservation(result.observationId())).isFalse();
    }
    @Test void completeSourceCoverageCanProveZeroCompanyAndCriticalSalesRegression() {
        coverage();var result=service.evaluate(due(),read).orElseThrow();
        assertThat(result.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.REGRESSED);
        assertThat(result.reopenedContainmentId()).isNotNull();
        assertThat(reservationState()).isNotEqualTo("RELEASED");
    }
    @Test void lateCompletedSalesRegressionPreservesHistoryAndReacquiresQuarantine() {
        UUID first=observedSales("1000",null);coverage();
        var original=service.evaluate(due(),read).orElseThrow();
        read=read.plusSeconds(60);observedSales("500",first);
        var revision=service.evaluate(due(),read).orElseThrow();
        assertThat(revision.revisionNo()).isEqualTo(2);assertThat(revision.stage()).isEqualTo("OPERATIONAL_REVISED");
        assertThat(revision.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.REGRESSED);
        assertThat(revision.reopenedContainmentId()).isNotNull();
        assertThat(seed.sql("SELECT verdict FROM ops.ad_outcome_observation WHERE id=:id").param("id",original.observationId()).query(String.class).single()).isEqualTo("UNCHANGED");
        assertThat(reservationState()).isNotEqualTo("RELEASED");
    }
    @Test void ownerBlockingProviderIncidentCannotPassOtherwiseCompleteSalesSafety() {
        observedSales("1000",null);coverage();
        seed.sql("""
                INSERT INTO platform.ad_provider_incident VALUES(gen_random_uuid(),:org,:platform,:store,:source,true,:at,:until,'fixture://blocking-report-incident')
                """).param("org",graph.id("organization")).param("platform",graph.platform()).param("store",graph.id("store"))
                .param("source",graph.id("provenance")).param("at",Timestamp.from(read.minusSeconds(1))).param("until",Timestamp.from(read.plusSeconds(3600))).update();
        var result=service.evaluate(due(),read).orElseThrow();
        assertThat(result.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.INDETERMINATE);
        assertThat(reservationState()).isNotEqualTo("RELEASED");
    }
    @Test void aReviewedActionCannotSwapItsFrozenBaseline() {
        assertThatThrownBy(()->seed.sql("UPDATE ops.ad_bid_command SET outcome_baseline_id=gen_random_uuid() WHERE id=:id").param("id",command).update())
                .hasMessageContaining("reviewed action cannot replace");
    }
    /** Explicit synthetic pre-action numeric oracle, frozen before command creation.
     * The observed path uses actual SQL fact readers and canonical Metric rows. */
    private String matureGolden(String sql) {
        String money="jsonb_build_object('valueState','AVAILABLE','value',100,'evidenceState','CANONICAL_CONFIRMED')";
        String ratio="jsonb_build_object('valueState','AVAILABLE','value',1,'evidenceState','CANONICAL_CONFIRMED')";
        sql=sql.replace("'absoluteProfit',missing.value,'profitPerAdRub',missing.value", "'absoluteProfit',"+money+",'profitPerAdRub',"+ratio)
            .replace("jsonb_build_array('PRE_ACTION_PROFIT_UNRESOLVED')","jsonb_build_array()")
            .replace("CASE WHEN stage.code='OPERATIONAL' THEN available.value ELSE missing.value END","available.value")
            .replace("'traffic',NULL,'coverage',NULL,'confounderDigest',repeat('c',64)","'traffic',100,'coverage',1,'confounderDigest',encode(sha256(convert_to('7d693f80-2ad3-570d-8f47-e589af7b5598:price=Money[amount=100.0000, currencyCode=RUB]:NO:sellable=YES:stock=POSITIVE'||chr(31),'UTF8')),'hex')")
            .replace("'officialSpend',missing.value","'officialSpend',"+money);
        return sql;
    }
    @Test void matureRetainedAndFavorableActualSettlementPreserveBothStageHistories() {
        var retained=retainedGolden("80");
        assertThat(retained.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.UNCHANGED);
        advanceSettlement("1100",false);
        var settled=service.evaluate(dueStage("SETTLED"),read).orElseThrow();
        assertThat(settled.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.IMPROVED);
        assertThat(axis(settled.observationId(),"observed_absolute_profit")).isEqualByComparingTo("200");
        assertThat(axis(settled.observationId(),"observed_profit_per_rub")).isEqualByComparingTo("2");
        assertThat(axis(settled.observationId(),"company_observed_sales")).isEqualByComparingTo("1100");
        assertThat(outcomes.forCommand(command)).extracting(AdvertisingOutcomeRepository.ObservationRow::outcomeStage).contains("RETAINED","SETTLED");
        assertThat(seed.sql("SELECT verdict FROM ops.ad_outcome_observation WHERE id=:id").param("id",retained.observationId()).query(String.class).single()).isEqualTo("UNCHANGED");
        assertThat(outcomes.due(graph.id("organization"),graph.id("object"),read,100)).noneMatch(row->command.equals(row.commandId()) && row.nextStage().startsWith("SETTLED"));
    }
    @Test void matureActualSettlementContradictionDoesNotOverwritePriorRetainedSuccess() {
        var retained=retainedGolden("70");
        assertThat(retained.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.IMPROVED);
        advanceSettlement("800",false);
        var settled=service.evaluate(dueStage("SETTLED"),read).orElseThrow();
        assertThat(settled.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.REGRESSED);
        assertThat(settled.reopenedContainmentId()).isNotNull();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_outcome_review_responsibility WHERE action_id=:id AND required_role_code='FINANCE_ANALYST'")
                .param("id",command).query(Integer.class).single()).isEqualTo(1);
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_case_responsibility WHERE case_id=:id").param("id",graph.id("caseId")).query(Integer.class).single()).isEqualTo(1);
        assertThat(axis(settled.observationId(),"observed_absolute_profit")).isEqualByComparingTo("0");
        assertThat(axis(settled.observationId(),"company_observed_sales")).isEqualByComparingTo("800");
        assertThat(seed.sql("SELECT verdict FROM ops.ad_outcome_observation WHERE id=:id").param("id",retained.observationId()).query(String.class).single()).isEqualTo("IMPROVED");
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_outcome_observation WHERE command_id=:id AND outcome_stage='SETTLED'").param("id",command).query(Integer.class).single()).isEqualTo(1);
    }
    @Test void matureUnallocatedFinancialFactCannotBecomeSettledEfficiencySuccess() {
        retainedGolden("80");advanceSettlement("1100",true);
        var settled=service.evaluate(dueStage("SETTLED"),read).orElseThrow();
        assertThat(settled.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.INDETERMINATE);
        assertThat(settled.reopenedContainmentId()).isNull();
        assertThat(axis(settled.observationId(),"observed_absolute_profit")).isNull();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_outcome_review_responsibility WHERE action_id=:id").param("id",command).query(Integer.class).single()).isZero();
        assertThat(axis(settled.observationId(),"company_observed_sales")).isEqualByComparingTo("1100");
    }
    UUID retainedEvent;
    AdvertisingOutcomeService.Result retainedGolden(String unitCost) {
        to=from.plusSeconds(720*3600L);read=to.plusSeconds(60);
        companyStage("RETAINED","1000",from);
        retainedEvent=UUID.randomUUID();
        seed.sql("""
                INSERT INTO ledger.ad_linked_sale_event(id,organization_id,provenance_id,ad_native_object_id,affected_set_id,platform_listing_variant_id,
                    conversion_definition_id,sale_stage,linkage_basis,linkage_evidence_ref,event_count,net_sales_amount,currency_code,
                    occurred_at,period_start,period_end,source_time,recorded_at)
                VALUES(:id,:org,:source,:object,:set,:listing,:definition,'CANONICAL_AD_LINKED_RETAINED_SALE',
                    'DETERMINISTIC_OBJECT_LINKAGE','fixture://actual-financial-link',10,1000,'RUB',:from,:from,:to,:at,:at)
                """).param("id",retainedEvent).param("org",graph.id("organization")).param("source",graph.id("provenance"))
                .param("object",graph.id("object")).param("set",graph.id("affectedSet")).param("listing",graph.id("listingVariant"))
                .param("definition",graph.id("conversion")).param("from",Timestamp.from(from)).param("to",Timestamp.from(to)).param("at",Timestamp.from(read)).update();
        coverage();context();
        UUID metricRun=UUID.randomUUID();
        seed.sql("""
                INSERT INTO mart.calculation_run(id,organization_id,trigger_kind,scope_kind,window_code,period_start,period_end,
                    definition_set_digest,state,subject_count,value_count,started_at,completed_at,correlation_id)
                VALUES(:id,:org,'BACKFILL','ORGANIZATION','D30',:from,:to,repeat('a',64),'SUCCEEDED',1,4,:at,:at,'synthetic-canonical-metric-oracle')
                """).param("id",metricRun).param("org",graph.id("organization")).param("from",Timestamp.from(from))
                .param("to",Timestamp.from(to)).param("at",Timestamp.from(read)).update();
        for(String code:List.of("UNIT_COST","PLATFORM_FEES_PER_UNIT","RETURN_LOSS_PER_UNIT","VARIABLE_TAX_PER_UNIT")) {
            UUID id=UUID.randomUUID();
            seed.sql("""
                    INSERT INTO mart.metric_value(id,organization_id,calculation_run_id,metric_code,definition_version,subject_kind,subject_id,
                        window_code,period_start,period_end,value_state,numeric_value,currency_code,confidence_state,estimated,oldest_source_time,
                        freshness_seconds,input_digest,computed_at)
                    VALUES(:id,:org,:run,:code,2,'PLATFORM_LISTING_VARIANT',:listing,'D30',:from,:to,'AVAILABLE',:amount,'RUB',
                        'CANONICAL_CONFIRMED',false,:at,0,:digest,:at)
                    """).param("id",id).param("org",graph.id("organization")).param("run",metricRun).param("code",code)
                    .param("listing",graph.id("listingVariant")).param("from",Timestamp.from(from)).param("to",Timestamp.from(to))
                    .param("amount",code.equals("UNIT_COST")?new BigDecimal(unitCost):BigDecimal.ZERO).param("at",Timestamp.from(read))
                    .param("digest",com.mimococo.marketops.shared.Digest.ofText(id.toString())).update();
            seed.sql("INSERT INTO mart.metric_input_reference VALUES(gen_random_uuid(),:id,'FACT_PROVENANCE',:source)")
                .param("id",id).param("source",graph.id("provenance")).update();
        }
        return service.evaluate(dueStage("RETAINED"),read).orElseThrow();
    }
    void advanceSettlement(String money,boolean noAttribution) {
        Instant half=to;to=from.plusSeconds(1440*3600L);read=to.plusSeconds(60);
        UUID financial=companyStage("SETTLED",money,read.minusSeconds(1));
        if(!noAttribution) seed.sql("INSERT INTO ledger.ad_settlement_attribution VALUES(gen_random_uuid(),:org,:event,:financial,'fixture://exact-financial-allocation',:at)")
                .param("org",graph.id("organization")).param("event",retainedEvent).param("financial",financial).param("at",Timestamp.from(read)).update();
        seed.sql("""
                INSERT INTO ledger.ad_object_fact(id,organization_id,provenance_id,ad_native_object_id,store_id,source_fact_key,period_start,period_end,
                    currency_code,spend_amount,clicks,report_window_complete,correction_window_open,source_time,recorded_at,supersedes_fact_id,adjustment_kind)
                SELECT gen_random_uuid(),:org,:source,:object,:store,:key,:from,:to,'RUB',100,100,true,false,:at,:at,id,'CORRECTION'
                FROM ledger.ad_object_fact f WHERE f.ad_native_object_id=:object AND f.period_start=:from
                    AND NOT EXISTS(SELECT 1 FROM ledger.ad_object_fact newer WHERE newer.supersedes_fact_id=f.id)
                """).param("org",graph.id("organization")).param("source",graph.id("provenance")).param("object",graph.id("object"))
                .param("store",graph.id("store")).param("key",UUID.randomUUID().toString()).param("from",Timestamp.from(from)).param("to",Timestamp.from(to)).param("at",Timestamp.from(read)).update();
        seed.sql("""
                INSERT INTO ledger.return_quality_evidence_snapshot(id,organization_id,platform_listing_variant_id,report_window_start,report_window_end,
                    completed_coverage,retained_coverage,return_coverage,qc_coverage,completed_source_updated_at,retained_source_updated_at,
                    return_source_updated_at,qc_source_updated_at,evidence_reference,accepted_at,correlation_id)
                VALUES(gen_random_uuid(),:org,:listing,:from,:to,'COMPLETE','COMPLETE','COMPLETE_ZERO','COMPLETE',:at,:at,:at,:at,
                    'fixture://sixty-day-financial-window',:at,'actual-financial-outcome')
                """).param("org",graph.id("organization")).param("listing",graph.id("listingVariant")).param("from",Timestamp.from(from))
                .param("to",Timestamp.from(to)).param("at",Timestamp.from(read)).update();
        context();refreshCostProof();
    }
    void refreshCostProof() {
        UUID run=UUID.randomUUID();
        seed.sql("""
            INSERT INTO mart.calculation_run(id,organization_id,trigger_kind,scope_kind,window_code,period_start,period_end,
                definition_set_digest,state,subject_count,value_count,started_at,completed_at,correlation_id)
            VALUES(:run,:org,'BACKFILL','ORGANIZATION','D30',:from,:to,repeat('a',64),'SUCCEEDED',1,4,:at,:at,'synthetic-refreshed-effective-cost-proof')
            """).param("run",run).param("org",graph.id("organization")).param("from",Timestamp.from(from))
            .param("to",Timestamp.from(from.plusSeconds(720*3600L))).param("at",Timestamp.from(read)).update();
        for(String code:List.of("UNIT_COST","PLATFORM_FEES_PER_UNIT","RETURN_LOSS_PER_UNIT","VARIABLE_TAX_PER_UNIT")) {
            UUID metric=UUID.randomUUID();
            seed.sql("""
                INSERT INTO mart.metric_value(id,organization_id,calculation_run_id,metric_code,definition_version,subject_kind,subject_id,
                    window_code,period_start,period_end,value_state,numeric_value,currency_code,confidence_state,estimated,oldest_source_time,
                    freshness_seconds,input_digest,computed_at)
                SELECT :id,organization_id,:run,metric_code,definition_version,subject_kind,subject_id,window_code,period_start,period_end,
                    value_state,numeric_value,currency_code,confidence_state,estimated,oldest_source_time,0,:digest,:at
                FROM mart.metric_value WHERE organization_id=:org AND subject_id=:listing AND metric_code=:code
                ORDER BY computed_at DESC LIMIT 1
                """).param("id",metric).param("run",run).param("digest",com.mimococo.marketops.shared.Digest.ofText(metric.toString()))
                .param("at",Timestamp.from(read)).param("org",graph.id("organization")).param("listing",graph.id("listingVariant")).param("code",code).update();
            seed.sql("INSERT INTO mart.metric_input_reference VALUES(gen_random_uuid(),:id,'FACT_PROVENANCE',:source)")
                .param("id",metric).param("source",graph.id("provenance")).update();
        }
    }
    UUID companyStage(String stage,String amount,Instant occurred) {
        UUID id=UUID.randomUUID(),provenance=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note)
                VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:actor,'synthetic actual mature company cohort')
                """).param("id",provenance).param("org",graph.id("organization")).param("actor",graph.id("ownerUser")).param("at",Timestamp.from(read)).update();
        seed.sql("""
                INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,sale_stage,retention_window_days,
                    source_fact_key,native_order_key,native_line_key,occurred_at,quantity,currency_code,gross_amount,net_amount)
                VALUES(:id,:org,:source,:listing,:store,:stage,:retention,:key,'actual-mature-order','actual-mature-line',:at,10,'RUB',:money,:money)
                """).param("id",id).param("org",graph.id("organization")).param("source",provenance).param("listing",graph.id("listingVariant"))
                .param("store",graph.id("store")).param("stage",stage).param("retention",stage.equals("RETAINED")?30:null)
                .param("key",id.toString()).param("at",Timestamp.from(occurred)).param("money",new BigDecimal(amount)).update();
        return id;
    }
    void context() {
        UUID source=UUID.randomUUID();
        seed.sql("INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note) VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:actor,'synthetic refreshed source report for the exact historical safety window')")
            .param("id",source).param("org",graph.id("organization")).param("at",Timestamp.from(read)).param("actor",graph.id("ownerUser")).update();
        var at=Timestamp.from(from.minusSeconds(1));
        seed.sql("INSERT INTO core.listing_price_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,currency_code,selling_price,promotion_active) VALUES(gen_random_uuid(),:org,:source,:listing,:key,:at,'RUB',100,'NO')")
            .param("org",graph.id("organization")).param("source",source).param("listing",graph.id("listingVariant")).param("key",UUID.randomUUID().toString()).param("at",at).update();
        seed.sql("INSERT INTO core.listing_health_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,sellable) VALUES(gen_random_uuid(),:org,:source,:listing,:key,:at,'YES')")
            .param("org",graph.id("organization")).param("source",source).param("listing",graph.id("listingVariant")).param("key",UUID.randomUUID().toString()).param("at",at).update();
        seed.sql("INSERT INTO core.listing_stock_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,fulfillment_mode_code,available_quantity) VALUES(gen_random_uuid(),:org,:source,:listing,:key,:at,'SELLER_FULFILLED',100)")
            .param("org",graph.id("organization")).param("source",source).param("listing",graph.id("listingVariant")).param("key",UUID.randomUUID().toString()).param("at",at).update();
    }
    AdvertisingOutcomeRepository.DueRow dueStage(String stage) {
        return outcomes.due(graph.id("organization"),graph.id("object"),read,100).stream().filter(row->command.equals(row.commandId()) && row.nextStage().equals(stage)).findFirst().orElseThrow();
    }
    BigDecimal axis(UUID observation,String column) {
        if(!List.of("observed_absolute_profit","observed_profit_per_rub","company_observed_sales").contains(column)) throw new IllegalArgumentException();
        return seed.sql("SELECT "+column+" FROM ops.ad_outcome_axes WHERE observation_id=:id").param("id",observation).query(BigDecimal.class).optional().orElse(null);
    }

}
