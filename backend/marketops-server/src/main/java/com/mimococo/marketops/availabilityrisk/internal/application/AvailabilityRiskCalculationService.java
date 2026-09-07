package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.availabilityrisk.ChildKind;
import com.mimococo.marketops.availabilityrisk.internal.domain.AvailabilityPolicySet;
import com.mimococo.marketops.availabilityrisk.internal.domain.CarriedForwardDemand;
import com.mimococo.marketops.availabilityrisk.internal.domain.ChannelRiskCalculator;
import com.mimococo.marketops.availabilityrisk.internal.domain.ChildRisk;
import com.mimococo.marketops.availabilityrisk.internal.domain.CompanyObservation;
import com.mimococo.marketops.availabilityrisk.internal.domain.CompanyRiskCalculator;
import com.mimococo.marketops.availabilityrisk.internal.domain.DemandDecision;
import com.mimococo.marketops.availabilityrisk.internal.domain.DemandPolicyEngine;
import com.mimococo.marketops.availabilityrisk.internal.domain.DemandPolicySettings;
import com.mimococo.marketops.availabilityrisk.internal.domain.DemandWindow;
import com.mimococo.marketops.availabilityrisk.internal.domain.DemandWindowEvidence;
import com.mimococo.marketops.availabilityrisk.internal.domain.InboundConsignment;
import com.mimococo.marketops.availabilityrisk.internal.domain.LeadTimeResolution;
import com.mimococo.marketops.availabilityrisk.internal.domain.PriorityPolicy;
import com.mimococo.marketops.availabilityrisk.internal.domain.PriorityPolicyVersion;
import com.mimococo.marketops.availabilityrisk.internal.domain.ReturnQualityPolicyVersion;
import com.mimococo.marketops.availabilityrisk.internal.domain.ReturnQualityAssessment;
import com.mimococo.marketops.availabilityrisk.internal.domain.ProfitAssessment;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyRepository;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityProjectionRepository;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.InboundAttestationRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces one variant's complete risk from published facts and policy.
 *
 * <p>This is the only place a risk is calculated. The targeted worker and the
 * hourly sweep both call it, so "the targeted result equals the sweep result"
 * is true by construction for the same as-of instant and policy set, and the
 * tests that assert it are checking that the construction has not been broken
 * rather than that two independent implementations happen to agree.
 *
 * <p>The service reads; it does not write. Persisting is a separate step, which
 * keeps the calculation free to be run twice and compared.
 */
@Service
public class AvailabilityRiskCalculationService {

    /** The demand policy's freshness bound also governs stock observations. */
    private static final BigDecimal DEFAULT_LIFECYCLE_WEIGHT = BigDecimal.ZERO;

    private final AvailabilityEvidenceGatherer evidence;
    private final AvailabilityPolicyRepository policies;
    private final InboundAttestationRepository inbound;
    private final ProfitLaneResolver profit;
    private final AvailabilityProjectionRepository projection;

    public AvailabilityRiskCalculationService(AvailabilityEvidenceGatherer evidence,
                                              AvailabilityPolicyRepository policies,
                                              InboundAttestationRepository inbound,
                                              ProfitLaneResolver profit,
                                              AvailabilityProjectionRepository projection) {
        this.evidence = evidence;
        this.policies = policies;
        this.inbound = inbound;
        this.profit = profit;
        this.projection = projection;
    }

    /**
     * Calculate one variant's channel and company risks.
     *
     * <p>A missing demand policy blocks everything, because without it there is
     * no versioned rule by which any window could be selected and any rate the
     * code chose would be the code's opinion rather than the organization's.
     */
    @Transactional(readOnly = true)
    public VariantRisk calculate(UUID organizationId, UUID productVariantId, Instant asOf) {
        Optional<DemandPolicySettings> demandPolicy =
                policies.resolveDemandPolicy(organizationId, asOf);
        LeadTimeResolution leadTime = policies.resolveLeadTime(
                organizationId, productVariantId, null, null, null, asOf);

        DemandPolicySettings demandSettings = demandPolicy.orElse(null);
        // Demand selection and stock freshness are independent policy effects.
        // A cancelled demand selector must not erase the approved freshness
        // boundary that applied to an exact channel observation in that same
        // effective interval. With no declared boundary at all, zero minutes
        // remains fail-closed rather than inventing a TTL.
        long freshnessMinutes = demandSettings == null
                ? policies.resolveStockFreshnessMax(organizationId, asOf)
                        .map(java.time.Duration::toMinutes).orElse(0L)
                : demandSettings.stockFreshnessMax().toMinutes();

        PriorityPolicyVersion priority = policies.resolvePriorityPolicy(organizationId, asOf)
                .orElse(null);
        ReturnQualityPolicyVersion returnQuality = policies
                .resolveReturnQualityPolicy(organizationId, asOf).orElse(null);
        AvailabilityPolicySet policySet = new AvailabilityPolicySet(leadTime, demandSettings,
                policies.resolveActivationPolicy(organizationId, asOf).orElse(null), priority,
                returnQuality);

        // One gatherer for this calculation, so the channel view and the company
        // view of the same variant ask each identical question once. They read
        // one snapshot at one instant, so the second answer could only ever have
        // been the first one again.
        AvailabilityEvidenceGatherer reading = evidence.forOneCalculation();

        List<AvailabilityEvidenceGatherer.ChannelSubject> subjects =
                reading.channelSubjects(productVariantId, asOf);
        List<VariantRisk.ScoredChild> children = new ArrayList<>();

        for (AvailabilityEvidenceGatherer.ChannelSubject subject : subjects) {
            UUID listingVariantId = subject.observation().platformListingVariantId();
            long modesForListing = subjects.stream()
                    .filter(candidate -> candidate.observation().platformListingVariantId()
                            .equals(listingVariantId))
                    .count();
            boolean modeAttributable = modesForListing == 1
                    && !"UNKNOWN".equals(subject.observation().fulfillmentModeCode());
            List<DemandWindowEvidence> windows =
                    reading.channelDemandWindows(listingVariantId,
                            subject.observation().fulfillmentModeCode(), modeAttributable, asOf);
            DemandDecision demand = demandSettings == null
                    ? missingDemandPolicy()
                    : DemandPolicyEngine.decide(windows, demandSettings,
                            carriedForward(ChildKind.CHANNEL, organizationId, productVariantId,
                                    listingVariantId,
                                    subject.observation().fulfillmentModeCode()), asOf);
            ProfitAssessment assessment = profit.resolve(listingVariantId, asOf);
            ChildRisk risk = ChannelRiskCalculator.calculate(subject.observation(), demand,
                    leadTime, assessment, freshnessMinutes, asOf);
            ReturnQualityAssessment quality = reading.returnQuality(
                    listingVariantId, returnQuality, asOf);
            if (quality.state() != ReturnQualityAssessment.State.CLEAR) {
                risk = risk.qualityReview(quality.blockerCode(),
                        quality.state() == ReturnQualityAssessment.State.POLICY_BLOCKED);
            }
            risk = priority == null ? risk.withBlocker("PRIORITY_POLICY_UNRESOLVED") : risk;
            children.add(new VariantRisk.ScoredChild(risk, subject,
                    ranking(risk, priority), windows));
        }

        List<DemandWindowEvidence> companyWindows =
                reading.companyDemandWindows(subjects, asOf);
        DemandDecision companyDemand = demandSettings == null
                ? missingDemandPolicy()
                : DemandPolicyEngine.decide(companyWindows, demandSettings,
                        carriedForward(ChildKind.COMPANY, organizationId, productVariantId,
                                null, null), asOf);
        List<InboundConsignment> consignments =
                inbound.currentFor(organizationId, productVariantId);
        CompanyObservation companyObservation = reading.companyObservation(
                organizationId, productVariantId, subjects, consignments, asOf);
        // The company answer inherits the strongest profit lane any of its
        // channels established. A variant profitable on one marketplace is
        // worth replenishing even when another channel has no figure.
        ProfitAssessment companyProfit = strongestProfit(children);
        ChildRisk companyRisk = CompanyRiskCalculator.calculate(companyObservation, companyDemand,
                leadTime, companyProfit, freshnessMinutes, asOf);
        companyRisk = priority == null
                ? companyRisk.withBlocker("PRIORITY_POLICY_UNRESOLVED") : companyRisk;
        children.add(new VariantRisk.ScoredChild(companyRisk, null,
                ranking(companyRisk, priority), companyWindows));

        return new VariantRisk(organizationId, productVariantId, asOf, policySet,
                List.copyOf(children));
    }

    /**
     * The company answer when no demand policy version is in force.
     *
     * <p>There is no channel detail to report either: without a policy the
     * windows cannot be judged, and reporting per-channel lanes derived from an
     * unversioned rule is exactly what the Contract forbids.
     */
    private static DemandDecision missingDemandPolicy() {
        return new DemandDecision(null, null,
                "no active demand-observation policy version is in force",
                com.mimococo.marketops.availabilityrisk.RiskEvidenceState.POLICY_BLOCKED,
                com.mimococo.marketops.availabilityrisk.RiskConfidence.UNUSABLE,
                List.of(), null, null);
    }

    private static PriorityPolicy.Ranking ranking(ChildRisk risk,
                                                  PriorityPolicyVersion priority) {
        return priority == null ? PriorityPolicy.unranked(risk)
                : PriorityPolicy.rank(risk, DEFAULT_LIFECYCLE_WEIGHT, priority);
    }

    /**
     * The last eligible demand answer stored for this subject.
     *
     * <p>It cannot come from the current windows: if one of those were
     * eligible the policy would have selected it and carry-forward would never
     * arise. It comes from what was stored the last time observation worked.
     */
    private CarriedForwardDemand carriedForward(ChildKind childKind, UUID organizationId,
                                                UUID productVariantId,
                                                UUID platformListingVariantId,
                                                String fulfillmentModeCode) {
        return projection.lastEligibleDemand(organizationId, childKind, productVariantId,
                        platformListingVariantId, fulfillmentModeCode)
                .map(row -> new CarriedForwardDemand(row.dailyRate(),
                        DemandWindow.valueOf(row.windowCode()), row.periodEnd()))
                .orElse(null);
    }

    private static ProfitAssessment strongestProfit(List<VariantRisk.ScoredChild> children) {
        return children.stream()
                .map(child -> child.risk().profit())
                .min((left, right) -> Integer.compare(left.lane().ordinal(), right.lane().ordinal()))
                .orElseGet(() -> ProfitAssessment.unknown("no channel published a profit figure"));
    }
}
