package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import com.mimococo.marketops.operationsworkflow.AvailabilityCaseState;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes accountable availability cases and their journal.
 *
 * <p>Case rows carry state and are updated under an optimistic version. The
 * journal is append-only: a reopen adds an event rather than resetting a
 * counter, so "this is the fourth time this month" survives the reopen that
 * produced it.
 */
@Repository
public class AvailabilityCaseRepository {

    private final JdbcClient jdbc;

    public AvailabilityCaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The live case governing a cause, when one exists. */
    public Optional<AvailabilityCaseView> liveByCause(UUID organizationId, String causeKey) {
        return jdbc.sql(SELECT + """
                         WHERE organization_id = :organizationId
                           AND cause_key = :causeKey
                           AND state NOT IN ('VERIFIED_SUCCESS', 'CANCELLED')
                        """)
                .param("organizationId", organizationId)
                .param("causeKey", causeKey)
                .query(AvailabilityCaseRepository::map)
                .optional();
    }

    /** One case by identity. */
    public Optional<AvailabilityCaseView> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(AvailabilityCaseRepository::map)
                .optional();
    }

    /**
     * The organization's accountable availability work, most urgent first.
     *
     * <p>Ordered by severity and then by the action deadline, because that is
     * the order somebody would work it: the most serious first, and among
     * equally serious the one running out of time. Sorting by when a case was
     * raised would put a week-old WATCH above a CRITICAL raised this morning.
     */
    public List<AvailabilityCaseView> queue(UUID organizationId, boolean liveOnly,
                                            UUID assigneeUserId, UUID[] permittedStoreIds,
                                            UUID[] permittedProductVariantIds, int limit) {
        return jdbc.sql(SELECT + """
                         WHERE organization_id = :organizationId
                           AND EXISTS (
                               SELECT 1
                                 FROM mart.availability_risk_child scoped_child
                                 JOIN mart.availability_risk_card scoped_card
                                   ON scoped_card.id = scoped_child.card_id
                                  AND scoped_card.organization_id = scoped_child.organization_id
                                WHERE scoped_child.id = ops.availability_case.child_id
                                  AND scoped_child.organization_id
                                      = ops.availability_case.organization_id
                                  AND scoped_card.product_variant_id
                                      = ANY (:permittedProductVariantIds)
                                  AND (scoped_child.child_kind = 'COMPANY'
                                       OR scoped_child.store_id = ANY (:permittedStoreIds)))
                           AND (:liveOnly = FALSE
                                OR state NOT IN ('VERIFIED_SUCCESS', 'CANCELLED'))
                           AND (CAST(:assigneeUserId AS uuid) IS NULL
                                OR assignee_user_id = :assigneeUserId)
                         ORDER BY CASE severity
                                      WHEN 'CRITICAL' THEN 0
                                      WHEN 'UNRESOLVED' THEN 1
                                      WHEN 'REVIEW' THEN 1
                                      WHEN 'HIGH' THEN 2
                                      ELSE 3
                                  END,
                                  action_due_at
                         LIMIT :limit
                        """)
                .param("organizationId", organizationId)
                .param("liveOnly", liveOnly)
                .param("assigneeUserId", assigneeUserId)
                .param("permittedStoreIds", permittedStoreIds)
                .param("permittedProductVariantIds", permittedProductVariantIds)
                .param("limit", limit)
                .query(AvailabilityCaseRepository::map)
                .list();
    }

    /** Every case raised from one card, newest evidence first. */
    public List<AvailabilityCaseView> forCard(UUID cardId) {
        return jdbc.sql(SELECT + " WHERE card_id = :cardId ORDER BY last_evidence_at DESC")
                .param("cardId", cardId)
                .query(AvailabilityCaseRepository::map)
                .list();
    }

    /** Everything that ever happened to one case, oldest first. */
    public List<CaseJournalEntry> journal(UUID caseId) {
        return jdbc.sql("""
                        SELECT sequence_no, event_kind, from_state, to_state, action_kind,
                               verification_kind, verification_outcome, actor_user_id,
                               actor_role_code, reason, evidence_reference, observed_at,
                               occurred_at
                          FROM ops.availability_case_event
                         WHERE case_id = :caseId
                         ORDER BY sequence_no
                        """)
                .param("caseId", caseId)
                .query((rows, rowNumber) -> new CaseJournalEntry(
                        rows.getInt("sequence_no"),
                        rows.getString("event_kind"),
                        rows.getString("from_state"),
                        rows.getString("to_state"),
                        rows.getString("action_kind"),
                        rows.getString("verification_kind"),
                        rows.getString("verification_outcome"),
                        rows.getObject("actor_user_id", UUID.class),
                        rows.getString("actor_role_code"),
                        rows.getString("reason"),
                        rows.getString("evidence_reference"),
                        rows.getTimestamp("observed_at") == null
                                ? null : rows.getTimestamp("observed_at").toInstant(),
                        rows.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    /**
     * One entry in a case's journal.
     *
     * @param sequenceNo its position in the case's history
     * @param eventKind what happened
     * @param fromState the state before, or {@code null}
     * @param toState the state after, or {@code null}
     * @param actionKind the structured action, or {@code null}
     * @param verificationKind what was verified, or {@code null}
     * @param verificationOutcome how the verification came out, or {@code null}
     * @param actorUserId who did it, or {@code null} when nothing human did
     * @param actorRoleCode the role they acted as, or {@code null}
     * @param reason why
     * @param evidenceReference the artefact behind it, or {@code null}
     * @param observedAt when the evidence was observed, or {@code null}
     * @param occurredAt when it happened
     */
    public record CaseJournalEntry(int sequenceNo, String eventKind, String fromState,
                                   String toState, String actionKind, String verificationKind,
                                   String verificationOutcome, UUID actorUserId,
                                   String actorRoleCode, String reason, String evidenceReference,
                                   Instant observedAt, Instant occurredAt) {
    }

    /** Raise a new case. */
    public void insert(NewCase row) {
        jdbc.sql("""
                        INSERT INTO ops.availability_case
                            (id, organization_id, card_id, child_id, cause_code, cause_key,
                             child_kind, severity, state, accountable_role_code, action_due_at,
                             outcome_due_at, activation_policy_id, first_activated_at,
                             last_evidence_at, correlation_id, created_at, updated_at)
                        VALUES (:id, :organizationId, :cardId, :childId, :causeCode, :causeKey,
                                :childKind, :severity, 'OPEN', :roleCode, :actionDueAt,
                                :outcomeDueAt, :policyId, :at, :at, :correlationId, :at, :at)
                        """)
                .param("id", row.id()).param("organizationId", row.organizationId())
                .param("cardId", row.cardId()).param("childId", row.childId())
                .param("causeCode", row.causeCode()).param("causeKey", row.causeKey())
                .param("childKind", row.childKind()).param("severity", row.severity())
                .param("roleCode", row.accountableRoleCode())
                .param("actionDueAt", Timestamp.from(row.actionDueAt()))
                .param("outcomeDueAt", row.outcomeDueAt() == null
                        ? null : Timestamp.from(row.outcomeDueAt()))
                .param("policyId", row.activationPolicyId())
                .param("correlationId", row.correlationId())
                .param("at", Timestamp.from(row.at()))
                .update();
    }

    /**
     * Refresh a live case with what the latest calculation established.
     *
     * <p>Severity and due time may move under policy; the case identity, its
     * first activation and its history may not.
     */
    public void refresh(UUID id, String severity, Instant actionDueAt, Instant lastEvidenceAt) {
        jdbc.sql("""
                        UPDATE ops.availability_case
                           SET severity = :severity,
                               action_due_at = :actionDueAt,
                               last_evidence_at = :lastEvidenceAt,
                               updated_at = :lastEvidenceAt,
                               version = version + 1
                         WHERE id = :id
                        """)
                .param("id", id).param("severity", severity)
                .param("actionDueAt", Timestamp.from(actionDueAt))
                .param("lastEvidenceAt", Timestamp.from(lastEvidenceAt))
                .update();
    }

    /**
     * Every live case an automatic observation of one child applies to.
     *
     * <p>Keyed on the child rather than on the cause, because by the time a
     * cause is repaired the recalculated child no longer carries it. Looking
     * the case up by its current cause would find nothing precisely when the
     * good news arrived, and the case would wait for a person forever.
     */
    public List<AvailabilityCaseView> awaitingOutcome(UUID childId) {
        return jdbc.sql(SELECT + """
                         WHERE child_id = :childId
                           AND state IN ('ACTION_RECORDED', 'VERIFYING')
                         ORDER BY first_activated_at
                        """)
                .param("childId", childId)
                .query(AvailabilityCaseRepository::map)
                .list();
    }

    /**
     * Record when the cause was first observed repaired, or that it no longer is.
     *
     * <p>Set once and cleared on regression. Overwriting it on every observation
     * would restart the governed window each time the risk was looked at, and a
     * window that never elapses can never verify anything.
     */
    public void markImprovement(UUID id, Instant firstSeenAt, Instant at) {
        jdbc.sql("""
                        UPDATE ops.availability_case
                           SET improvement_first_seen_at = :firstSeenAt,
                               last_evidence_at = :at,
                               updated_at = :at,
                               version = version + 1
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("firstSeenAt", firstSeenAt == null ? null : Timestamp.from(firstSeenAt))
                .param("at", Timestamp.from(at))
                .update();
    }

    /** Move a case to a new state, carrying the timestamps that state requires. */
    public void transition(Transition transition) {
        jdbc.sql("""
                        UPDATE ops.availability_case
                           SET state = :state,
                               action_recorded_at =
                                   coalesce(action_recorded_at, :actionRecordedAt),
                               verification_started_at =
                                   coalesce(verification_started_at, :verificationStartedAt),
                               verified_at = :verifiedAt,
                               closed_at = :closedAt,
                               closure_reason = :closureReason,
                               outcome_due_at = coalesce(:outcomeDueAt, outcome_due_at),
                               reopen_count = reopen_count + :reopenIncrement,
                               escalation_level = escalation_level + :escalationIncrement,
                               last_evidence_at = :at,
                               updated_at = :at,
                               version = version + 1
                         WHERE id = :id
                        """)
                .param("id", transition.id())
                .param("state", transition.state().name())
                .param("actionRecordedAt", transition.actionRecordedAt() == null
                        ? null : Timestamp.from(transition.actionRecordedAt()))
                .param("verificationStartedAt", transition.verificationStartedAt() == null
                        ? null : Timestamp.from(transition.verificationStartedAt()))
                .param("verifiedAt", transition.verifiedAt() == null
                        ? null : Timestamp.from(transition.verifiedAt()))
                .param("closedAt", transition.closedAt() == null
                        ? null : Timestamp.from(transition.closedAt()))
                .param("closureReason", transition.closureReason())
                .param("outcomeDueAt", transition.outcomeDueAt() == null
                        ? null : Timestamp.from(transition.outcomeDueAt()))
                .param("reopenIncrement", transition.reopenIncrement())
                .param("escalationIncrement", transition.escalationIncrement())
                .param("at", Timestamp.from(transition.at()))
                .update();
    }

    /** Append one event to a case's journal. */
    public void appendEvent(CaseEvent event) {
        jdbc.sql("""
                        INSERT INTO ops.availability_case_event
                            (id, case_id, organization_id, sequence_no, event_kind, from_state,
                             to_state, action_kind, action_evidence, verification_kind,
                             verification_outcome, actor_user_id, actor_role_code, reason,
                             evidence_reference, observed_at, occurred_at, correlation_id)
                        VALUES (:id, :caseId, :organizationId,
                                (SELECT coalesce(max(sequence_no), 0) + 1
                                   FROM ops.availability_case_event WHERE case_id = :caseId),
                                :eventKind, :fromState, :toState, :actionKind,
                                CAST(:actionEvidence AS jsonb), :verificationKind,
                                :verificationOutcome, :actorUserId, :actorRoleCode, :reason,
                                :evidenceReference, :observedAt, :occurredAt, :correlationId)
                        """)
                .param("id", event.id()).param("caseId", event.caseId())
                .param("organizationId", event.organizationId())
                .param("eventKind", event.eventKind()).param("fromState", event.fromState())
                .param("toState", event.toState()).param("actionKind", event.actionKind())
                .param("actionEvidence", event.actionEvidence())
                .param("verificationKind", event.verificationKind())
                .param("verificationOutcome", event.verificationOutcome())
                .param("actorUserId", event.actorUserId())
                .param("actorRoleCode", event.actorRoleCode())
                .param("reason", event.reason())
                .param("evidenceReference", event.evidenceReference())
                .param("observedAt", event.observedAt() == null
                        ? null : Timestamp.from(event.observedAt()))
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .param("correlationId", event.correlationId())
                .update();
    }

    private static final String SELECT = """
            SELECT id, organization_id, card_id, child_id, cause_code, cause_key, severity,
                   state, accountable_role_code, assignee_user_id, action_due_at, outcome_due_at,
                   reopen_count, escalation_level, first_activated_at, last_evidence_at,
                   improvement_first_seen_at
              FROM ops.availability_case
            """;

    private static AvailabilityCaseView map(java.sql.ResultSet rows, int rowNumber)
            throws java.sql.SQLException {
        return new AvailabilityCaseView(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("card_id", UUID.class),
                rows.getObject("child_id", UUID.class),
                rows.getString("cause_code"),
                rows.getString("cause_key"),
                rows.getString("severity"),
                AvailabilityCaseState.valueOf(rows.getString("state")),
                rows.getString("accountable_role_code"),
                rows.getObject("assignee_user_id", UUID.class),
                rows.getTimestamp("action_due_at").toInstant(),
                rows.getTimestamp("outcome_due_at") == null
                        ? null : rows.getTimestamp("outcome_due_at").toInstant(),
                rows.getInt("reopen_count"),
                rows.getInt("escalation_level"),
                rows.getTimestamp("first_activated_at").toInstant(),
                rows.getTimestamp("last_evidence_at").toInstant(),
                rows.getTimestamp("improvement_first_seen_at") == null
                        ? null
                        : rows.getTimestamp("improvement_first_seen_at").toInstant());
    }

    /** A case to raise. */
    public record NewCase(UUID id, UUID organizationId, UUID cardId, UUID childId,
                          String causeCode, String causeKey, String childKind, String severity,
                          String accountableRoleCode, Instant actionDueAt, Instant outcomeDueAt,
                          UUID activationPolicyId, String correlationId, Instant at) {
    }

    /** A state movement with the timestamps that state requires. */
    public record Transition(UUID id, AvailabilityCaseState state, Instant actionRecordedAt,
                             Instant verificationStartedAt, Instant verifiedAt, Instant closedAt,
                             String closureReason, Instant outcomeDueAt, int reopenIncrement,
                             int escalationIncrement, Instant at) {
    }

    /** One journal entry. */
    public record CaseEvent(UUID id, UUID caseId, UUID organizationId, String eventKind,
                            String fromState, String toState, String actionKind,
                            String actionEvidence, String verificationKind,
                            String verificationOutcome, UUID actorUserId, String actorRoleCode,
                            String reason, String evidenceReference, Instant observedAt,
                            Instant occurredAt, String correlationId) {
    }
}
