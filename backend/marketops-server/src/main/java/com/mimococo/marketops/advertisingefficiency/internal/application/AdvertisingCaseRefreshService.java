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
    private final AdvertisingTraceRepository trace;
    private final IdGenerator ids;

    AdvertisingCaseRefreshService(
            AdvertisingCaseCalculationService calculation,
            AdvertisingProjectionWriter writer,
            AdvertisingProposalService proposals,
            AdvertisingTraceRepository trace,
            IdGenerator ids) {
        this.calculation = calculation;
        this.writer = writer;
        this.proposals = proposals;
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

        // Proposing happens inside the same seam and the same transaction, so
        // the targeted path and the hourly sweep produce the same proposals from
        // the same evidence rather than one of them producing more.
        java.util.List<UUID> proposed = proposals.proposeFor(result, written.cases(),
                correlationId);
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

    /** The correlation identifier a caller should attribute its own work to. */
    static String currentCorrelation() {
        return CorrelationId.current();
    }
}
