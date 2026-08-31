package com.mimococo.marketops.operationsworkflow;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * How a calculated risk becomes somebody's work.
 *
 * <p>The workflow module owns cases; the module that calculates risk asks for
 * one through this contract rather than writing case rows itself. That keeps
 * one Task authority, and it keeps the dependency in one direction — the risk
 * module knows about workflow, and workflow never has to know what a stockout
 * is.
 *
 * <p>Activation is idempotent on the cause. Calling it a thousand times for one
 * cause updates one case and appends evidence; it does not raise a thousand
 * tasks.
 */
public interface AvailabilityCaseIntake {

    /**
     * Raise or refresh the case for one cause.
     *
     * @param request what the calculation established
     * @return the case that now governs the cause
     */
    AvailabilityCaseView activate(CaseActivation request);

    /**
     * Record structured, attributable action evidence.
     *
     * <p>Recording an action moves the case to verification. It never moves it
     * to success: whether the business risk improved is a separate observation
     * that has not been made yet.
     */
    AvailabilityCaseView recordAction(UUID caseId, UUID actorUserId, String actorRoleCode,
                                      CaseActionKind actionKind, String evidenceReference,
                                      String reason);

    /**
     * Record what a fresh cause-specific observation showed.
     *
     * <p>Only {@link CaseVerificationOutcome#VERIFIED} closes the case. A
     * failure or a regression returns it to somebody with its history intact.
     */
    AvailabilityCaseView observeVerification(UUID caseId, String verificationKind,
                                             CaseVerificationOutcome outcome, Instant observedAt,
                                             String reason);

    /**
     * Every live case awaiting an outcome for one exact calculated child.
     *
     * <p>Keyed on the child rather than on the cause, because by the time a
     * cause is repaired the recalculated child no longer carries it. Looking
     * the case up by its current cause would find nothing precisely when the
     * good news arrived, and the case would wait for a person forever.
     */
    java.util.List<AvailabilityCaseView> awaitingOutcome(UUID childId);

    /**
     * Record what a fresh recalculation showed about the cause itself.
     *
     * <p>The caller reports one fact — whether the cause-specific condition
     * holds right now — and this authority decides what that means. Keeping the
     * decision here is the point: "improved and held through the governed
     * window" is a rule about time and about the case's own history, and a
     * caller that could name the outcome directly could name success on the
     * first good reading.
     *
     * @param caseId the case
     * @param verificationKind what was observed
     * @param conditionHolds whether the cause is repaired at this instant
     * @param observedAt when the observation was made
     * @param verificationWindow how long the improvement must hold to count
     * @return the case, moved only if the observation moved it
     */
    AvailabilityCaseView observeCondition(UUID caseId, String verificationKind,
                                          boolean conditionHolds, Instant observedAt,
                                          java.time.Duration verificationWindow);

    /** Reopen the same case because the risk returned or its evidence expired. */
    AvailabilityCaseView reopen(UUID caseId, String reason, Instant at);

    /** Raise the case to a higher authority under policy. */
    AvailabilityCaseView escalate(UUID caseId, String reason, Instant at);

    /** The live case governing a cause, when one exists. */
    Optional<AvailabilityCaseView> liveCase(UUID organizationId, String causeKey);

    /**
     * What a calculation established about one cause.
     *
     * @param organizationId owning organization
     * @param cardId the card
     * @param childId the exact child
     * @param childKind {@code CHANNEL} or {@code COMPANY}
     * @param causeCode why somebody is needed
     * @param causeKey the deduplication identity
     * @param severity the calculated lane
     * @param accountableRoleCode the role that owns the cause
     * @param activationPolicyId the work-activation version in force
     * @param actionDueAt when the action stage is due
     * @param outcomeDueAt when the outcome stage is due
     * @param correlationId the calculation's correlation identity
     * @param at the calculation instant
     */
    record CaseActivation(
            UUID organizationId,
            UUID cardId,
            UUID childId,
            String childKind,
            String causeCode,
            String causeKey,
            String severity,
            String accountableRoleCode,
            UUID activationPolicyId,
            Instant actionDueAt,
            Instant outcomeDueAt,
            String correlationId,
            Instant at) {
    }
}
