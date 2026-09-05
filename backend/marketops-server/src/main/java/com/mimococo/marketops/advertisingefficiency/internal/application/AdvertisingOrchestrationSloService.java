package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingTraceRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Actual advertising P95 and hard-bound distributions; recovery never erases an observation. */
@Service
public class AdvertisingOrchestrationSloService {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final AdvertisingTraceRepository trace;
    private final ObjectMapper mapper;
    AdvertisingOrchestrationSloService(JdbcClient jdbc,Clock clock,AdvertisingTraceRepository trace,ObjectMapper mapper) {
        this.jdbc=jdbc;this.clock=clock;this.trace=trace;this.mapper=mapper;
    }
    public Map<String,Object> snapshot(UUID org,List<UUID> stores,Instant now) {
        var metrics=jdbc.sql("""
                SELECT count(*) samples,count(*) FILTER(WHERE s.lane='PROTECTION') critical_samples,
                    percentile_disc(0.95) WITHIN GROUP(ORDER BY s.internal_latency_ms)
                      FILTER(WHERE s.lane='PROTECTION' AND s.clock_state='VALID') critical_p95_ms,
                    max(s.internal_latency_ms) maximum_ms,
                    count(*) FILTER(WHERE s.internal_latency_ms>900000) hard_breaches,
                    count(*) FILTER(WHERE s.clock_state='CLOCK_INCONSISTENT') clock_defects
                FROM ops.ad_slo_observation s JOIN core.ad_native_object obj ON obj.id=s.ad_native_object_id
                WHERE s.organization_id=:org AND obj.store_id=ANY(:stores) AND s.calculated_at>=:from
                """).param("org",org).param("stores",stores.toArray(UUID[]::new)).param("from",Timestamp.from(now.minus(Duration.ofHours(24))))
                .query((rs,n)->{
                    var result=new LinkedHashMap<String,Object>();result.put("sampleCount",rs.getLong("samples"));
                    result.put("criticalSampleCount",rs.getLong("critical_samples"));result.put("criticalP95Millis",rs.getObject("critical_p95_ms",Long.class));
                    result.put("maximumMillis",rs.getObject("maximum_ms",Long.class));result.put("hardBreachCount",rs.getLong("hard_breaches"));
                    result.put("clockDefectCount",rs.getLong("clock_defects"));return result;
                }).single();
        var backlog=jdbc.sql("""
                SELECT count(*) FILTER(WHERE r.state IN('PENDING','LEASED')) pending,
                    count(*) FILTER(WHERE r.state IN('FAILED','ABANDONED')) failures,
                    min(r.fact_accepted_at) oldest
                FROM ops.ad_recalculation_request r JOIN core.ad_native_object obj ON obj.id=r.ad_native_object_id
                WHERE r.organization_id=:org AND obj.store_id=ANY(:stores) AND r.state<>'COMPLETED'
                """).param("org",org).param("stores",stores.toArray(UUID[]::new))
                .query((rs,n)->new Backlog(rs.getLong("pending"),rs.getLong("failures"),rs.getTimestamp("oldest")==null?null:rs.getTimestamp("oldest").toInstant())).single();
        var last=jdbc.sql("""
                SELECT state,completed_at,started_at FROM ops.ad_reconciliation_run WHERE organization_id=:org
                  ORDER BY started_at DESC,id DESC LIMIT 1
                """).param("org",org).query((rs,n)->new Sweep(rs.getString("state"),
                        rs.getTimestamp("completed_at")==null?null:rs.getTimestamp("completed_at").toInstant(),rs.getTimestamp("started_at").toInstant())).optional();
        long unresolvedPlans=jdbc.sql("""
                SELECT count(*) FROM ops.ad_trace_event t JOIN core.ad_native_object obj ON obj.id=t.ad_native_object_id
                WHERE t.organization_id=:org AND obj.store_id=ANY(:stores) AND t.occurred_at>=:from
                  AND t.stage_code='OUTCOME_MATURITY_SWEEP' AND t.status='FAILED'
                  AND t.detail->>'reason'='AD_OUTCOME_PLAN_DEADLINE_UNRESOLVED'
                """).param("org",org).param("stores",stores.toArray(UUID[]::new)).param("from",Timestamp.from(now.minus(Duration.ofHours(24))))
                .query(Long.class).single();
        var incidents=new ArrayList<String>();
        if(unresolvedPlans>0) incidents.add("AD_OUTCOME_PLAN_DEADLINE_UNRESOLVED"); Long p95=(Long)metrics.get("criticalP95Millis");
        if(p95!=null&&p95>300000) incidents.add("CRITICAL_P95_BREACHED");
        if((Long)metrics.get("hardBreachCount")>0) incidents.add("HARD_BOUND_BREACHED");
        if((Long)metrics.get("clockDefectCount")>0) incidents.add("CLOCK_INCONSISTENT");
        if(backlog.failures()>0) incidents.add("TARGETED_FAILURE");
        if(backlog.oldest()!=null&&Duration.between(backlog.oldest(),now).compareTo(Duration.ofMinutes(15))>0) incidents.add("BACKLOG_HARD_BOUND_BREACHED");
        if(last.isEmpty()||last.get().completedAt()==null||Duration.between(last.get().completedAt(),now).compareTo(Duration.ofHours(1))>0)
            incidents.add("HOURLY_RECONCILIATION_NOT_CURRENT");
        if(last.isPresent()&&"FAILED".equals(last.get().state())) incidents.add("LATEST_RECONCILIATION_FAILED");
        metrics.put("pendingRequests",backlog.pending());metrics.put("failedRequests",backlog.failures());
        metrics.put("oldestFactAcceptedAt",backlog.oldest());metrics.put("lastSweepState",last.map(Sweep::state).orElse("NOT_ESTABLISHED"));
        metrics.put("lastSweepCompletedAt",last.map(Sweep::completedAt).orElse(null));
        metrics.put("observedAt",now);metrics.put("windowHours",24);metrics.put("incidents",List.copyOf(incidents));
        metrics.put("state",incidents.isEmpty()?"WITHIN_OBSERVED_BOUNDS":"INCIDENT");
        metrics.put("distributionState",p95==null?"NO_CRITICAL_OBSERVATIONS":"MEASURED");
        return metrics;
    }
    public void record(UUID org) {
        List<UUID> stores=jdbc.sql("SELECT id FROM core.store WHERE organization_id=:org").param("org",org).query(UUID.class).list();
        Instant now=clock.instant();Map<String,Object> snapshot=snapshot(org,stores,now);
        trace.record(UUID.randomUUID(),org,null,"OPERATIONS","SLO_RECORDED",
                "INCIDENT".equals(snapshot.get("state"))?"FAILED":"OBSERVED","advertising-slo:"+UUID.randomUUID(),
                null,null,mapper.writeValueAsString(snapshot),now);
    }
    private record Backlog(long pending,long failures,Instant oldest) { }
    private record Sweep(String state,Instant completedAt,Instant startedAt) { }
}
