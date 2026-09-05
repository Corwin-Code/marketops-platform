package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.operationsworkflow.AdvertisingOutcomeReviewIntake;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskEventRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A Finance review supplements the original advertising responsibility and preserves its age. */
@Service
class AdvertisingOutcomeReviewService implements AdvertisingOutcomeReviewIntake {
    private final JdbcClient jdbc;
    private final WorkTaskRepository tasks;
    private final WorkTaskEventRepository journal;
    private final IdGenerator ids;
    private final Clock clock;
    AdvertisingOutcomeReviewService(JdbcClient jdbc,WorkTaskRepository tasks,WorkTaskEventRepository journal,
            IdGenerator ids,Clock clock) {
        this.jdbc=jdbc;this.tasks=tasks;this.journal=journal;this.ids=ids;this.clock=clock;
    }

    @Override @Transactional
    public UUID record(UUID observationId) {
        var found=jdbc.sql("SELECT * FROM ops.ad_settled_review_context(:id)").param("id",observationId)
                .query((rs,n)->new Context(rs.getObject("organization_id",UUID.class),rs.getObject("case_id",UUID.class),
                        rs.getObject("outcome_baseline_id",UUID.class),rs.getObject("action_id",UUID.class),
                        rs.getString("action_kind"),rs.getObject("primary_task_id",UUID.class),
                        rs.getObject("recommendation_id",UUID.class),rs.getTimestamp("observed_at").toInstant(),
                        rs.getString("reason_code"),rs.getString("correlation_id"))).optional();
        if(found.isEmpty()) return null;
        Context context=found.get();
        jdbc.sql("SELECT id FROM ops.work_task WHERE id=:id FOR UPDATE").param("id",context.primaryTask()).query(UUID.class).single();
        var existing=jdbc.sql("""
            SELECT task_id FROM ops.ad_outcome_review_responsibility
            WHERE action_kind=:kind AND action_id=:action AND required_role_code='FINANCE_ANALYST'
            """).param("kind",context.actionKind()).param("action",context.action()).query(UUID.class).optional();
        UUID taskId=existing.orElseGet(ids::newId);
        if(existing.isEmpty()) {
            var primary=tasks.find(context.primaryTask()).orElseThrow();
            tasks.insert(taskId,context.organization(),context.recommendation(),
                    "Finance review of the contradictory Settled advertising outcome",primary.dueAt(),context.observedAt());
            jdbc.sql("""
                INSERT INTO ops.ad_outcome_review_responsibility(task_id,organization_id,case_id,primary_task_id,
                    outcome_baseline_id,action_id,action_kind,first_observation_id,first_raised_at)
                VALUES(:task,:org,:case,:primary,:baseline,:action,:kind,:observation,:raised)
                """).param("task",taskId).param("org",context.organization()).param("case",context.caseId())
                    .param("primary",context.primaryTask()).param("baseline",context.baseline()).param("action",context.action())
                    .param("kind",context.actionKind()).param("observation",observationId)
                    .param("raised",Timestamp.from(context.observedAt())).update();
            event(taskId,context,"RAISED","FINANCE_ANALYST",context.observedAt());
        }
        int added=jdbc.sql("""
            INSERT INTO ops.ad_outcome_review_observation(task_id,observation_id,recorded_at)
            VALUES(:task,:observation,:at) ON CONFLICT DO NOTHING
            """).param("task",taskId).param("observation",observationId).param("at",Timestamp.from(clock.instant())).update();
        if(added==0) return taskId;
        var review=tasks.find(taskId).orElseThrow();
        if(java.util.List.of("DONE","CANCELLED").contains(review.state())) {
            tasks.reopen(taskId,clock.instant(),review.version());
            event(taskId,context,"REOPENED","FINANCE_ANALYST",clock.instant());
        } else if(existing.isPresent()) event(taskId,context,"ESCALATED","FINANCE_ANALYST",clock.instant());
        journal.append(new WorkTaskEventRepository.Event(ids.newId(),taskId,context.organization(),"OUTCOME_OBSERVED",
                "advertising-case:"+context.caseId(),null,null,null,"SETTLED","ad-outcome:"+observationId,
                null,null,null,null,context.reason()+": canonical evidence requires Finance and advertising review",clock.instant(),context.correlation()));
        var primary=tasks.find(context.primaryTask()).orElseThrow();
        if(java.util.List.of("DONE","CANCELLED").contains(primary.state())) {
            tasks.reopen(primary.id(),clock.instant(),primary.version());
            event(primary.id(),context,"REOPENED",null,clock.instant());
        } else event(primary.id(),context,"ESCALATED",null,clock.instant());
        return taskId;
    }

    private void event(UUID task,Context context,String kind,String role,Instant at) {
        journal.append(new WorkTaskEventRepository.Event(ids.newId(),task,context.organization(),kind,
                "advertising-case:"+context.caseId(),null,null,null,null,null,null,null,null,role,
                "Canonical Settled contradiction retains this action lineage and its original responsibility",at,context.correlation()));
    }
    private record Context(UUID organization,UUID caseId,UUID baseline,UUID action,String actionKind,
                           UUID primaryTask,UUID recommendation,Instant observedAt,String reason,String correlation) { }
}
