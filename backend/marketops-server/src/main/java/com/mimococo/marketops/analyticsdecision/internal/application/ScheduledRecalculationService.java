package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.StoreRecalculation;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.StoreListingFindingsRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Scheduled calculations run through the same engine as the console's, with no requesting user. */
@Service
class ScheduledRecalculationService implements StoreRecalculation {

    private final AnalyticsCalculationService calculation;
    private final StoreListingFindingsRepository runs;

    ScheduledRecalculationService(AnalyticsCalculationService calculation, StoreListingFindingsRepository runs) {
        this.calculation = calculation;
        this.runs = runs;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> latestPeriodEnd(UUID storeId, MetricWindow window) {
        return runs.latestPeriodEnd(storeId, window.name());
    }

    @Override
    public Result recalculate(UUID storeId, MetricWindow window) {
        AnalyticsCalculationService.RunSummary summary = calculation.run(storeId, window, "SCHEDULED", null);
        return new Result(summary.calculationRunId(), summary.subjectCount(), summary.valueCount(),
                summary.findingCount());
    }
}
