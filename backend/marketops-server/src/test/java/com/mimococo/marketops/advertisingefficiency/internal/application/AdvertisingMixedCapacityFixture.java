package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.AdvertisingR1Fixture;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Trusted synthetic historical INPUT only. No trigger, constraint or application gate is disabled. */
final class AdvertisingMixedCapacityFixture {
    static final int HISTORIES=40;
    private final JdbcClient seed;
    private final ObjectMapper mapper;
    private final DataSource migration;
    final AdvertisingR1Fixture.Graph shared;
    final UUID templateCommand;
    final List<History> histories=new java.util.ArrayList<>();
    record History(AdvertisingR1Fixture.Graph graph,UUID command,Instant prepared,Instant landed,Instant from,Instant to,UUID retainedEvent) { }

    AdvertisingMixedCapacityFixture(JdbcClient seed,ObjectMapper mapper,DataSource migration,
            AdvertisingR1Fixture.Graph shared,UUID templateCommand) {
        this.seed=seed;this.mapper=mapper;this.migration=migration;this.shared=shared;this.templateCommand=templateCommand;
    }

    static String currentTemplate(String sql) {
        return golden(sql).replace("now() - interval '1 day'","now() - interval '150 days'")
                .replace("now()-interval '1 day'","now()-interval '150 days'")
                .replace("now()+interval '1 day'","now()+interval '150 days'");
    }

    static String golden(String sql) {
        String money="jsonb_build_object('valueState','AVAILABLE','value',100,'evidenceState','CANONICAL_CONFIRMED')";
        String ratio="jsonb_build_object('valueState','AVAILABLE','value',1,'evidenceState','CANONICAL_CONFIRMED')";
        return sql.replace("720, 1440, 336, 0.80000","720, 1440, 24, 0.80000")
                .replace("ARRAY[]::uuid[], 'COMPLETE'","ARRAY['7d693f80-2ad3-570d-8f47-e589af7b5598']::uuid[], 'COMPLETE'")
                .replace("'absoluteProfit',missing.value,'profitPerAdRub',missing.value", "'absoluteProfit',"+money+",'profitPerAdRub',"+ratio)
                .replace("jsonb_build_array('PRE_ACTION_PROFIT_UNRESOLVED')","jsonb_build_array()")
                .replace("CASE WHEN stage.code='OPERATIONAL' THEN available.value ELSE missing.value END","available.value")
                .replace("'traffic',NULL,'coverage',NULL,'confounderDigest',repeat('c',64)","'traffic',100,'coverage',1,'confounderDigest',encode(sha256(convert_to('7d693f80-2ad3-570d-8f47-e589af7b5598:price=Money[amount=100.0000, currencyCode=RUB]:NO:sellable=YES:stock=POSITIVE'||chr(31),'UTF8')),'hex')")
                .replace("'officialSpend',missing.value","'officialSpend',"+money);
    }

    History historicalObject(int ordinal,Instant historicalPrepared) throws Exception {
        var graph=AdvertisingR1Fixture.forkOutcomeObject(migration,shared,
                sql->golden(sql).replace("now()","TIMESTAMPTZ '"+historicalPrepared+"'"));
        Instant landed=historicalPrepared.plusSeconds(60),from=landed.plusSeconds(1800),to=from.plusSeconds(720*3600L);
        UUID command=UUID.randomUUID();
        copy("ops.ad_action_authorization","recommendation_id",shared.id("recommendation"),graph,Map.of(
                "id",UUID.randomUUID(),"final_approved_at",historicalPrepared.plusSeconds(1),"expires_at",historicalPrepared.plusSeconds(900)));
        copy("ops.ad_action_reservation","id",shared.id("reservation"),graph,Map.of(
                "reserved_at",historicalPrepared.plusSeconds(2),"correlation_id","fixture-mixed-history-"+ordinal));
        var fields=new LinkedHashMap<String,Object>();
        fields.put("id",command);fields.put("idempotency_key","fixture-mixed-"+command);
        fields.put("created_at",historicalPrepared.plusSeconds(3));fields.put("updated_at",historicalPrepared.plusSeconds(3));
        fields.put("approval_expires_at",historicalPrepared.plusSeconds(900));
        copy("ops.ad_bid_command","id",templateCommand,graph,fields);
        History row=new History(graph,command,historicalPrepared,landed,from,to,UUID.randomUUID());histories.add(row);return row;
    }

    /** Clone a constrained synthetic input with exact new FK identities; never used for measured outputs. */
    private void copy(String table,String key,UUID original,AdvertisingR1Fixture.Graph graph,Map<String,Object> fields) throws Exception {
        if(!List.of("ops.ad_action_authorization","ops.ad_action_reservation","ops.ad_bid_command").contains(table)) throw new IllegalArgumentException();
        String json=seed.sql("SELECT to_jsonb(row)::text FROM "+table+" row WHERE "+key+"=:id").param("id",original).query(String.class).single();
        for(var entry:shared.ids().entrySet()) json=json.replace(entry.getValue().toString(),graph.id(entry.getKey()).toString());
        var parsed=(tools.jackson.databind.node.ObjectNode)mapper.readTree(json);
        for(var field:fields.entrySet()) parsed.set(field.getKey(),mapper.valueToTree(field.getValue().toString()));
        seed.sql("INSERT INTO "+table+" SELECT row.* FROM jsonb_populate_record(NULL::"+table+",CAST(:json AS jsonb)) row")
                .param("json",mapper.writeValueAsString(parsed)).update();
    }

    void landedReadback(History row) {
        UUID attempt=UUID.randomUUID(),raw=UUID.randomUUID(),content=UUID.randomUUID();
        seed.sql("""
                INSERT INTO ops.ad_bid_command_attempt(id,command_id,attempt_no,purpose,fence_token,lease_owner,started_at,completed_at,
                    outcome_class,correlation_id,request_digest,operation_snapshot)
                VALUES(:id,:command,1,'READBACK',1,'fictional-mixed-history',:at,:at,'ACCEPTED','fictional-mixed-history',repeat('a',64),'{}')
                """).param("id",attempt).param("command",row.command()).param("at",Timestamp.from(row.landed())).update();
        seed.sql("INSERT INTO raw.raw_content(id,hash_algorithm,hash_value,byte_length,object_ref) VALUES(:id,'SHA256',:hash,2,'object-ref://fictional/mixed-outcome')")
                .param("id",content).param("hash",com.mimococo.marketops.shared.Digest.ofText(content.toString())).update();
        seed.sql("""
                INSERT INTO raw.ad_bid_response_observation(id,command_id,attempt_id,raw_content_id,request_digest,http_status,
                    response_headers,evidence_class,response_complete,observed_bid,observed_currency,observed_unit,observed_at,correlation_id)
                VALUES(:id,:command,:attempt,:content,repeat('a',64),200,'{}','PROTOCOL_FIXTURE',true,20,'RUB','CURRENCY_MAJOR',:at,'fictional-mixed-history')
                """).param("id",raw).param("command",row.command()).param("attempt",attempt).param("content",content).param("at",Timestamp.from(row.landed())).update();
        seed.sql("INSERT INTO ops.ad_bid_command_readback VALUES(gen_random_uuid(),:command,:attempt,:at,20,'RUB','CURRENCY_MAJOR','MATCHES_TARGET',:raw,'fictional-mixed-history')")
                .param("command",row.command()).param("attempt",attempt).param("at",Timestamp.from(row.landed())).param("raw",raw).update();
        seed.sql("UPDATE ops.ad_bid_command SET state='READBACK_MATCHED',terminal_at=:at WHERE id=:id")
                .param("id",row.command()).param("at",Timestamp.from(row.landed())).update();
    }

    void companySale(String stage,Instant occurred,Instant accepted,String amount,String key) {
        UUID source=UUID.randomUUID();
        seed.sql("INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note) VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:owner,'Synthetic mixed-workload historical company cohort')")
                .param("id",source).param("org",shared.id("organization")).param("at",Timestamp.from(accepted)).param("owner",shared.id("ownerUser")).update();
        seed.sql("""
                INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,sale_stage,retention_window_days,
                    source_fact_key,native_order_key,occurred_at,quantity,currency_code,gross_amount,net_amount)
                VALUES(gen_random_uuid(),:org,:source,:listing,:store,:stage,:retention,:key,:key,:occurred,10,'RUB',:amount,:amount)
                """).param("org",shared.id("organization")).param("source",source).param("listing",shared.id("listingVariant"))
                .param("store",shared.id("store")).param("stage",stage).param("retention",stage.equals("RETAINED")?30:null)
                .param("key",key).param("occurred",Timestamp.from(occurred)).param("amount",new BigDecimal(amount)).update();
    }

    void coverage(Instant from,Instant to,Instant accepted) {
        seed.sql("""
                INSERT INTO ledger.return_quality_evidence_snapshot(id,organization_id,platform_listing_variant_id,report_window_start,report_window_end,
                    completed_coverage,retained_coverage,return_coverage,qc_coverage,completed_source_updated_at,retained_source_updated_at,
                    return_source_updated_at,qc_source_updated_at,evidence_reference,accepted_at,correlation_id)
                VALUES(gen_random_uuid(),:org,:listing,:from,:to,'COMPLETE','COMPLETE','COMPLETE_ZERO','COMPLETE',:at,:at,:at,:at,
                    'fixture://mixed/complete-covered-cohort',:at,'fixture-mixed-capacity')
                """).param("org",shared.id("organization")).param("listing",shared.id("listingVariant"))
                .param("from",Timestamp.from(from)).param("to",Timestamp.from(to)).param("at",Timestamp.from(accepted)).update();
    }

    UUID spend(History row,Instant from,Instant to,Instant accepted,int amount,UUID supersedes) {
        UUID id=UUID.randomUUID();
        seed.sql("""
                INSERT INTO ledger.ad_object_fact(id,organization_id,provenance_id,ad_native_object_id,store_id,source_fact_key,period_start,period_end,
                    currency_code,spend_amount,clicks,report_window_complete,correction_window_open,source_time,recorded_at,supersedes_fact_id,adjustment_kind)
                VALUES(:id,:org,:source,:object,:store,:key,:from,:to,'RUB',:amount,100,true,false,:at,:at,:supersedes,:adjustment)
                """).param("id",id).param("org",shared.id("organization")).param("source",shared.id("provenance"))
                .param("object",row.graph().id("object")).param("store",shared.id("store")).param("key","mixed-report-"+id)
                .param("from",Timestamp.from(from)).param("to",Timestamp.from(to)).param("amount",amount).param("at",Timestamp.from(accepted))
                .param("supersedes",supersedes).param("adjustment",supersedes==null?null:"CORRECTION").update();
        return id;
    }

    void matureReport(History row,Instant accepted,int spend) {
        companySale("RETAINED",row.from(),accepted,"1000","mixed-retained-"+row.command());
        seed.sql("""
                INSERT INTO ledger.ad_linked_sale_event(id,organization_id,provenance_id,ad_native_object_id,affected_set_id,platform_listing_variant_id,
                    conversion_definition_id,sale_stage,linkage_basis,linkage_evidence_ref,event_count,net_sales_amount,currency_code,
                    occurred_at,period_start,period_end,source_time,recorded_at)
                VALUES(:id,:org,:source,:object,:set,:listing,:conversion,'CANONICAL_AD_LINKED_RETAINED_SALE',
                    'DETERMINISTIC_OBJECT_LINKAGE','fixture://mixed/one-object-cohort',10,1000,'RUB',:from,:from,:to,:at,:at)
                """).param("id",row.retainedEvent()).param("org",shared.id("organization")).param("source",shared.id("provenance"))
                .param("object",row.graph().id("object")).param("set",row.graph().id("affectedSet")).param("listing",shared.id("listingVariant"))
                .param("conversion",shared.id("conversion")).param("from",Timestamp.from(row.from())).param("to",Timestamp.from(row.to()))
                .param("at",Timestamp.from(accepted)).update();
        coverage(row.from(),row.to(),accepted);
        UUID prior=seed.sql("SELECT id FROM ledger.ad_object_fact WHERE ad_native_object_id=:object AND period_start=:from ORDER BY recorded_at DESC,id LIMIT 1")
                .param("object",row.graph().id("object")).param("from",Timestamp.from(row.from())).query(UUID.class).single();
        spend(row,row.from(),row.to(),accepted,spend,prior);
        seed.sql("""
                INSERT INTO core.ad_object_configuration_observation SELECT clone.* FROM core.ad_object_configuration_observation base
                CROSS JOIN LATERAL jsonb_populate_record(NULL::core.ad_object_configuration_observation,to_jsonb(base)||jsonb_build_object(
                    'id',gen_random_uuid(),'source_time',CAST(:at AS timestamptz),'observed_at',CAST(:at AS timestamptz),'observed_bid_amount',20,
                    'source_fact_key','mixed-current-configuration-'||base.ad_native_object_id)) clone WHERE base.id=:id
                """).param("id",row.graph().id("configuration")).param("at",Timestamp.from(accepted)).update();
        UUID metricRun=UUID.randomUUID();
        seed.sql("""
                INSERT INTO mart.calculation_run(id,organization_id,trigger_kind,scope_kind,window_code,period_start,period_end,
                    definition_set_digest,state,subject_count,value_count,started_at,completed_at,correlation_id)
                VALUES(:id,:org,'BACKFILL','ORGANIZATION','D30',:from,:to,repeat('a',64),'SUCCEEDED',1,4,:at,:at,'synthetic-mixed-metric-oracle')
                """).param("id",metricRun).param("org",shared.id("organization")).param("from",Timestamp.from(row.from()))
                .param("to",Timestamp.from(row.to())).param("at",Timestamp.from(accepted)).update();
        for(String code:List.of("UNIT_COST","PLATFORM_FEES_PER_UNIT","RETURN_LOSS_PER_UNIT","VARIABLE_TAX_PER_UNIT")) {
            UUID id=UUID.randomUUID();
            seed.sql("""
                    INSERT INTO mart.metric_value(id,organization_id,calculation_run_id,metric_code,definition_version,subject_kind,subject_id,
                        window_code,period_start,period_end,value_state,numeric_value,currency_code,confidence_state,estimated,oldest_source_time,
                        freshness_seconds,input_digest,computed_at)
                    VALUES(:id,:org,:run,:code,2,'PLATFORM_LISTING_VARIANT',:listing,'D30',:from,:to,'AVAILABLE',:amount,'RUB',
                        'CANONICAL_CONFIRMED',false,:at,0,:digest,:at)
                    """).param("id",id).param("org",shared.id("organization")).param("run",metricRun).param("code",code)
                    .param("listing",shared.id("listingVariant")).param("from",Timestamp.from(row.from())).param("to",Timestamp.from(row.to()))
                    .param("amount",code.equals("UNIT_COST")?new BigDecimal("80"):BigDecimal.ZERO).param("at",Timestamp.from(accepted))
                    .param("digest",com.mimococo.marketops.shared.Digest.ofText(id.toString())).update();
            seed.sql("INSERT INTO mart.metric_input_reference VALUES(gen_random_uuid(),:id,'FACT_PROVENANCE',:source)")
                    .param("id",id).param("source",shared.id("provenance")).update();
        }
    }

    void freshHistoricalSafetyReport(Instant accepted) {
        UUID source=UUID.randomUUID();
        seed.sql("INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note) VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:owner,'Synthetic newly published retrospective safety report covering the historical action cohorts')")
                .param("id",source).param("org",shared.id("organization")).param("at",Timestamp.from(accepted)).param("owner",shared.id("ownerUser")).update();
        Instant effective=histories.stream().map(History::from).min(Instant::compareTo).orElseThrow().minusSeconds(1);
        var common=Map.of("org",shared.id("organization"),"source",source,"listing",shared.id("listingVariant"),"at",Timestamp.from(effective));
        seed.sql("INSERT INTO core.listing_price_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,currency_code,selling_price,promotion_active) VALUES(gen_random_uuid(),:org,:source,:listing,'mixed-historical-price-report',:at,'RUB',100,'NO')").params(common).update();
        seed.sql("INSERT INTO core.listing_health_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,sellable) VALUES(gen_random_uuid(),:org,:source,:listing,'mixed-historical-sellability-report',:at,'YES')").params(common).update();
        seed.sql("INSERT INTO core.listing_stock_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,fulfillment_mode_code,available_quantity) VALUES(gen_random_uuid(),:org,:source,:listing,'mixed-historical-availability-report',:at,'SELLER_FULFILLED',100)").params(common).update();
        // The same freshly published report also confirms present context; all
        // values match its historical coverage, so no hidden promotion change exists.
        for(String table:List.of("core.listing_price_observation","core.listing_health_observation","core.listing_stock_observation"))
            seed.sql("INSERT INTO "+table+" SELECT clone.* FROM "+table+" old CROSS JOIN LATERAL jsonb_populate_record(NULL::"+table+",to_jsonb(old)||jsonb_build_object("
                    +"'id',gen_random_uuid(),'source_fact_key',old.source_fact_key||'-present','observed_at',CAST(:at AS timestamptz))) clone WHERE old.provenance_id=:source")
                    .param("at",Timestamp.from(accepted)).param("source",source).update();
    }
}
