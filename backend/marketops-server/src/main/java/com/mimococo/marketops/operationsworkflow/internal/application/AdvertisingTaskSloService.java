package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.operationsworkflow.internal.domain.StaffedResponseClock;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Computes both response stages from frozen coverage and attributable Task events. */
@Service
public class AdvertisingTaskSloService implements com.mimococo.marketops.operationsworkflow.AdvertisingTaskSloQuery {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    AdvertisingTaskSloService(JdbcClient jdbc,ObjectMapper json,Clock clock) { this.jdbc=jdbc;this.json=json;this.clock=clock; }

    @Override
    public java.util.Optional<Status> statusForCase(UUID caseId) {
        return statusForCase(caseId, clock.instant());
    }

    @Override
    public java.util.Optional<Status> statusForCase(UUID caseId, Instant asOf) {
        return jdbc.sql("SELECT task_id FROM ops.ad_case_responsibility WHERE case_id=:id AND first_raised_at<=:at AND recorded_at<=:at")
                .param("id",caseId).param("at",Timestamp.from(asOf)).query(UUID.class).optional().map(id -> status(id, asOf));
    }

    public Status status(UUID taskId) { return status(taskId, clock.instant()); }

    public Status status(UUID taskId, Instant now) {
        var row=jdbc.sql("""
                SELECT r.case_id,r.first_raised_at,r.profile_snapshot::text,c.lane FROM ops.ad_case_responsibility r
                JOIN mart.ad_case c ON c.id=r.case_id WHERE r.task_id=:id
                """).param("id",taskId).query((rs,n)->new Binding(rs.getObject("case_id",UUID.class),
                        rs.getTimestamp("first_raised_at").toInstant(),rs.getString("profile_snapshot"),rs.getString("lane"))).optional();
        if(row.isEmpty()) return null;
        var snapshot=json.readTree(row.get().snapshot());
        while(snapshot.path("resolvedAt").isTextual() && snapshot.path("unresolvedOriginal").isObject()
                && java.time.OffsetDateTime.parse(snapshot.path("resolvedAt").asText()).toInstant().isAfter(now)) {
            snapshot=snapshot.path("unresolvedOriginal");
        }
        var profile=snapshot.path("slo");var calendar=snapshot.path("calendar");
        if(!profile.path("staffed").asBoolean(false) || !profile.path("timezone").isTextual()
                || !calendar.path("days").isArray() || !calendar.path("timezone").asText().equals(profile.path("timezone").asText())) {
            return new Status("PROFILE_OR_CALENDAR_MISSING",null,null,null,null,null,null,
                    java.time.Duration.between(row.get().raisedAt(),now).toSeconds(),false,false,false);
        }
        Set<Integer> days=StreamSupport.stream(calendar.path("days").spliterator(),false)
                .map(value->value.asInt()).collect(Collectors.toSet());
        var coverage=new StaffedResponseClock.Coverage(ZoneId.of(profile.path("timezone").asText()),days,
                profile.path("start").asInt(),profile.path("end").asInt());
        List<StaffedResponseClock.Pause> pauses=jdbc.sql("""
                SELECT greatest(x.effective_from,x.approved_at) AS pause_from,
                       least(x.expires_at,x.review_due_at,coalesce(x.ended_at,x.expires_at),x.authority_valid_until,authority.changed_at) AS pause_until
                FROM ops.ad_accepted_exception x
                LEFT JOIN LATERAL(SELECT min(changed_at) changed_at FROM ops.ad_exception_authority_change
                    WHERE exception_id=x.id AND changed_at<=:at) authority ON true
                WHERE x.case_id=:case AND x.approved_at IS NOT NULL AND x.approved_at<=:at
                  AND least(x.expires_at,x.review_due_at,coalesce(x.ended_at,x.expires_at),x.authority_valid_until,authority.changed_at)>greatest(x.effective_from,x.approved_at)
                ORDER BY x.approved_at,x.id
                """).param("case",row.get().caseId()).param("at",Timestamp.from(now)).query((rs,n)->new StaffedResponseClock.Pause(
                        rs.getTimestamp("pause_from").toInstant(),rs.getTimestamp("pause_until").toInstant())).list();
        Instant ackDue=StaffedResponseClock.deadline(row.get().raisedAt(),profile.path("ackMinutes").asInt(),coverage);
        Instant actionDue=StaffedResponseClock.deadline(row.get().raisedAt(),profile.path("actionMinutes").asInt(),coverage,pauses);
        Instant escalationDue=StaffedResponseClock.deadline(row.get().raisedAt(),profile.path("escalationMinutes").asInt(),coverage,pauses);
        var events=jdbc.sql("""
                WITH epoch AS (SELECT max(occurred_at) AS start_at FROM ops.work_task_event
                    WHERE task_id=:task AND occurred_at<=:at AND event_kind IN('RAISED','REOPENED'))
                SELECT min(e.occurred_at) FILTER(WHERE e.event_kind='ACKNOWLEDGED') AS acknowledged,
                    min(e.occurred_at) FILTER(WHERE e.event_kind='ACTION_RECORDED') AS acted
                FROM ops.work_task_event e CROSS JOIN epoch WHERE e.task_id=:task
                    AND e.occurred_at>=epoch.start_at AND e.occurred_at<=:at
                """).param("task",taskId).param("at",Timestamp.from(now)).query((rs,n)->new Events(instant(rs,"acknowledged"),instant(rs,"acted"))).single();
        boolean paused=pauses.stream().anyMatch(pause->!now.isBefore(pause.from()) && now.isBefore(pause.until()));
        return new Status(paused?"ACCEPTED_EXCEPTION_ACTIVE":coverage.contains(now)?"IN_COVERAGE":"PROTECTION".equals(row.get().lane())?"OUT_OF_COVERAGE_ACTIVE_HARM":"OUT_OF_COVERAGE",
                ackDue,actionDue,escalationDue,StaffedResponseClock.nextStaffed(now,coverage),events.ack(),events.action(),
                java.time.Duration.between(row.get().raisedAt(),now).toSeconds(),
                events.ack()==null?now.isAfter(ackDue):events.ack().isAfter(ackDue),
                events.action()==null?!paused && now.isAfter(actionDue):events.action().isAfter(actionDue),paused);
    }

    private static Instant instant(java.sql.ResultSet rs,String column) throws java.sql.SQLException {
        Timestamp value=rs.getTimestamp(column);return value==null?null:value.toInstant();
    }
    private record Binding(UUID caseId,Instant raisedAt,String snapshot,String lane) { }
    private record Events(Instant ack,Instant action) { }
}
