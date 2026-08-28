package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.analyticsdecision.DiagnosisFindingView;
import com.mimococo.marketops.analyticsdecision.DiagnosisQuery;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.marketplaceintegration.PriceChangeHistory;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.PriceSnapshot;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.GuardrailReason;
import com.mimococo.marketops.operationsworkflow.GuardrailVerdict;
import com.mimococo.marketops.operationsworkflow.ImpactPreview;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.domain.GuardrailInput;
import com.mimococo.marketops.operationsworkflow.internal.domain.GuardrailOutcome;
import com.mimococo.marketops.operationsworkflow.internal.domain.PolicyLimits;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.GuardrailRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.PolicyRepository;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.ListingVariantContext;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.time.Clock;
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
    private final OperatingFactQuery facts;
    private final ListingIdentityDirectory listings;
    private final PolicyRepository policies;
    private final GuardrailRepository evaluations;
    private final PriceChangeHistory changeHistory;
    private final IdGenerator idGenerator;
    private final Clock clock;

    GuardrailService(MetricQuery metrics,
                     DiagnosisQuery diagnosis,
                     OperatingFactQuery facts,
                     ListingIdentityDirectory listings,
                     PolicyRepository policies,
                     GuardrailRepository evaluations,
                     PriceChangeHistory changeHistory,
                     IdGenerator idGenerator,
                     Clock clock) {
        this.metrics = metrics;
        this.diagnosis = diagnosis;
        this.facts = facts;
        this.listings = listings;
        this.policies = policies;
        this.evaluations = evaluations;
        this.changeHistory = changeHistory;
        this.idGenerator = idGenerator;
        this.clock = clock;
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
        Instant now = clock.instant();
        String authoritySnapshot = evaluations.authoritySnapshot(proposal.id());
        GuardrailInput input = gather(proposal, authorizationBound, now);
        GuardrailOutcome outcome = GuardrailEngine.evaluate(input);
        String inputDigest = digestOf(proposal, input, outcome);

        UUID evaluationId = idGenerator.newId();
        PolicyLimits policy = input.policy();
        evaluations.insert(evaluationId, proposal.organizationId(), proposal.id(),
                policy == null ? null : policy.policyId(),
                policy == null ? null : policy.policyVersion(),
                purpose, outcome.passed(), outcome.reasons(), outcome.detail(), inputDigest,
                authoritySnapshot, now, CorrelationId.current());

        GuardrailVerdict verdict = new GuardrailVerdict(evaluationId, purpose, outcome.passed(),
                outcome.reasons(), policy == null ? null : policy.policyId(),
                policy == null ? null : policy.policyVersion(), outcome.detail(), inputDigest);
        return new ImpactPreview(proposal.id(),
                policy == null ? currencyOf(input) : policy.currencyCode(),
                input.currentPrice(), input.proposedPrice(), outcome.changeRate(),
                outcome.breakEvenPrice(), outcome.currentUnitProfit(),
                outcome.projectedUnitProfit(), outcome.currentMargin(),
                outcome.projectedMargin(), verdict);
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
                                  Instant now) {
        UUID subjectId = proposal.subjectId();
        Map<MetricCode, MetricValueView> current = metrics.currentValues(
                SubjectKind.PLATFORM_LISTING_VARIANT, subjectId, proposal.window());

        Optional<ListingVariantContext> context = listings.variantContext(subjectId, now);
        boolean mapped = context.map(ListingVariantContext::mapped).orElse(false);
        boolean conflict = context.map(ListingVariantContext::conflictOpen).orElse(true);
        UUID productVariantId = context.map(ListingVariantContext::productVariantId)
                .orElse(null);
        String platformCode = context.map(ListingVariantContext::platformCode).orElse(null);

        PolicyLimits policy = policies.inForce(proposal.organizationId(), platformCode,
                proposal.storeId(), productVariantId, now).orElse(null);

        BigDecimal currentPrice = facts.latestPrice(subjectId, now)
                .map(PriceSnapshot::effectivePrice)
                .map(money -> money == null ? null : money.amount())
                .orElse(null);

        boolean blockedByDiagnosis = diagnosis
                .currentFindings(SubjectKind.PLATFORM_LISTING_VARIANT, subjectId,
                        proposal.window())
                .stream()
                .anyMatch(DiagnosisFindingView::blocksExecution);

        return new GuardrailInput(policy, current, currentPrice,
                proposedPrice(proposal), changeHistory.cumulativeChangeRate(subjectId,
                        now.minus(java.time.Duration.ofHours(DAILY_WINDOW_HOURS))),
                changeHistory.lastChangeAt(subjectId).orElse(null), now, mapped, conflict,
                blockedByDiagnosis,
                EntityVersion.of(current).equals(proposal.entityVersionDigest()),
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
        components.add(input.proposedPrice().toPlainString());
        components.add(String.valueOf(input.lastChangeAt()));
        components.add(input.cumulativeDailyChangeRate().toPlainString());
        components.add(Boolean.toString(input.mappingResolved()));
        components.add(Boolean.toString(input.mappingConflictOpen()));
        components.add(Boolean.toString(input.diagnosisBlocksExecution()));
        components.add(outcome.reasons().stream().map(GuardrailReason::name)
                .sorted().reduce("", (left, right) -> left + ',' + right));
        return Digest.ofComponents(components);
    }
}
