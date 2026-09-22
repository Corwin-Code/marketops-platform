package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operationsworkflow.ListingTaskDependencyHold;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskEventRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Uses the original Task and its frozen policy; no second case or notification is created. */
@Service
class ListingTaskDependencyHoldService implements ListingTaskDependencyHold {
    private final JdbcClient jdbc;
    private final WorkTaskRepository tasks;
    private final WorkTaskEventRepository journal;
    private final WorkTaskService taskService;
    private final IdGenerator ids;

    ListingTaskDependencyHoldService(JdbcClient jdbc, WorkTaskRepository tasks,
            WorkTaskEventRepository journal, WorkTaskService taskService, IdGenerator ids) {
        this.jdbc=jdbc; this.tasks=tasks; this.journal=journal; this.taskService=taskService; this.ids=ids;
    }

    @Override
    @Transactional
    public View request(AuthenticatedActor actor, UUID listingId, UUID taskId, UUID dependencyTaskId,
                        int minutes, String evidenceReference) {
        taskService.requireTaskAction(actor,taskId,false);
        taskService.requireTaskAction(actor,dependencyTaskId,true);
        Context context=context(taskId);
        if (!context.listingId().equals(listingId) || taskId.equals(dependencyTaskId))
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        String evidence=MetadataFieldPolicy.requireText("evidenceReference",evidenceReference);
        if (minutes<1) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        synchronizeDue(200);
        // Stable lock order prevents two reciprocal requests from deadlocking;
        // the database guard then rejects a dependency cycle.
        jdbc.sql("SELECT id FROM ops.work_task WHERE id IN (:first,:second) ORDER BY id FOR UPDATE")
                .param("first",taskId).param("second",dependencyTaskId).query(UUID.class).list();
        Instant now=tasks.databaseNow();
        UUID collaboration=jdbc.sql("""
                SELECT id FROM ops.lc_collaboration_link
                WHERE organization_id=:org AND platform_listing_id=:listing
                  AND task_id=:dependency AND evidence_reference=:evidence
                  AND link_kind='DEPENDENCY_REEVALUATION'
                  AND recorded_at<=:at AND (source_time IS NULL OR source_time<=:at)
                ORDER BY recorded_at DESC,id DESC LIMIT 1
                """).param("org",context.organizationId()).param("listing",listingId)
                .param("dependency",dependencyTaskId).param("evidence",evidence)
                .param("at",Timestamp.from(now)).query(UUID.class).optional()
                .orElseThrow(()->OperationRejectedException.of(ErrorCode.RAW_EVIDENCE_MISSING));
        Optional<View> active=current(taskId);
        if (active.isPresent() && "ACTIVE".equals(active.get().state())) {
            View previous=active.get();
            if (previous.dependencyTaskId().equals(dependencyTaskId) && previous.minutes()==minutes
                    && previous.evidenceReference().equals(evidence)) return previous;
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        UUID id=ids.newId();
        jdbc.sql("""
                INSERT INTO ops.lc_task_dependency_hold(id,task_id,dependency_task_id,organization_id,
                    requester_user_id,hold_minutes,evidence_reference,collaboration_link_id,started_at,expires_at,state)
                VALUES(:id,:task,:dependency,:org,:actor,:minutes,:evidence,:collaboration,:at,
                    CAST(:at AS timestamptz)+make_interval(mins=>:minutes),'ACTIVE')
                """).param("id",id).param("task",taskId).param("dependency",dependencyTaskId)
                .param("org",context.organizationId()).param("actor",actor.userId()).param("minutes",minutes)
                .param("evidence",evidence).param("collaboration",collaboration)
                .param("at",Timestamp.from(now)).update();
        record(context,"DEPENDENCY_HOLD_STARTED",id,actor.userId(),evidence,now);
        return current(taskId).orElseThrow();
    }

    @Override
    @Transactional(readOnly=true)
    public Optional<View> current(UUID taskId) {
        Instant now=tasks.databaseNow();
        return jdbc.sql("""
                SELECT id,dependency_task_id,hold_minutes,evidence_reference,started_at,expires_at,
                  CASE WHEN state='ACTIVE' AND expires_at<=:at THEN 'EXPIRED' ELSE state END AS view_state,
                  CASE WHEN state='ACTIVE' AND expires_at<=:at THEN expires_at ELSE ended_at END AS view_ended,
                  CASE WHEN state='ACTIVE' AND expires_at<=:at THEN 'finite dependency hold expired' ELSE end_reason END AS view_reason
                FROM ops.lc_task_dependency_hold WHERE task_id=:task
                ORDER BY started_at DESC,id DESC LIMIT 1
                """).param("task",taskId).param("at",Timestamp.from(now)).query((rs,n)->new View(
                        rs.getObject("id",UUID.class),rs.getObject("dependency_task_id",UUID.class),
                        rs.getInt("hold_minutes"),rs.getString("evidence_reference"),
                        rs.getTimestamp("started_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("view_state"),instant(rs,"view_ended"),rs.getString("view_reason"))).optional();
    }

    @Override
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int synchronizeDue(int limit) {
        Instant now=tasks.databaseNow();
        var due=jdbc.sql("""
                SELECT h.id,h.task_id,h.expires_at,d.state AS dependency_state,d.closed_at
                FROM ops.lc_task_dependency_hold h JOIN ops.work_task d ON d.id=h.dependency_task_id
                WHERE h.state='ACTIVE' AND (h.expires_at<=:at OR d.state IN ('DONE','CANCELLED'))
                ORDER BY least(h.expires_at,coalesce(d.closed_at,h.expires_at)),h.id
                LIMIT :limit FOR UPDATE OF h SKIP LOCKED
                """).param("at",Timestamp.from(now)).param("limit",Math.clamp(limit,1,200))
                .query((rs,n)->new Due(rs.getObject("id",UUID.class),rs.getObject("task_id",UUID.class),
                        rs.getTimestamp("expires_at").toInstant(),rs.getString("dependency_state"),
                        instant(rs,"closed_at"))).list();
        for (Due row:due) {
            boolean dependencyFirst=row.closedAt()!=null && !row.closedAt().isAfter(row.expiresAt());
            String state=dependencyFirst?("DONE".equals(row.dependencyState())?"RESUMED":"INVALIDATED"):"EXPIRED";
            Instant ended=dependencyFirst?row.closedAt():row.expiresAt();
            String reason=switch(state) {
                case "RESUMED" -> "dependency Task completed with its recorded result";
                case "INVALIDATED" -> "dependency Task was cancelled; dependency qualification must be reviewed";
                default -> "finite dependency hold expired";
            };
            Context context=context(row.taskId());
            UUID queue=ids.newId();
            boolean risk="NECESSARY_RISK".equals(context.lane());
            jdbc.sql("""
                    INSERT INTO ops.lc_recalculation_queue(id,organization_id,platform_listing_id,trigger_class,
                        target_minutes,trigger_reference,source_time,accepted_at,state)
                    VALUES(:id,:org,:listing,:class,:target,:reference,:source,:accepted,'QUEUED')
                    """).param("id",queue).param("org",context.organizationId()).param("listing",context.listingId())
                    .param("class",risk?"RISK":"ORDINARY").param("target",risk?5:15)
                    .param("reference","task-dependency-hold:"+row.id()+":"+state.toLowerCase(java.util.Locale.ROOT))
                    .param("source",Timestamp.from(ended)).param("accepted",Timestamp.from(now)).update();
            jdbc.sql("""
                    UPDATE ops.lc_task_dependency_hold SET state=:state,ended_at=:ended,end_reason=:reason,
                        review_queue_id=:queue WHERE id=:id AND state='ACTIVE'
                    """).param("state",state).param("ended",Timestamp.from(ended)).param("reason",reason)
                    .param("queue",queue).param("id",row.id()).update();
            String event=switch(state) {
                case "RESUMED" -> "DEPENDENCY_RESUMED";
                case "INVALIDATED" -> "DEPENDENCY_INVALIDATED";
                default -> "DEPENDENCY_HOLD_EXPIRED";
            };
            record(context,event,row.id(),null,reason,ended);
        }
        return due.size();
    }

    private Context context(UUID taskId) {
        return jdbc.sql("""
                SELECT b.task_id,b.organization_id,coalesce(b.platform_listing_id,a.platform_listing_id),
                    b.responsibility_lane,t.recommendation_id
                FROM ops.lc_task_responsibility b JOIN ops.work_task t ON t.id=b.task_id
                LEFT JOIN ops.lc_action a ON a.recommendation_id=b.recommendation_id WHERE b.task_id=:task
                """).param("task",taskId).query((rs,n)->new Context(rs.getObject(1,UUID.class),
                        rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4),
                        rs.getObject(5,UUID.class)))
                .optional().orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void record(Context context,String kind,UUID hold,UUID actor,String reason,Instant at) {
        String lineage="ACTION".equals(context.lane())?"recommendation:"+context.recommendationId():
                "listing-diagnosis:"+context.taskId();
        journal.append(new WorkTaskEventRepository.Event(ids.newId(),context.taskId(),
                context.organizationId(),kind,lineage,null,null,
                "lc-task-dependency-hold:"+hold,null,null,null,null,actor,null,reason,at,
                "lc-task-dependency-hold:"+hold+":"+kind));
    }

    private static Instant instant(java.sql.ResultSet rs,String column) throws java.sql.SQLException {
        Timestamp value=rs.getTimestamp(column); return value==null?null:value.toInstant();
    }
    private record Context(UUID taskId,UUID organizationId,UUID listingId,String lane,UUID recommendationId) { }
    private record Due(UUID id,UUID taskId,Instant expiresAt,String dependencyState,Instant closedAt) { }
}
