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

    /**
     * One page of the organization's cases as the console lists them: the
     * case, the product and channel it is about, and the acceptance currently
     * occupying it.
     *
     * <p>Scoped exactly as {@link #queue} is, ordered the same way, with the
     * identifier as the final tie-break so pages never overlap or skip.
     *
     * @param productVariantId one variant, or {@code null} for all permitted
     * @param assigneeUserId one assignee, or {@code null}
     */
    public List<ConsoleCaseRow> consoleQueue(UUID organizationId, QueueView view,
                                             UUID productVariantId, UUID assigneeUserId,
                                             UUID[] permittedStoreIds,
                                             UUID[] permittedProductVariantIds,
                                             int limit, int offset) {
        return jdbc.sql(CONSOLE_SELECT + consoleWhere(view) + """
                         ORDER BY CASE c.severity
                                      WHEN 'CRITICAL' THEN 0
                                      WHEN 'UNRESOLVED' THEN 1
                                      WHEN 'REVIEW' THEN 1
                                      WHEN 'HIGH' THEN 2
                                      ELSE 3
                                  END,
                                  c.action_due_at, c.id
                         LIMIT :limit OFFSET :offset
                        """)
                .param("organizationId", organizationId)
                .param("productVariantId", productVariantId)
                .param("assigneeUserId", assigneeUserId)
                .param("permittedStoreIds", permittedStoreIds)
                .param("permittedProductVariantIds", permittedProductVariantIds)
                .param("limit", limit)
                .param("offset", offset)
                .query(AvailabilityCaseRepository::mapConsole)
                .list();
    }

    /** How many cases the same view, variant and scope match in total. */
    public long countConsoleQueue(UUID organizationId, QueueView view, UUID productVariantId,
                                  UUID assigneeUserId, UUID[] permittedStoreIds,
                                  UUID[] permittedProductVariantIds) {
        Long count = jdbc.sql("SELECT count(*)" + CONSOLE_FROM + consoleWhere(view))
                .param("organizationId", organizationId)
                .param("productVariantId", productVariantId)
                .param("assigneeUserId", assigneeUserId)
                .param("permittedStoreIds", permittedStoreIds)
                .param("permittedProductVariantIds", permittedProductVariantIds)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    /**
     * One case as the console shows it, without any scope narrowing.
     *
     * <p>The caller authorizes the case itself first; this only adds what the
     * case is about and what occupies it.
     */
    public Optional<ConsoleCaseRow> consoleFind(UUID caseId) {
        return jdbc.sql(CONSOLE_SELECT + " WHERE c.id = :caseId")
                .param("caseId", caseId)
                .query(AvailabilityCaseRepository::mapConsole)
                .optional();
    }

    /**
     * Which cases a console view asks for.
     *
     * <p>{@code ESCALATED} means live work that has been raised at least once,
     * whatever its current state: a case that was escalated and then acted on
     * is still escalated work. {@code EXCEPTION_PENDING} means a request to
     * accept the risk is waiting for a decision.
     */
    public enum QueueView { LIVE, ESCALATED, EXCEPTION_PENDING, ALL }

    /** The scope filter every console read shares, plus the one view predicate. */
    private static String consoleWhere(QueueView view) {
        String predicate = switch (view) {
            case LIVE -> "c.state NOT IN ('VERIFIED_SUCCESS', 'CANCELLED')";
            case ESCALATED -> "c.state NOT IN ('VERIFIED_SUCCESS', 'CANCELLED')"
                    + " AND c.escalation_level > 0";
            case EXCEPTION_PENDING -> """
                    EXISTS (SELECT 1
                              FROM ops.availability_accepted_exception pending
                             WHERE pending.case_id = c.id
                               AND pending.organization_id = c.organization_id
                               AND pending.state = 'REQUESTED')""";
            case ALL -> "TRUE";
        };
        return """
                 WHERE c.organization_id = :organizationId
                   AND card.product_variant_id = ANY (:permittedProductVariantIds)
                   AND (child.child_kind = 'COMPANY'
                        OR child.store_id = ANY (:permittedStoreIds))
                   AND (CAST(:productVariantId AS uuid) IS NULL
                        OR card.product_variant_id = CAST(:productVariantId AS uuid))
                   AND (CAST(:assigneeUserId AS uuid) IS NULL
                        OR c.assignee_user_id = CAST(:assigneeUserId AS uuid))
                   AND (""" + predicate + ")\n";
    }

    /**
     * A case, the child and variant it was raised on, and the acceptance
     * occupying it.
     *
     * <p>The card is the case's own; the occupying acceptance is the newest
     * request still holding the one-live-acceptance slot, when there is one.
     */
    private static final String CONSOLE_FROM = """
              FROM ops.availability_case c
              JOIN mart.availability_risk_child child
                ON child.id = c.child_id
               AND child.organization_id = c.organization_id
              JOIN mart.availability_risk_card card
                ON card.id = c.card_id
               AND card.organization_id = c.organization_id
              JOIN core.product_variant variant
                ON variant.id = card.product_variant_id
               AND variant.organization_id = card.organization_id
              LEFT JOIN core.store store
                ON store.id = child.store_id
               AND store.organization_id = child.organization_id
              LEFT JOIN core.marketplace_account account
                ON account.id = store.marketplace_account_id
               AND account.organization_id = store.organization_id
              LEFT JOIN core.platform_listing_variant listing_variant
                ON listing_variant.id = child.platform_listing_variant_id
               AND listing_variant.organization_id = child.organization_id
              LEFT JOIN LATERAL (
                   SELECT accepted.id, accepted.state, accepted.required_authority_level,
                          accepted.expires_at
                     FROM ops.availability_accepted_exception accepted
                    WHERE accepted.case_id = c.id
                      AND accepted.organization_id = c.organization_id
                      AND accepted.state IN ('REQUESTED', 'AUTHORITY_BLOCKED', 'ACTIVE')
                    ORDER BY accepted.requested_at DESC, accepted.id
                    LIMIT 1
              ) open_exception ON true
            """;

    private static final String CONSOLE_SELECT = """
            SELECT c.id, c.organization_id, c.card_id, c.child_id, c.cause_code, c.cause_key,
                   c.severity, c.state, c.accountable_role_code, c.assignee_user_id,
                   c.action_due_at, c.outcome_due_at, c.original_action_due_at,
                   c.action_sla_paused_at, c.action_sla_remaining_ms, c.reopen_count,
                   c.escalation_level, c.first_activated_at, c.last_evidence_at,
                   c.improvement_first_seen_at,
                   card.product_variant_id AS subject_variant_id,
                   variant.sku_code AS subject_sku_code,
                   variant.display_name AS subject_display_name,
                   child.child_kind AS subject_child_kind,
                   account.platform_code AS subject_platform_code,
                   child.fulfillment_mode_code AS subject_fulfillment_mode_code,
                   child.store_id AS subject_store_id,
                   store.code AS subject_store_code,
                   store.display_name AS subject_store_name,
                   child.platform_listing_variant_id AS subject_listing_variant_id,
                   coalesce(listing_variant.native_sku_key, listing_variant.native_variant_key)
                       AS subject_platform_sku_key,
                   child.profit_at_risk_amount AS subject_profit_at_risk_amount,
                   child.profit_at_risk_currency AS subject_profit_at_risk_currency,
                   open_exception.id AS open_exception_id,
                   open_exception.state AS open_exception_state,
                   open_exception.required_authority_level AS open_exception_authority,
                   open_exception.expires_at AS open_exception_expires_at
            """ + CONSOLE_FROM;

    private static ConsoleCaseRow mapConsole(java.sql.ResultSet rows, int rowNumber)
            throws java.sql.SQLException {
        UUID openId = rows.getObject("open_exception_id", UUID.class);
        return new ConsoleCaseRow(
                map(rows, rowNumber),
                new CaseSubject(
                        rows.getObject("subject_variant_id", UUID.class),
                        rows.getString("subject_sku_code"),
                        rows.getString("subject_display_name"),
                        rows.getString("subject_child_kind"),
                        rows.getString("subject_platform_code"),
                        rows.getString("subject_fulfillment_mode_code"),
                        rows.getObject("subject_store_id", UUID.class),
                        rows.getString("subject_store_code"),
                        rows.getString("subject_store_name"),
                        rows.getObject("subject_listing_variant_id", UUID.class),
                        rows.getString("subject_platform_sku_key"),
                        rows.getBigDecimal("subject_profit_at_risk_amount"),
                        rows.getString("subject_profit_at_risk_currency")),
                openId == null ? null : new OpenException(openId,
                        rows.getString("open_exception_state"),
                        rows.getString("open_exception_authority"),
                        rows.getTimestamp("open_exception_expires_at") == null
                                ? null : rows.getTimestamp("open_exception_expires_at").toInstant()));
    }

    /**
     * One case with what the console needs beside it.
     *
     * @param openException the acceptance occupying the case, or {@code null}
     */
    public record ConsoleCaseRow(AvailabilityCaseView view, CaseSubject subject,
                                 OpenException openException) {
    }

    /**
     * What a case is about: the variant, and for a channel child the exact
     * store, marketplace, listing variant and fulfillment mode.
     *
     * @param platformSkuKey the marketplace's own key for the listing variant, or {@code null}
     * @param profitAtRiskAmount the child's calculated exposure, or {@code null}
     */
    public record CaseSubject(UUID productVariantId, String skuCode, String displayName,
                              String childKind, String platformCode, String fulfillmentModeCode,
                              UUID storeId, String storeCode, String storeName,
                              UUID platformListingVariantId, String platformSkuKey,
                              java.math.BigDecimal profitAtRiskAmount,
                              String profitAtRiskCurrency) {
    }

    /** The acceptance occupying a case. */
    public record OpenException(UUID id, String state, String requiredAuthority,
                                Instant expiresAt) {
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
                             original_action_due_at,
                             outcome_due_at, activation_policy_id, first_activated_at,
                             last_evidence_at, correlation_id, created_at, updated_at)
                        VALUES (:id, :organizationId, :cardId, :childId, :causeCode, :causeKey,
                                :childKind, :severity, 'OPEN', :roleCode, :actionDueAt,
                                :actionDueAt,
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
                               action_due_at = CASE WHEN state = 'ACCEPTED_RISK'
                                   THEN action_due_at ELSE :actionDueAt END,
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

    /** Pause only the ordinary Action SLA; no history or original deadline is erased. */
    public void pauseActionSla(UUID id, Instant at) {
        jdbc.sql("""
                        UPDATE ops.availability_case
                           SET state = 'ACCEPTED_RISK',
                               action_sla_paused_at = :at,
                               action_sla_remaining_ms = greatest(0,
                                   floor(extract(epoch FROM (action_due_at - :at)) * 1000)),
                               updated_at = :at,
                               version = version + 1
                         WHERE id = :id
                           AND action_sla_paused_at IS NULL
                        """)
                .param("id", id).param("at", Timestamp.from(at)).update();
    }

    /** Resume deterministically from the exact remainder stored at acceptance. */
    public void resumeActionSla(UUID id, Instant at) {
        jdbc.sql("""
                        UPDATE ops.availability_case
                           SET state = 'REOPENED',
                               action_due_at = CAST(:at AS timestamptz)
                                   + action_sla_remaining_ms * interval '1 millisecond',
                               action_sla_paused_at = NULL,
                               action_sla_remaining_ms = NULL,
                               updated_at = :at,
                               version = version + 1
                         WHERE id = :id
                           AND action_sla_paused_at IS NOT NULL
                           AND action_sla_remaining_ms IS NOT NULL
                        """)
                .param("id", id).param("at", Timestamp.from(at)).update();
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
                   original_action_due_at, action_sla_paused_at, action_sla_remaining_ms,
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
                rows.getTimestamp("original_action_due_at").toInstant(),
                rows.getTimestamp("action_sla_paused_at") == null
                        ? null : rows.getTimestamp("action_sla_paused_at").toInstant(),
                rows.getObject("action_sla_remaining_ms", Long.class),
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
