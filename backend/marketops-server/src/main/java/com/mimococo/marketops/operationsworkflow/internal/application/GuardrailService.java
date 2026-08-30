package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.analyticsdecision.DiagnosisFindingView;
import com.mimococo.marketops.analyticsdecision.DiagnosisQuery;
import com.mimococo.marketops.analyticsdecision.DecisionFreshness;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.marketplaceintegration.PriceChangeHistory;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.GuardrailReason;
import com.mimococo.marketops.operationsworkflow.GuardrailVerdict;
import com.mimococo.marketops.operationsworkflow.ImpactPreview;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.domain.GuardrailInput;
import com.mimococo.marketops.operationsworkflow.internal.domain.GuardrailOutcome;
import com.mimococo.marketops.operationsworkflow.internal.domain.PolicyLimits;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.GuardrailRepository;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gathers what the guardrail reads, decides, and records the decision.
 *
 * <p>Gathering and deciding are separate on purpose. Everything uncertain — the
 * clock, the database, the policy resolution — happens here; the decision itself
 * is a pure function of the gathered value. That is what makes a refusal
 * reproducible: the recorded digest identifies the exact input, and re-running
 * the engine on it gives the same answer.
 *
 * <p>The verdict is recorded on every evaluation, including passes. After an
 * unwanted price change the question is not what the system refuses today but
 * what it believed when it agreed.
 */
@Service
public class GuardrailService {

    /** How much of the day's cumulative movement is looked back over. */
    private static final int DAILY_WINDOW_HOURS = 24;

    private final MetricQuery metrics;
    private final DiagnosisQuery diagnosis;
    private final GuardrailRepository evaluations;
    private final PriceChangeHistory changeHistory;
    private final IdGenerator idGenerator;

    GuardrailService(MetricQuery metrics,
                     DiagnosisQuery diagnosis,
                     GuardrailRepository evaluations,
                     PriceChangeHistory changeHistory,
                     IdGenerator idGenerator) {
        this.metrics = metrics;
        this.diagnosis = diagnosis;
        this.evaluations = evaluations;
        this.changeHistory = changeHistory;
        this.idGenerator = idGenerator;
    }

    /**
     * Evaluate one proposal and record the verdict.
     *
     * @param proposal what is being decided about
     * @param authorizationBound bound of the authorization being used, or {@code null}
     * @param purpose why the evaluation is running
     */
    @Transactional
    public GuardrailVerdict evaluate(RecommendationView proposal, BigDecimal authorizationBound,
                                     GuardrailPurpose purpose) {
        return preview(proposal, authorizationBound, purpose).verdict();
    }

    /**
     * Evaluate one proposal and project what it would do.
     *
     * <p>The preview and the verdict come from one evaluation. Computing them
     * separately would let an operator approve a projection the gate never saw.
     */
    @Transactional
    public ImpactPreview preview(RecommendationView proposal, BigDecimal authorizationBound,
                                 GuardrailPurpose purpose) {
        GuardrailRepository.AuthoritySnapshot authority =
                evaluations.captureAuthority(proposal.id());
        GuardrailInput input = gather(proposal, authorizationBound, authority);
        GuardrailOutcome outcome = GuardrailEngine.evaluate(input);
        authority.requireOutcomeIdentity(outcome);
        String inputDigest = digestOf(proposal, input, outcome);

        UUID evaluationId = idGenerator.newId();
        PolicyLimits policy = input.policy();
        evaluations.insert(evaluationId, proposal.organizationId(), proposal.id(),
                policy == null ? null : policy.policyId(),
                policy == null ? null : policy.policyVersion(),
                purpose, outcome.passed(), outcome.reasons(), outcome.detail(), inputDigest,
                authority.document(), authority.evaluationAsOf(), CorrelationId.current());

        GuardrailVerdict verdict = new GuardrailVerdict(evaluationId, purpose, outcome.passed(),
                outcome.reasons(), policy == null ? null : policy.policyId(),
                policy == null ? null : policy.policyVersion(), outcome.detail(), inputDigest);
        return new ImpactPreview(proposal.id(),
                policy == null ? currencyOf(input) : policy.currencyCode(),
                input.currentPrice(), input.proposedPrice(), outcome.changeRate(),
                outcome.breakEvenPrice(), outcome.minimumPrice(), outcome.currentUnitProfit(),
                outcome.projectedUnitProfit(), outcome.currentMargin(),
                outcome.projectedMargin(), outcome.economicsProfileId(),
                outcome.economicsProfileVersion(), outcome.fulfillmentModeCode(),
                outcome.projectedComponentIds(), verdict);
    }

    /** Whether an execution verdict for this proposal currently passes. */
    @Transactional(readOnly = true)
    public boolean executionPassRecorded(UUID recommendationId) {
        return evaluations.executionPassRecorded(recommendationId);
    }

    /** Every verdict about one proposal, newest first. */
    @Transactional(readOnly = true)
    public List<GuardrailRepository.EvaluationRow> history(UUID recommendationId, int limit) {
        return evaluations.history(recommendationId, limit);
    }

    /**
     * Read everything the decision depends on, once.
     *
     * <p>One instant is used for every lookup. Reading the mapping at one moment
     * and the price at another would let the guardrail compare a price to a cost
     * that belonged to a different product for part of the evaluation.
     */
    private GuardrailInput gather(RecommendationView proposal, BigDecimal authorizationBound,
                                  GuardrailRepository.AuthoritySnapshot authority) {
        Instant now = authority.evaluationAsOf();
        UUID subjectId = proposal.subjectId();
        Map<MetricCode, MetricValueView> current = metrics.currentValuesAt(
                SubjectKind.PLATFORM_LISTING_VARIANT, subjectId, proposal.window(), now);
        String evaluatedEntityDigest = EntityVersion.of(current);
        if (!java.util.Objects.equals(evaluatedEntityDigest,
                authority.currentEntityDigest())) {
            throw new IllegalStateException(
                    "evaluated metric identity does not match authority snapshot");
        }

        boolean blockedByDiagnosis = diagnosis
                .currentFindingsAt(SubjectKind.PLATFORM_LISTING_VARIANT, subjectId,
                        proposal.window(), now)
                .stream()
                .anyMatch(DiagnosisFindingView::blocksExecution);

        return new GuardrailInput(authority.policy(), current, authority.currentPrice(),
                authority.currentPriceCurrency(), proposedPrice(proposal),
                authority.fulfillmentModeCode(), authority.economics(), authority.freshness(),
                changeHistory.cumulativeChangeRate(subjectId,
                        now.minus(java.time.Duration.ofHours(DAILY_WINDOW_HOURS)), now),
                changeHistory.lastChangeAt(subjectId, now).orElse(null), now,
                authority.mappingResolved(), authority.mappingConflictOpen(),
                blockedByDiagnosis,
                evaluatedEntityDigest.equals(proposal.entityVersionDigest()),
                proposal.validUntil().isAfter(now), authorizationBound);
    }

    /**
     * The price the proposal names.
     *
     * <p>A proposal that names no target price is not a price change anybody can
     * evaluate. Rather than substituting a value, the input carries zero, which
     * the engine refuses through the break-even and floor checks.
     */
    private static BigDecimal proposedPrice(RecommendationView proposal) {
        String target = proposal.proposedParameters().get("targetPrice");
        if (target == null || target.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(target);
        } catch (NumberFormatException notANumber) {
            return BigDecimal.ZERO;
        }
    }

    /** The currency the values speak, when no policy names one. */
    private static String currencyOf(GuardrailInput input) {
        return input.metrics().values().stream()
                .map(MetricValueView::currencyCode)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * Digest exactly what the decision was made from.
     *
     * <p>The proposal, the policy version, the gathered facts and the resulting
     * reasons all contribute, so an evaluation can be identified without storing
     * the operating data it read.
     */
    private static String digestOf(RecommendationView proposal, GuardrailInput input,
                                   GuardrailOutcome outcome) {
        List<String> components = new ArrayList<>();
        components.add(proposal.id().toString());
        components.add(proposal.entityVersionDigest());
        components.add(input.policy() == null
                ? "NO_POLICY" : input.policy().policyId() + ":" + input.policy().policyVersion());
        components.add(String.valueOf(input.currentPrice()));
        components.add(String.valueOf(input.currentPriceCurrency()));
        components.add(input.proposedPrice().toPlainString());
        components.add(String.valueOf(input.fulfillmentModeCode()));
        components.add(input.evaluatedAt().toString());
        components.add(input.economics().status().name());
        if (input.economics().available()) {
            var profile = input.economics().profile();
            components.add(profile.profileId().toString());
            components.add(Integer.toString(profile.profileVersion()));
            components.add(profile.verificationState().name());
            components.add(profile.verifiedAt().toString());
            components.add(String.valueOf(profile.verificationExpiresAt()));
            profile.familyApplicability().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> components.add("FAMILY:" + entry.getKey()
                            + ':' + entry.getValue()));
            profile.components().stream()
                    .sorted(java.util.Comparator.comparing(component ->
                            component.componentId().toString()))
                    .forEach(component -> components.add("COMPONENT:"
                            + component.componentId() + ':' + component.componentCode()
                            + ':' + component.family() + ':' + component.kind() + ':'
                            + component.fixedAmount() + ':' + component.rate() + ':'
                            + component.lowerPriceInclusive() + ':'
                            + component.upperPriceExclusive()));
        }
        input.decisionFreshness().requiredFeeds().stream().sorted().forEach(feed -> {
            DecisionFreshness.Watermark watermark =
                    input.decisionFreshness().watermarks().get(feed);
            components.add(watermark == null ? "WATERMARK:" + feed + ":MISSING"
                    : "WATERMARK:" + feed + ':' + watermark.watermarkId() + ':'
                            + watermark.sourceUpdatedAt() + ':' + watermark.ingestedAt()
                            + ':' + watermark.reconciledAt() + ':'
                            + watermark.effectiveAt());
            components.add("WATERMARK_AGE:" + feed + ':'
                    + input.decisionFreshness().agesAt(input.evaluatedAt()).get(feed));
        });
        components.add(String.valueOf(input.lastChangeAt()));
        components.add(input.cumulativeDailyChangeRate().toPlainString());
        components.add(Boolean.toString(input.mappingResolved()));
        components.add(Boolean.toString(input.mappingConflictOpen()));
        components.add(Boolean.toString(input.diagnosisBlocksExecution()));
        input.metrics().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> components.add(entry.getKey() + "="
                        + entry.getValue().valueState() + ":"
                        + entry.getValue().numericValue() + ":"
                        + entry.getValue().currencyCode() + ":"
                        + entry.getValue().confidenceState() + ":"
                        + entry.getValue().freshnessSeconds() + ":"
                        + entry.getValue().inputDigest()));
        components.add(outcome.reasons().stream().map(GuardrailReason::name)
                .sorted().reduce("", (left, right) -> left + ',' + right));
        return Digest.ofComponents(components);
    }
}
