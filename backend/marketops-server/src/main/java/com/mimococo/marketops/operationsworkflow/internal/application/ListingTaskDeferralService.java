package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.OwnedResource;
import com.mimococo.marketops.operationsworkflow.ListingTaskDeferralIntake;
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
import tools.jackson.databind.ObjectMapper;

/** Deferral schedules reconsideration, and never changes Task age, due time or action qualification. */
@Service
class ListingTaskDeferralService implements ListingTaskDeferralIntake {
    private final JdbcClient jdbc;
    private final WorkTaskRepository tasks;
    private final WorkTaskEventRepository journal;
    private final BusinessAuthorization authorization;
    private final IdGenerator ids;
    private final ObjectMapper json;

    ListingTaskDeferralService(JdbcClient jdbc,WorkTaskRepository tasks,WorkTaskEventRepository journal,
            BusinessAuthorization authorization,IdGenerator ids,ObjectMapper json) {
        this.jdbc=jdbc;this.tasks=tasks;this.journal=journal;this.authorization=authorization;this.ids=ids;this.json=json;
    }

    @Override
    @Transactional
    public View request(AuthenticatedActor actor,UUID listingId,UUID taskId,int minutes,String reason) {
        authorization.requireOwned(actor,ActionScopeCode.TASK_ASSIGN,new OwnedResource(OwnedResource.Kind.WORK_TASK,taskId));
        Context context=context(taskId);
        if(!context.listing().equals(listingId)) throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        jdbc.sql("SELECT id FROM core.platform_listing WHERE id=:id FOR UPDATE").param("id",context.listing()).query(UUID.class).single();
        jdbc.sql("SELECT id FROM ops.work_task WHERE id=:id FOR UPDATE").param("id",taskId).query(UUID.class).single();
        if(!List.of("OPEN","ASSIGNED","IN_PROGRESS").contains(tasks.find(taskId).orElseThrow().state()))
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        String validReason=MetadataFieldPolicy.requireText("reason",reason);
        var policy=json.readTree(context.policy());
        var limit=(context.risk()?policy.path("necessaryRisk"):policy).path("maximumDeferMinutes");
        if(!limit.isIntegralNumber() || !limit.canConvertToInt() || limit.intValue()<1)
            throw OperationRejectedException.of(ErrorCode.CALIBRATION_UNRESOLVED);
        if(minutes<1 || minutes>limit.intValue()) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        Optional<View> existing=current(taskId);
        if(existing.isPresent() && List.of("ACTIVE","REVIEW_DUE").contains(existing.get().state())) {
            boolean same=jdbc.sql("SELECT requester_user_id=:actor FROM ops.lc_task_deferral WHERE id=:id")
                    .param("actor",actor.userId()).param("id",existing.get().id()).query(Boolean.class).single();
            if(same && existing.get().minutes()==minutes && existing.get().reason().equals(validReason)) return existing.get();
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if(existing.isPresent() && existing.get().reviewHealthId()==null)
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        String basis=jdbc.sql("SELECT ops.lc_task_reassessment_basis(:task)").param("task",taskId)
                .query(String.class).optional().orElseThrow(()->OperationRejectedException.of(ErrorCode.RAW_EVIDENCE_MISSING));
        Instant now=tasks.databaseNow(); UUID id=ids.newId();
        jdbc.sql("""
                INSERT INTO ops.lc_task_deferral(id,task_id,organization_id,requester_user_id,defer_minutes,reason,
                    requested_at,expires_at,basis_digest,state)
                VALUES (:id,:task,:org,:actor,:minutes,:reason,:at,:expires,:basis,'ACTIVE')
                """).param("id",id).param("task",taskId).param("org",context.organization()).param("actor",actor.userId())
                .param("minutes",minutes).param("reason",validReason).param("at",Timestamp.from(now))
                .param("expires",Timestamp.from(now.plusSeconds(Math.multiplyExact((long)minutes,60))))
                .param("basis",basis).update();
        record(taskId,context,"DEFERRED",id,actor.userId(),validReason,now);
        return current(taskId).orElseThrow();
    }

    @Override
    @Transactional(readOnly=true)
    public Optional<View> current(UUID taskId) {
        return at(taskId,tasks.databaseNow());
    }

    @Override
    @Transactional(readOnly=true)
    public Optional<View> at(UUID taskId,Instant asOf) {
        return jdbc.sql("""
                SELECT *,CASE WHEN (state='ACTIVE' OR ended_at>:at) AND expires_at<=:at THEN 'REVIEW_DUE'
                    WHEN ended_at>:at THEN 'ACTIVE' ELSE state END AS view_state,
                    CASE WHEN ended_at<=:at AND EXISTS(SELECT 1 FROM mart.lc_listing_health h
                        WHERE h.id=review_health_id AND h.computed_at<=:at) THEN review_health_id END AS visible_review
                FROM ops.lc_task_deferral WHERE task_id=:id AND requested_at<=:at ORDER BY requested_at DESC,id DESC LIMIT 1
                """).param("id",taskId).param("at",Timestamp.from(asOf)).query((rs,n)->new View(rs.getObject("id",UUID.class),rs.getInt("defer_minutes"),
                        rs.getString("reason"),rs.getTimestamp("requested_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("view_state"),rs.getObject("visible_review",UUID.class))).optional();
    }

    @Override
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public List<ReviewRequest> expireDue(int limit) {
        Instant now=tasks.databaseNow();
        var due=jdbc.sql("""
                SELECT id,task_id,expires_at FROM ops.lc_task_deferral WHERE state='ACTIVE' AND expires_at<=:at
                ORDER BY expires_at,id LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("at",Timestamp.from(now)).param("limit",Math.clamp(limit,1,200))
                .query((rs,n)->new Due(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getTimestamp(3).toInstant())).list();
        return due.stream().map(row->{
            Context context=context(row.task());
            jdbc.sql("UPDATE ops.lc_task_deferral SET state='EXPIRED',ended_at=expires_at WHERE id=:id")
                    .param("id",row.id()).update();
            record(row.task(),context,"REASSESSMENT_REQUIRED",row.id(),null,"Finite deferral expired; qualification must be reviewed",now);
            return new ReviewRequest(row.id(),context.organization(),context.listing(),context.risk(),row.expires());
        }).toList();
    }

    @Override
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void queued(UUID deferralId,UUID queueId) {
        jdbc.sql("UPDATE ops.lc_task_deferral SET review_queue_id=:queue WHERE id=:id AND state='EXPIRED'")
                .param("queue",queueId).param("id",deferralId).update();
    }

    @Override
    @Transactional
    public void reassessed(UUID healthId) {
        var scope=jdbc.sql("SELECT platform_listing_id,computed_at FROM mart.lc_listing_health WHERE id=:id")
                .param("id",healthId).query((rs,n)->new Scope(rs.getObject(1,UUID.class),rs.getTimestamp(2).toInstant())).single();
        var active=jdbc.sql("""
                SELECT d.id,d.task_id,d.expires_at<=:at AS expired FROM ops.lc_task_deferral d
                JOIN ops.lc_task_responsibility b ON b.task_id=d.task_id LEFT JOIN ops.lc_action a ON a.recommendation_id=b.recommendation_id
                WHERE coalesce(b.platform_listing_id,a.platform_listing_id)=:listing AND d.state='ACTIVE'
                  AND (d.expires_at<=:at OR d.basis_digest IS DISTINCT FROM ops.lc_task_reassessment_basis(d.task_id))
                ORDER BY d.id FOR UPDATE OF d
                """).param("listing",scope.listing()).param("at",Timestamp.from(scope.at()))
                .query((rs,n)->new Changed(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getBoolean(3))).list();
        for(var row:active) {
            jdbc.sql("UPDATE ops.lc_task_deferral SET state=:state,ended_at=:at,review_health_id=:health WHERE id=:id")
                    .param("state",row.expired()?"EXPIRED":"INVALIDATED").param("at",Timestamp.from(scope.at()))
                    .param("health",healthId).param("id",row.id()).update();
            record(row.task(),context(row.task()),"REASSESSMENT_REQUIRED",row.id(),null,"Current diagnosis reassessed deferred work",scope.at());
        }
        jdbc.sql("""
                UPDATE ops.lc_task_deferral d SET review_health_id=:health
                FROM ops.lc_task_responsibility b LEFT JOIN ops.lc_action a ON a.recommendation_id=b.recommendation_id
                WHERE b.task_id=d.task_id AND coalesce(b.platform_listing_id,a.platform_listing_id)=:listing
                  AND d.state<>'ACTIVE' AND d.review_health_id IS NULL AND d.ended_at<=:at
                """).param("health",healthId).param("listing",scope.listing()).param("at",Timestamp.from(scope.at())).update();
    }

    private Context context(UUID task) {
        return jdbc.sql("""
                SELECT b.organization_id,coalesce(b.platform_listing_id,a.platform_listing_id),b.source_health_id IS NOT NULL,
                    b.slo_snapshot::text,t.recommendation_id FROM ops.lc_task_responsibility b
                JOIN ops.work_task t ON t.id=b.task_id LEFT JOIN ops.lc_action a ON a.recommendation_id=b.recommendation_id WHERE b.task_id=:id
                """).param("id",task).query((rs,n)->new Context(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        rs.getBoolean(3),rs.getString(4),rs.getObject(5,UUID.class)))
                .optional().orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }
    private void record(UUID task,Context context,String kind,UUID deferral,UUID actor,String reason,Instant at) {
        journal.append(new WorkTaskEventRepository.Event(ids.newId(),task,context.organization(),kind,
                context.risk()?"listing-diagnosis:"+task:"recommendation:"+context.recommendation(),
                null,null,"lc-task-deferral:"+deferral,null,null,null,null,actor,null,reason,at,"lc-task-deferral:"+deferral));
    }
    private record Context(UUID organization,UUID listing,boolean risk,String policy,UUID recommendation) { }
    private record Due(UUID id,UUID task,Instant expires) { }
    private record Scope(UUID listing,Instant at) { }
    private record Changed(UUID id,UUID task,boolean expired) { }
}
