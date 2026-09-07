package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.internal.domain.StaffedResponseClock;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.RecommendationRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskEventRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** The same Case retains one responsibility Task across recalculation and reassignment. */
@Service
class AdvertisingResponsibilityService implements AdvertisingResponsibilityIntake {
    private final JdbcClient jdbc;
    private final RecommendationRepository recommendations;
    private final WorkTaskRepository tasks;
    private final WorkTaskEventRepository journal;
    private final IdGenerator ids;
    private final Clock clock;
    private final ObjectMapper json;
    private final AdvertisingExceptionService exceptions;
    private final AdvertisingTaskSloMonitor sloMonitor;

    AdvertisingResponsibilityService(JdbcClient jdbc, RecommendationRepository recommendations,
            WorkTaskRepository tasks, WorkTaskEventRepository journal, IdGenerator ids,
            Clock clock, ObjectMapper json, AdvertisingExceptionService exceptions,AdvertisingTaskSloMonitor sloMonitor) {
        this.jdbc = jdbc;
        this.recommendations = recommendations;
        this.tasks = tasks;
        this.journal = journal;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
        this.exceptions = exceptions;
        this.sloMonitor=sloMonitor;
    }

    @Override
    @Transactional
    public void synchronizeObject(UUID organizationId, UUID adNativeObjectId) {
        jdbc.sql("SELECT id FROM mart.ad_case WHERE organization_id=:org AND ad_native_object_id=:object")
                .param("org",organizationId).param("object",adNativeObjectId).query(UUID.class).list()
                .forEach(caseId->{exceptions.refreshInvalidation(caseId);sloMonitor.inspect(caseId);});
    }

    @Override
    @Transactional
    public UUID ensureResponsibility(UUID caseId, UUID calculationRunId, String accountableRoleCode) {
        CaseRow kase = jdbc.sql("""
                SELECT id,organization_id,store_id,ad_native_object_id,lane,cause_code,
                       calculation_id,created_at,policy_version_digest,platform_code
                  FROM mart.ad_case WHERE id=:id FOR UPDATE
                """).param("id", caseId).query((rs, n) -> new CaseRow(rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("store_id", UUID.class),
                        rs.getObject("ad_native_object_id", UUID.class), rs.getString("lane"),
                        rs.getString("cause_code"), rs.getObject("calculation_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("policy_version_digest"),
                        rs.getString("platform_code"))).single();
        if ("WATCH".equals(kase.lane()) || accountableRoleCode == null) return null;
        var existing = jdbc.sql("SELECT task_id FROM ops.ad_case_responsibility WHERE case_id=:id")
                .param("id", caseId).query(UUID.class).optional();
        if (existing.isPresent()) {
            var task = tasks.find(existing.get()).orElseThrow();
            if (List.of("DONE","CANCELLED").contains(task.state()) && !exceptions.hasActive(caseId)) {
                tasks.reopen(task.id(),clock.instant(),task.version());
                journal.append(new WorkTaskEventRepository.Event(ids.newId(),task.id(),kase.organizationId(),
                        "REOPENED","advertising-case:"+caseId,null,null,null,null,null,null,null,null,null,
                        "The same canonical cause recurred",clock.instant(),"advertising-case-reopened:"+caseId));
            }
            boolean resolved=jdbc.sql("SELECT coverage_state NOT IN('PROFILE_MISSING','CALENDAR_MISSING') FROM ops.ad_case_responsibility WHERE case_id=:id")
                    .param("id",caseId).query(Boolean.class).single();
            if(resolved) { sloMonitor.inspect(caseId);return existing.get(); }
        }

        Instant now = clock.instant();
        List<Slo> profiles = jdbc.sql("""
                SELECT * FROM core.ad_human_slo_profile
                WHERE organization_id=:org AND lane=:lane AND status='ACTIVE'
                  AND effective_from<=:at AND (effective_to IS NULL OR effective_to>:at)
                LIMIT 2
                """).param("org", kase.organizationId()).param("lane", kase.lane())
                .param("at", Timestamp.from(now)).query((rs, n) -> new Slo(
                        rs.getObject("id", UUID.class), rs.getInt("policy_version"),
                        rs.getInt("acknowledgement_minutes"), rs.getInt("action_minutes"),
                        rs.getInt("escalation_minutes"), rs.getBoolean("staffed_coverage_enabled"),
                        rs.getString("staffed_coverage_timezone"),
                        rs.getObject("staffed_coverage_start_minute", Integer.class),
                        rs.getObject("staffed_coverage_end_minute", Integer.class))).list();
        Slo profile = profiles.size() == 1 ? profiles.getFirst() : null;
        List<CalendarRow> calendars = jdbc.sql("""
                WITH scoped AS (SELECT *, dense_rank() OVER (ORDER BY CASE scope_kind
                    WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END) AS precedence
                    FROM core.ad_reporting_calendar WHERE organization_id=:org
                      AND (scope_kind='ORGANIZATION' OR (scope_kind='PLATFORM' AND platform_code=:platform)
                        OR (scope_kind='STORE' AND store_ref_id=:store))
                      AND status='ACTIVE' AND effective_from<=:at
                      AND (effective_to IS NULL OR effective_to>:at))
                SELECT * FROM scoped WHERE precedence=1 LIMIT 2
                """).param("org", kase.organizationId()).param("store", kase.storeId())
                .param("platform", kase.platformCode()).param("at", Timestamp.from(now))
                .query((rs, n) -> new CalendarRow(rs.getObject("id", UUID.class),
                        rs.getInt("policy_version"), rs.getString("reporting_timezone"),
                        Arrays.stream((Object[]) rs.getArray("operating_days").getArray())
                                .map(value -> ((Number) value).intValue()).toList())).list();
        CalendarRow calendar = calendars.size() == 1 ? calendars.getFirst() : null;
        Instant ack = null, action = null, escalation = null, next = null;
        String coverageState = "PROFILE_MISSING";
        if (profile != null && profile.staffed() && profile.start() != null && profile.end() != null) {
            coverageState = "CALENDAR_MISSING";
            if (calendar != null && calendar.timezone().equals(profile.timezone())) {
                var coverage = new StaffedResponseClock.Coverage(ZoneId.of(profile.timezone()),
                        calendar.days().stream().collect(Collectors.toSet()), profile.start(), profile.end());
                ack = StaffedResponseClock.deadline(kase.raisedAt(), profile.ackMinutes(), coverage);
                action = StaffedResponseClock.deadline(kase.raisedAt(), profile.actionMinutes(), coverage);
                escalation = StaffedResponseClock.deadline(kase.raisedAt(), profile.escalationMinutes(), coverage);
                next = StaffedResponseClock.nextStaffed(now, coverage);
                coverageState = coverage.contains(now) ? "IN_COVERAGE"
                        : "PROTECTION".equals(kase.lane()) ? "OUT_OF_COVERAGE_ACTIVE_HARM" : "OUT_OF_COVERAGE";
            }
        }
        if(existing.isPresent()) {
            if(!List.of("PROFILE_MISSING","CALENDAR_MISSING").contains(coverageState)) {
                jdbc.sql("""
                    UPDATE ops.ad_case_responsibility SET slo_profile_id=:profile,slo_profile_version=:profileVersion,
                        calendar_id=:calendar,calendar_version=:calendarVersion,acknowledgement_due_at=:ack,
                        action_due_at=:action,escalation_due_at=:escalation,next_staffed_response_at=:next,
                        coverage_state=:coverage,profile_snapshot=CAST(:snapshot AS jsonb)
                          || jsonb_build_object('unresolvedOriginal',profile_snapshot,'resolvedAt',CAST(:now AS timestamptz))
                    WHERE case_id=:case AND coverage_state IN('PROFILE_MISSING','CALENDAR_MISSING')
                    """).param("case",caseId).param("profile",profile.id()).param("profileVersion",profile.version())
                    .param("calendar",calendar.id()).param("calendarVersion",calendar.version())
                    .param("ack",timestamp(ack)).param("action",timestamp(action)).param("escalation",timestamp(escalation))
                    .param("next",timestamp(next)).param("coverage",coverageState).param("now",timestamp(now))
                    .param("snapshot",json.writeValueAsString(Map.of("slo",profile,"calendar",calendar))).update();
            }
            sloMonitor.inspect(caseId);
            return existing.get();
        }
        UUID recommendationId = ids.newId(), taskId = ids.newId();
        recommendations.insert(recommendationId, kase.organizationId(), kase.storeId(),
                SubjectKind.AD_NATIVE_OBJECT, kase.objectId(), ActionKind.ADVERTISING_REVIEW,
                "DETERMINISTIC", null, calculationRunId, MetricWindow.D30, RecommendationState.TASK_ONLY,
                BigDecimal.ZERO, Map.of("caseId", caseId.toString(), "cause", kase.cause()),
                Map.of("lane", kase.lane(), "accountableRole", accountableRoleCode), "HIGH", 30,
                kase.digest(), now.plus(java.time.Duration.ofDays(30)), now);
        String title = "Review the accountable advertising case";
        tasks.insert(taskId, kase.organizationId(), recommendationId, title, action, kase.raisedAt());
        journal.append(new WorkTaskEventRepository.Event(ids.newId(), taskId, kase.organizationId(),
                "RAISED", "advertising-case:" + caseId, null, null, null, null, null, null, null,
                null, accountableRoleCode, title, kase.raisedAt(), "advertising-case:" + caseId));
        jdbc.sql("""
                INSERT INTO ops.ad_case_responsibility(case_id,organization_id,task_id,recommendation_id,
                    owner_role_code,slo_profile_id,slo_profile_version,calendar_id,calendar_version,
                    first_raised_at,acknowledgement_due_at,action_due_at,escalation_due_at,
                    next_staffed_response_at,coverage_state,profile_snapshot)
                VALUES (:case,:org,:task,:recommendation,:role,:profile,:profileVersion,:calendar,:calendarVersion,
                    :raised,:ack,:action,:escalation,:next,:coverage,CAST(:snapshot AS jsonb))
                """).param("case", caseId).param("org", kase.organizationId()).param("task", taskId)
                .param("recommendation", recommendationId).param("role", accountableRoleCode)
                .param("profile", profile == null ? null : profile.id())
                .param("profileVersion", profile == null ? null : profile.version())
                .param("calendar", calendar == null ? null : calendar.id())
                .param("calendarVersion", calendar == null ? null : calendar.version())
                .param("raised", Timestamp.from(kase.raisedAt())).param("ack", timestamp(ack))
                .param("action", timestamp(action)).param("escalation", timestamp(escalation))
                .param("next", timestamp(next)).param("coverage", coverageState)
                .param("snapshot", json.writeValueAsString(Map.of("slo", profile == null ? Map.of() : profile,
                        "calendar", calendar == null ? Map.of() : calendar))).update();
        sloMonitor.inspect(caseId);
        return taskId;
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private record CaseRow(UUID id, UUID organizationId, UUID storeId, UUID objectId, String lane,
                           String cause, UUID calculationId, Instant raisedAt, String digest, String platformCode) { }
    private record Slo(UUID id, int version, int ackMinutes, int actionMinutes, int escalationMinutes,
                       boolean staffed, String timezone, Integer start, Integer end) { }
    private record CalendarRow(UUID id, int version, String timezone, List<Integer> days) { }
}
