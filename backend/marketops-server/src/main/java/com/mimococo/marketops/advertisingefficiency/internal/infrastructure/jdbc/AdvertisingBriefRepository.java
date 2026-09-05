package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import com.mimococo.marketops.advertisingefficiency.AdvertisingBriefView;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes and reads the published briefs.
 *
 * <p>Append-only in both directions, and not by convention: the application role
 * holds {@code SELECT} and {@code INSERT} on all four tables and nothing else,
 * and a trigger refuses an update or a delete of a publication outright. A
 * report that could be edited after somebody read it would make every decision
 * taken from it unauditable.
 *
 * <p>There is no method here that writes a Case, a Task, an Approval, a Metric
 * or an Outcome. That is the whole point of the projection: it links to those by
 * identity and owns none of them.
 */
@Repository
public class AdvertisingBriefRepository {

    private final JdbcClient jdbc;

    AdvertisingBriefRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One publication, exactly as it is stored. */
    public record PublicationRow(
            UUID id, UUID organizationId, String briefKind, String periodKey,
            Instant periodStartsAt, Instant periodEndsAt, Instant asOf,
            UUID calendarPolicyId, int calendarPolicyVersion, String cursorFeedCode,
            Instant cursorPositionAt, String cursorPositionItemKey, UUID maxCalculationId,
            UUID reconciliationRunId, String policyVersionDigest, String bundleVersionSnapshot,
            List<String> gapCodes, int revisionNo, UUID supersedesPublicationId,
            String revisionKind, String adjustmentReason, String lateFactReference,
            String contentDigest, Instant publishedAt, String correlationId) {
    }

    /** One section of one publication. */
    public record SectionRow(UUID id, UUID publicationId, UUID organizationId, String sectionCode,
                             int ordinal, int itemCount, String coverageState,
                             List<String> blockerCodes, String summaryNote) {
    }

    /** One canonical link of one section. */
    public record ItemRow(UUID id, UUID publicationId, UUID organizationId, String sectionCode,
                          int ordinal, String subjectKind, UUID caseId, UUID workTaskId,
                          UUID recommendationId, UUID outcomeObservationId, UUID sloObservationId,
                          UUID containmentId, UUID reservationId, UUID bidCommandId,
                          UUID manualPacketId, UUID bundleId, UUID metricValueId, UUID storeId,
                          String lane, String protectionTier, String causeCode, String valueState,
                          BigDecimal numericValue, String currencyCode, String evidenceState,
                          String confidenceState, List<String> blockerCodes, Instant observedAt) {
    }

    /** One line of what a revision changed. */
    public record DeltaRow(UUID id, UUID publicationId, UUID organizationId, String revisionKind,
                           UUID supersedesPublicationId, String sectionCode, String changeKind,
                           UUID previousItemId, UUID currentItemId, String previousValueState,
                           BigDecimal previousNumericValue, String currentValueState,
                           BigDecimal currentNumericValue, String lateFactReference,
                           String changeReason) {
    }

    public void insertPublication(PublicationRow row) {
        jdbc.sql("""
                INSERT INTO ops.ad_brief_publication (
                    id, organization_id, brief_kind, period_key, period_starts_at,
                    period_ends_at, as_of, calendar_policy_id, calendar_policy_version,
                    cursor_feed_code, cursor_position_at, cursor_position_item_key,
                    max_calculation_id, reconciliation_run_id, policy_version_digest,
                    bundle_version_snapshot, gap_codes, revision_no, supersedes_publication_id,
                    revision_kind, adjustment_reason, late_fact_reference, content_digest,
                    published_at, correlation_id)
                VALUES (:id, :organizationId, :briefKind, :periodKey, :periodStartsAt,
                    :periodEndsAt, :asOf, :calendarPolicyId, :calendarPolicyVersion,
                    :cursorFeedCode, :cursorPositionAt, :cursorItemKey, :maxCalculationId,
                    :reconciliationRunId, :policyVersionDigest,
                    CAST(:bundleSnapshot AS jsonb), CAST(:gapCodes AS text[]), :revisionNo,
                    :supersedes, :revisionKind, :adjustmentReason, :lateFactReference,
                    :contentDigest, :publishedAt, :correlationId)
                """)
                .param("id", row.id())
                .param("organizationId", row.organizationId())
                .param("briefKind", row.briefKind())
                .param("periodKey", row.periodKey())
                .param("periodStartsAt", Timestamp.from(row.periodStartsAt()))
                .param("periodEndsAt", Timestamp.from(row.periodEndsAt()))
                .param("asOf", Timestamp.from(row.asOf()))
                .param("calendarPolicyId", row.calendarPolicyId())
                .param("calendarPolicyVersion", row.calendarPolicyVersion())
                .param("cursorFeedCode", row.cursorFeedCode())
                .param("cursorPositionAt", Timestamp.from(row.cursorPositionAt()))
                .param("cursorItemKey", row.cursorPositionItemKey())
                .param("maxCalculationId", row.maxCalculationId())
                .param("reconciliationRunId", row.reconciliationRunId())
                .param("policyVersionDigest", row.policyVersionDigest())
                .param("bundleSnapshot", row.bundleVersionSnapshot())
                .param("gapCodes", textArrayLiteral(row.gapCodes()))
                .param("revisionNo", row.revisionNo())
                .param("supersedes", row.supersedesPublicationId())
                .param("revisionKind", row.revisionKind())
                .param("adjustmentReason", row.adjustmentReason())
                .param("lateFactReference", row.lateFactReference())
                .param("contentDigest", row.contentDigest())
                .param("publishedAt", Timestamp.from(row.publishedAt()))
                .param("correlationId", row.correlationId())
                .update();
    }

    public void insertSection(SectionRow row) {
        jdbc.sql("""
                INSERT INTO mart.ad_brief_section (
                    id, publication_id, organization_id, section_code, ordinal, item_count,
                    coverage_state, blocker_codes, summary_note)
                VALUES (:id, :publicationId, :organizationId, :sectionCode, :ordinal, :itemCount,
                    :coverageState, CAST(:blockerCodes AS text[]), :summaryNote)
                """)
                .param("id", row.id())
                .param("publicationId", row.publicationId())
                .param("organizationId", row.organizationId())
                .param("sectionCode", row.sectionCode())
                .param("ordinal", row.ordinal())
                .param("itemCount", row.itemCount())
                .param("coverageState", row.coverageState())
                .param("blockerCodes", textArrayLiteral(row.blockerCodes()))
                .param("summaryNote", row.summaryNote())
                .update();
    }

    public void insertItem(ItemRow row) {
        jdbc.sql("""
                INSERT INTO mart.ad_brief_item (
                    id, publication_id, organization_id, section_code, ordinal, subject_kind,
                    case_id, work_task_id, recommendation_id, outcome_observation_id,
                    slo_observation_id, containment_id, reservation_id, bid_command_id,
                    manual_packet_id, bundle_id, metric_value_id, store_id, lane,
                    protection_tier, cause_code, value_state, numeric_value, currency_code,
                    evidence_state, confidence_state, blocker_codes, observed_at)
                VALUES (:id, :publicationId, :organizationId, :sectionCode, :ordinal,
                    :subjectKind, :caseId, :workTaskId, :recommendationId, :outcomeId, :sloId,
                    :containmentId, :reservationId, :commandId, :packetId, :bundleId,
                    :metricValueId, :storeId, :lane, :protectionTier, :causeCode, :valueState,
                    :numericValue, :currencyCode, :evidenceState, :confidenceState,
                    CAST(:blockerCodes AS text[]), :observedAt)
                """)
                .param("id", row.id())
                .param("publicationId", row.publicationId())
                .param("organizationId", row.organizationId())
                .param("sectionCode", row.sectionCode())
                .param("ordinal", row.ordinal())
                .param("subjectKind", row.subjectKind())
                .param("caseId", row.caseId())
                .param("workTaskId", row.workTaskId())
                .param("recommendationId", row.recommendationId())
                .param("outcomeId", row.outcomeObservationId())
                .param("sloId", row.sloObservationId())
                .param("containmentId", row.containmentId())
                .param("reservationId", row.reservationId())
                .param("commandId", row.bidCommandId())
                .param("packetId", row.manualPacketId())
                .param("bundleId", row.bundleId())
                .param("metricValueId", row.metricValueId())
                .param("storeId", row.storeId())
                .param("lane", row.lane())
                .param("protectionTier", row.protectionTier())
                .param("causeCode", row.causeCode())
                .param("valueState", row.valueState())
                .param("numericValue", row.numericValue())
                .param("currencyCode", row.currencyCode())
                .param("evidenceState", row.evidenceState())
                .param("confidenceState", row.confidenceState())
                .param("blockerCodes", textArrayLiteral(row.blockerCodes()))
                .param("observedAt", row.observedAt() == null
                        ? null : Timestamp.from(row.observedAt()))
                .update();
    }

    public void insertDelta(DeltaRow row) {
        jdbc.sql("""
                INSERT INTO mart.ad_brief_delta (
                    id, publication_id, organization_id, revision_kind,
                    supersedes_publication_id, section_code, change_kind, previous_item_id,
                    current_item_id, previous_value_state, previous_numeric_value,
                    current_value_state, current_numeric_value, late_fact_reference,
                    change_reason)
                VALUES (:id, :publicationId, :organizationId, :revisionKind, :supersedes,
                    :sectionCode, :changeKind, :previousItemId, :currentItemId,
                    :previousValueState, :previousNumericValue, :currentValueState,
                    :currentNumericValue, :lateFactReference, :changeReason)
                """)
                .param("id", row.id())
                .param("publicationId", row.publicationId())
                .param("organizationId", row.organizationId())
                .param("revisionKind", row.revisionKind())
                .param("supersedes", row.supersedesPublicationId())
                .param("sectionCode", row.sectionCode())
                .param("changeKind", row.changeKind())
                .param("previousItemId", row.previousItemId())
                .param("currentItemId", row.currentItemId())
                .param("previousValueState", row.previousValueState())
                .param("previousNumericValue", row.previousNumericValue())
                .param("currentValueState", row.currentValueState())
                .param("currentNumericValue", row.currentNumericValue())
                .param("lateFactReference", row.lateFactReference())
                .param("changeReason", row.changeReason())
                .update();
    }

    /** The calendar in force for an organization, if one has been published. */
    public Optional<CalendarRow> activeCalendar(UUID organizationId, Instant at) {
        return jdbc.sql("""
                SELECT id, policy_version, reporting_timezone, daily_cut_minute,
                       operating_days, weekly_cut_weekday, weekly_cut_minute,
                       late_revision_horizon_hours
                  FROM core.ad_reporting_calendar
                 WHERE organization_id = :organizationId
                   AND status = 'ACTIVE'
                   AND effective_from <= :at
                   AND (effective_to IS NULL OR effective_to > :at)
                 ORDER BY CASE scope_kind WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END,
                          effective_from DESC
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("at", Timestamp.from(at))
                .query((ResultSet rs, int index) -> new CalendarRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("policy_version"),
                        rs.getString("reporting_timezone"),
                        rs.getInt("daily_cut_minute"),
                        shorts(rs, "operating_days"),
                        rs.getInt("weekly_cut_weekday"),
                        rs.getInt("weekly_cut_minute"),
                        rs.getInt("late_revision_horizon_hours")))
                .optional();
    }

    /** The reporting calendar in force. */
    public record CalendarRow(UUID id, int policyVersion, String reportingTimezone,
                              int dailyCutMinute, List<Integer> operatingDays,
                              int weeklyCutWeekday, int weeklyCutMinute,
                              int lateRevisionHorizonHours) {
    }

    /** The newest reading of one period, whichever revision that is. */
    public Optional<AdvertisingBriefView> latest(UUID organizationId, String briefKind,
                                                 String periodKey) {
        Optional<UUID> id = jdbc.sql("""
                SELECT id FROM ops.ad_brief_publication
                 WHERE organization_id = :organizationId AND brief_kind = :briefKind
                   AND period_key = :periodKey
                 ORDER BY revision_no DESC
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("briefKind", briefKind)
                .param("periodKey", periodKey)
                .query(UUID.class)
                .optional();
        return id.flatMap(this::publication);
    }

    /**
     * The period of the newest reading of one kind, or nothing when none exists.
     *
     * <p>Ordered by the period rather than by publication time, because a late
     * revision of last Tuesday is still last Tuesday's reading and must not
     * displace today's.
     */
    public Optional<String> mostRecentPeriodKey(UUID organizationId, String briefKind) {
        return jdbc.sql("""
                SELECT period_key FROM ops.ad_brief_publication
                 WHERE organization_id = :organizationId AND brief_kind = :briefKind
                 ORDER BY period_starts_at DESC, revision_no DESC
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("briefKind", briefKind)
                .query(String.class)
                .optional();
    }

    /** Every reading of one period, oldest first, so a restatement is visible. */
    public List<AdvertisingBriefView> history(UUID organizationId, String briefKind,
                                              String periodKey) {
        return jdbc.sql("""
                SELECT id FROM ops.ad_brief_publication
                 WHERE organization_id = :organizationId AND brief_kind = :briefKind
                   AND period_key = :periodKey
                 ORDER BY revision_no
                """)
                .param("organizationId", organizationId)
                .param("briefKind", briefKind)
                .param("periodKey", periodKey)
                .query(UUID.class)
                .list()
                .stream()
                .map(this::publication)
                .flatMap(Optional::stream)
                .toList();
    }

    /** One publication with its sections and their items. */
    public Optional<AdvertisingBriefView> publication(UUID publicationId) {
        Optional<Header> header = jdbc.sql("""
                SELECT id, brief_kind, period_key, period_starts_at, period_ends_at, as_of,
                       cursor_position_at, revision_no, revision_kind,
                       supersedes_publication_id, adjustment_reason, late_fact_reference,
                       gap_codes, content_digest, published_at
                  FROM ops.ad_brief_publication
                 WHERE id = :id
                """)
                .param("id", publicationId)
                .query((ResultSet rs, int index) -> new Header(
                        rs.getObject("id", UUID.class),
                        rs.getString("brief_kind"),
                        rs.getString("period_key"),
                        rs.getTimestamp("period_starts_at").toInstant(),
                        rs.getTimestamp("period_ends_at").toInstant(),
                        rs.getTimestamp("as_of").toInstant(),
                        rs.getTimestamp("cursor_position_at").toInstant(),
                        rs.getInt("revision_no"),
                        rs.getString("revision_kind"),
                        rs.getObject("supersedes_publication_id", UUID.class),
                        rs.getString("adjustment_reason"),
                        rs.getString("late_fact_reference"),
                        strings(rs, "gap_codes"),
                        rs.getString("content_digest"),
                        rs.getTimestamp("published_at").toInstant()))
                .optional();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        Map<String,List<AdvertisingBriefView.Item>> itemsBySection = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT section_code, subject_kind, lane, cause_code, value_state, numeric_value,
                       currency_code, evidence_state, blocker_codes, observed_at,
                       coalesce(case_id, work_task_id, recommendation_id, outcome_observation_id,
                                slo_observation_id, containment_id, reservation_id,
                                bid_command_id, manual_packet_id, bundle_id, metric_value_id)
                           AS reference_id
                  FROM mart.ad_brief_item
                 WHERE publication_id = :publicationId
                 ORDER BY section_code, ordinal
                """)
                .param("publicationId", publicationId)
                .query((ResultSet rs, int index) -> {
                    Timestamp observed = rs.getTimestamp("observed_at");
                    return Map.entry(rs.getString("section_code"),
                            new AdvertisingBriefView.Item(
                                    rs.getString("subject_kind"),
                                    rs.getObject("reference_id", UUID.class),
                                    rs.getString("lane"),
                                    rs.getString("cause_code"),
                                    rs.getString("value_state"),
                                    rs.getBigDecimal("numeric_value"),
                                    rs.getString("currency_code"),
                                    rs.getString("evidence_state"),
                                    strings(rs, "blocker_codes"),
                                    observed == null ? null : observed.toInstant()));
                })
                .list()
                .forEach(entry -> itemsBySection
                        .computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(entry.getValue()));

        List<AdvertisingBriefView.Section> sections = jdbc.sql("""
                SELECT section_code, ordinal, item_count, coverage_state, blocker_codes,
                       summary_note
                  FROM mart.ad_brief_section
                 WHERE publication_id = :publicationId
                 ORDER BY ordinal
                """)
                .param("publicationId", publicationId)
                .query((ResultSet rs, int index) -> new AdvertisingBriefView.Section(
                        rs.getString("section_code"),
                        rs.getInt("ordinal"),
                        rs.getInt("item_count"),
                        rs.getString("coverage_state"),
                        strings(rs, "blocker_codes"),
                        rs.getString("summary_note"),
                        itemsBySection.getOrDefault(rs.getString("section_code"), List.of())))
                .list();

        Header found = header.get();
        return Optional.of(new AdvertisingBriefView(found.id(), found.briefKind(),
                found.periodKey(), found.periodStartsAt(), found.periodEndsAt(), found.asOf(),
                found.cursorPositionAt(), found.revisionNo(), found.revisionKind(),
                found.supersedesPublicationId(), found.adjustmentReason(),
                found.lateFactReference(), found.gapCodes(), found.contentDigest(),
                found.publishedAt(), sections));
    }

    /** The highest revision written for one period, or zero when none is. */
    public int highestRevision(UUID organizationId, String briefKind, String periodKey) {
        return jdbc.sql("""
                SELECT coalesce(max(revision_no), 0) FROM ops.ad_brief_publication
                 WHERE organization_id = :organizationId AND brief_kind = :briefKind
                   AND period_key = :periodKey
                """)
                .param("organizationId", organizationId)
                .param("briefKind", briefKind)
                .param("periodKey", periodKey)
                .query(Integer.class)
                .single();
    }

    private record Header(UUID id, String briefKind, String periodKey, Instant periodStartsAt,
                          Instant periodEndsAt, Instant asOf, Instant cursorPositionAt,
                          int revisionNo, String revisionKind, UUID supersedesPublicationId,
                          String adjustmentReason, String lateFactReference,
                          List<String> gapCodes, String contentDigest, Instant publishedAt) {
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static List<Integer> shorts(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        List<Integer> values = new ArrayList<>();
        for (Short value : (Short[]) array.getArray()) {
            values.add(value.intValue());
        }
        return List.copyOf(values);
    }

    /**
     * A text array as PostgreSQL reads it.
     *
     * <p>Every element is quoted and its quotes escaped, so a code carrying a
     * comma or a brace cannot end the array early.
     */
    private static String textArrayLiteral(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder literal = new StringBuilder("{");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append('"')
                    .append(values.get(index).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return literal.append('}').toString();
    }
}
