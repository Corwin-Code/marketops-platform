package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.availabilityrisk.ProfitLane;
import com.mimococo.marketops.availabilityrisk.internal.domain.ProfitAssessment;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Decides which profit authority may speak for a stockout, and how loudly.
 *
 * <p>The ladder is strongest-first and never blended. A settled figure is a
 * different kind of claim from an operational one, and an operational one is a
 * different kind of claim from an estimate; averaging them would produce a
 * number that is none of the three and that no reviewer could check.
 *
 * <p>Freshness is judged from the metric's own source time against the instant
 * being asked about, not from the confidence the metric was stored with. A
 * stored confidence is a statement about the moment of computation and does not
 * become false as it ages, so treating it as a freshness signal would let a
 * month-old figure present itself as current.
 */
@Component
public class ProfitLaneResolver {

    /**
     * How old a profit figure's oldest source may be and still be current.
     *
     * <p>Two days spans a weekend, which is the shortest gap in which a
     * settlement feed can be quiet without anything being wrong.
     */
    private static final Duration FRESHNESS_BOUND = Duration.ofDays(2);

    private final MetricQuery metrics;

    public ProfitLaneResolver(MetricQuery metrics) {
        this.metrics = metrics;
    }

    /**
     * Resolve the profit lane for one listing variant.
     *
     * <p>Metric values exist per platform listing variant, so a company-level
     * answer is resolved from the channel a variant sells through rather than
     * invented at the internal-variant level where no metric authority writes.
     */
    public ProfitAssessment resolve(UUID platformListingVariantId, Instant asOf) {
        Optional<MetricValueView> settled = metrics.current(
                MetricCode.SETTLED_CONTRIBUTION_PROFIT, SubjectKind.PLATFORM_LISTING_VARIANT,
                platformListingVariantId, MetricWindow.D30);
        AuthorityResult settledResult = assess(settled, asOf,
                ProfitLane.CONFIRMED_ELIGIBLE,
                "fresh complete positive settled contribution profit");
        if (settledResult.decisive()) {
            return settledResult.assessment();
        }

        // Operational evidence is a fallback only when the settled authority
        // genuinely has no applicable value. A current settled loss is a
        // business answer, not an invitation to ask a weaker authority.
        Optional<MetricValueView> operational = metrics.current(
                MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT, SubjectKind.PLATFORM_LISTING_VARIANT,
                platformListingVariantId, MetricWindow.D30);
        AuthorityResult operationalResult = assess(operational, asOf,
                ProfitLane.OPERATIONAL_ELIGIBLE,
                "settled profit unavailable; fresh complete positive operational profit");
        if (operationalResult.decisive()) {
            return operationalResult.assessment();
        }

        // Neither authority produced an eligible answer. Say which of the two
        // failure shapes it was, because they route to different people: a
        // stale or conflicted figure is a data repair, a confidently negative
        // one is a commercial decision.
        MetricValueView candidate = operational.orElse(null);
        if (candidate == null) {
            return ProfitAssessment.unknown("no profit authority published a value");
        }
        if (candidate.valueState() != ValueState.AVAILABLE) {
            return new ProfitAssessment(ProfitLane.PROFIT_DATA_BLOCKED, null, null,
                    candidate.metricValueId(), "profit is unavailable: "
                    + candidate.valueState().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (!fresh(candidate, asOf) || blocked(candidate.confidenceState())) {
            return new ProfitAssessment(ProfitLane.PROFIT_DATA_BLOCKED, null, null,
                    candidate.metricValueId(),
                    "profit evidence is stale, incomplete or conflicted");
        }
        if (candidate.numericValue() != null && candidate.numericValue().signum() <= 0) {
            return new ProfitAssessment(ProfitLane.NOT_PROFITABLE, candidate.numericValue(),
                    candidate.currencyCode(), candidate.metricValueId(),
                    "fresh complete profit is zero or negative");
        }
        return ProfitAssessment.unknown("profit could not be classified from the published value");
    }

    /**
     * Accept one authority's value, or decline and let the ladder continue.
     *
     * <p>An explicitly estimated positive value is accepted as
     * {@link ProfitLane#PROVISIONAL} rather than as its authority's own lane.
     * It is still ranked — hiding a real risk because its profit is estimated
     * would be worse — but it is visibly marked as an estimate.
     */
    private AuthorityResult assess(Optional<MetricValueView> value, Instant asOf,
                                   ProfitLane lane, String reason) {
        if (value.isEmpty()) {
            return AuthorityResult.unavailable();
        }
        MetricValueView metric = value.get();
        if (metric.valueState() != ValueState.AVAILABLE || metric.numericValue() == null) {
            return AuthorityResult.unavailable();
        }
        if (!fresh(metric, asOf) || blocked(metric.confidenceState())) {
            return AuthorityResult.decisive(new ProfitAssessment(
                    ProfitLane.PROFIT_DATA_BLOCKED, null, null, metric.metricValueId(),
                    "profit evidence is stale, incomplete or conflicted"));
        }
        if (metric.numericValue().signum() <= 0) {
            return AuthorityResult.decisive(new ProfitAssessment(ProfitLane.NOT_PROFITABLE,
                    metric.numericValue(), metric.currencyCode(), metric.metricValueId(),
                    "fresh complete profit is zero or negative"));
        }
        if (metric.estimated() || metric.confidenceState() == ConfidenceState.ESTIMATED_EXPLAINED) {
            return AuthorityResult.decisive(new ProfitAssessment(
                    ProfitLane.PROVISIONAL, metric.numericValue(),
                    metric.currencyCode(), metric.metricValueId(),
                    "positive only through an explicit estimate"));
        }
        return AuthorityResult.decisive(new ProfitAssessment(lane, metric.numericValue(),
                metric.currencyCode(), metric.metricValueId(), reason));
    }

    /** Typed authority outcome: unavailable may fall through; every other answer is final. */
    private record AuthorityResult(boolean decisive, ProfitAssessment assessment) {
        static AuthorityResult unavailable() {
            return new AuthorityResult(false, null);
        }

        static AuthorityResult decisive(ProfitAssessment assessment) {
            return new AuthorityResult(true, assessment);
        }
    }

    private boolean fresh(MetricValueView metric, Instant asOf) {
        Instant source = metric.oldestSourceTime();
        return source != null && !source.plus(FRESHNESS_BOUND).isBefore(asOf);
    }

    private static boolean blocked(ConfidenceState confidence) {
        return confidence == ConfidenceState.STALE
                || confidence == ConfidenceState.INCOMPLETE
                || confidence == ConfidenceState.CONFLICTED;
    }
}
