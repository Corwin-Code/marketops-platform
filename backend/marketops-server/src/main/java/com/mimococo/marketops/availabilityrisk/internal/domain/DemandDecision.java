package com.mimococo.marketops.availabilityrisk.internal.domain;

import com.mimococo.marketops.availabilityrisk.RiskConfidence;
import com.mimococo.marketops.availabilityrisk.RiskEvidenceState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The deterministic answer to "how fast is this selling", with its reasoning.
 *
 * <p>Every field a reviewer would need to disagree with the answer is present:
 * each window's evidence, which one was selected, why, and what the selection
 * cost in confidence. A single rate with no reasoning is not auditable, and an
 * operator who cannot audit a number stops trusting the queue that shows it.
 *
 * @param selectedRate units per day, or {@code null} when demand is unusable
 * @param selectedWindow the window the rate came from, or {@code null}
 * @param reason a short, stable explanation of the selection
 * @param evidenceState what kind of evidence the rate rests on
 * @param confidence how much weight the rank should give it
 * @param windows every window considered
 * @param carriedForwardFrom when the carried-forward answer was originally true
 * @param carryForwardExpiresAt when carrying it forward stops being allowed
 */
public record DemandDecision(
        BigDecimal selectedRate,
        DemandWindow selectedWindow,
        String reason,
        RiskEvidenceState evidenceState,
        RiskConfidence confidence,
        List<DemandWindowEvidence> windows,
        Instant carriedForwardFrom,
        Instant carryForwardExpiresAt) {

    public DemandDecision {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(evidenceState, "evidenceState");
        Objects.requireNonNull(confidence, "confidence");
        windows = List.copyOf(Objects.requireNonNull(windows, "windows"));
        if (selectedRate != null && selectedRate.signum() < 0) {
            throw new IllegalArgumentException("selectedRate cannot be negative");
        }
    }

    /**
     * Whether the rate may be used to project a stockout date.
     *
     * <p>A blocked or unknown demand answer is not zero demand. Projecting from
     * it would produce an infinite days-of-cover and a reassuring green card for
     * a variant nobody can currently observe.
     */
    public boolean usable() {
        return selectedRate != null
                && evidenceState != RiskEvidenceState.DATA_BLOCKED
                && evidenceState != RiskEvidenceState.POLICY_BLOCKED
                && evidenceState != RiskEvidenceState.UNKNOWN;
    }

    /** The evidence for one window, or {@code null} when it was not evaluated. */
    public DemandWindowEvidence window(DemandWindow window) {
        return windows.stream().filter(evidence -> evidence.window() == window)
                .findFirst().orElse(null);
    }
}
