package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.operationsworkflow.ListingDiagnosticIntake;
import com.mimococo.marketops.operationsworkflow.ListingResponsibilityBasis;
import com.mimococo.marketops.operationsworkflow.internal.domain.ListingResponsibilitySchedule;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskEventRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Retained known failures create review work before anybody prepares a platform action. */
@Service
class ListingDiagnosticIntakeService implements ListingDiagnosticIntake {
    private final JdbcClient jdbc;
    private final WorkTaskRepository tasks;
    private final WorkTaskEventRepository journal;
    private final ListingTaskSloService clocks;
    private final IdGenerator ids;
    private final WorkTaskService taskService;

    ListingDiagnosticIntakeService(JdbcClient jdbc,
            WorkTaskRepository tasks, WorkTaskEventRepository journal, ListingTaskSloService clocks, IdGenerator ids,
            WorkTaskService taskService) {
        this.jdbc=jdbc;this.tasks=tasks;this.journal=journal;this.clocks=clocks;this.ids=ids;
        this.taskService=taskService;
    }

    @Override
    @Transactional
    public void acknowledge(com.mimococo.marketops.identityaccess.AuthenticatedActor actor, UUID listingId, UUID taskId) {
        if(!jdbc.sql("SELECT EXISTS(SELECT 1 FROM ops.lc_task_responsibility WHERE task_id=:task AND platform_listing_id=:listing AND organization_id=:org)")
                .param("task",taskId).param("listing",listingId).param("org",actor.organizationId()).query(Boolean.class).single())
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        taskService.acknowledge(actor,taskId);
    }

    @Override
    @Transactional
    public void synchronize(UUID healthId, ListingResponsibilityBasis basis) {
        var source=jdbc.sql("""
                SELECT h.organization_id,h.store_id,h.platform_listing_id,h.calculation_run_id,h.computed_at
                FROM mart.lc_listing_health h WHERE h.id=:id
                """).param("id",healthId).query((rs,n)->new Source(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getTimestamp(5).toInstant())).single();
        jdbc.sql("SELECT id FROM core.platform_listing WHERE id=:id FOR UPDATE").param("id",source.listing())
                .query(UUID.class).single();
        // The same listing lock is held by recomputation. A retained old version cannot reactivate old work.
        if(!jdbc.sql("SELECT id=:id FROM mart.lc_listing_health WHERE platform_listing_id=:listing ORDER BY health_version DESC LIMIT 1")
                .param("id",healthId).param("listing",source.listing()).query(Boolean.class).single()) return;
        List<Cause> causes=qualifiedCauses(healthId);
        for(Cause qualified:causes) {
            String cause=qualified.code();
            UUID existing=jdbc.sql("""
                    SELECT task_id FROM ops.lc_task_responsibility WHERE organization_id=:org
                      AND platform_listing_id=:listing AND cause_code=:cause
                    """).param("org",source.organization()).param("listing",source.listing()).param("cause",cause)
                    .query(UUID.class).optional().orElse(null);
            Instant now=tasks.databaseNow();
            if(existing!=null) {
                var task=tasks.find(existing).orElseThrow();
                if(List.of("DONE","CANCELLED").contains(task.state())) {
                    if(!tasks.reopen(existing,now,task.version())) throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
                    record(existing,source.organization(),"REOPENED",cause,healthId,now);
                }
                continue;
            }
            UUID task=ids.newId();
            var schedule=ListingResponsibilitySchedule.resolve(now,basis.slo(),basis.coverage(),qualified.necessaryRisk());
            tasks.insert(task,source.organization(),null,
                    (qualified.necessaryRisk()?"Review the Listing necessary condition: ":"Assess the qualified Listing opportunity: ")+cause,
                    schedule.actionDueAt(),now);
            record(task,source.organization(),"RAISED",cause,healthId,now);
            clocks.bind(task,source.organization(),null,now,basis,schedule,healthId,source.listing(),cause);
        }
        UUID previous=jdbc.sql("""
                SELECT id FROM mart.lc_listing_health WHERE platform_listing_id=:listing
                  AND health_version<(SELECT health_version FROM mart.lc_listing_health WHERE id=:id)
                ORDER BY health_version DESC LIMIT 1
                """).param("listing",source.listing()).param("id",healthId).query(UUID.class).optional().orElse(null);
        if (previous!=null) {
            java.util.Set<String> current=causes.stream().map(Cause::code).collect(java.util.stream.Collectors.toSet());
            for (Cause prior:qualifiedCauses(previous)) {
                if (current.contains(prior.code())) continue;
                UUID task=jdbc.sql("""
                        SELECT task_id FROM ops.lc_task_responsibility WHERE organization_id=:org
                          AND platform_listing_id=:listing AND cause_code=:cause
                        """).param("org",source.organization()).param("listing",source.listing())
                        .param("cause",prior.code()).query(UUID.class).optional().orElse(null);
                if (task!=null) record(task,source.organization(),"QUALIFICATION_INVALIDATED",prior.code(),healthId,source.computedAt());
            }
        }
    }

    private List<Cause> qualifiedCauses(UUID healthId) {
        return jdbc.sql("""
                SELECT cause_code,necessary_risk FROM (
                  SELECT c->>'code' AS cause_code,true AS necessary_risk
                  FROM mart.lc_listing_health h CROSS JOIN LATERAL jsonb_array_elements(h.necessary_conditions) c
                  WHERE h.id=:id AND c->>'state'='FAIL'
                    AND c->>'code' IN ('AFFECTED_SET_COMPLETE','MAPPING_RESOLVED','DESCRIPTION_OBSERVED',
                        'NOT_CONTAINED','CALIBRATION_RESOLVED')
                  UNION ALL
                  SELECT o->>'code',false
                  FROM mart.lc_listing_health h CROSS JOIN LATERAL jsonb_array_elements(h.opportunities) o
                  WHERE h.id=:id AND h.necessary_state='PASS' AND h.eligibility->>'EVALUATION'='ELIGIBLE'
                    AND o->>'code' IN ('DESCRIPTION_NOT_RUSSIAN','KIZ_MARKING_UNDECLARED',
                        'SOURCE_STRATIFICATION_MISSING','NOT_SELLABLE_AT_LAST_OBSERVATION','FEEDBACK_THEMES_PRESENT')
                ) qualified ORDER BY necessary_risk DESC,cause_code
                """).param("id",healthId).query((rs,n)->new Cause(rs.getString(1),rs.getBoolean(2))).list();
    }

    private void record(UUID task,UUID organization,String event,String cause,UUID health,Instant at) {
        journal.append(new WorkTaskEventRepository.Event(ids.newId(),task,organization,event,"listing-diagnosis:"+task,
                null,null,null,null,null,null,null,null,null,"Retained necessary diagnosis "+cause+": "+health,
                at,"listing-diagnosis:"+health));
    }
    private record Source(UUID organization,UUID store,UUID listing,UUID run,Instant computedAt) { }
    private record Cause(String code,boolean necessaryRisk) { }
}
