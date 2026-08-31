package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.operationsworkflow.AvailabilityCaseView;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calculates one variant, writes the result and raises the work it calls for.
 *
 * <p>The three steps are one transaction on purpose. A card that named a
 * triggering child no case pointed at, or a case whose child row a failed write
 * never created, would each be a state the schema forbids and an operator could
 * not act on. Committing them together means a reader sees the calculation and
 * its consequences or neither.
 *
 * <p>The targeted worker and the hourly sweep both enter here, differing only in
 * the calculation kind they declare and the run they belong to. That is what
 * makes the Contract's equivalence obligation a property of one code path
 * rather than an agreement between two.
 */
@Service
public class AvailabilityRiskRefreshService {

    /** A targeted recalculation caused by one accepted fact. */
    public static final String TARGETED = "TARGETED";

    /** A variant visited by a full portfolio sweep. */
    public static final String RECONCILIATION = "RECONCILIATION";

    private final AvailabilityRiskCalculationService calculation;
    private final AvailabilityProjectionWriter writer;
    private final AvailabilityCaseActivationService activation;
    private final AvailabilityOutcomeVerificationService verification;
    private final IdGenerator ids;

    public AvailabilityRiskRefreshService(AvailabilityRiskCalculationService calculation,
                                          AvailabilityProjectionWriter writer,
                                          AvailabilityCaseActivationService activation,
                                          AvailabilityOutcomeVerificationService verification,
                                          IdGenerator ids) {
        this.calculation = calculation;
        this.writer = writer;
        this.activation = activation;
        this.verification = verification;
        this.ids = ids;
    }

    /**
     * Recalculate one variant and bring its cases up to date.
     *
     * @param organizationId owning organization
     * @param productVariantId the internal variant
     * @param asOf the instant to read evidence at
     * @param calculationKind {@link #TARGETED} or {@link #RECONCILIATION}
     * @param reconciliationRunId the sweep this belongs to, or {@code null}
     * @return the calculation, what was written and what it raised
     */
    @Transactional
    public RefreshOutcome refresh(UUID organizationId, UUID productVariantId, Instant asOf,
                                  String calculationKind, UUID reconciliationRunId) {
        VariantRisk risk = calculation.calculate(organizationId, productVariantId, asOf);
        AvailabilityProjectionWriter.WrittenCard written =
                writer.write(risk, calculationKind, reconciliationRunId);

        // The calculation run gets its own identity, distinct from any case's.
        // A case that carried a run identity would appear to be a different
        // case on every recalculation, which is exactly the duplication the
        // cause key exists to prevent.
        String correlationId = calculationKind + ':'
                + (reconciliationRunId == null ? ids.newId() : reconciliationRunId);
        AvailabilityCaseActivationService.ActivationResult raised =
                activation.activate(risk, written, correlationId);

        // Verification runs after activation and on the same calculation, so a
        // case raised a moment ago and a case waiting on an outcome are both
        // answered by one reading of the evidence rather than by two that could
        // disagree.
        var verified = verification.observe(risk, written);
        return new RefreshOutcome(risk, written, raised, verified, correlationId);
    }

    /**
     * One variant's complete refresh.
     *
     * @param risk what was calculated
     * @param written the card and children exactly as persisted
     * @param activation what was raised, refreshed or left alone
     * @param verified every case this calculation reported an outcome for
     * @param correlationId the calculation run's own identity
     */
    public record RefreshOutcome(VariantRisk risk,
                                 AvailabilityProjectionWriter.WrittenCard written,
                                 AvailabilityCaseActivationService.ActivationResult activation,
                                 List<AvailabilityCaseView> verified,
                                 String correlationId) {
    }
}
