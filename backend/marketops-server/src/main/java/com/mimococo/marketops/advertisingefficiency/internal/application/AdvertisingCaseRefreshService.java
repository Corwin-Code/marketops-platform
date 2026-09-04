package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingTraceRepository;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single seam both schedules enter.
 *
 * <p>Everything else in the loop — the trigger queue, the lease, the cursor, the
 * sweep mutex, the SLO rows — is scaffolding around this one method. The
 * targeted path and the hourly reconciliation differ only in the
 * {@code calculationKind} they pass and the run they attribute to, which is what
 * makes "targeted equals sweep" a structural property rather than a promise: two
 * calls with the same {@code asOf} and the same evidence run the same code and
 * produce the same value.
 *
 * <p>One transaction, so a calculation that wrote its case but failed before its
 * factors leaves nothing rather than a case nobody can explain.
 */
@Service
class AdvertisingCaseRefreshService {

    private final AdvertisingCaseCalculationService calculation;
    private final AdvertisingProjectionWriter writer;
    private final AdvertisingProposalService proposals;
    private final com.mimococo.marketops.analyticsdecision.CalculationRunLedger runs;
    private final AdvertisingTraceRepository trace;
    private final IdGenerator ids;

    AdvertisingCaseRefreshService(
            AdvertisingCaseCalculationService calculation,
            AdvertisingProjectionWriter writer,
            AdvertisingProposalService proposals,
            com.mimococo.marketops.analyticsdecision.CalculationRunLedger runs,
            AdvertisingTraceRepository trace,
            IdGenerator ids) {
        this.calculation = calculation;
        this.writer = writer;
        this.proposals = proposals;
        this.runs = runs;
        this.trace = trace;
        this.ids = ids;
    }

    /** What one refresh did, so the caller can report progress without re-reading. */
    record RefreshOutcome(
            AdCaseCalculation calculation,
            AdvertisingProjectionWriter.Written written,
            java.util.List<UUID> proposed,
            String correlationId) {
    }

    @Transactional
    Optional<RefreshOutcome> refresh(
            UUID organizationId, UUID objectId, Instant asOf,
            String calculationKind, UUID reconciliationRunId, String parentCorrelationId) {
        String correlationId = calculationKind + ':'
                + (reconciliationRunId == null ? ids.newId() : reconciliationRunId + ":" + objectId);

        record(organizationId, objectId, calculationKind, "CALCULATION_STARTED", "STARTED",
                correlationId, parentCorrelationId, null, asOf);

        Optional<AdCaseCalculation> calculated =
                calculation.calculate(organizationId, objectId, asOf);
        if (calculated.isEmpty()) {
            // An object that has disappeared is not an error and not a case. It is
            // recorded so a sweep that visited nothing is distinguishable from a
            // sweep that never ran.
            record(organizationId, objectId, calculationKind, "CALCULATION_STARTED", "SUPPRESSED",
                    correlationId, parentCorrelationId, "object not found", asOf);
            return Optional.empty();
        }
        AdCaseCalculation result = calculated.get();
        record(organizationId, objectId, calculationKind, "EVIDENCE_AND_LANE_CALCULATED",
                "COMPLETED", correlationId, parentCorrelationId,
                "{\"cases\":" + result.cases().size() + "}", asOf);

        AdvertisingProjectionWriter.Written written =
                writer.write(result, calculationKind, reconciliationRunId);
        record(organizationId, objectId, calculationKind, "PROJECTION_WRITTEN", "COMPLETED",
                correlationId, parentCorrelationId,
                "{\"laneChanged\":" + written.anyLaneChanged() + "}", asOf);

        // The recommendation names a calculation run, and a run is this
        // product's lineage record of work actually done. Advertising records a
        // real one through the module that owns that table rather than putting
        // a case identifier in the column, which is what the foreign key
        // refused.
        UUID calculationRunId = runs.recordCompletedRun(
                new com.mimococo.marketops.analyticsdecision.CalculationRunLedger.CompletedRun(
                        organizationId, result.storeId(), runTriggerOf(calculationKind),
                        com.mimococo.marketops.analyticsdecision.MetricWindow.D30,
                        asOf.minus(java.time.Duration.ofDays(30)), asOf,
                        result.policies().versionDigest(), 1, result.cases().size(),
                        true, null, asOf));

        // Proposing happens inside the same seam and the same transaction, so
        // the targeted path and the hourly sweep produce the same proposals from
        // the same evidence rather than one of them producing more.
        java.util.List<UUID> proposed = proposals.proposeFor(result, written.cases(),
                calculationRunId, correlationId);
        if (!proposed.isEmpty()) {
            record(organizationId, objectId, calculationKind, "PROJECTION_WRITTEN", "COMPLETED",
                    correlationId, parentCorrelationId,
                    "{\"proposed\":" + proposed.size() + "}", asOf);
        }

        return Optional.of(new RefreshOutcome(result, written, proposed, correlationId));
    }

    private void record(UUID organizationId, UUID objectId, String calculationKind,
            String stage, String status, String correlationId, String parentCorrelationId,
            String detail, Instant at) {
        String detailJson = detail == null ? "{}"
                : detail.startsWith("{") ? detail : "{\"note\":\"" + detail + "\"}";
        trace.record(ids.newId(), organizationId, objectId, calculationKind, stage, status,
                correlationId, parentCorrelationId, objectId.toString(), detailJson, at);
    }

    /**
     * Why the run happened, in the lineage table's vocabulary.
     *
     * <p>A targeted pass is a response to data arriving, which is what
     * {@code LATE_DATA} names; the hourly sweep is scheduled. Neither is manual,
     * and calling either one of those would make the lineage lie about who asked.
     */
    private static String runTriggerOf(String calculationKind) {
        return "TARGETED".equals(calculationKind) ? "LATE_DATA" : "SCHEDULED";
    }

    /** The correlation identifier a caller should attribute its own work to. */
    static String currentCorrelation() {
        return CorrelationId.current();
    }
}
