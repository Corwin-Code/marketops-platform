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
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.AdBidImpactPreview;
import com.mimococo.marketops.operationsworkflow.AdvertisingBidProjection;
import com.mimococo.marketops.operationsworkflow.AdvertisingDecisionAuthority;
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
    private final AdvertisingDecisionAuthority advertising;
    private final IdGenerator idGenerator;
    private final AdvertisingImpactEvidenceService impactEvidence;

    GuardrailService(MetricQuery metrics,
                     DiagnosisQuery diagnosis,
                     GuardrailRepository evaluations,
                     PriceChangeHistory changeHistory,
                     AdvertisingDecisionAuthority advertising,
                     IdGenerator idGenerator,AdvertisingImpactEvidenceService impactEvidence) {
        this.metrics = metrics;
        this.diagnosis = diagnosis;
        this.evaluations = evaluations;
        this.changeHistory = changeHistory;
        this.advertising = advertising;
        this.idGenerator = idGenerator;
        this.impactEvidence=impactEvidence;
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
        if (proposal.actionKind() == ActionKind.AD_BID_CHANGE) {
            return previewAdBidChange(proposal, purpose).verdict();
        }
        return preview(proposal, authorizationBound, purpose).verdict();
    }

    /**
     * Evaluate one proposed bid change and project what it would do.
     *
     * <p>Structurally the same as the price preview: one evaluation produces
     * both the verdict and the projection, and the verdict is recorded in the
     * same table by the same writer. What differs is where the facts come from.
     * The price engine reads price, cost and stock; there is no equivalent for a
     * bid, because whether a bid is safe is an advertising question about
     * conversion, attribution and the set of variants one object reaches.
     *
     * <p>So the advertising module's deterministic refusals are carried through
     * rather than re-derived, and this method adds the refusals that belong to
     * the workflow: an elapsed proposal, facts that moved since the case, an
     * approval bound the change exceeds. Neither side overrides the other, and
     * an empty union is the only way to pass.
     */
    @Transactional
    public AdBidImpactPreview previewAdBidChange(RecommendationView proposal,
                                                 GuardrailPurpose purpose) {
        GuardrailRepository.AdvertisingAuthority authority =
                evaluations.captureAdBidAuthority(proposal.id());
        Instant now = authority.evaluationAsOf();
        AdvertisingBidProjection projection =
                advertising.bidProjection(proposal.id()).orElse(null);
        List<String> unresolved = advertising.unresolvedReasons(proposal.id());
        var evidence=impactEvidence.capture(proposal.id(),now,projection==null?null:projection.decisionBundleId());

        var headroom=evidence==null?null:evidence.path("policyVersions").path("target").path("ceiling_headroom_ratio");
        java.math.BigDecimal headroomRatio=headroom!=null && headroom.isNumber()?headroom.decimalValue():null;
        List<GuardrailReason> reasons = adBidReasons(proposal, projection, unresolved, now, purpose,
                evidence!=null && evidence.path("policyVersions").path("target").path("allow_protection_intermediate_target").asBoolean(false),headroomRatio);
        boolean passed = reasons.isEmpty();

        UUID evaluationId = idGenerator.newId();
        String inputDigest = Digest.ofComponents(List.of(adBidDigest(proposal, projection, unresolved, now, purpose),
                evidence == null ? "EVIDENCE_NOT_AVAILABLE" : evidence.toString()));
        evaluations.insert(evaluationId, proposal.organizationId(), proposal.id(),
                null, null,
                projection == null ? null : projection.decisionBundleId(),
                projection == null ? null : projection.decisionBundleVersion(),
                purpose, passed, reasons, adBidDetail(projection),
                inputDigest, authority.document(), now, CorrelationId.current());

        GuardrailVerdict verdict = new GuardrailVerdict(evaluationId, purpose, passed,
                reasons, null, null, adBidDetail(projection), inputDigest);
        impactEvidence.record(evaluationId,proposal.id(),evidence,now);
        return new AdBidImpactPreview(proposal.id(), projection,
                List.of(), unresolved, verdict,evidence);
    }

    /**
     * Everything that refuses this bid change, from both sides.
     *
     * <p>The advertising module's blockers are mapped to one reason rather than
     * copied in one by one, because they are its vocabulary and it owns their
     * meaning; the detail carries them verbatim so nothing is lost.
     */
    private static List<GuardrailReason> adBidReasons(RecommendationView proposal,
                                                      AdvertisingBidProjection projection,
                                                      List<String> unresolved, Instant now, GuardrailPurpose purpose,boolean intermediateAllowed,
                                                      java.math.BigDecimal headroomRatio) {
        List<GuardrailReason> reasons = new ArrayList<>();
        if (!proposal.validUntil().isAfter(now)) {
            reasons.add(GuardrailReason.RECOMMENDATION_EXPIRED);
        }
        if (projection == null) {
            // Nothing is known about the change, so nothing about it can pass.
            reasons.add(GuardrailReason.ADVERTISING_CASE_BLOCKED);
            return List.copyOf(reasons);
        }
        if (!projection.actionBlockerCodes().isEmpty()) {
            reasons.add(GuardrailReason.ADVERTISING_CASE_BLOCKED);
        }
        if (!java.util.Objects.equals(projection.entityVersionDigest(),
                proposal.entityVersionDigest())) {
            reasons.add(GuardrailReason.ENTITY_VERSION_CHANGED);
        }
        if (projection.currentBidAmount() == null || projection.targetBidAmount() == null) {
            reasons.add(GuardrailReason.CURRENT_BID_NOT_OBSERVED);
        } else if (projection.currentBidAmount().compareTo(projection.targetBidAmount()) == 0) {
            reasons.add(GuardrailReason.NO_CHANGE_PROPOSED);
        }
        boolean causeBoundProtection = "PROTECTION_DECREASE".equals(projection.direction())
                && "CAUSE_BOUND_PROTECTION_STEP".equals(projection.candidateBasis())
                && "PROTECTION".equals(projection.lane());
        if (projection.maxCpcAmount() == null && !causeBoundProtection) {
            reasons.add(GuardrailReason.MAX_CPC_UNAVAILABLE);
        } else if (!causeBoundProtection) {
            if(headroomRatio==null || headroomRatio.signum()<0 || headroomRatio.compareTo(java.math.BigDecimal.ONE)>=0) {
                reasons.add(GuardrailReason.AD_POLICY_BUNDLE_UNRESOLVED);
            } else if(projection.targetBidAmount()!=null && projection.maxCpcAmount()!=null
                    && projection.targetBidAmount().compareTo(projection.maxCpcAmount().multiply(java.math.BigDecimal.ONE.subtract(headroomRatio)))>0
                    && !("PROTECTION_DECREASE".equals(projection.direction()) && intermediateAllowed)) {
                reasons.add(GuardrailReason.ABOVE_MAX_CPC);
            }
        }
        if (!projection.exhaustedExposureAxes().isEmpty()) {
            reasons.add(GuardrailReason.EXPOSURE_ENVELOPE_EXHAUSTED);
        }
        if (!projection.authorised()) {
            // A verdict that passes has to name the authority that let it pass,
            // and for an advertising decision that is the bundle. Without one
            // there is nothing to record a PASS against.
            reasons.add(GuardrailReason.AD_POLICY_BUNDLE_UNRESOLVED);
        }
        for (String reason : unresolved) {
            switch (reason) {
                case "BID_MOVED_SINCE_CANDIDATE" ->
                        reasons.add(GuardrailReason.BID_MOVED_SINCE_CANDIDATE);
                case "CURRENT_BID_NOT_OBSERVED" ->
                        reasons.add(GuardrailReason.CURRENT_BID_NOT_OBSERVED);
                case "CONTROL_GRANULARITY_UNPROVEN" ->
                        reasons.add(GuardrailReason.CONTROL_GRANULARITY_UNPROVEN);
                case "RESERVATION_NOT_HELD" ->
                        reasons.add(GuardrailReason.RESERVATION_NOT_HELD);
                case "BUNDLE_UNRESOLVED", "BUNDLE_AMBIGUOUS" ->
                        reasons.add(GuardrailReason.AD_POLICY_BUNDLE_UNRESOLVED);
                case "APPROVAL_LEASE_POLICY_ABSENT" ->
                        reasons.add(GuardrailReason.APPROVAL_LEASE_POLICY_ABSENT);
                case "RECOMMENDATION_EXPIRED" ->
                        reasons.add(GuardrailReason.RECOMMENDATION_EXPIRED);
                case "NO_CHANGE_PROPOSED" ->
                        reasons.add(GuardrailReason.NO_CHANGE_PROPOSED);
                case "APPROVAL_MISSING" -> {
                    if (purpose == GuardrailPurpose.EXECUTION) reasons.add(GuardrailReason.ADVERTISING_CASE_BLOCKED);
                }
                default -> reasons.add(GuardrailReason.ADVERTISING_CASE_BLOCKED);
            }
        }
        return List.copyOf(new java.util.LinkedHashSet<>(reasons));
    }

    /** What the verdict rests on, in the form the console reads. */
    private static Map<String, String> adBidDetail(AdvertisingBidProjection projection) {
        if (projection == null) {
            return Map.of("projection", "UNAVAILABLE");
        }
        Map<String, String> detail = new java.util.LinkedHashMap<>();
        detail.put("lane", String.valueOf(projection.lane()));
        detail.put("causeCode", String.valueOf(projection.causeCode()));
        detail.put("direction", projection.direction());
        detail.put("currentBid", projection.currentBidAmount() == null ? "UNKNOWN" : projection.currentBidAmount().toPlainString());
        detail.put("targetBid", projection.targetBidAmount() == null ? "UNKNOWN" : projection.targetBidAmount().toPlainString());
        detail.put("currency", String.valueOf(projection.currencyCode()));
        detail.put("bidUnit", String.valueOf(projection.bidUnitCode()));
        detail.put("materialityRoute", String.valueOf(projection.materialityRoute()));
        detail.put("affectedVariantCount",
                Integer.toString(projection.affectedVariantCount()));
        detail.put("affectedSetDigest", String.valueOf(projection.affectedSetDigest()));
        detail.put("maxCpcState", String.valueOf(projection.maxCpcState()));
        if (!projection.blockerCodes().isEmpty()) {
            detail.put("advertisingBlockers", String.join(",", projection.blockerCodes()));
        }
        if (!projection.exhaustedExposureAxes().isEmpty()) {
            detail.put("exhaustedExposureAxes",
                    String.join(",", projection.exhaustedExposureAxes()));
        }
        return Map.copyOf(detail);
    }

    /** Digest exactly what this advertising verdict was made from. */
    private static String adBidDigest(RecommendationView proposal,
                                      AdvertisingBidProjection projection,
                                      List<String> unresolved, Instant now, GuardrailPurpose purpose) {
        List<String> components = new ArrayList<>();
        components.add(proposal.id().toString());
        components.add(purpose.name());
        components.add(proposal.entityVersionDigest());
        components.add(now.toString());
        components.add(projection == null ? "NO_PROJECTION" : projection.direction());
        components.add(projection == null
                ? "NO_PROJECTION" : projection.currentBidAmount() == null ? "UNKNOWN" : projection.currentBidAmount().toPlainString());
        components.add(projection == null
                ? "NO_PROJECTION" : projection.targetBidAmount() == null ? "UNKNOWN" : projection.targetBidAmount().toPlainString());
        components.add(projection == null
                ? "NO_PROJECTION" : String.valueOf(projection.affectedSetDigest()));
        components.add(projection == null
                ? "NO_PROJECTION" : String.join(",", projection.blockerCodes()));
        components.add(String.join(",", unresolved));
        return Digest.ofComponents(components);
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
