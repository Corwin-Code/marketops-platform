package com.mimococo.marketops;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.mimococo.marketops.marketplaceintegration.RawCustody;

/** Synthetic browser input only: no packet, approval, command or claimed outcome. */
public final class AdvertisingManualBrowserSeed {
    private final JdbcClient seed;
    private final AdvertisingR1Fixture.Graph graph;
    private final RawCustody custody;
    private AdvertisingManualBrowserSeed(ApplicationContext context,JdbcClient migration,AdvertisingR1Fixture.Graph graph) {
        this.seed=migration;this.graph=graph;this.custody=context.getBean(RawCustody.class);
    }
    public static UUID seed(ApplicationContext context,JdbcClient migration,AdvertisingR1Fixture.Graph graph) {
        return new AdvertisingManualBrowserSeed(context,migration,graph).prepare();
    }
    private UUID prepare() {
        seedOutcomeAuthority();
        Instant now=Instant.now();
        companyWindow(now.minusSeconds(61*86400L),now.plusSeconds(300),now.minusSeconds(1),"0");
        UUID provenance=rawConfigurationProvenance();
        configuration("30",provenance);
        UUID policy=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_manual_policy(id,organization_id,store_id,semantic_profile_id,policy_version,cause_code,
                  outcome_policy_id,action_kind,candidate_basis,currency_code,verification_mode,configuration_max_age_seconds,
                  packet_lease_seconds,effective_from,effective_to,approved_by_user_id,approved_at,evidence_reference)
                VALUES(:id,:org,:store,:profile,1,'PROVEN_ADVERTISING_LOSS',:outcome,'AD_BID_CHANGE','MAX_CPC_BOUNDED','RUB',
                  'INDEPENDENT_OR_OFFICIAL',3600,1800,now()-interval '1 minute',now()+interval '1 hour',:owner,now(),
                  'fixture://isolated-browser/synthetic-owner-manual-policy')
                """).param("id",policy).param("org",graph.id("organization")).param("store",graph.id("store"))
                .param("profile",graph.id("profile")).param("outcome",graph.id("outcome")).param("owner",graph.id("ownerUser")).update();
        return policy;
    }
    private void seedOutcomeAuthority() {
        seed.sql("""
                UPDATE core.ad_outcome_policy SET completed_sales_guard_hours=24,critical_unit_definition_complete=true,
                  material_profit_delta=10,material_profit_per_rub_delta=0.1,sales_preservation_tolerance_ratio=0.05,
                  non_worsening_profit_band=0,non_worsening_per_rub_band=0,minimum_ad_spend_denominator=1,
                  comparison_scale=4,comparison_rounding_mode='HALF_UP',material_boundary_inclusive=true,negative_profit_terminal='KEEP_PROTECTION_OPEN'
                WHERE id=:id
                """).param("id",graph.id("outcome")).update();
        seed.sql("""
                INSERT INTO core.ad_outcome_critical_unit_rule(id,organization_id,outcome_policy_id,product_variant_id,store_id,reason,evidence_reference)
                VALUES(gen_random_uuid(),:org,:policy,:product,:store,'synthetic Owner required unit','fixture://manual-critical-unit')
                """).param("org",graph.id("organization")).param("policy",graph.id("outcome")).param("product",graph.id("productVariant")).param("store",graph.id("store")).update();
        int profiles=seed.sql("""
                SELECT count(*) FROM core.ad_freshness_profile WHERE organization_id=:org AND profile_version=1
                  AND (evidence_kind,decision_purpose) IN (
                    ('COMPANY_COMPLETED_SALE','EARLY_COMPLETED_SALES_OUTCOME'),
                    ('COMPANY_RETAINED_SALE','FINAL_RETAINED_SALES_OUTCOME'),
                    ('SETTLEMENT','SETTLED_FINANCIAL_OUTCOME'))
                  AND status='ACTIVE' AND source_max_age_minutes=1440 AND accepted_fact_max_age_minutes=1440
                  AND requires_window_complete AND requires_correction_window_closed AND minimum_coverage_ratio=1
                """).param("org",graph.id("organization")).query(Integer.class).single();
        if(profiles!=3) throw new IllegalStateException("Explicit synthetic Outcome purpose profiles are incomplete");
    }
    private void companyWindow(java.time.Instant from,java.time.Instant to,java.time.Instant accepted,String amount) {
        UUID source=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note)
                VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:owner,'synthetic canonical company report')
                """).param("id",source).param("org",graph.id("organization")).param("at",java.sql.Timestamp.from(accepted)).param("owner",graph.id("ownerUser")).update();
        seed.sql("""
                INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,sale_stage,source_fact_key,
                    native_order_key,occurred_at,quantity,currency_code,gross_amount,net_amount)
                VALUES(gen_random_uuid(),:org,:source,:listing,:store,'COMPLETED',:key,:key,:occurred,10,'RUB',:amount,:amount)
                """).param("org",graph.id("organization")).param("source",source).param("listing",graph.id("listingVariant")).param("store",graph.id("store"))
                .param("key",UUID.randomUUID().toString()).param("occurred",java.sql.Timestamp.from(accepted.isAfter(to)?from:accepted.minusSeconds(60)))
                .param("amount",new BigDecimal(amount)).update();
        seed.sql("""
                INSERT INTO ledger.return_quality_evidence_snapshot(id,organization_id,platform_listing_variant_id,report_window_start,report_window_end,
                  completed_coverage,retained_coverage,return_coverage,qc_coverage,completed_source_updated_at,retained_source_updated_at,
                  return_source_updated_at,qc_source_updated_at,evidence_reference,accepted_at,correlation_id)
                VALUES(gen_random_uuid(),:org,:listing,:from,:to,'COMPLETE','COMPLETE','COMPLETE_ZERO','COMPLETE',:at,:at,:at,:at,
                  'fixture://actual-manual-company-window',:at,'manual-outcome')
                """).param("org",graph.id("organization")).param("listing",graph.id("listingVariant")).param("from",java.sql.Timestamp.from(from))
                .param("to",java.sql.Timestamp.from(to)).param("at",java.sql.Timestamp.from(accepted)).update();
    }

    private UUID configuration(String amount,UUID provenance) {
        UUID id=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_object_configuration_observation(id,organization_id,ad_native_object_id,provenance_id,
                    semantic_profile_id,lineage_generation,observed_bid_amount,bid_currency_code,bid_unit_code,observed_status,
                    native_status_raw,observed_bidding_mode,evidence_grade,observed_at,source_time,created_at)
                VALUES(:id,:org,:object,:provenance,:profile,1,:amount,'RUB',:unit,'RUNNING','native-running','MANUAL_BID',
                    'OFFICIAL_API_READBACK',clock_timestamp(),clock_timestamp(),clock_timestamp())
                """).param("id",id).param("org",graph.id("organization")).param("object",graph.id("object"))
                .param("provenance",provenance).param("profile",graph.id("profile")).param("amount",new BigDecimal(amount))
                .param("unit",seed.sql("SELECT bid_unit_code FROM platform.ad_semantic_profile WHERE id=:id")
                    .param("id",graph.id("profile")).query(String.class).single()).update();
        return id;
    }
    private UUID rawConfigurationProvenance() {
        UUID service=UUID.randomUUID(),endpoint=UUID.randomUUID(),job=UUID.randomUUID(),run=UUID.randomUUID();
        UUID unit=UUID.randomUUID(),observation=UUID.randomUUID(),provenance=UUID.randomUUID();
        String bidField=graph.platform().equals("WILDBERRIES")?"placementBidMinor":"keywordBidMajor";
        byte[] bytes=("{\"fixtureNativeObject\":\""+graph.id("object")+"\",\""+bidField+"\":30}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID content=custody.store("manual-fixture",bytes).contentId();
        if (!java.util.Arrays.equals(custody.readById(content).orElseThrow(),bytes)) throw new IllegalStateException("Synthetic raw custody round-trip failed");
        seed.sql("INSERT INTO iam.service_account(id,organization_id,code,display_name,purpose,owner_label,status,expires_at,created_at,updated_at) VALUES(:id,:org,:code,'Stored synthetic configuration','INGESTION','fixture','ACTIVE',now()+interval '1 day',now(),now())")
                .param("id",service).param("org",graph.id("organization")).param("code","manual-"+service).update();
        seed.sql("INSERT INTO platform.platform_endpoint(id,platform_code,endpoint_code,api_version,read_write_class,pagination_model,idempotency_support,verification_state,owner_label,contract_test_status,status,created_at,updated_at) VALUES(:id,:platform,'manual.config','v1','READ','NONE','UNKNOWN','UNVERIFIED','fixture','NOT_IMPLEMENTED','ACTIVE',now(),now())")
                .param("id",endpoint).param("platform",graph.platform()).update();
        seed.sql("INSERT INTO platform.ingestion_job(id,organization_id,marketplace_account_id,platform_code,service_account_id,endpoint_id,job_code,display_name,status,created_at,updated_at) VALUES(:id,:org,:account,:platform,:service,:endpoint,:code,'Synthetic stored configuration','PAUSED',now(),now())")
                .param("id",job).param("org",graph.id("organization")).param("account",graph.id("account"))
                .param("platform",graph.platform()).param("service",service).param("endpoint",endpoint).param("code","manual-"+job).update();
        seed.sql("INSERT INTO ops.ingestion_run(id,job_id,state,fence_token,attempt_no,last_call_seq,created_at,updated_at) VALUES(:id,:job,'SUCCEEDED',1,1,1,now(),now())").param("id",run).param("job",job).update();
        seed.sql("INSERT INTO raw.raw_logical_unit(id,job_id,marketplace_account_id,unit_kind,source_unit_key,source_time) VALUES(:id,:job,:account,'AD_CONFIGURATION',:key,now())")
                .param("id",unit).param("job",job).param("account",graph.id("account")).param("key",unit.toString()).update();
        seed.sql("INSERT INTO raw.raw_acquisition_observation(id,run_id,logical_unit_id,content_id,call_seq,native_status,outcome_class,pagination_outcome) VALUES(:id,:run,:unit,:content,1,'fixture-success','SUCCESS_BYTES','END')")
                .param("id",observation).param("run",run).param("unit",unit).param("content",content).update();
        seed.sql("INSERT INTO core.fact_provenance(id,organization_id,source_kind,raw_observation_id,source_time,ingestion_time,evidence_note) VALUES(:id,:org,'MARKETPLACE_RAW',:observation,now(),now(),'Isolated synthetic raw configuration oracle')")
                .param("id",provenance).param("org",graph.id("organization")).param("observation",observation).update();
        return provenance;
    }
}
