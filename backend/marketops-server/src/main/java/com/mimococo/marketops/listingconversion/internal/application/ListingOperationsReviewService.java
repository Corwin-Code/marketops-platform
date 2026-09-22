package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.listingconversion.EvaluationView;
import com.mimococo.marketops.listingconversion.ListingActionView;
import com.mimococo.marketops.listingconversion.ListingHealthView;
import com.mimococo.marketops.operationsworkflow.ListingTaskSloQuery;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Three operating readings over one exact canonical database snapshot. */
@Service
public class ListingOperationsReviewService {
    private final JdbcClient jdbc;
    private final BusinessAuthorization authorization;
    private final ListingHealthService health;
    private final ListingActionService actions;
    private final EvaluationService evaluations;
    private final ListingTaskSloQuery responsibilities;

    ListingOperationsReviewService(JdbcClient jdbc, BusinessAuthorization authorization,
            ListingHealthService health, ListingActionService actions, EvaluationService evaluations,
            ListingTaskSloQuery responsibilities) {
        this.jdbc=jdbc; this.authorization=authorization; this.health=health; this.actions=actions;
        this.evaluations=evaluations; this.responsibilities=responsibilities;
    }

    public record Bundle(Instant asOf,UUID storeId,String timezone,Reading current,
                         Reading daily,Reading weekly) { }
    public record Reading(String kind,LocalDate periodStart,Instant asOf,List<Row> rows) {
        public Reading { rows=List.copyOf(rows); }
    }
    public record Row(String lane,ListingHealthView health,List<Task> responsibilities,
                      List<Action> actions,RecalculationReceipt recalculation) {
        public Row {
            responsibilities=List.copyOf(responsibilities);
            actions=List.copyOf(actions);
        }
    }
    public record Task(UUID taskId,String causeCode,String lane,String taskState,
                       ListingTaskSloQuery.Status status) { }
    public record Action(ListingActionView action,ListingTaskSloQuery.Status responsibility,
                         EvaluationView evaluation,Command command) { }
    public record Command(UUID id,String state,String failureCode,Instant updatedAt) { }
    public record RecalculationReceipt(UUID id,String triggerClass,int targetMinutes,String state,
            Instant sourceTime,Instant acceptedAt,Instant startedAt,Instant finishedAt,
            UUID healthResultId,List<UUID> measurementResultIds,Integer bindingAssessedCount,
            Integer bindingInvalidatedCount,String failureCode) {
        public RecalculationReceipt { measurementResultIds=List.copyOf(measurementResultIds); }
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Bundle read(AuthenticatedActor actor,UUID storeId,int limit) {
        if (limit<1 || limit>200) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        String timezone=jdbc.sql("""
                SELECT coalesce(s.timezone,o.default_timezone,'UTC')
                  FROM core.store s JOIN core.organization o ON o.id=s.organization_id
                 WHERE s.id=:store AND s.organization_id=:org
                """).param("store",storeId).param("org",actor.organizationId()).query(String.class).optional()
                .orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED));
        authorization.require(actor,ActionScopeCode.LISTING_CONVERSION_VIEW,ResourceScope.store(storeId));
        ZoneId zone;
        try { zone=ZoneId.of(timezone); }
        catch (java.time.DateTimeException invalid) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Instant asOf=jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
        List<ListingHealthView> healthRows=health.queue(actor.organizationId(),List.of(storeId),null,limit);
        Map<UUID,List<ListingActionView>> actionRows=new HashMap<>();
        for (ListingActionView action:actions.actions(actor.organizationId(),List.of(storeId),null,limit))
            actionRows.computeIfAbsent(action.platformListingId(),ignored->new ArrayList<>()).add(action);

        List<Row> rows=new ArrayList<>();
        for (ListingHealthView healthRow:healthRows) {
            List<Task> tasks=tasks(healthRow.platformListingId(),asOf);
            List<Action> actionViews=new ArrayList<>();
            for (ListingActionView action:actionRows.getOrDefault(healthRow.platformListingId(),List.of())) {
                ListingTaskSloQuery.Status responsibility=responsibilities
                        .statusForRecommendation(action.recommendationId(),asOf).orElse(null);
                EvaluationView evaluation=evaluations.view(actor,action.id()).orElse(null);
                actionViews.add(new Action(action,responsibility,evaluation,command(action.id())));
            }
            String lane=lane(healthRow,tasks,actionViews);
            rows.add(new Row(lane,healthRow,tasks,actionViews,recalculation(healthRow.platformListingId())));
        }
        rows.sort(Comparator.comparingInt((Row row)->laneRank(row.lane()))
                .thenComparing(row->earliestDue(row.responsibilities()),Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row->row.health().computedAt(),Comparator.reverseOrder())
                .thenComparing(row->row.health().platformListingId()));
        List<Row> frozen=List.copyOf(rows);
        LocalDate date=asOf.atZone(zone).toLocalDate();
        Reading current=new Reading("CURRENT_QUEUE",date,asOf,frozen);
        Reading daily=new Reading("DAILY_ACTION_BRIEF",date,asOf,frozen);
        Reading weekly=new Reading("WEEKLY_EVIDENCE_REVIEW",
                date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),asOf,frozen);
        return new Bundle(asOf,storeId,timezone,current,daily,weekly);
    }

    private List<Task> tasks(UUID listingId,Instant asOf) {
        record Bound(UUID taskId,String causeCode,String lane,String state) { }
        List<Bound> bound=jdbc.sql("""
                SELECT r.task_id,r.cause_code,r.responsibility_lane,t.state
                  FROM ops.lc_task_responsibility r JOIN ops.work_task t ON t.id=r.task_id
                 WHERE r.platform_listing_id=:listing AND t.state NOT IN ('DONE','CANCELLED')
                 ORDER BY CASE r.responsibility_lane WHEN 'NECESSARY_RISK' THEN 0 WHEN 'ACTION' THEN 1 ELSE 2 END,
                          r.first_raised_at,r.cause_code,r.task_id
                """).param("listing",listingId).query((rs,n)->new Bound(rs.getObject("task_id",UUID.class),
                        rs.getString("cause_code"),rs.getString("responsibility_lane"),rs.getString("state"))).list();
        return bound.stream().map(row->new Task(row.taskId(),row.causeCode(),row.lane(),row.state(),
                responsibilities.statusForTask(row.taskId(),asOf).orElseThrow())).toList();
    }

    private Command command(UUID actionId) {
        return jdbc.sql("""
                SELECT id,state,failure_code,updated_at FROM ops.lc_description_command
                 WHERE action_id=:action
                """).param("action",actionId).query((rs,n)->new Command(rs.getObject("id",UUID.class),
                        rs.getString("state"),rs.getString("failure_code"),rs.getTimestamp("updated_at").toInstant()))
                .optional().orElse(null);
    }

    private RecalculationReceipt recalculation(UUID listingId) {
        return jdbc.sql("""
                SELECT id,trigger_class,target_minutes,state,source_time,accepted_at,started_at,finished_at,
                       health_result_id,measurement_result_ids,binding_assessed_count,binding_invalidated_count,failure_code
                  FROM ops.lc_recalculation_queue WHERE platform_listing_id=:listing
                 ORDER BY accepted_at DESC,id DESC LIMIT 1
                """).param("listing",listingId).query((rs,n)->new RecalculationReceipt(
                        rs.getObject("id",UUID.class),rs.getString("trigger_class"),rs.getInt("target_minutes"),
                        rs.getString("state"),instant(rs,"source_time"),instant(rs,"accepted_at"),
                        instant(rs,"started_at"),instant(rs,"finished_at"),rs.getObject("health_result_id",UUID.class),
                        rs.getArray("measurement_result_ids")==null?List.of():
                                List.of((UUID[])rs.getArray("measurement_result_ids").getArray()),
                        rs.getObject("binding_assessed_count",Integer.class),
                        rs.getObject("binding_invalidated_count",Integer.class),rs.getString("failure_code")))
                .optional().orElse(null);
    }

    private static String lane(ListingHealthView health,List<Task> tasks,List<Action> actions) {
        if ("FAIL".equals(health.necessaryState()) || tasks.stream().anyMatch(t->"NECESSARY_RISK".equals(t.lane())))
            return "NECESSARY_RISK";
        if (actions.stream().anyMatch(a->!List.of("CLOSED","CANCELLED").contains(a.action().state().name())))
            return "ACTION";
        if (tasks.stream().anyMatch(t->"QUALIFIED_OPPORTUNITY".equals(t.lane()))) return "QUALIFIED_OPPORTUNITY";
        return "WATCH";
    }
    private static int laneRank(String lane) {
        return switch (lane) { case "NECESSARY_RISK"->0; case "ACTION"->1;
            case "QUALIFIED_OPPORTUNITY"->2; default->3; };
    }
    private static Instant earliestDue(List<Task> tasks) {
        return tasks.stream().map(Task::status).map(ListingTaskSloQuery.Status::actionDueAt)
                .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    }
    private static Instant instant(java.sql.ResultSet rs,String column) throws java.sql.SQLException {
        Timestamp value=rs.getTimestamp(column); return value==null?null:value.toInstant();
    }
}
