package com.mimococo.marketops.analyticsdecision;

import java.time.Instant;
import java.util.UUID;

/**
 * How a module that calculates something records that it did.
 *
 * <p>{@code mart.calculation_run} is this module's table and this module writes
 * it. Another module that needed a run identifier had two bad options: write the
 * table itself, which would make two writers of one lineage, or put something
 * else in the column, which is what happened — the advertising path passed a
 * case identifier where a run identifier was required, and the foreign key
 * refused it.
 *
 * <p>So there is a third option, which is this. The calculating module asks for
 * a run, gets an identifier that is real everywhere the schema expects one, and
 * the lineage stays owned by one place.
 */
public interface CalculationRunLedger {

    /**
     * Record one completed calculation and return its identifier.
     *
     * <p>Opened and closed together, because the caller already knows how it
     * went. A run that could be left open by a caller that forgot would be a
     * lineage row nobody can interpret.
     *
     * @param request what was calculated, over what, and how much of it
     * @return the run, usable wherever the schema requires a calculation run
     */
    UUID recordCompletedRun(CompletedRun request);

    /**
     * One finished calculation.
     *
     * @param organizationId owning organization
     * @param storeId the store it covered, or {@code null} for the organization
     * @param triggerKind what caused it to run
     * @param window the observation window it used
     * @param periodStart start of the window it read
     * @param periodEnd end of the window it read
     * @param definitionSetDigest identity of the definitions it applied
     * @param subjectCount how many subjects it visited
     * @param valueCount how many values it produced
     * @param succeeded whether it finished its work
     * @param failureCode why it did not, or {@code null}
     * @param completedAt when it finished
     */
    record CompletedRun(
            UUID organizationId,
            UUID storeId,
            String triggerKind,
            MetricWindow window,
            Instant periodStart,
            Instant periodEnd,
            String definitionSetDigest,
            int subjectCount,
            int valueCount,
            boolean succeeded,
            String failureCode,
            Instant completedAt) {

        public CompletedRun {
            java.util.Objects.requireNonNull(organizationId, "organizationId");
            java.util.Objects.requireNonNull(triggerKind, "triggerKind");
            java.util.Objects.requireNonNull(window, "window");
            java.util.Objects.requireNonNull(periodStart, "periodStart");
            java.util.Objects.requireNonNull(periodEnd, "periodEnd");
            java.util.Objects.requireNonNull(definitionSetDigest, "definitionSetDigest");
            java.util.Objects.requireNonNull(completedAt, "completedAt");
            if (succeeded != (failureCode == null)) {
                throw new IllegalArgumentException(
                        "a run that failed says why, and one that succeeded has nothing to say");
            }
        }
    }
}
