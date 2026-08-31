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

        if (demandPolicy.isEmpty()) {
            return blockedByMissingDemandPolicy(organizationId, productVariantId, asOf, leadTime);
        }
        DemandPolicySettings demandSettings = demandPolicy.get();
        long freshnessMinutes = demandSettings.stockFreshnessMax().toMinutes();

        Optional<AvailabilityPolicyRepository.ActivationRow> activation =
                policies.resolveActivationPolicy(organizationId, asOf);
        AvailabilityPolicySet policySet = new AvailabilityPolicySet(leadTime, demandSettings,
                activation.map(AvailabilityPolicyRepository.ActivationRow::id).orElse(null),
                activation.map(AvailabilityPolicyRepository.ActivationRow::policyVersion)
                        .orElse(null));

        List<AvailabilityEvidenceGatherer.ChannelSubject> subjects =
                evidence.channelSubjects(productVariantId, asOf);
        List<VariantRisk.ScoredChild> children = new ArrayList<>();

        for (AvailabilityEvidenceGatherer.ChannelSubject subject : subjects) {
            UUID listingVariantId = subject.observation().platformListingVariantId();
            List<DemandWindowEvidence> windows =
                    evidence.channelDemandWindows(listingVariantId, asOf);
            DemandDecision demand = DemandPolicyEngine.decide(windows, demandSettings,
                    carriedForward(ChildKind.CHANNEL, organizationId, productVariantId,
                            listingVariantId, subject.observation().fulfillmentModeCode()),
                    asOf);
            ProfitAssessment assessment = profit.resolve(listingVariantId, asOf);
            ChildRisk risk = ChannelRiskCalculator.calculate(subject.observation(), demand,
                    leadTime, assessment, freshnessMinutes, asOf);
            children.add(new VariantRisk.ScoredChild(risk, subject,
                    PriorityPolicy.rank(risk, DEFAULT_LIFECYCLE_WEIGHT), windows));
        }

        List<UUID> listingVariantIds = subjects.stream()
                .map(subject -> subject.observation().platformListingVariantId())
                .distinct()
                .toList();
        List<DemandWindowEvidence> companyWindows =
                evidence.companyDemandWindows(listingVariantIds, asOf);
        DemandDecision companyDemand = DemandPolicyEngine.decide(companyWindows, demandSettings,
                carriedForward(ChildKind.COMPANY, organizationId, productVariantId, null, null),
                asOf);
        List<InboundConsignment> consignments =
                inbound.currentFor(organizationId, productVariantId);
        CompanyObservation companyObservation = evidence.companyObservation(
                organizationId, productVariantId, subjects, consignments, asOf);
        // The company answer inherits the strongest profit lane any of its
        // channels established. A variant profitable on one marketplace is
        // worth replenishing even when another channel has no figure.
        ProfitAssessment companyProfit = strongestProfit(children);
        ChildRisk companyRisk = CompanyRiskCalculator.calculate(companyObservation, companyDemand,
                leadTime, companyProfit, freshnessMinutes, asOf);
        children.add(new VariantRisk.ScoredChild(companyRisk, null,
                PriorityPolicy.rank(companyRisk, DEFAULT_LIFECYCLE_WEIGHT), companyWindows));

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
    private VariantRisk blockedByMissingDemandPolicy(UUID organizationId, UUID productVariantId,
                                                     Instant asOf, LeadTimeResolution leadTime) {
        DemandPolicySettings placeholder = new DemandPolicySettings(
                new UUID(0, 0), 0, 1, BigDecimal.valueOf(2), BigDecimal.valueOf(0.5),
                BigDecimal.ONE, BigDecimal.ONE, Duration.ZERO, Duration.ofMinutes(1));
        DemandDecision blocked = new DemandDecision(null, null,
                "no active demand-observation policy version is in force",
                com.mimococo.marketops.availabilityrisk.RiskEvidenceState.POLICY_BLOCKED,
                com.mimococo.marketops.availabilityrisk.RiskConfidence.UNUSABLE,
                List.of(), null, null);
        ChildRisk risk = new ChildRisk(ChildKind.COMPANY,
                com.mimococo.marketops.availabilityrisk.AvailabilityLane.REVIEW,
                com.mimococo.marketops.availabilityrisk.RiskEvidenceState.POLICY_BLOCKED,
                com.mimococo.marketops.availabilityrisk.RiskConfidence.UNUSABLE,
                com.mimococo.marketops.availabilityrisk.RiskCause.DEMAND_POLICY_MISSING,
                com.mimococo.marketops.availabilityrisk.internal.domain.ProvenSupply.none(),
                blocked, leadTime, ProfitAssessment.unknown("not evaluated without a demand policy"),
                null, null,
                com.mimococo.marketops.availabilityrisk.internal.domain.ConservativeProof.none(),
                List.of("DEMAND_POLICY_UNRESOLVED"));
        return new VariantRisk(organizationId, productVariantId, asOf,
                new AvailabilityPolicySet(leadTime, placeholder, null, null),
                List.of(new VariantRisk.ScoredChild(risk, null,
                        PriorityPolicy.rank(risk, DEFAULT_LIFECYCLE_WEIGHT), List.of())));
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
