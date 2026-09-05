package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.CalculationRunLedger;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.MetricRepository;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.IdGenerator;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one writer of the calculation-run lineage, offered to other modules.
 *
 * <p>Nothing here is new behaviour; it is the same two repository calls the
 * analytics run already makes, exposed so that a module which calculates
 * something else does not have to write this table to name its own work.
 */
@Service
class CalculationRunService implements CalculationRunLedger {

    private final MetricRepository metrics;
    private final IdGenerator ids;

    CalculationRunService(MetricRepository metrics, IdGenerator ids) {
        this.metrics = metrics;
        this.ids = ids;
    }

    @Override
    @Transactional
    public UUID recordCompletedRun(CompletedRun request) {
        UUID runId = ids.newId();
        metrics.openRun(runId, request.organizationId(), request.triggerKind(),
                request.storeId() == null ? "ORGANIZATION" : "STORE", request.storeId(),
                request.window().name(), request.periodStart(), request.periodEnd(),
                request.definitionSetDigest(), null, request.completedAt(),
                CorrelationId.current());
        metrics.closeRun(runId, request.succeeded() ? "SUCCEEDED" : "FAILED",
                request.subjectCount(), request.valueCount(), request.failureCode(),
                request.completedAt());
        return runId;
    }
}
