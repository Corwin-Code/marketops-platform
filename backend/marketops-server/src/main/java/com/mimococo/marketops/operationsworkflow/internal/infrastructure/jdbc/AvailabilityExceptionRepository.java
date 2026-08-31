package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import com.mimococo.marketops.operationsworkflow.AcceptedExceptionState;
import com.mimococo.marketops.operationsworkflow.AcceptedExceptionView;
import com.mimococo.marketops.operationsworkflow.ExceptionAuthorityLevel;
import com.mimococo.marketops.operationsworkflow.ExceptionReasonCode;
import com.mimococo.marketops.operationsworkflow.ExceptionScopeKind;
import com.mimococo.marketops.operationsworkflow.internal.domain.ExceptionMaterialityPolicy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes accepted-risk requests and the decisions taken on them.
 *
 * <p>The request row carries state; the decision rows are append-only. A second
 * approval of the same request is refused by a partial unique index rather than
 * by a service check, because a second differently-bounded licence to ignore
 * one risk is precisely the thing that must be impossible rather than merely
 * unlikely.
 */
@Repository
public class AvailabilityExceptionRepository {

    private static final String SELECT = """
            SELECT id, organization_id, case_id, child_id, cause_code, scope_kind,
                   scope_reference, reason_code, rationale, expected_consequence,
                   consequence_amount, consequence_currency, evidence_reference,
                   requested_by_user_id, requested_at, decision_owner_role_code,
                   required_authority_level, state, effective_from, expires_at, review_at,
                   invalidated_at, invalidation_reason, materiality_policy_id, occurrence_count
              FROM ops.availability_accepted_exception
            """;

    private final JdbcClient jdbc;

    public AvailabilityExceptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The materiality policy in force, or empty when none is.
     *
     * <p>Empty is the fail-closed answer. Nothing in this repository invents a
     * threshold, so a caller that finds nothing here cannot accidentally size a
     * decision by a rule the organization never published.
     */
    public Optional<ExceptionMaterialityPolicy> resolveMateriality(UUID organizationId,
                                                                   Instant asOf) {
        return jdbc.sql("""
                        SELECT policy.id, policy.policy_version, policy.currency_code,
                               policy.material_profit_at_risk, policy.material_duration_days,
                               policy.repeat_occurrence_count, policy.repeat_lookback_days,
                               policy.max_exception_days
                          FROM core.exception_materiality_policy AS policy
                         WHERE policy.organization_id = :organizationId
                           AND policy.status = 'ACTIVE'
                           AND policy.effective_from <= :asOf
                           AND (policy.effective_to IS NULL OR policy.effective_to > :asOf)
                         LIMIT 1
                        """)
                .param("organizationId", organizationId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new ExceptionMaterialityPolicy(
                        rows.getObject("id", UUID.class),
                        rows.getInt("policy_version"),
                        rows.getString("currency_code"),
                        rows.getBigDecimal("material_profit_at_risk"),
                        Duration.ofDays(rows.getInt("material_duration_days")),
                        rows.getInt("repeat_occurrence_count"),
                        Duration.ofDays(rows.getInt("repeat_lookback_days")),
                        Duration.ofDays(rows.getInt("max_exception_days"))))
                .optional();
    }

    /** Record a request, whatever answer it is going to get. */
    public void insert(NewException row) {
        jdbc.sql("""
                        INSERT INTO ops.availability_accepted_exception
                            (id, organization_id, case_id, child_id, cause_code, scope_kind,
                             scope_reference, reason_code, rationale, expected_consequence,
                             consequence_amount, consequence_currency, evidence_reference,
                             requested_by_user_id, requested_at, decision_owner_role_code,
                             required_authority_level, state, effective_from, expires_at,
                             review_at, materiality_policy_id, policy_version, occurrence_count,
                             created_at, updated_at)
                        VALUES (:id, :organizationId, :caseId, :childId, :causeCode, :scopeKind,
                                :scopeReference, :reasonCode, :rationale, :consequence,
                                :amount, :currency, :evidenceReference, :requestedBy, :at,
                                :roleCode, :authority, :state, :effectiveFrom, :expiresAt,
                                :reviewAt, :policyId, :policyVersion, :occurrenceCount, :at, :at)
                        """)
                .param("id", row.id()).param("organizationId", row.organizationId())
                .param("caseId", row.caseId()).param("childId", row.childId())
                .param("causeCode", row.causeCode())
                .param("scopeKind", row.scopeKind().name())
                .param("scopeReference", row.scopeReference())
                .param("reasonCode", row.reasonCode().name())
                .param("rationale", row.rationale())
                .param("consequence", row.expectedConsequence())
                .param("amount", row.consequenceAmount())
                .param("currency", row.consequenceCurrency())
                .param("evidenceReference", row.evidenceReference())
                .param("requestedBy", row.requestedByUserId())
                .param("roleCode", row.decisionOwnerRoleCode())
                .param("authority", row.requiredAuthority().name())
                .param("state", row.state().name())
                .param("effectiveFrom", Timestamp.from(row.effectiveFrom()))
                .param("expiresAt", Timestamp.from(row.expiresAt()))
                .param("reviewAt", Timestamp.from(row.reviewAt()))
                .param("policyId", row.materialityPolicyId())
                .param("policyVersion", row.policyVersion())
                .param("occurrenceCount", row.occurrenceCount())
                .param("at", Timestamp.from(row.at()))
                .update();
    }

    /** One request by identity. */
    public Optional<AcceptedExceptionView> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(AvailabilityExceptionRepository::map)
                .optional();
    }

    /** The request occupying one cause and scope, when one exists. */
    public Optional<AcceptedExceptionView> occupying(UUID organizationId, UUID childId,
                                                     String causeCode, ExceptionScopeKind scopeKind,
                                                     String scopeReference) {
        return jdbc.sql(SELECT + """
                         WHERE organization_id = :organizationId
                           AND child_id = :childId
                           AND cause_code = :causeCode
                           AND scope_kind = :scopeKind
                           AND scope_reference = :scopeReference
                           AND state IN ('REQUESTED', 'AUTHORITY_BLOCKED', 'ACTIVE')
                        """)
                .param("organizationId", organizationId).param("childId", childId)
                .param("causeCode", causeCode).param("scopeKind", scopeKind.name())
                .param("scopeReference", scopeReference)
                .query(AvailabilityExceptionRepository::map)
                .optional();
    }

    /**
     * How often this cause has already been accepted on this scope.
     *
     * <p>Counted over acceptances that were actually granted, not over requests.
     * A request somebody refused is not evidence that the organization keeps
     * living with the problem.
     */
    public int countGrantedSince(UUID organizationId, UUID childId, String causeCode,
                                 Instant since) {
        Long count = jdbc.sql("""
                        SELECT count(*) FROM ops.availability_accepted_exception
                         WHERE organization_id = :organizationId
                           AND child_id = :childId
                           AND cause_code = :causeCode
                           AND requested_at >= :since
                           AND state IN ('ACTIVE', 'EXPIRED', 'INVALIDATED')
                        """)
                .param("organizationId", organizationId).param("childId", childId)
                .param("causeCode", causeCode).param("since", Timestamp.from(since))
                .query(Long.class).single();
        return count == null ? 0 : count.intValue();
    }

    /** Put a granted acceptance into force for exactly the period decided. */
    public void activate(UUID id, Instant effectiveFrom, Instant expiresAt, Instant reviewAt,
                         UUID materialityPolicyId, Integer policyVersion, Instant at) {
        jdbc.sql("""
                        UPDATE ops.availability_accepted_exception
                           SET state = 'ACTIVE', effective_from = :effectiveFrom,
                               expires_at = :expiresAt, review_at = :reviewAt,
                               materiality_policy_id = :policyId, policy_version = :policyVersion,
                               accepted_risk_digest = (
                                   SELECT md5(concat_ws('|', child.cause_code, child.lane,
                                       child.evidence_state, child.confidence_state,
                                       child.available_units, child.daily_demand_rate,
                                       child.days_of_cover, child.projected_stockout_at,
                                       child.conservative_proof::text, child.blocker_codes::text,
                                       card.policy_version_digest))
                                     FROM mart.availability_risk_child child
                                     JOIN mart.availability_risk_card card
                                       ON card.id = child.card_id
                                      AND card.organization_id = child.organization_id
                                    WHERE child.id = ops.availability_accepted_exception.child_id
                                      AND child.organization_id =
                                          ops.availability_accepted_exception.organization_id),
                               updated_at = :at, version = version + 1
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("effectiveFrom", Timestamp.from(effectiveFrom))
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("reviewAt", Timestamp.from(reviewAt))
                .param("policyId", materialityPolicyId).param("policyVersion", policyVersion)
                .param("at", Timestamp.from(at))
                .update();
    }

    /** Move a request to a state that grants nothing. */
    public void setState(UUID id, AcceptedExceptionState state, Instant at) {
        jdbc.sql("""
                        UPDATE ops.availability_accepted_exception
                           SET state = :state, updated_at = :at, version = version + 1
                         WHERE id = :id
                        """)
                .param("id", id).param("state", state.name()).param("at", Timestamp.from(at))
                .update();
    }

    /** End an acceptance, recording when and why. */
    public void close(UUID id, AcceptedExceptionState state, String reason, Instant at) {
        jdbc.sql("""
                        UPDATE ops.availability_accepted_exception
                           SET state = :state, invalidated_at = :at, invalidation_reason = :reason,
                               updated_at = :at, version = version + 1
                         WHERE id = :id
                        """)
                .param("id", id).param("state", state.name()).param("reason", reason)
                .param("at", Timestamp.from(at))
                .update();
    }

    /** Every acceptance whose granted period has run out. */
    public List<AcceptedExceptionView> dueForExpiry(UUID organizationId, Instant at) {
        return jdbc.sql(SELECT + """
                         WHERE organization_id = :organizationId
                           AND state = 'ACTIVE'
                           AND expires_at <= :at
                         ORDER BY expires_at
                        """)
                .param("organizationId", organizationId).param("at", Timestamp.from(at))
                .query(AvailabilityExceptionRepository::map)
                .list();
    }

    /** Active acceptances that must remain valid as their authorities change. */
    public List<AcceptedExceptionView> active(UUID organizationId, Instant at) {
        return jdbc.sql(SELECT + """
                         WHERE organization_id = :organizationId
                           AND state = 'ACTIVE'
                           AND effective_from <= :at AND expires_at > :at
                         ORDER BY id
                        """)
                .param("organizationId", organizationId).param("at", Timestamp.from(at))
                .query(AvailabilityExceptionRepository::map).list();
    }

    /** Current calculated target to which one acceptance is relationally bound. */
    public Optional<CurrentRisk> currentRisk(UUID exceptionId) {
        return jdbc.sql("""
                        SELECT child.cause_code, child.lane, child.store_id,
                               child.platform_listing_variant_id,
                               child.fulfillment_mode_code, card.product_variant_id,
                               child.profit_at_risk_amount, child.profit_at_risk_currency,
                               md5(concat_ws('|', child.cause_code, child.lane,
                                   child.evidence_state, child.confidence_state,
                                   child.available_units, child.daily_demand_rate,
                                   child.days_of_cover, child.projected_stockout_at,
                                   child.conservative_proof::text, child.blocker_codes::text,
                                   card.policy_version_digest)) AS risk_digest,
                               accepted.accepted_risk_digest
                          FROM ops.availability_accepted_exception accepted
                          JOIN mart.availability_risk_child child
                            ON child.id = accepted.child_id
                           AND child.organization_id = accepted.organization_id
                          JOIN mart.availability_risk_card card
                            ON card.id = child.card_id
                           AND card.organization_id = child.organization_id
                         WHERE accepted.id = :exceptionId
                        """)
                .param("exceptionId", exceptionId)
                .query((rows, number) -> new CurrentRisk(
                        rows.getString("cause_code"), rows.getString("lane"),
                        rows.getObject("store_id", UUID.class),
                        rows.getObject("platform_listing_variant_id", UUID.class),
                        rows.getString("fulfillment_mode_code"),
                        rows.getObject("product_variant_id", UUID.class),
                        rows.getBigDecimal("profit_at_risk_amount"),
                        rows.getString("profit_at_risk_currency"),
                        rows.getString("risk_digest"),
                        rows.getString("accepted_risk_digest")))
                .optional();
    }

    /** Whether the approving identity still holds the role under which it decided. */
    public boolean approvalAuthorityLive(UUID exceptionId, Instant at) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                              FROM ops.availability_exception_decision decision
                              JOIN iam.user_account actor
                                ON actor.id = decision.decided_by_user_id
                               AND actor.organization_id = decision.organization_id
                              JOIN iam.user_role_assignment assignment
                                ON assignment.user_id = actor.id
                               AND assignment.organization_id = actor.organization_id
                               AND assignment.role_code = decision.decided_by_role_code
                             WHERE decision.exception_id = :exceptionId
                               AND decision.decision = 'APPROVED'
                               AND actor.status = 'ACTIVE'
                               AND assignment.status = 'ACTIVE'
                               AND assignment.effective_from <= :at
                               AND (assignment.effective_to IS NULL
                                    OR assignment.effective_to > :at))
                        """)
                .param("exceptionId", exceptionId).param("at", Timestamp.from(at))
                .query(Boolean.class).single());
    }

    /** Every acceptance recorded against one case, newest first. */
    public List<AcceptedExceptionView> forCase(UUID caseId) {
        return jdbc.sql(SELECT + " WHERE case_id = :caseId ORDER BY requested_at DESC")
                .param("caseId", caseId)
                .query(AvailabilityExceptionRepository::map)
                .list();
    }

    /** Append one decision. */
    public void insertDecision(DecisionRow row) {
        jdbc.sql("""
                        INSERT INTO ops.availability_exception_decision
                            (id, organization_id, exception_id, decision, authority_level,
                             decided_by_user_id, decided_by_role_code, delegation_reference,
                             requester_is_approver, separation_required, authenticated_at,
                             step_up_satisfied, reason, granted_effective_from,
                             granted_expires_at, decided_at, correlation_id)
                        VALUES (:id, :organizationId, :exceptionId, :decision, :authority,
                                :decidedBy, :roleCode, :delegation, :requesterIsApprover,
                                :separationRequired, :authenticatedAt, :stepUp, :reason,
                                :grantedFrom, :grantedUntil, :decidedAt, :correlationId)
                        """)
                .param("id", row.id()).param("organizationId", row.organizationId())
                .param("exceptionId", row.exceptionId()).param("decision", row.decision())
                .param("authority", row.authorityLevel().name())
                .param("decidedBy", row.decidedByUserId())
                .param("roleCode", row.decidedByRoleCode())
                .param("delegation", row.delegationReference())
                .param("requesterIsApprover", row.requesterIsApprover())
                .param("separationRequired", row.separationRequired())
                .param("authenticatedAt", row.authenticatedAt() == null
                        ? null : Timestamp.from(row.authenticatedAt()))
                .param("stepUp", row.stepUpSatisfied())
                .param("reason", row.reason())
                .param("grantedFrom", row.grantedEffectiveFrom() == null
                        ? null : Timestamp.from(row.grantedEffectiveFrom()))
                .param("grantedUntil", row.grantedExpiresAt() == null
                        ? null : Timestamp.from(row.grantedExpiresAt()))
                .param("decidedAt", Timestamp.from(row.decidedAt()))
                .param("correlationId", row.correlationId())
                .update();
    }

    private static AcceptedExceptionView map(ResultSet rows, int rowNumber) throws SQLException {
        return new AcceptedExceptionView(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("case_id", UUID.class),
                rows.getObject("child_id", UUID.class),
                rows.getString("cause_code"),
                ExceptionScopeKind.valueOf(rows.getString("scope_kind")),
                rows.getString("scope_reference"),
                ExceptionReasonCode.valueOf(rows.getString("reason_code")),
                rows.getString("rationale"),
                rows.getString("expected_consequence"),
                rows.getBigDecimal("consequence_amount"),
                rows.getString("consequence_currency"),
                rows.getString("evidence_reference"),
                rows.getObject("requested_by_user_id", UUID.class),
                instant(rows, "requested_at"),
                rows.getString("decision_owner_role_code"),
                ExceptionAuthorityLevel.valueOf(rows.getString("required_authority_level")),
                AcceptedExceptionState.valueOf(rows.getString("state")),
                instant(rows, "effective_from"),
                instant(rows, "expires_at"),
                instant(rows, "review_at"),
                instant(rows, "invalidated_at"),
                rows.getString("invalidation_reason"),
                rows.getObject("materiality_policy_id", UUID.class),
                rows.getInt("occurrence_count"));
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /**
     * A request to record.
     *
     * @param id the acceptance
     * @param organizationId owning organization
     * @param caseId the case it disposes of
     * @param childId the exact calculated child
     * @param causeCode the cause being accepted
     * @param scopeKind what it covers
     * @param scopeReference the exact scope
     * @param reasonCode the business reason
     * @param rationale why, in the requester's words
     * @param expectedConsequence what the business expects to lose
     * @param consequenceAmount the expected exposure, or {@code null}
     * @param consequenceCurrency its currency, or {@code null}
     * @param evidenceReference the evidence behind the request
     * @param requestedByUserId who asked
     * @param decisionOwnerRoleCode the role accountable for deciding
     * @param requiredAuthority the level the decision needs
     * @param state the state the request is recorded in
     * @param effectiveFrom the period the requester is asking for
     * @param expiresAt when that period ends
     * @param reviewAt when the acceptance must be reviewed
     * @param materialityPolicyId the version that sized it, or {@code null}
     * @param policyVersion its version number, or {@code null}
     * @param occurrenceCount acceptances of this cause inside the lookback
     * @param at when it was asked
     */
    public record NewException(UUID id, UUID organizationId, UUID caseId, UUID childId,
                               String causeCode, ExceptionScopeKind scopeKind,
                               String scopeReference, ExceptionReasonCode reasonCode,
                               String rationale, String expectedConsequence,
                               BigDecimal consequenceAmount, String consequenceCurrency,
                               String evidenceReference, UUID requestedByUserId,
                               String decisionOwnerRoleCode,
                               ExceptionAuthorityLevel requiredAuthority,
                               AcceptedExceptionState state, Instant effectiveFrom,
                               Instant expiresAt, Instant reviewAt, UUID materialityPolicyId,
                               Integer policyVersion, int occurrenceCount, Instant at) {
    }

    /**
     * One decision to append.
     *
     * @param id the decision
     * @param organizationId owning organization
     * @param exceptionId the request decided
     * @param decision {@code APPROVED}, {@code REJECTED} or {@code AUTHORITY_BLOCKED}
     * @param authorityLevel the level it was decided at
     * @param decidedByUserId who decided
     * @param decidedByRoleCode the role they decided as
     * @param delegationReference the delegation relied on, or {@code null}
     * @param requesterIsApprover whether the decider is the requester
     * @param separationRequired whether separation was required
     * @param authenticatedAt when they re-authenticated, or {@code null}
     * @param stepUpSatisfied whether the step-up requirement was met
     * @param reason why
     * @param grantedEffectiveFrom the granted start, or {@code null}
     * @param grantedExpiresAt the granted end, or {@code null}
     * @param decidedAt when it was decided
     * @param correlationId the decision's own identity
     */
    public record DecisionRow(UUID id, UUID organizationId, UUID exceptionId, String decision,
                              ExceptionAuthorityLevel authorityLevel, UUID decidedByUserId,
                              String decidedByRoleCode, String delegationReference,
                              boolean requesterIsApprover, boolean separationRequired,
                              Instant authenticatedAt, boolean stepUpSatisfied, String reason,
                              Instant grantedEffectiveFrom, Instant grantedExpiresAt,
                              Instant decidedAt, String correlationId) {
    }

    public record CurrentRisk(String causeCode, String severity, UUID storeId,
                              UUID platformListingVariantId, String fulfillmentModeCode,
                              UUID productVariantId, BigDecimal profitAtRiskAmount,
                              String profitAtRiskCurrency, String riskDigest,
                              String acceptedRiskDigest) {
    }
}
