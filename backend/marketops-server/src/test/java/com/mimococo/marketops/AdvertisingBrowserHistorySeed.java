package com.mimococo.marketops;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Explicit synthetic read oracles for rendered history, never Provider or business-computation proof.
 * Commands use the real isolated issuer/seal/creator; immutable history uses explicitly time-travelled fictional canonical rows.
 */
public final class AdvertisingBrowserHistorySeed {
    private AdvertisingBrowserHistorySeed() { }

    public static AdvertisingR1Fixture.Graph seed(ConfigurableApplicationContext context,
            DataSource migration, String scenario) throws Exception {
        boolean shortExpiry="HISTORY_EXPIRED".equals(scenario);
        var graph=AdvertisingR1Fixture.seedOutcome(migration,sql->{
            if("HISTORY_REGRESSION".equals(scenario)) {
                String money="jsonb_build_object('valueState','AVAILABLE','value',100,'evidenceState','CANONICAL_CONFIRMED')";
                String ratio="jsonb_build_object('valueState','AVAILABLE','value',1,'evidenceState','CANONICAL_CONFIRMED')";
                sql=sql.replace("'absoluteProfit',missing.value,'profitPerAdRub',missing.value",
                        "'absoluteProfit',"+money+",'profitPerAdRub',"+ratio)
                        .replace("jsonb_build_array('PRE_ACTION_PROFIT_UNRESOLVED')","jsonb_build_array()")
                        .replace("CASE WHEN stage.code='OPERATIONAL' THEN available.value ELSE missing.value END","available.value")
                        .replace("'officialSpend',missing.value","'officialSpend',"+money);
            }
            if(!shortExpiry) return sql;
            String old="now() + interval '2 hours',\n        'synthetic advertising fixture'";
            if(!sql.contains(old)) throw new IllegalStateException("Owner shortened fixture approval pattern missing");
            return sql.replace(old,"now() + interval '10 seconds',\n        'synthetic advertising fixture'");
        });
        var env=context.getEnvironment();
        var issuer=new DriverManagerDataSource(env.getRequiredProperty("marketops.identity.invocation.jdbc-url"),
                env.getRequiredProperty("marketops.identity.invocation.username"),
                env.getRequiredProperty("marketops.identity.invocation.password"));
        UUID command;
        try(Connection application=context.getBean(DataSource.class).getConnection()) {
            application.setAutoCommit(false);
            String proof=AdvertisingR1Fixture.proof(issuer,application,graph,graph.id("ownerUser"),null,
                    graph.id("recommendation"),graph.id("approval"));
            AdvertisingR1Fixture.seal(application,graph,proof);
            command=AdvertisingR1Fixture.createCommand(application,graph);
            application.commit();
        }
        var jdbc=JdbcClient.create(migration);
        switch(scenario) {
            case "HISTORY_UNKNOWN" -> {
                attempt(jdbc,command,"APPLY","TIMEOUT");
                jdbc.sql("UPDATE ops.ad_bid_command SET state='UNKNOWN_REQUIRES_READBACK',attempt_no=1,updated_at=clock_timestamp() WHERE id=:id")
                        .param("id",command).update();
            }
            case "HISTORY_MISMATCH" -> {
                readback(jdbc,command,27);
                jdbc.sql("UPDATE ops.ad_bid_command SET state='READBACK_MISMATCH',attempt_no=1,updated_at=clock_timestamp() WHERE id=:id")
                        .param("id",command).update();
            }
            case "HISTORY_REGRESSION" -> {
                readback(jdbc,command,20);
                jdbc.sql("UPDATE ops.ad_bid_command SET state='READBACK_MATCHED',attempt_no=1,terminal_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=:id")
                        .param("id",command).update();
                observation(jdbc,graph,command,"OPERATIONAL",1,null,"IMPROVED",110);
                observation(jdbc,graph,command,"RETAINED",1,null,"IMPROVED",115);
                UUID settled=observation(jdbc,graph,command,"SETTLED",1,null,"IMPROVED",120);
                UUID revised=observation(jdbc,graph,command,"SETTLED_REVISED",2,settled,"REGRESSED",80);
                // Actual containment consumes the canonical regression record; no reverse write occurs.
                jdbc.sql("SELECT ops.activate_ad_regression_containment(:observation)")
                        .param("observation",revised).query(UUID.class).single();
            }
            case "HISTORY_EXPIRED" -> {
                // The exact Owner-selected ten-second limit was frozen by the actual seal.
                // The browser observes natural expiry; no historical approval is changed.
            }
            default -> throw new IllegalArgumentException("Unknown isolated history scenario");
        }
        if(jdbc.sql("SELECT count(*) FROM ops.ad_gate_authority WHERE organization_id=:org AND production_write_enabled")
                .param("org",graph.id("organization")).query(Integer.class).single()!=0)
            throw new IllegalStateException("Isolated history may never enable production write");
        var ids=new HashMap<>(graph.ids());ids.put("historyCommand",command);
        return new AdvertisingR1Fixture.Graph(Map.copyOf(ids),graph.platform());
    }

    private static UUID attempt(JdbcClient jdbc,UUID command,String purpose,String outcome) {
        UUID id=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO ops.ad_bid_command_attempt(id,command_id,attempt_no,purpose,fence_token,lease_owner,
              started_at,completed_at,outcome_class,correlation_id,request_digest,operation_snapshot)
            VALUES(:id,:command,1,:purpose,1,'synthetic-browser-read-oracle',clock_timestamp(),clock_timestamp(),
              :outcome,'synthetic-browser-read-oracle',repeat('a',64),'{}')
            """).param("id",id).param("command",command).param("purpose",purpose).param("outcome",outcome).update();
        return id;
    }

    private static void readback(JdbcClient jdbc,UUID command,int bid) {
        UUID attempt=attempt(jdbc,command,"READBACK","ACCEPTED");
        UUID raw=UUID.randomUUID(),content=UUID.randomUUID();
        String bytes="{\"bid\":"+bid+",\"currency\":\"RUB\",\"unit\":\"CURRENCY_MAJOR\"}";
        jdbc.sql("INSERT INTO raw.raw_content(id,hash_algorithm,hash_value,byte_length,object_ref) VALUES(:id,'SHA256',:hash,:length,:ref)")
                .param("id",content).param("hash",com.mimococo.marketops.shared.Digest.ofText(bytes))
                .param("length",bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .param("ref","object-ref://synthetic-browser/"+content).update();
        jdbc.sql("""
            INSERT INTO raw.ad_bid_response_observation(id,command_id,attempt_id,raw_content_id,request_digest,http_status,
              response_headers,evidence_class,response_complete,observed_bid,observed_currency,observed_unit,observed_at,correlation_id)
            VALUES(:id,:command,:attempt,:content,repeat('a',64),200,'{}','PROTOCOL_FIXTURE',true,:bid,
              'RUB','CURRENCY_MAJOR',clock_timestamp(),'synthetic-browser-read-oracle')
            """).param("id",raw).param("command",command).param("attempt",attempt).param("content",content).param("bid",bid).update();
        jdbc.sql("""
            INSERT INTO ops.ad_bid_command_readback(id,command_id,attempt_id,observed_at,observed_bid,currency_code,
              bid_unit_code,match_state,raw_observation_id,correlation_id)
            VALUES(gen_random_uuid(),:command,:attempt,clock_timestamp(),:bid,'RUB','CURRENCY_MAJOR',:match,:raw,'synthetic-browser-read-oracle')
            """).param("command",command).param("attempt",attempt).param("bid",bid)
                .param("match",bid==20?"MATCHES_TARGET":"DIFFERENT").param("raw",raw).update();
    }

    private static UUID observation(JdbcClient jdbc,AdvertisingR1Fixture.Graph graph,UUID command,
            String stage,int revision,UUID prior,String verdict,int observed) {
        UUID id=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO ops.ad_outcome_observation(id,organization_id,command_id,ad_native_object_id,affected_set_digest,
              outcome_policy_id,outcome_policy_version,outcome_stage,window_starts_at,window_ends_at,
              baseline_metric_state,baseline_metric_value,observed_metric_state,observed_metric_value,
              observed_traffic_count,settled_coverage_ratio,verdict,guard_state,unresolved_reason_codes,
              evaluated_at,input_digest,correlation_id,revision_no,supersedes_observation_id,adjustment_reason)
            SELECT :id,c.organization_id,c.id,c.ad_native_object_id,c.affected_set_digest,:policy,1,:stage,
              w.starts_at,w.starts_at+make_interval(hours=>stage.window_hours),
              stage.snapshot#>>'{profit,absoluteProfit,valueState}',(stage.snapshot#>>'{profit,absoluteProfit,value}')::numeric,
              'AVAILABLE',:observed,1000,CASE WHEN :stage='OPERATIONAL' THEN NULL ELSE 1 END,:verdict,
              CASE WHEN :stage='OPERATIONAL' THEN 'NOT_APPLICABLE' ELSE 'SATISFIED' END,'{}',
              w.starts_at+make_interval(hours=>stage.window_hours)+make_interval(mins=>:timeOrder),:digest,
              'synthetic-browser-time-travel-read-oracle',:revision,:prior,:reason
            FROM ops.ad_bid_command c JOIN ops.ad_outcome_baseline baseline ON baseline.id=c.outcome_baseline_id
            JOIN ops.ad_outcome_stage_baseline stage ON stage.outcome_baseline_id=baseline.id AND stage.stage=replace(:stage,'_REVISED','')
            CROSS JOIN LATERAL(SELECT min(observed_at)+make_interval(mins=>(baseline.plan_snapshot->>'observationStartsMinutes')::integer) starts_at
                FROM ops.ad_bid_command_readback WHERE command_id=c.id AND match_state='MATCHES_TARGET') w
            WHERE c.id=:command
            """).param("id",id).param("command",command).param("policy",graph.id("outcome"))
                .param("stage",stage).param("observed",observed).param("verdict",verdict)
                .param("timeOrder",stage.equals("OPERATIONAL")?1:stage.equals("RETAINED")?2:stage.equals("SETTLED")?3:4)
                .param("digest",com.mimococo.marketops.shared.Digest.ofText("synthetic-browser:"+id))
                .param("revision",revision).param("prior",prior)
                .param("reason",prior==null?null:"Synthetic late settlement correction; preserved earlier versions").update();
        jdbc.sql("""
            INSERT INTO ops.ad_outcome_axes(observation_id,outcome_baseline_id,dual_axis_verdict,business_outcome,
              sales_preservation_verdict,baseline_absolute_profit,observed_absolute_profit,baseline_profit_per_rub,
              observed_profit_per_rub,company_baseline_sales,company_observed_sales,currency_code,input_snapshot)
            VALUES(:id,:baseline,:dual,:business,'PASS',100,:observed,1,:ratio,1000,1000,'RUB',
              '{"evidenceClass":"SYNTHETIC_BROWSER_READ_ORACLE","computationEvidence":false}')
            """).param("id",id).param("baseline",graph.id("baseline")).param("dual",verdict.equals("REGRESSED")?"REGRESSED":"VERIFIED_EFFICIENCY_SUCCESS")
                .param("business",verdict.equals("REGRESSED")?"PROTECTION_IN_PROGRESS":"VERIFIED_EFFICIENCY_SUCCESS")
                .param("observed",observed).param("ratio",java.math.BigDecimal.valueOf(observed,2)).update();
        return id;
    }
}
