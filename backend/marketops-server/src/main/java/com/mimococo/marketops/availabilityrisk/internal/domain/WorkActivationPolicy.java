package com.mimococo.marketops.availabilityrisk.internal.domain;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * When a calculated lane becomes somebody's work, and by when.
 *
 * <p>The rule is the organization's, not the code's. Every number here was
 * published by a named owner with a reason and an evidence reference; this type
 * only applies them, which is why it holds no default and refuses to be
 * constructed without one.
 *
 * <p>The shape follows the Contract exactly:
 *
 * <pre>
 *   CRITICAL                          activate now
 *   HIGH                              activate once the run has been sustained
 *   WATCH                             queue-visible, never activated here
 *   REVIEW / UNRESOLVED               cause-specific remediation, activate now
 * </pre>
 *
 * <p>The sustained gate exists because a HIGH that appeared in this cycle and a
 * HIGH that has held for four are different situations. Activating the first
 * one fills a queue with work that resolves itself before anybody opens it, and
 * an operator who learns that the queue cries wolf skims the CRITICAL rows too.
 *
 * @param policyId the published version's identity
 * @param policyVersion its version number
 * @param highSustainedCycles consecutive HIGH evaluations before HIGH is work
 * @param criticalActionSla how long a CRITICAL action may take
 * @param highActionSla how long a HIGH action may take
 * @param blockerActionSla how long a remediation action may take
 * @param outcomeSla how long after the action the outcome must be verified
 * @param verificationWindow how long the improvement must hold to count
 */
public record WorkActivationPolicy(
        UUID policyId,
        int policyVersion,
        int highSustainedCycles,
        Duration criticalActionSla,
        Duration highActionSla,
        Duration blockerActionSla,
        Duration outcomeSla,
        Duration verificationWindow) {

    public WorkActivationPolicy {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(criticalActionSla, "criticalActionSla");
        Objects.requireNonNull(highActionSla, "highActionSla");
        Objects.requireNonNull(blockerActionSla, "blockerActionSla");
        Objects.requireNonNull(outcomeSla, "outcomeSla");
        Objects.requireNonNull(verificationWindow, "verificationWindow");
        if (highSustainedCycles < 1) {
            throw new IllegalArgumentException(
                    "a sustained condition of fewer than one cycle is not a condition");
        }
    }

    /**
     * Whether this child is work yet, and when its stages fall due.
     *
     * @param child the calculated child
     * @param sustainedCycles consecutive calculations that produced its lane
     * @param at the calculation instant
     * @return the activation, or empty when the lane is not work
     */
    public Optional<Activation> decide(ChildRisk child, int sustainedCycles, Instant at) {
        if (!child.cause().actionable()) {
            return Optional.empty();
        }
        AvailabilityLane lane = child.lane();
        if (lane == AvailabilityLane.HEALTHY || lane == AvailabilityLane.WATCH) {
            // WATCH stays visible in the queue and raises nothing. Promotion is
            // a person's decision, and this policy does not take it for them.
            return Optional.empty();
        }

        // A defect is a defect on its first sighting. Waiting for a data or
        // policy blocker to repeat before anybody is told would leave the
        // calculation blind for exactly as long as the gate lasts.
        if (child.cause().blocker()) {
            return Optional.of(activation(blockerActionSla, at,
                    "cause-specific remediation for " + child.cause()));
        }
        if (lane == AvailabilityLane.CRITICAL) {
            return Optional.of(activation(criticalActionSla, at,
                    "critical availability risk activates on the calculation that found it"));
        }
        if (sustainedCycles >= highSustainedCycles) {
            return Optional.of(activation(highActionSla, at,
                    "high availability risk sustained for " + sustainedCycles
                            + " of " + highSustainedCycles + " required cycles"));
        }
        return Optional.empty();
    }

    private Activation activation(Duration actionSla, Instant at, String reason) {
        Instant actionDueAt = at.plus(actionSla);
        // The outcome clock runs from the action deadline rather than from now:
        // the outcome cannot be verified before the action exists, and a single
        // combined clock would make a late action look like a late outcome.
        return new Activation(actionDueAt, actionDueAt.plus(outcomeSla), reason);
    }

    /**
     * One activation's deadlines.
     *
     * @param actionDueAt when accountable structured action is due
     * @param outcomeDueAt when fresh outcome evidence is due
     * @param reason why the policy activated
     */
    public record Activation(Instant actionDueAt, Instant outcomeDueAt, String reason) {
    }
}
