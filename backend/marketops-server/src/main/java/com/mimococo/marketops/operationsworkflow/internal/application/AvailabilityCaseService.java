package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseIntake;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseState;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseView;
import com.mimococo.marketops.operationsworkflow.CaseActionKind;
import com.mimococo.marketops.operationsworkflow.CaseVerificationOutcome;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityCaseRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The accountable case lifecycle.
 *
 * <p>Two rules shape every method here.
 *
 * <p>A cause has one case. Activation looks for a live case first, and the
 * database's partial unique index catches the two threads that both looked and
 * both found nothing. When it fires, the loser re-reads and refreshes rather
 * than failing: two workers recalculating the same variant at the same instant
 * is ordinary, and it should produce one case, not an error.
 *
 * <p>Action is not outcome. {@link #recordAction} moves a case to
 * {@code VERIFYING} and never to success, and only a fresh cause-specific
 * observation through {@link #observeVerification} can produce
 * {@code VERIFIED_SUCCESS}. A failure or a regression returns the case to
 * somebody with its journal intact.
 */
@Service
public class AvailabilityCaseService implements AvailabilityCaseIntake {

    private final AvailabilityCaseRepository cases;
    private final MetadataAuditRecorder audit;
    private final IdGenerator ids;
    private final Clock clock;

    public AvailabilityCaseService(AvailabilityCaseRepository cases, MetadataAuditRecorder audit,
                                   IdGenerator ids, Clock clock) {
        this.cases = cases;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AvailabilityCaseView activate(CaseActivation request) {
        Optional<AvailabilityCaseView> live =
                cases.liveByCause(request.organizationId(), request.causeKey());
        if (live.isPresent()) {
            return appendEvidenceTo(live.get(), request);
        }
        UUID id = ids.newId();
        try {
            cases.insert(new AvailabilityCaseRepository.NewCase(id, request.organizationId(),
                    request.cardId(), request.childId(), request.causeCode(), request.causeKey(),
                    request.childKind(), request.severity(), request.accountableRoleCode(),
                    request.actionDueAt(), request.outcomeDueAt(), request.activationPolicyId(),
                    request.correlationId(), request.at()));
        } catch (DuplicateKeyException concurrent) {
            // Another worker raised the same cause between the read and the
            // insert. That is the index doing its job, not a failure.
            return cases.liveByCause(request.organizationId(), request.causeKey())
                    .map(existing -> appendEvidenceTo(existing, request))
                    .orElseThrow(() -> OperationRejectedException.of(ErrorCode.VERSION_CONFLICT));
        }
        cases.appendEvent(event(id, request.organizationId(), "ACTIVATED", null, "OPEN",
                "cause " + request.causeCode() + " activated at severity " + request.severity(),
                request.correlationId(), request.at()));
        record(request.organizationId(), id, AuditAction.CREATE, "availability-risk-calculation",
                Map.of("state", new com.mimococo.marketops.adminobservability.audit.FieldChange(
                        null, "OPEN")));
        return cases.find(id).orElseThrow();
    }

    @Override
    @Transactional
    public AvailabilityCaseView recordAction(UUID caseId, UUID actorUserId, String actorRoleCode,
                                             CaseActionKind actionKind, String evidenceReference,
                                             String reason) {
        AvailabilityCaseView existing = require(caseId);
        requireTransition(existing, AvailabilityCaseState.ACTION_RECORDED);
        if (actionKind == null || blank(evidenceReference) || actorUserId == null) {
            // The schema refuses this too. Refusing it here as well means the
            // caller gets a stable error code instead of a constraint name.
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Instant now = clock.instant();
        cases.appendEvent(new AvailabilityCaseRepository.CaseEvent(ids.newId(), caseId,
                existing.organizationId(), "ACTION_RECORDED", existing.state().name(),
                AvailabilityCaseState.ACTION_RECORDED.name(), actionKind.name(),
                "{\"reference\":\"" + escape(evidenceReference) + "\"}", null, null,
                actorUserId, actorRoleCode, reason, evidenceReference, null, now,
                "case-" + caseId));
        cases.transition(new AvailabilityCaseRepository.Transition(caseId,
                AvailabilityCaseState.ACTION_RECORDED, now, null, null, null, null, null,
                0, 0, now));

        // Recording an action starts the outcome clock immediately. The point
        // of the second stage is that nobody has to remember to start it.
        cases.appendEvent(event(caseId, existing.organizationId(), "VERIFICATION_STARTED",
                AvailabilityCaseState.ACTION_RECORDED.name(),
                AvailabilityCaseState.VERIFYING.name(),
                "waiting for fresh cause-specific evidence that the risk improved",
                "case-" + caseId, now));
        cases.transition(new AvailabilityCaseRepository.Transition(caseId,
                AvailabilityCaseState.VERIFYING, now, now, null, null, null, null, 0, 0, now));
        record(existing.organizationId(), caseId, AuditAction.STATUS_CHANGE,
                actorUserId.toString(),
                Map.of("state", new com.mimococo.marketops.adminobservability.audit.FieldChange(
                        existing.state().name(), AvailabilityCaseState.VERIFYING.name())));
        return cases.find(caseId).orElseThrow();
    }

    @Override
    @Transactional
    public AvailabilityCaseView observeVerification(UUID caseId, String verificationKind,
                                                    CaseVerificationOutcome outcome,
                                                    Instant observedAt, String reason) {
        AvailabilityCaseView existing = require(caseId);
        if (existing.state() != AvailabilityCaseState.VERIFYING
                && existing.state() != AvailabilityCaseState.ACTION_RECORDED) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        Instant now = clock.instant();
        cases.appendEvent(new AvailabilityCaseRepository.CaseEvent(ids.newId(), caseId,
                existing.organizationId(), "VERIFICATION_OBSERVED", existing.state().name(),
                null, null, null, verificationKind, outcome.name(), null, null, reason, null,
                observedAt, now, "case-" + caseId));

        AvailabilityCaseState next = switch (outcome) {
            case VERIFIED -> AvailabilityCaseState.VERIFIED_SUCCESS;
            case CONTINUING -> AvailabilityCaseState.VERIFYING;
            case FAILED -> AvailabilityCaseState.REWORK_REQUIRED;
            case REGRESSED -> AvailabilityCaseState.REOPENED;
        };
        if (next == AvailabilityCaseState.VERIFYING) {
            // Nothing has been decided yet; the outcome clock keeps running.
            return cases.find(caseId).orElseThrow();
        }
        requireTransition(existing, next);
        boolean verified = next == AvailabilityCaseState.VERIFIED_SUCCESS;
        cases.transition(new AvailabilityCaseRepository.Transition(caseId, next, now, now,
                verified ? now : null, verified ? now : null,
                verified ? "fresh cause-specific evidence showed the risk improved" : null,
                null, next == AvailabilityCaseState.REOPENED ? 1 : 0, 0, now));
        cases.appendEvent(event(caseId, existing.organizationId(),
                verified ? "VERIFIED_SUCCESS" : next.name(), existing.state().name(),
                next.name(), reason, "case-" + caseId, now));
        record(existing.organizationId(), caseId, AuditAction.STATUS_CHANGE,
                "availability-outcome-verification",
                Map.of("state", new com.mimococo.marketops.adminobservability.audit.FieldChange(
                        existing.state().name(), next.name())));
        return cases.find(caseId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<AvailabilityCaseView> awaitingOutcome(UUID childId) {
        return cases.awaitingOutcome(childId);
    }

    @Override
    @Transactional
    public AvailabilityCaseView observeCondition(UUID caseId, String verificationKind,
                                                 boolean conditionHolds, Instant observedAt,
                                                 Duration verificationWindow) {
        AvailabilityCaseView existing = require(caseId);
        if (existing.state() != AvailabilityCaseState.VERIFYING
                && existing.state() != AvailabilityCaseState.ACTION_RECORDED) {
            // Nothing is waiting on an outcome here. Recording one would be an
            // observation about a case nobody is verifying.
            return existing;
        }

        CaseVerificationOutcome outcome = outcomeOf(existing, conditionHolds, observedAt,
                verificationWindow);
        if (conditionHolds && existing.improvementFirstSeenAt() == null) {
            cases.markImprovement(caseId, observedAt, clock.instant());
        }
        if (!conditionHolds && existing.improvementFirstSeenAt() != null) {
            // The improvement is over. Clearing it means the governed window
            // starts again from the next improvement rather than counting time
            // during which the risk was back.
            cases.markImprovement(caseId, null, clock.instant());
        }
        return observeVerification(caseId, verificationKind, outcome, observedAt,
                reasonFor(outcome, conditionHolds, verificationWindow));
    }

    /**
     * What a fresh reading of the cause means for the case.
     *
     * <p>Four answers, and none of them is reachable by the caller directly. An
     * improvement that has not yet held for the governed window is not success;
     * an improvement that has gone away is not a failure of the action but a
     * regression of the risk; and a risk that has not improved is only a
     * failure once the outcome was actually due.
     */
    private static CaseVerificationOutcome outcomeOf(AvailabilityCaseView existing,
                                                     boolean conditionHolds, Instant observedAt,
                                                     Duration verificationWindow) {
        if (conditionHolds) {
            Instant since = existing.improvementFirstSeenAt();
            if (since == null) {
                return CaseVerificationOutcome.CONTINUING;
            }
            return observedAt.isBefore(since.plus(verificationWindow))
                    ? CaseVerificationOutcome.CONTINUING
                    : CaseVerificationOutcome.VERIFIED;
        }
        if (existing.improvementFirstSeenAt() != null) {
            return CaseVerificationOutcome.REGRESSED;
        }
        return existing.outcomeDueAt() != null && observedAt.isAfter(existing.outcomeDueAt())
                ? CaseVerificationOutcome.FAILED
                : CaseVerificationOutcome.CONTINUING;
    }

    private static String reasonFor(CaseVerificationOutcome outcome, boolean conditionHolds,
                                    Duration verificationWindow) {
        return switch (outcome) {
            case VERIFIED -> "the cause stayed repaired through the governed window of "
                    + verificationWindow.toMinutes() + " minutes";
            case REGRESSED -> "the cause had been repaired and is present again";
            case FAILED -> "the cause is still present past the outcome deadline";
            case CONTINUING -> conditionHolds
                    ? "the cause is repaired and the governed window has not elapsed"
                    : "the cause is still present and the outcome is not yet due";
        };
    }

    @Override
    @Transactional
    public AvailabilityCaseView reopen(UUID caseId, String reason, Instant at) {
        AvailabilityCaseView existing = require(caseId);
        requireTransition(existing, AvailabilityCaseState.REOPENED);
        cases.transition(new AvailabilityCaseRepository.Transition(caseId,
                AvailabilityCaseState.REOPENED, null, null, null, null, null, null, 1, 0, at));
        cases.appendEvent(event(caseId, existing.organizationId(), "REOPENED",
                existing.state().name(), AvailabilityCaseState.REOPENED.name(), reason,
                "case-" + caseId, at));
        record(existing.organizationId(), caseId, AuditAction.STATUS_CHANGE,
                "availability-risk-calculation",
                Map.of("state", new com.mimococo.marketops.adminobservability.audit.FieldChange(
                        existing.state().name(), AvailabilityCaseState.REOPENED.name())));
        return cases.find(caseId).orElseThrow();
    }

    @Override
    @Transactional
    public AvailabilityCaseView escalate(UUID caseId, String reason, Instant at) {
        AvailabilityCaseView existing = require(caseId);
        requireTransition(existing, AvailabilityCaseState.ESCALATED);
        if (existing.escalationLevel() >= 3) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        cases.transition(new AvailabilityCaseRepository.Transition(caseId,
                AvailabilityCaseState.ESCALATED, null, null, null, null, null, null, 0, 1, at));
        cases.appendEvent(event(caseId, existing.organizationId(), "ESCALATED",
                existing.state().name(), AvailabilityCaseState.ESCALATED.name(), reason,
                "case-" + caseId, at));
        record(existing.organizationId(), caseId, AuditAction.STATUS_CHANGE,
                "availability-escalation-policy",
                Map.of("escalationLevel",
                        new com.mimococo.marketops.adminobservability.audit.FieldChange(
                                String.valueOf(existing.escalationLevel()),
                                String.valueOf(existing.escalationLevel() + 1))));
        return cases.find(caseId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AvailabilityCaseView> liveCase(UUID organizationId, String causeKey) {
        return cases.liveByCause(organizationId, causeKey);
    }

    /**
     * Put a case under a governed acceptance.
     *
     * <p>Not on {@link AvailabilityCaseIntake}, because a calculation must never
     * be able to reach it: accepting a risk is a business decision taken by a
     * named person under a versioned policy, and the only caller is the
     * exception governance that enforces both.
     *
     * <p>The case is disposed of, not closed. It never reaches
     * {@code VERIFIED_SUCCESS} this way and the calculated risk it points at is
     * unchanged, which is what keeps an acceptance from becoming a way to make
     * a red card green.
     */
    @Transactional
    public AvailabilityCaseView acceptRisk(UUID caseId, String reason, Instant at) {
        AvailabilityCaseView existing = require(caseId);
        requireTransition(existing, AvailabilityCaseState.ACCEPTED_RISK);
        cases.transition(new AvailabilityCaseRepository.Transition(caseId,
                AvailabilityCaseState.ACCEPTED_RISK, null, null, null, null, null, null,
                0, 0, at));
        cases.appendEvent(event(caseId, existing.organizationId(), "EXCEPTION_APPLIED",
                existing.state().name(), AvailabilityCaseState.ACCEPTED_RISK.name(), reason,
                "case-" + caseId, at));
        record(existing.organizationId(), caseId, AuditAction.STATUS_CHANGE,
                "availability-exception-governance",
                Map.of("state", new com.mimococo.marketops.adminobservability.audit.FieldChange(
                        existing.state().name(), AvailabilityCaseState.ACCEPTED_RISK.name())));
        return cases.find(caseId).orElseThrow();
    }

    /**
     * Return a case from acceptance to somebody, with its history.
     *
     * <p>Separate from {@link #reopen} only in the event it journals: a reader
     * reviewing why an acceptance failed needs to see that this reopen was an
     * invalidation rather than a returning risk.
     */
    @Transactional
    public AvailabilityCaseView reopenFromException(UUID caseId, String reason, Instant at) {
        AvailabilityCaseView existing = require(caseId);
        requireTransition(existing, AvailabilityCaseState.REOPENED);
        cases.transition(new AvailabilityCaseRepository.Transition(caseId,
                AvailabilityCaseState.REOPENED, null, null, null, null, null, null, 1, 0, at));
        cases.appendEvent(event(caseId, existing.organizationId(), "EXCEPTION_INVALIDATED",
                existing.state().name(), AvailabilityCaseState.REOPENED.name(), reason,
                "case-" + caseId, at));
        record(existing.organizationId(), caseId, AuditAction.STATUS_CHANGE,
                "availability-exception-governance",
                Map.of("state", new com.mimococo.marketops.adminobservability.audit.FieldChange(
                        existing.state().name(), AvailabilityCaseState.REOPENED.name())));
        return cases.find(caseId).orElseThrow();
    }

    /**
     * Append what the latest calculation established to a case already open.
     *
     * <p>Severity may move under policy and the action deadline moves with it,
     * because a WATCH that became CRITICAL is more urgent than it was. The case
     * identity, its first activation and its journal do not move.
     */
    private AvailabilityCaseView appendEvidenceTo(AvailabilityCaseView existing,
                                                  CaseActivation request) {
        cases.refresh(existing.id(), request.severity(), request.actionDueAt(), request.at());
        cases.appendEvent(event(existing.id(), existing.organizationId(), "EVIDENCE_APPENDED",
                existing.state().name(), existing.state().name(),
                "recalculation confirmed cause " + request.causeCode()
                        + " at severity " + request.severity(),
                request.correlationId(), request.at()));
        return cases.find(existing.id()).orElseThrow();
    }

    private AvailabilityCaseView require(UUID caseId) {
        return cases.find(caseId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static void requireTransition(AvailabilityCaseView existing,
                                          AvailabilityCaseState next) {
        if (!existing.state().allowedNext().contains(next)) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private AvailabilityCaseRepository.CaseEvent event(UUID caseId, UUID organizationId,
                                                       String kind, String from, String to,
                                                       String reason, String correlationId,
                                                       Instant at) {
        return new AvailabilityCaseRepository.CaseEvent(ids.newId(), caseId, organizationId, kind,
                from, to, null, null, null, null, null, null, reason, null, null, at,
                correlationId);
    }

    /**
     * Journal one case movement.
     *
     * <p>The actor is the person when a person caused the movement and the
     * calculating component when nothing human did. Attributing an automatic
     * activation to a person would be a fabricated attribution, which the audit
     * contract refuses on principle.
     */
    private void record(UUID organizationId, UUID caseId, AuditAction action, String actorId,
                        Map<String, com.mimococo.marketops.adminobservability.audit.FieldChange>
                                changes) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW,
                actorId, action, "availability_case", caseId, null,
                changes, "availability case lifecycle", null));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** How long after an action the outcome is expected, when policy says nothing. */
    static Duration defaultOutcomeWindow() {
        return Duration.ofDays(2);
    }
}
