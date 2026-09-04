package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdvertisingContributionProfit;
import com.mimococo.marketops.advertisingefficiency.internal.domain.OutcomeEvaluation;
import com.mimococo.marketops.advertisingefficiency.internal.domain.OutcomeMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingOutcomeRepository;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Measuring what a bid change did, against a plan written before it happened.
 *
 * <p>The two windows are read identically. The baseline is the same length as
 * the observation, immediately before the change landed, from the same tables
 * with the same supersession filter — so a difference between them is a
 * difference in the world rather than in how they were fetched.
 *
 * <p>Which sale stage counts is what separates the two stages of the outcome.
 * An Operational view counts orders placed; a Settled view counts sales that
 * survived. That is the whole substance of the distinction, and collapsing it
 * would be reporting a campaign successful on sales later returned.
 */
@Service
class AdvertisingOutcomeService {

    /** Orders placed. What the operational view counts. */
    private static final String ORDER_STAGE = "CANONICAL_AD_LINKED_ORDER";

    /** Sales that survived cancellation and return. What a settled view counts. */
    private static final String RETAINED_STAGE = "CANONICAL_AD_LINKED_RETAINED_SALE";

    private final AdvertisingOutcomeRepository outcomes;
    private final AdvertisingEvidenceRepository facts;
    private final AdvertisingEvidenceGatherer gatherer;
    private final IdGenerator ids;

    AdvertisingOutcomeService(AdvertisingOutcomeRepository outcomes,
                              AdvertisingEvidenceRepository facts,
                              AdvertisingEvidenceGatherer gatherer,
                              IdGenerator ids) {
        this.outcomes = outcomes;
        this.facts = facts;
        this.gatherer = gatherer;
        this.ids = ids;
    }

    /** What one evaluation produced, so a caller can report without re-reading. */
    record Result(UUID observationId, String stage, int revisionNo,
                  OutcomeEvaluation evaluation, UUID reopenedContainmentId) {
    }

    /**
     * Evaluate one command's next due stage.
     *
     * <p>One transaction. An evaluation that wrote its observation and then
     * failed to reopen the lineage would leave a recorded regression nobody
     * acted on, which is worse than no record at all.
     */
    @Transactional
    Optional<Result> evaluate(AdvertisingOutcomeRepository.DueRow due, Instant now) {
        String stage = due.nextStage();
        boolean settledStage = !"OPERATIONAL".equals(stage);
        String saleStage = settledStage ? RETAINED_STAGE : ORDER_STAGE;

        Instant observedFrom = due.windowStartsAt();
        Instant observedTo = due.windowEndsAt(settledStage ? "SETTLED" : "OPERATIONAL");
        Duration length = Duration.between(observedFrom, observedTo);
        Instant baselineFrom = observedFrom.minus(length);

        Optional<OutcomeMeasure> measure = OutcomeMeasure.of(due.primaryMetricCode());
        List<UUID> variantIds = facts.affectedSet(due.organizationId(), due.adNativeObjectId())
                .map(AdvertisingEvidenceRepository.AffectedSetRow::productVariantIds)
                .orElseGet(List::of);

        OutcomeMeasure.WindowFacts baselineFacts = windowFacts(due, saleStage, baselineFrom,
                observedFrom, variantIds);
        OutcomeMeasure.WindowFacts observedFacts = windowFacts(due, saleStage, observedFrom,
                observedTo, variantIds);

        BigDecimal coverage = settledStage
                ? settledCoverage(due, observedFrom, observedTo) : null;
        OutcomeEvaluation.GuardState guard = settledStage
                ? outcomes.guardState(due.commandId(), coverage)
                : OutcomeEvaluation.GuardState.NOT_APPLICABLE;

        OutcomeEvaluation evaluation = measure
                .map(resolved -> OutcomeEvaluation.evaluate(
                        settledStage ? OutcomeEvaluation.Stage.SETTLED
                                     : OutcomeEvaluation.Stage.OPERATIONAL,
                        resolved.compute(baselineFacts), resolved.compute(observedFacts),
                        observedFacts.clicks(), plan(due, resolved), guard))
                // A plan naming a measure this product cannot compute settles
                // nothing, and says exactly that rather than reporting a zero.
                .orElseGet(() -> new OutcomeEvaluation(
                        settledStage ? OutcomeEvaluation.Stage.SETTLED
                                     : OutcomeEvaluation.Stage.OPERATIONAL,
                        OutcomeEvaluation.Verdict.INDETERMINATE, guard, null,
                        List.of("OUTCOME_MEASURE_NOT_IMPLEMENTED")));

        AdMeasure baseline = measure.map(m -> m.compute(baselineFacts))
                .orElseGet(() -> AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE));
        AdMeasure observed = measure.map(m -> m.compute(observedFacts))
                .orElseGet(() -> AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE));

        int revisionNo = "SETTLED_REVISED".equals(stage)
                ? (due.latestSettledRevision() == null ? 2 : due.latestSettledRevision() + 1) : 1;
        UUID supersedes = "SETTLED_REVISED".equals(stage) ? due.latestSettledId() : null;
        String adjustmentReason = "SETTLED_REVISED".equals(stage)
                ? "the facts underneath the settled window were restated" : null;

        UUID observationId = outcomes.record(ids.newId(), due, stage, revisionNo, supersedes,
                adjustmentReason, observedFrom, observedTo, baseline, observed,
                observedFacts.clicks(), coverage, evaluation, now,
                inputDigest(due, stage, revisionNo, baseline, observed, observedFacts),
                CorrelationId.current());

        UUID reopened = null;
        if (settledStage && evaluation.verdict() == OutcomeEvaluation.Verdict.REGRESSED
                && guard == OutcomeEvaluation.GuardState.SATISFIED) {
            // Same lineage, through the containment the lane resolver already
            // reads. Nothing here decides what the case will say; the next
            // calculation does, from the same authority as every other case.
            reopened = outcomes.reopenAfterRegression(ids.newId(), observationId,
                    accountableRole(due), CorrelationId.current());
        }
        return Optional.of(new Result(observationId, stage, revisionNo, evaluation, reopened));
    }

    /**
     * One window's facts, read the same way whichever window it is.
     *
     * <p>The contribution profit is assembled from the same components the case
     * calculation uses, so a case and its own outcome cannot end up arguing
     * about the same product. In this Slice it blocks on the absent promotion
     * feed, exactly as the case does, and the outcome says so rather than
     * substituting a profit with a component missing.
     */
    private OutcomeMeasure.WindowFacts windowFacts(AdvertisingOutcomeRepository.DueRow due,
                                                   String saleStage, Instant from, Instant to,
                                                   List<UUID> variantIds) {
        Optional<AdvertisingOutcomeRepository.WindowRow> found =
                outcomes.window(due.organizationId(), due.adNativeObjectId(), saleStage, from, to);
        if (found.isEmpty()) {
            return OutcomeMeasure.WindowFacts.absent();
        }
        AdvertisingOutcomeRepository.WindowRow row = found.get();
        AdEvidenceState evidence = evidenceOf(row);

        AdMeasure netSales = row.netSalesAmount() == null
                ? AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE)
                : AdMeasure.available(row.netSalesAmount(), evidence);
        AdMeasure spend = row.spendAmount() == null
                ? AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE)
                : AdMeasure.available(row.spendAmount(), evidence);

        var economics = gatherer.economicsFor(variantIds);
        AdvertisingContributionProfit profit = AdvertisingContributionProfit.compute(
                new AdvertisingContributionProfit.Components(
                        netSales,
                        row.saleEvents() == null ? 0L : row.saleEvents(),
                        AdvertisingEvidenceGatherer.sumAcross(variantIds, economics,
                                AdvertisingEvidenceGatherer.VariantEconomics::unitCost),
                        AdvertisingEvidenceGatherer.sumAcross(variantIds, economics,
                                AdvertisingEvidenceGatherer.VariantEconomics::platformFeesPerUnit),
                        AdvertisingEvidenceGatherer.sumAcross(variantIds, economics,
                                AdvertisingEvidenceGatherer.VariantEconomics::returnLossPerUnit),
                        // The same absent promotion feed the case calculation
                        // blocks on. Absent rather than zero, so the gap stays
                        // visible instead of becoming a profit nobody earned.
                        AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE),
                        AdvertisingEvidenceGatherer.sumAcross(variantIds, economics,
                                AdvertisingEvidenceGatherer.VariantEconomics::variableTaxPerUnit),
                        spend,
                        "RUB"));

        return new OutcomeMeasure.WindowFacts(row.spendAmount(), row.clicks(),
                row.netSalesAmount(), profit.absoluteProfit().orElse(null), evidence);
    }

    /**
     * How much of the observation window has actually settled.
     *
     * <p>Retained sales over orders placed. A window whose orders have mostly not
     * resolved yet cannot support a claim about the whole of it, however good the
     * part that has resolved looks.
     */
    private BigDecimal settledCoverage(AdvertisingOutcomeRepository.DueRow due,
                                       Instant from, Instant to) {
        long orders = outcomes.window(due.organizationId(), due.adNativeObjectId(),
                        ORDER_STAGE, from, to)
                .map(row -> row.saleEvents() == null ? 0L : row.saleEvents())
                .orElse(0L);
        long retained = outcomes.window(due.organizationId(), due.adNativeObjectId(),
                        RETAINED_STAGE, from, to)
                .map(row -> row.saleEvents() == null ? 0L : row.saleEvents())
                .orElse(0L);
        if (orders <= 0) {
            // Nothing was ordered in the window, so nothing is outstanding. That
            // is full coverage of an empty set rather than no coverage at all.
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(retained)
                .divide(BigDecimal.valueOf(orders), 5, RoundingMode.DOWN)
                .min(BigDecimal.ONE);
    }

    /**
     * How good the weakest input behind one window is.
     *
     * <p>An incomplete report window or an open correction window means the
     * numbers can still move, which is a fact about the evidence rather than
     * about the campaign.
     */
    private static AdEvidenceState evidenceOf(AdvertisingOutcomeRepository.WindowRow row) {
        if (row.anyCorrectionOpen()) {
            return AdEvidenceState.PROVISIONAL_OR_ESTIMATED;
        }
        return row.everyWindowComplete()
                ? AdEvidenceState.CANONICAL_CONFIRMED : AdEvidenceState.INCOMPLETE;
    }

    private static OutcomeEvaluation.OutcomePlan plan(AdvertisingOutcomeRepository.DueRow due,
                                                      OutcomeMeasure measure) {
        return new OutcomeEvaluation.OutcomePlan(measure, due.improvementThresholdRatio(),
                due.regressionThresholdRatio(), due.minimumTrafficCount());
    }

    /**
     * Who owns a regression on this lineage.
     *
     * <p>Read from the cause the case carried rather than assigned here, so the
     * person who receives the reopened work is the person the cause vocabulary
     * already says is accountable for it.
     */
    private static String accountableRole(AdvertisingOutcomeRepository.DueRow due) {
        try {
            AdvertisingCause cause = AdvertisingCause.valueOf(due.causeCode());
            return cause.accountableRole() == null
                    ? AdvertisingCause.ACTION_OUTCOME_REGRESSION.accountableRole().name()
                    : cause.accountableRole().name();
        } catch (IllegalArgumentException unknownCause) {
            return AdvertisingCause.ACTION_OUTCOME_REGRESSION.accountableRole().name();
        }
    }

    private static String inputDigest(AdvertisingOutcomeRepository.DueRow due, String stage,
                                      int revisionNo, AdMeasure baseline, AdMeasure observed,
                                      OutcomeMeasure.WindowFacts observedFacts) {
        List<String> components = new ArrayList<>();
        components.add(due.commandId().toString());
        components.add(due.policyId().toString());
        components.add(Integer.toString(due.policyVersion()));
        components.add(stage);
        components.add(Integer.toString(revisionNo));
        components.add(due.landedAt().toString());
        components.add(baseline.valueState().name());
        components.add(String.valueOf(baseline.orElse(null)));
        components.add(observed.valueState().name());
        components.add(String.valueOf(observed.orElse(null)));
        components.add(String.valueOf(observedFacts.clicks()));
        return Digest.ofComponents(components);
    }
}
