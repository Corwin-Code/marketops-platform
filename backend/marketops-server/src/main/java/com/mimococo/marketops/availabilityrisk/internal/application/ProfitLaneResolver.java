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
import java.math.BigDecimal;
import java.math.MathContext;
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
        Optional<Figure> settled = figure(metrics.current(
                MetricCode.SETTLED_CONTRIBUTION_PROFIT, SubjectKind.PLATFORM_LISTING_VARIANT,
                platformListingVariantId, MetricWindow.D30), MetricCode.SETTLED_UNITS,
                platformListingVariantId);
        AuthorityResult settledResult = assess(settled, asOf,
                ProfitLane.CONFIRMED_ELIGIBLE,
                "fresh complete positive settled contribution profit");
        if (settledResult.decisive()) {
            return settledResult.assessment();
        }

        // Operational evidence is a fallback only when the settled authority
        // genuinely has no applicable value. A current settled loss is a
        // business answer, not an invitation to ask a weaker authority.
        Optional<Figure> operational = figure(metrics.current(
                MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT, SubjectKind.PLATFORM_LISTING_VARIANT,
                platformListingVariantId, MetricWindow.D30), MetricCode.COMPLETED_UNITS,
                platformListingVariantId);
        AuthorityResult operationalResult = assess(operational, asOf,
                ProfitLane.OPERATIONAL_ELIGIBLE,
                "settled profit unavailable; fresh complete positive operational profit");
        if (operationalResult.decisive()) {
            return operationalResult.assessment();
        }

        // Neither authority has sales to speak from. The estimated unit profit
        // (price less the stated tariffs and the unit cost) may still make the
        // stockout worth the queue, as an estimate and visibly so (P9, Owner
        // decision 2026-10-02). Only a positive, fresh estimate speaks: an
        // estimated loss is no complete figure, so it cannot rule the item out.
        Optional<MetricValueView> projected = metrics.current(
                MetricCode.PROJECTED_UNIT_PROFIT, SubjectKind.PLATFORM_LISTING_VARIANT,
                platformListingVariantId, MetricWindow.D30);
        if (projected.isPresent() && projected.get().valueState() == ValueState.AVAILABLE
                && projected.get().numericValue() != null
                && projected.get().numericValue().signum() > 0
                && fresh(projected.get(), asOf) && !blocked(projected.get().confidenceState())) {
            MetricValueView estimate = projected.get();
            return new ProfitAssessment(ProfitLane.PROVISIONAL, estimate.numericValue(),
                    estimate.currencyCode(), estimate.metricValueId(),
                    "no sales to settle or operate on; positive estimated unit profit");
        }

        // No authority produced an eligible answer. Say which failure shape it
        // was, because they route to different people: a stale or conflicted
        // figure is a data repair, a confidently negative one is a commercial
        // decision.
        MetricValueView candidate = operational.map(Figure::metric).orElse(null);
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
        BigDecimal perUnit = operational.get().perUnit();
        if (perUnit != null && perUnit.signum() <= 0) {
            return new ProfitAssessment(ProfitLane.NOT_PROFITABLE, perUnit,
                    candidate.currencyCode(), candidate.metricValueId(),
                    "fresh complete profit is zero or negative");
        }
        return ProfitAssessment.unknown("profit could not be classified from the published value");
    }

    /**
     * One authority's figure and what it comes to per unit.
     *
     * @param metric the stored total over the window
     * @param perUnit the total divided by the units it was earned on, or {@code null} when the
     *        total is not available
     */
    private record Figure(MetricValueView metric, BigDecimal perUnit) {
    }

    /**
     * Read a contribution-profit total per unit.
     *
     * <p>The settled and operational figures are totals over their window,
     * and the stockout question is about the units that cannot be sold, so
     * the total is divided by the units it was earned on. Without units to
     * divide by — nothing sold, or the count itself unavailable — the total
     * says nothing about one unit, and the authority counts as having no
     * applicable value so the ladder continues.
     */
    private Optional<Figure> figure(Optional<MetricValueView> total, MetricCode unitsCode,
                                    UUID platformListingVariantId) {
        if (total.isEmpty()) {
            return Optional.empty();
        }
        MetricValueView metric = total.get();
        if (metric.valueState() != ValueState.AVAILABLE || metric.numericValue() == null) {
            return Optional.of(new Figure(metric, null));
        }
        Optional<MetricValueView> units = metrics.current(unitsCode,
                SubjectKind.PLATFORM_LISTING_VARIANT, platformListingVariantId, MetricWindow.D30);
        if (units.isEmpty() || units.get().valueState() != ValueState.AVAILABLE
                || units.get().numericValue() == null
                || units.get().numericValue().signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Figure(metric, metric.numericValue()
                .divide(units.get().numericValue(), MathContext.DECIMAL64)));
    }

    /**
     * Accept one authority's figure, or decline and let the ladder continue.
     *
     * <p>An explicitly estimated positive value is accepted as
     * {@link ProfitLane#PROVISIONAL} rather than as its authority's own lane.
     * It is still ranked — hiding a real risk because its profit is estimated
     * would be worse — but it is visibly marked as an estimate.
     */
    private AuthorityResult assess(Optional<Figure> value, Instant asOf,
                                   ProfitLane lane, String reason) {
        if (value.isEmpty() || value.get().perUnit() == null) {
            return AuthorityResult.unavailable();
        }
        MetricValueView metric = value.get().metric();
        BigDecimal perUnit = value.get().perUnit();
        if (!fresh(metric, asOf) || blocked(metric.confidenceState())) {
            return AuthorityResult.decisive(new ProfitAssessment(
                    ProfitLane.PROFIT_DATA_BLOCKED, null, null, metric.metricValueId(),
                    "profit evidence is stale, incomplete or conflicted"));
        }
        if (perUnit.signum() <= 0) {
            return AuthorityResult.decisive(new ProfitAssessment(ProfitLane.NOT_PROFITABLE,
                    perUnit, metric.currencyCode(), metric.metricValueId(),
                    "fresh complete profit is zero or negative"));
        }
        if (metric.estimated() || metric.confidenceState() == ConfidenceState.ESTIMATED_EXPLAINED) {
            return AuthorityResult.decisive(new ProfitAssessment(
                    ProfitLane.PROVISIONAL, perUnit,
                    metric.currencyCode(), metric.metricValueId(),
                    "positive only through an explicit estimate"));
        }
        return AuthorityResult.decisive(new ProfitAssessment(lane, perUnit,
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
