package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.operationsworkflow.ListingResponsibilityBasis;
import com.mimococo.marketops.operationsworkflow.ListingTaskSloQuery;
import com.mimococo.marketops.operationsworkflow.internal.domain.ListingResponsibilitySchedule;
import com.mimococo.marketops.operationsworkflow.internal.domain.StaffedResponseClock;
import com.mimococo.marketops.shared.Digest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Original ordinary-work clocks and two separate stages of attributable Task events. */
@Service
public class ListingTaskSloService implements ListingTaskSloQuery {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final com.mimococo.marketops.operationsworkflow.ListingTaskDeferralIntake deferrals;

    ListingTaskSloService(JdbcClient jdbc, ObjectMapper json,
            com.mimococo.marketops.operationsworkflow.ListingTaskDeferralIntake deferrals) {
        this.jdbc=jdbc; this.json=json; this.deferrals=deferrals;
    }

    void lockRecommendation(UUID organization, UUID recommendation) {
        jdbc.sql("SELECT id FROM ops.recommendation WHERE id=:id AND organization_id=:org FOR UPDATE")
                .param("id",recommendation).param("org",organization).query(UUID.class).single();
    }

    /** Caller-labelled journal input is not a Listing business disposition. */
    void requireBusinessActionProducer(UUID taskId) {
        boolean listing=jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM ops.work_task t JOIN ops.recommendation r ON r.id=t.recommendation_id
                  WHERE t.id=:task AND r.action_kind IN ('LISTING_DESCRIPTION_CHANGE','LISTING_PROMOTION_ACTION'))
                  OR EXISTS(SELECT 1 FROM ops.lc_task_responsibility b WHERE b.task_id=:task AND b.source_health_id IS NOT NULL)
                """).param("task",taskId).query(Boolean.class).single();
        if (listing) throw com.mimococo.marketops.shared.OperationRejectedException.of(
                com.mimococo.marketops.shared.ErrorCode.ACTION_NOT_PERMITTED);
    }

    /** A view, acknowledgement or old pre-reopen action cannot complete current work. */
    void requireQualifiedDisposition(UUID taskId) {
        jdbc.sql("SELECT id FROM ops.work_task WHERE id=:id FOR UPDATE").param("id",taskId).query(UUID.class).single();
        boolean missing=jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM ops.work_task t JOIN ops.recommendation r ON r.id=t.recommendation_id
                  WHERE t.id=:task AND r.action_kind IN ('LISTING_DESCRIPTION_CHANGE','LISTING_PROMOTION_ACTION')
                    AND r.state NOT IN ('CANCELLED','REJECTED') AND NOT EXISTS (
                      SELECT 1 FROM ops.work_task_event e WHERE e.task_id=t.id AND e.event_kind='ACTION_RECORDED'
                        AND e.occurred_at<=clock_timestamp() AND e.sequence_no>coalesce((
                          SELECT max(reopened.sequence_no) FROM ops.work_task_event reopened
                            WHERE reopened.task_id=t.id AND reopened.event_kind='REOPENED'),0)))
                """).param("task",taskId).query(Boolean.class).single();
        if (missing) throw com.mimococo.marketops.shared.OperationRejectedException.of(
                com.mimococo.marketops.shared.ErrorCode.ACTION_NOT_PERMITTED);
        boolean unresolved=jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM ops.lc_task_responsibility b WHERE b.task_id=:task AND b.source_health_id IS NOT NULL
                  AND NOT EXISTS(SELECT 1 FROM mart.lc_listing_health h
                    WHERE h.id=(SELECT current.id FROM mart.lc_listing_health current
                      WHERE current.platform_listing_id=b.platform_listing_id ORDER BY current.health_version DESC LIMIT 1)
                      AND ((b.responsibility_lane='NECESSARY_RISK' AND EXISTS(
                          SELECT 1 FROM jsonb_array_elements(h.necessary_conditions) c
                          WHERE c->>'code'=b.cause_code AND c->>'state'='PASS'))
                        OR (b.responsibility_lane='QUALIFIED_OPPORTUNITY' AND NOT (
                          h.necessary_state='PASS' AND h.eligibility->>'EVALUATION'='ELIGIBLE'
                          AND EXISTS(SELECT 1 FROM jsonb_array_elements(h.opportunities) o
                            WHERE o->>'code'=b.cause_code))))))
                """).param("task",taskId).query(Boolean.class).single();
        if (unresolved) throw com.mimococo.marketops.shared.OperationRejectedException.of(
                com.mimococo.marketops.shared.ErrorCode.ACTION_NOT_PERMITTED);
    }

    public ListingResponsibilitySchedule.Schedule schedule(Instant raisedAt, ListingResponsibilityBasis basis) {
        return ListingResponsibilitySchedule.resolve(raisedAt, basis.slo(), basis.coverage());
    }

    public void bind(UUID taskId, UUID organization, UUID recommendation, Instant raisedAt,
                     ListingResponsibilityBasis basis, ListingResponsibilitySchedule.Schedule schedule) {
        bind(taskId,organization,recommendation,raisedAt,basis,schedule,null,null,null);
    }

    void bind(UUID taskId, UUID organization, UUID recommendation, Instant raisedAt,
              ListingResponsibilityBasis basis, ListingResponsibilitySchedule.Schedule schedule,
              UUID healthId, UUID listingId, String cause) {
        JsonNode slo = object(basis.slo()), coverage = object(basis.coverage());
        String sloText=json.writeValueAsString(slo), coverageText=json.writeValueAsString(coverage);
        String digest=Digest.ofComponents(List.of("LC_TASK_RESPONSIBILITY_1",taskId.toString(),String.valueOf(recommendation),
                String.valueOf(basis.calibrationPackageId()),String.valueOf(basis.calibrationVersion()),
                raisedAt.toString(),sloText,coverageText));
        jdbc.sql("""
                INSERT INTO ops.lc_task_responsibility(task_id,organization_id,recommendation_id,
                    calibration_package_id,calibration_version,first_raised_at,slo_snapshot,coverage_snapshot,
                    basis_digest,clock_state,acknowledgement_due_at,action_due_at,outcome_maturity_due_at,recorded_at,
                    source_health_id,platform_listing_id,cause_code)
                VALUES (:task,:org,:recommendation,:package,:version,:raised,CAST(:slo AS jsonb),CAST(:coverage AS jsonb),
                    :digest,:state,:ack,:action,:outcome,clock_timestamp(),:health,:listing,:cause)
                """).param("task",taskId).param("org",organization).param("recommendation",recommendation)
                .param("package",basis.calibrationPackageId()).param("version",basis.calibrationVersion())
                .param("raised",Timestamp.from(raisedAt)).param("slo",sloText).param("coverage",coverageText)
                .param("digest",digest).param("state",schedule.state()).param("ack",timestamp(schedule.acknowledgementDueAt()))
                .param("action",timestamp(schedule.actionDueAt())).param("outcome",timestamp(schedule.outcomeMaturityDueAt()))
                .param("health",healthId).param("listing",listingId).param("cause",cause).update();
    }

    @Override
    @Transactional(readOnly=true)
    public Optional<Status> statusForRecommendation(UUID recommendationId) {
        Instant now=jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
        return statusForRecommendation(recommendationId,now);
    }

    @Override
    @Transactional(readOnly=true)
    public Optional<Status> statusForRecommendation(UUID recommendationId, Instant asOf) {
        return status(recommendationId,null,asOf);
    }

    @Override
    @Transactional(readOnly=true)
    public Optional<Status> statusForTask(UUID taskId, Instant asOf) {
        return status(null,taskId,asOf);
    }

    @Override
    @Transactional(readOnly=true)
    public List<DiagnosticStatus> diagnosticsForListing(UUID listingId) {
        Instant now=jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
        return jdbc.sql("SELECT task_id,cause_code FROM ops.lc_task_responsibility WHERE platform_listing_id=:id ORDER BY first_raised_at,cause_code,task_id")
                .param("id",listingId).query((rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2)}).list().stream()
                .map(row->new DiagnosticStatus((String)row[1],status(null,(UUID)row[0],now).orElseThrow())).toList();
    }

    private Optional<Status> status(UUID recommendationId, UUID taskId, Instant asOf) {
        return jdbc.sql("""
                SELECT r.*,r.slo_snapshot::text AS slo,r.coverage_snapshot::text AS coverage,
                    (SELECT min(e.occurred_at) FROM ops.work_task_event e WHERE e.task_id=r.task_id
                       AND e.event_kind='ACKNOWLEDGED' AND e.occurred_at>=r.first_raised_at AND e.occurred_at<=:at
                       AND e.sequence_no>coalesce((SELECT max(x.sequence_no) FROM ops.work_task_event x
                         WHERE x.task_id=r.task_id AND x.event_kind='REOPENED' AND x.occurred_at<=:at),0)) AS acknowledged,
                    (SELECT min(e.occurred_at) FROM ops.work_task_event e WHERE e.task_id=r.task_id
                       AND e.event_kind='ACTION_RECORDED' AND e.occurred_at>=r.first_raised_at AND e.occurred_at<=:at
                       AND e.sequence_no>coalesce((SELECT max(x.sequence_no) FROM ops.work_task_event x
                         WHERE x.task_id=r.task_id AND x.event_kind='REOPENED' AND x.occurred_at<=:at),0)) AS acted
                FROM ops.lc_task_responsibility r WHERE (r.recommendation_id=:recommendation OR r.task_id=:task)
                  AND r.first_raised_at<=:at AND r.recorded_at<=:at
                """).param("recommendation",recommendationId).param("task",taskId).param("at",Timestamp.from(asOf)).query((rs,n)->{
                    Instant raised=instant(rs,"first_raised_at"), ackDue=instant(rs,"acknowledgement_due_at"),
                            originalActionDue=instant(rs,"action_due_at"), acknowledged=instant(rs,"acknowledged"), acted=instant(rs,"acted");
                    String state=rs.getString("clock_state");
                    Instant next=null;
                    var holds=dependencyHolds(rs.getObject("task_id",UUID.class),asOf);
                    Instant actionDue=effectiveActionDue(raised,originalActionDue,state,rs.getString("slo"),
                            rs.getString("coverage"),holds.projected());
                    if ("COVERAGE_CONFIGURED".equals(state)) {
                        var coverage=ListingResponsibilitySchedule.coverage(json.readTree(rs.getString("coverage")));
                        state=coverage.contains(asOf)?"IN_COVERAGE":"OUT_OF_COVERAGE";
                        next=StaffedResponseClock.nextStaffed(asOf,coverage);
                    }
                    return new Status(rs.getObject("task_id",UUID.class),rs.getObject("calibration_package_id",UUID.class),
                            rs.getObject("calibration_version",Integer.class),rs.getString("basis_digest"),state,raised,
                            ackDue,originalActionDue,actionDue,instant(rs,"outcome_maturity_due_at"),next,acknowledged,acted,
                            breached(ackDue,acknowledged,asOf),breached(actionDue,acted,asOf),
                            Math.max(0,Duration.between(raised,asOf).getSeconds()),
                            holds.elapsedSeconds(),deferrals.at(rs.getObject("task_id",UUID.class),asOf).orElse(null),
                            holds.current());
                }).optional();
    }

    private Instant effectiveActionDue(Instant raised,Instant original,String state,String sloText,String coverageText,
                                       List<StaffedResponseClock.Pause> pauses) {
        if (original==null || pauses.isEmpty()) return original;
        if ("CONTINUOUS_RISK".equals(state)) {
            long seconds=pauses.stream().mapToLong(p->Duration.between(p.from(),p.until()).getSeconds()).sum();
            return original.plusSeconds(seconds);
        }
        if (!"COVERAGE_CONFIGURED".equals(state)) return original;
        JsonNode slo=json.readTree(sloText);
        JsonNode minutes=slo.path("actionMinutes");
        if (!minutes.isIntegralNumber() || !minutes.canConvertToInt() || minutes.intValue()<1) return original;
        return StaffedResponseClock.deadline(raised,minutes.intValue(),
                ListingResponsibilitySchedule.coverage(json.readTree(coverageText)),pauses);
    }

    private Holds dependencyHolds(UUID taskId,Instant asOf) {
        var rows=jdbc.sql("""
                SELECT id,dependency_task_id,hold_minutes,evidence_reference,started_at,expires_at,state,ended_at,end_reason
                FROM ops.lc_task_dependency_hold WHERE task_id=:task AND started_at<=:at
                ORDER BY started_at,id
                """).param("task",taskId).param("at",Timestamp.from(asOf)).query((rs,n)->new HoldRow(
                        rs.getObject("id",UUID.class),rs.getObject("dependency_task_id",UUID.class),rs.getInt("hold_minutes"),
                        rs.getString("evidence_reference"),rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),rs.getString("state"),instant(rs,"ended_at"),
                        rs.getString("end_reason"))).list();
        List<StaffedResponseClock.Pause> projected=new java.util.ArrayList<>();
        long elapsed=0; com.mimococo.marketops.operationsworkflow.ListingTaskDependencyHold.View current=null;
        for (HoldRow row:rows) {
            boolean historicallyActive=row.endedAt()==null || row.endedAt().isAfter(asOf);
            Instant projectedUntil=historicallyActive?row.expiresAt():row.endedAt();
            projected.add(new StaffedResponseClock.Pause(row.startedAt(),projectedUntil));
            Instant elapsedUntil=projectedUntil.isBefore(asOf)?projectedUntil:asOf;
            if (elapsedUntil.isAfter(row.startedAt())) elapsed+=Duration.between(row.startedAt(),elapsedUntil).getSeconds();
            String viewState=historicallyActive?(row.expiresAt().isAfter(asOf)?"ACTIVE":"EXPIRED"):row.state();
            Instant viewEnded="ACTIVE".equals(viewState)?null:(historicallyActive?row.expiresAt():row.endedAt());
            String reason="EXPIRED".equals(viewState)&&historicallyActive?"finite dependency hold expired":row.endReason();
            current=new com.mimococo.marketops.operationsworkflow.ListingTaskDependencyHold.View(row.id(),
                    row.dependencyTaskId(),row.minutes(),row.evidenceReference(),row.startedAt(),row.expiresAt(),
                    viewState,viewEnded,reason);
        }
        return new Holds(List.copyOf(projected),elapsed,current);
    }

    private record HoldRow(UUID id,UUID dependencyTaskId,int minutes,String evidenceReference,Instant startedAt,
                           Instant expiresAt,String state,Instant endedAt,String endReason) { }
    private record Holds(List<StaffedResponseClock.Pause> projected,long elapsedSeconds,
                         com.mimococo.marketops.operationsworkflow.ListingTaskDependencyHold.View current) { }

    private JsonNode object(JsonNode value) { return value!=null && value.isObject()?value:json.createObjectNode(); }
    private static Boolean breached(Instant due, Instant completed, Instant at) {
        return due==null?null:(completed==null?at:completed).isAfter(due);
    }
    private static Timestamp timestamp(Instant at) { return at==null?null:Timestamp.from(at); }
    private static Instant instant(java.sql.ResultSet rs,String column) throws java.sql.SQLException {
        Timestamp value=rs.getTimestamp(column); return value==null?null:value.toInstant();
    }
}
