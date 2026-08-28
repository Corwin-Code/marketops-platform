package com.mimococo.marketops.operationsworkflow.internal.domain;

import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the guardrail reads, gathered once.
 *
 * <p>The engine is given a value rather than a set of collaborators so the
 * verdict is a pure function of what was read. Two evaluations of the same
 * input produce the same verdict and the same digest, which is what lets an
 * operator ask why a change was refused a week later and get the real answer.
 *
 * @param policy the limits in force, or {@code null} when none are
 * @param metrics current canonical values for the subject
 * @param currentPrice the price observed on the platform, or {@code null}
 * @param proposedPrice the price the recommendation proposes
 * @param cumulativeDailyChangeRate how much the price already moved today
 * @param lastChangeAt when the price last changed, or {@code null}
 * @param evaluatedAt the instant the evaluation is made for
 * @param mappingResolved whether the listing maps to an internal variant
 * @param mappingConflictOpen whether a mapping conflict is unresolved
 * @param diagnosisBlocksExecution whether a finding blocks a platform write
 * @param entityVersionMatches whether the facts still match the recorded case
 * @param recommendationValid whether the proposal is still within its window
 * @param authorizationMaxChangeRate bound of the authorization being used, or {@code null}
 */
public record GuardrailInput(
        PolicyLimits policy,
        Map<MetricCode, MetricValueView> metrics,
        BigDecimal currentPrice,
        BigDecimal proposedPrice,
        BigDecimal cumulativeDailyChangeRate,
        Instant lastChangeAt,
        Instant evaluatedAt,
        boolean mappingResolved,
        boolean mappingConflictOpen,
        boolean diagnosisBlocksExecution,
        boolean entityVersionMatches,
        boolean recommendationValid,
        BigDecimal authorizationMaxChangeRate) {

    public GuardrailInput {
        metrics = Map.copyOf(Objects.requireNonNull(metrics, "metrics"));
        Objects.requireNonNull(proposedPrice, "proposedPrice");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        cumulativeDailyChangeRate = cumulativeDailyChangeRate == null
                ? BigDecimal.ZERO : cumulativeDailyChangeRate;
    }
}
