package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.operationsworkflow.AdvertisingTaskSloQuery;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskEventRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Breaches escalate the same responsibility; they confer no action authority. */
@Service
class AdvertisingTaskSloMonitor {
    private final AdvertisingTaskSloQuery slo;
    private final JdbcClient jdbc;
    private final WorkTaskEventRepository journal;
    private final IdGenerator ids;
    private final Clock clock;
    AdvertisingTaskSloMonitor(AdvertisingTaskSloQuery slo,JdbcClient jdbc,WorkTaskEventRepository journal,IdGenerator ids,Clock clock) {
        this.slo=slo;this.jdbc=jdbc;this.journal=journal;this.ids=ids;this.clock=clock;
    }
    void inspect(UUID caseId) { inspect(caseId,clock.instant()); }
    int inspect(UUID caseId,java.time.Instant asOf) {
        var value=slo.statusForCase(caseId,asOf);
        if(value.isEmpty()) return 0;
        int changed=0;
        var state=value.get();
        if(state.acknowledgementBreached()) changed+=append(caseId,"ACKNOWLEDGEMENT_SLO_BREACHED",asOf);
        if(state.actionBreached()) changed+=append(caseId,"ACTION_SLO_BREACHED",asOf);
        if(state.coverageState().contains("MISSING")) changed+=append(caseId,"RESPONSE_PROFILE_OR_CALENDAR_UNRESOLVED",asOf);
        return changed;
    }
    private int append(UUID caseId,String reason,java.time.Instant asOf) {
        var row=jdbc.sql("SELECT b.task_id,b.organization_id FROM ops.ad_case_responsibility b JOIN ops.work_task t ON t.id=b.task_id WHERE b.case_id=:case FOR UPDATE OF t")
                .param("case",caseId).query((rs,n)->new Task(rs.getObject("task_id",UUID.class),rs.getObject("organization_id",UUID.class))).single();
        boolean already=jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM ops.work_task_event e WHERE e.task_id=:task AND e.event_kind='ESCALATED'
                 AND e.reason=:reason AND e.occurred_at>=coalesce((SELECT max(r.occurred_at) FROM ops.work_task_event r
                  WHERE r.task_id=:task AND r.event_kind IN('RAISED','REOPENED')),'epoch'::timestamptz))
                """).param("task",row.id()).param("reason",reason).query(Boolean.class).single();
        if(!already) journal.append(new WorkTaskEventRepository.Event(ids.newId(),row.id(),row.org(),"ESCALATED",
                "advertising-case:"+caseId,null,null,null,null,null,null,null,null,null,reason,asOf,"ad-slo:"+caseId));
        return already?0:1;
    }
    private record Task(UUID id,UUID org) { }
}
