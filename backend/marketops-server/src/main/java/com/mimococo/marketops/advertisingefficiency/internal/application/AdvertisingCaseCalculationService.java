package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.AdConfidence;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseIdentity;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdLaneResolver;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdLinkedConversion;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdPolicySet;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdPriorityPolicy;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdvertisingContributionProfit;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AffectedSet;
import com.mimococo.marketops.advertisingefficiency.internal.domain.MaxCpc;
import com.mimococo.marketops.advertisingefficiency.internal.domain.OneSidedDangerProof;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns gathered evidence into cases, and writes nothing.
 *
 * <p>This is a pure function of {@link AdvertisingEvidenceGatherer.Evidence}
 * plus the injected clock-free {@code asOf}. That is what makes the targeted
 * path and the hourly sweep equivalent by construction rather than by
 * inspection: both hand the same evidence to this method and compare the value
 * it returns.
 *
 * <p>The method reads as a sequence of questions, and the order they are asked
 * in is the design. Measures first, because a lane cannot be decided without
 * them. Danger proofs second, each naming the facts that prove it and the facts
 * that are missing. The ladder third. Ranking last, because a rank is a property
 * of a decided case rather than an input to deciding it.
 */
@Service
class AdvertisingCaseCalculationService {

    private static final MathContext CONTEXT = new MathContext(20, RoundingMode.HALF_UP);

    private final AdvertisingEvidenceGatherer gatherer;

    AdvertisingCaseCalculationService(AdvertisingEvidenceGatherer gatherer) {
        this.gatherer = gatherer;
    }

    @Transactional(readOnly = true)
    Optional<AdCaseCalculation> calculate(UUID organizationId, UUID objectId, Instant asOf) {
        return gatherer.gather(organizationId, objectId, asOf).map(this::calculateFrom);
    }

    AdCaseCalculation calculateFrom(AdvertisingEvidenceGatherer.Evidence evidence) {
        AdvertisingEvidenceRepository.ObjectRow object = evidence.object();
        AffectedSet affectedSet = affectedSetOf(evidence);
        String currency = currencyOf(evidence);

        AdMeasure currentBid = currentBidOf(evidence);
        AdMeasure officialSpend = officialSpendOf(evidence);
        AdMeasure eligibleTraffic = eligibleTrafficOf(evidence);
        AdLinkedConversion conversion = conversionOf(evidence, affectedSet, eligibleTraffic);
        MaxCpc maxCpc = maxCpcOf(evidence, conversion, currency);
        AdvertisingContributionProfit profit = profitOf(evidence, affectedSet, currency);
        AdMeasure attributionGap = attributionGapOf(evidence);
        AdMeasure recoverable = recoverableProfitOf(profit, maxCpc, economicBidOf(evidence, currentBid), officialSpend);

        AdEvidenceState evidenceState = evidenceStateOf(
                affectedSet, officialSpend, profit, conversion);
        AdConfidence confidence = confidenceOf(evidenceState, conversion, profit);

        List<String> blockers = new ArrayList<>();
        AdvertisingCause dataDefect = dataDefectOf(evidence, affectedSet, profit,
                conversion, attributionGap, blockers);

        AdLaneResolver.Signals signals = new AdLaneResolver.Signals(
                evidence.containment().unresolvedCommandOpen(),
                !evidence.containment().containmentKinds().isEmpty(),
                evidence.authorities().compensationPending(),
                sellabilityDanger(evidence, officialSpend),
                availabilityDanger(evidence, officialSpend),
                evidence.authorities().criticalSignals().regressed() ? OneSidedDangerProof.of(
                        "CRITICAL_SALES_UNIT_AT_RISK", List.of("FRESH_FROZEN_REQUIRED_UNIT_REGRESSION"),
                        evidence.authorities().criticalSignals().unknown() ? List.of("OTHER_CRITICAL_UNIT_UNKNOWN") : List.of(), false)
                        : OneSidedDangerProof.none(),
                economicHarm(evidence, profit, officialSpend, conversion),
                OneSidedDangerProof.none(),
                dataDefect,
                List.copyOf(blockers),
                object.independentlyControllable(),
                optimizationQualified(evidence, conversion, officialSpend, profit),
                optimizationMaterial(evidence, recoverable),
                recoverable,
                evidenceState,
                confidence);

        AdLaneResolver.Decision primary = AdLaneResolver.resolve(signals);
        AdPolicySet policies = policySetOf(evidence);

        List<AdCaseCalculation.ScoredCase> cases = new ArrayList<>(2);
        cases.add(score(evidence, primary, policies, profit, officialSpend, eligibleTraffic,
                conversion, maxCpc, attributionGap, currentBid, recoverable, currency));

        // A defect owned by somebody other than the person the primary case routes
        // to is separate work with a separate owner, so it is a separate case.
        // The Contract permits exactly this and forbids folding them together.
        if (dataDefect != null
                && primary.cause() != dataDefect
                && dataDefect.accountableRole() != null
                && dataDefect.accountableRole() != primary.cause().accountableRole()) {
            AdLaneResolver.Decision repair = new AdLaneResolver.Decision(
                    AdvertisingLane.DATA_REPAIR, null, dataDefect, evidenceState, confidence,
                    List.copyOf(blockers));
            cases.add(score(evidence, repair, policies, profit, officialSpend, eligibleTraffic,
                    conversion, maxCpc, attributionGap, currentBid, recoverable, currency));
        }

        return new AdCaseCalculation(
                object.organizationId(), object.id(), object.storeId(), object.platformCode(),
                object.semanticProfileId(), object.lineageGeneration(), evidence.asOf(),
                policies, affectedSet,
                evidence.affectedSet()
                        .map(AdvertisingEvidenceRepository.AffectedSetRow::id)
                        .orElse(null),
                List.copyOf(cases), java.util.stream.Stream.concat(evidence.taskQualification().stream(),
                        evidence.writeQualification().stream()).map(policy -> new AdCaseCalculation.QualificationPeriod(
                                policy.id(), evidence.windowStart(), evidence.asOf(),
                                qualificationConditions(evidence, policy, conversion, officialSpend, profit)))
                        .toList(), java.util.stream.Stream.of("QUEUE_OBSERVATION", "TASK_ACTIVATION",
                                "PROTECTION_RECOMMENDATION", "OPTIMIZATION_RECOMMENDATION", "PROTECTION_BID_WRITE", "OPTIMIZATION_BID_WRITE")
                                .flatMap(purpose -> AdvertisingPurposeFreshness.assess(evidence, purpose,
                                        List.of("OFFICIAL_AD_SPEND", "OFFICIAL_AD_TRAFFIC", "AD_LINKED_SALE_EVENT",
                                                "COST_AND_FEE", "AD_OBJECT_CONFIGURATION", "AFFECTED_SET", "SELLABILITY", "AVAILABILITY")).stream()).toList(),
                        evidence.writeQualification().map(policy -> qualificationConditions(evidence, policy,
                                conversion, officialSpend, profit)
                                && evidence.authorities().sustainedPeriods().getOrDefault(policy.id(), 0) + 1
                                        >= policy.minimumSustainedPeriods()).orElse(false));
    }

    // ----------------------------------------------------------------------
    // Measures
    // ----------------------------------------------------------------------

    private static AffectedSet affectedSetOf(AdvertisingEvidenceGatherer.Evidence evidence) {
        return evidence.affectedSet()
                .map(row -> "COMPLETE".equals(row.resolutionState())
                        ? AffectedSet.complete(row.productVariantIds(),
                                row.platformListingVariantIds())
                        : AffectedSet.unresolved(row.productVariantIds(),
                                row.platformListingVariantIds(),
                                AffectedSet.Resolution.valueOf(row.resolutionState()),
                                row.unresolvedReasonCodes().isEmpty()
                                        ? List.of("AFFECTED_SET_REASON_NOT_RECORDED")
                                        : row.unresolvedReasonCodes()))
                .orElseGet(() -> AffectedSet.unresolved(List.of(), List.of(),
                        AffectedSet.Resolution.UNRESOLVED, List.of("AFFECTED_SET_NEVER_RESOLVED")));
    }

    private static String currencyOf(AdvertisingEvidenceGatherer.Evidence evidence) {
        return evidence.objectFacts()
                .map(AdvertisingEvidenceRepository.ObjectFactAggregate::currencyCode)
                .or(() -> evidence.configuration()
                        .map(AdvertisingEvidenceRepository.ConfigurationRow::bidCurrencyCode))
                .orElse(null);
    }

    private static AdMeasure currentBidOf(AdvertisingEvidenceGatherer.Evidence evidence) {
        return evidence.configuration()
                .filter(row -> row.observedBidAmount() != null)
                .map(row -> AdMeasure.available(row.observedBidAmount(),
                        gradeOf(row.evidenceGrade())))
                // No reported bid is not a bid of zero. It is an object whose bid
                // we cannot see, and every write purpose consuming it fails closed.
                .orElseGet(() -> AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE));
    }

    /** The configuration evidence hierarchy, mapped onto trust. Grades never promote. */
    private static AdEvidenceState gradeOf(String evidenceGrade) {
        return switch (evidenceGrade) {
            case "OFFICIAL_API_READBACK", "OFFICIAL_CONFIGURATION_EXPORT" ->
                    AdEvidenceState.CANONICAL_CONFIRMED;
            case "INDEPENDENT_MANUAL_VERIFICATION" -> AdEvidenceState.OPERATIONAL;
            default -> AdEvidenceState.PROVISIONAL_OR_ESTIMATED;
        };
    }

    private static AdMeasure officialSpendOf(AdvertisingEvidenceGatherer.Evidence evidence) {
        return evidence.objectFacts()
                .filter(facts -> facts.spendAmount() != null)
                .map(facts -> AdMeasure.available(facts.spendAmount(),
                        facts.everyWindowComplete()
                                ? (facts.anyCorrectionWindowOpen()
                                        ? AdEvidenceState.OPERATIONAL
                                        : AdEvidenceState.CANONICAL_CONFIRMED)
                                : AdEvidenceState.INCOMPLETE))
                .orElseGet(() -> AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE));
    }

    /**
     * The traffic denominator the resolved conversion definition names.
     *
     * <p>Clicks, views or impressions — whichever the definition says. Choosing
     * one here would be inventing the denominator the definition exists to fix.
     */
    private static AdMeasure eligibleTrafficOf(AdvertisingEvidenceGatherer.Evidence evidence) {
        Optional<String> kind = evidence.conversion()
                .map(AdvertisingPolicyRepository.ConversionDefinition::trafficDenominatorKind);
        if (kind.isEmpty() || evidence.objectFacts().isEmpty()) {
            return AdMeasure.notAvailable(AdEvidenceState.PROFILE_UNRESOLVED);
        }
        var facts = evidence.objectFacts().get();
        Long value = switch (kind.get()) {
            case "CLICKS" -> facts.clicks();
            case "VIEWS" -> facts.views();
            case "IMPRESSIONS" -> facts.impressions();
            default -> null;
        };
        return value == null
                ? AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE)
                : AdMeasure.available(BigDecimal.valueOf(value),
                        facts.everyWindowComplete()
                                ? AdEvidenceState.CANONICAL_CONFIRMED
                                : AdEvidenceState.INCOMPLETE);
    }

    private static AdLinkedConversion conversionOf(
            AdvertisingEvidenceGatherer.Evidence evidence,
            AffectedSet affectedSet,
            AdMeasure eligibleTraffic) {
        var definition = evidence.conversion();
        if (definition.isEmpty()) {
            return AdLinkedConversion.writeGrade(
                    SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE, 0, 0, null, null, false, false,
                    1, null, null, AdEvidenceState.PROFILE_UNRESOLVED);
        }
        var resolved = definition.get();
        long linked = evidence.completedSales()
                .map(AdvertisingEvidenceRepository.LinkedSaleAggregate::eventCount).orElse(0L);
        long traffic = eligibleTraffic.present() ? eligibleTraffic.value().longValue() : 0L;
        long distinctVariants = evidence.completedSales()
                .map(AdvertisingEvidenceRepository.LinkedSaleAggregate::distinctVariants)
                .orElse(0L);
        BigDecimal setCoverage = affectedSet.platformListingVariantIds().isEmpty()
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(distinctVariants).divide(
                        BigDecimal.valueOf(affectedSet.platformListingVariantIds().size()),
                        CONTEXT).min(BigDecimal.ONE);
        var lines = evidence.completedSales().map(AdvertisingEvidenceRepository.LinkedSaleAggregate::lines).orElse(List.of());
        long qualifiedUnits = lines.stream().filter(line -> line.productVariantId() != null
                && line.conversionDefinitionId().equals(resolved.id())
                && line.saleStage().equals(resolved.saleStage())
                && line.linkageBasis().equals(resolved.linkageBasis())
                && evidence.affectedSet().map(set -> set.id().equals(line.affectedSetId())).orElse(false))
                .mapToLong(AdvertisingEvidenceRepository.LinkedSaleLine::units).sum();
        BigDecimal linkageCoverage = linked <= 0 ? null : BigDecimal.valueOf(qualifiedUnits)
                .divide(BigDecimal.valueOf(linked), CONTEXT);
        boolean windowsAligned = !lines.isEmpty() && lines.stream().allMatch(line ->
                !line.periodStart().isBefore(evidence.windowStart()) && !line.periodEnd().isAfter(evidence.asOf()));
        AdEvidenceState state = eligibleTraffic.present()
                ? eligibleTraffic.evidenceState()
                : AdEvidenceState.NOT_AVAILABLE;

        return AdLinkedConversion.writeGrade(
                SaleStage.valueOf(resolved.saleStage()),
                linked, traffic, linkageCoverage, setCoverage,
                affectedSet.sufficientForWrite(), windowsAligned,
                resolved.minimumSampleEvents(),
                resolved.minimumLinkageCoverageRatio(),
                resolved.minimumAffectedSetCoverageRatio(),
                state);
    }

    private static AdvertisingAttributedEconomics.Result economicsOf(
            AdvertisingEvidenceGatherer.Evidence evidence, String currency) {
        return AdvertisingAttributedEconomics.calculate(evidence.completedSales().orElse(null),
                evidence.economics(), evidence.authorities().cpaByVariant(), officialSpendOf(evidence), currency);
    }

    private static MaxCpc maxCpcOf(AdvertisingEvidenceGatherer.Evidence evidence,
            AdLinkedConversion conversion, String currency) {
        if (evidence.authorities().cpaByVariant().isEmpty()) {
            return MaxCpc.absent(MaxCpc.Absence.ALLOWABLE_CPA_UNRESOLVED, AdEvidenceState.POLICY_BLOCKED);
        }
        var result = economicsOf(evidence, currency);
        long units = evidence.completedSales().map(AdvertisingEvidenceRepository.LinkedSaleAggregate::eventCount).orElse(0L);
        if (!result.allowableSpend().present() || units <= 0 || currency == null
                || result.allowableSpend().value().signum() <= 0) {
            return MaxCpc.absent(MaxCpc.Absence.ALLOWABLE_CPA_UNRESOLVED, result.profit().resolved()
                    ? AdEvidenceState.POLICY_BLOCKED : AdEvidenceState.DATA_BLOCKED);
        }
        if (!result.allowableSpend().sufficientForWrite()) {
            return MaxCpc.absent(MaxCpc.Absence.ALLOWABLE_CPA_UNRESOLVED, result.allowableSpend().evidenceState());
        }
        BigDecimal cpa = result.allowableSpend().value().divide(BigDecimal.valueOf(units), 8, RoundingMode.HALF_UP);
        return MaxCpc.compute(Money.of(cpa, currency), conversion.stage(), conversion);
    }

    static AdvertisingContributionProfit profitOf(AdvertisingEvidenceGatherer.Evidence evidence,
            AffectedSet affectedSet, String currency) {
        return economicsOf(evidence, currency).profit();
    }

    private static AdMeasure attributionGapOf(AdvertisingEvidenceGatherer.Evidence evidence) {
        Long providerOrders = evidence.objectFacts()
                .map(AdvertisingEvidenceRepository.ObjectFactAggregate::providerAttributedOrders)
                .orElse(null);
        Long canonical = evidence.completedSales()
                .map(AdvertisingEvidenceRepository.LinkedSaleAggregate::eventCount)
                .orElse(null);
        if (providerOrders == null || canonical == null) {
            return AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE);
        }
        if (canonical == 0) {
            // A gap over a zero canonical count is undefined, not infinite. The
            // absence of company sales is itself the finding.
            return AdMeasure.undefined(AdEvidenceState.INCOMPLETE);
        }
        BigDecimal gap = BigDecimal.valueOf(Math.abs(providerOrders - canonical))
                .divide(BigDecimal.valueOf(canonical), 6, RoundingMode.HALF_UP);
        return AdMeasure.available(gap, AdEvidenceState.OPERATIONAL);
    }

    /**
     * How much contribution a bounded correction could plausibly recover.
     *
     * <p>Only computable when the current bid sits above a write-grade ceiling:
     * the recoverable amount is the spend that the ceiling says was never
     * economic. With no ceiling there is no defensible number, and an invented
     * one would become the rank of an opportunity nobody can justify.
     */
    private static AdMeasure economicBidOf(AdvertisingEvidenceGatherer.Evidence evidence, AdMeasure nativeBid) {
        if (!nativeBid.present() || evidence.configuration().isEmpty()) { return AdMeasure.notAvailable(AdEvidenceState.UNKNOWN); }
        return switch (evidence.configuration().get().bidUnitCode()) {
            case "CURRENCY_MAJOR" -> nativeBid;
            case "CURRENCY_MINOR" -> AdMeasure.available(nativeBid.value().movePointLeft(2), nativeBid.evidenceState());
            default -> AdMeasure.notAvailable(AdEvidenceState.UNKNOWN);
        };
    }

    private static AdMeasure recoverableProfitOf(
            AdvertisingContributionProfit profit, MaxCpc maxCpc,
            AdMeasure currentBid, AdMeasure officialSpend) {
        if (!maxCpc.writeGrade() || !currentBid.present() || !officialSpend.present()) {
            return AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE);
        }
        BigDecimal ceiling = maxCpc.ceiling().amount();
        if (currentBid.value().compareTo(ceiling) <= 0) {
            return AdMeasure.available(BigDecimal.ZERO, maxCpc.evidenceState());
        }
        BigDecimal excessShare = currentBid.value().subtract(ceiling)
                .divide(currentBid.value(), CONTEXT);
        return AdMeasure.available(
                officialSpend.value().multiply(excessShare, CONTEXT)
                        .setScale(Money.SCALE, RoundingMode.HALF_UP),
                maxCpc.evidenceState().weakest(officialSpend.evidenceState()));
    }

    // ----------------------------------------------------------------------
    // Danger proofs
    // ----------------------------------------------------------------------

    /**
     * Every promoted variant is unsellable and money is still going out.
     *
     * <p>The strongest one-sided proof this Slice has: no conversion rate, however
     * favourable, can make a variant nobody can buy worth advertising. That is
     * exactly why the missing conversion cannot reverse the direction.
     */
    private static OneSidedDangerProof sellabilityDanger(
            AdvertisingEvidenceGatherer.Evidence evidence, AdMeasure officialSpend) {
        var states = evidence.variantAvailability();
        if (states.isEmpty() || !officialSpend.present()
                || officialSpend.value().signum() <= 0 || !officialSpend.sufficientForWrite()) {
            return OneSidedDangerProof.none();
        }
        if (!AdvertisingPurposeFreshness.failures(evidence, "PROTECTION_RECOMMENDATION",
                List.of("OFFICIAL_AD_SPEND", "SELLABILITY")).isEmpty()) { return OneSidedDangerProof.none(); }
        boolean everyVariantUnsellable = states.values().stream()
                .anyMatch(state -> "NOT_SELLABLE".equals(state.sellabilityState()));
        if (!everyVariantUnsellable) {
            return OneSidedDangerProof.none();
        }
        return OneSidedDangerProof.of("PROMOTED_VARIANT_NOT_SELLABLE",
                List.of("CONFIRMED_AFFECTED_VARIANT_NOT_SELLABLE", "OFFICIAL_SPEND_CONTINUING"),
                List.of("AD_LINKED_CONVERSION"), false);
    }

    /** Every promoted variant is unavailable and money is still going out. */
    private static OneSidedDangerProof availabilityDanger(
            AdvertisingEvidenceGatherer.Evidence evidence, AdMeasure officialSpend) {
        var states = evidence.variantAvailability();
        if (states.isEmpty() || !officialSpend.present()
                || officialSpend.value().signum() <= 0 || !officialSpend.sufficientForWrite()) {
            return OneSidedDangerProof.none();
        }
        if (!AdvertisingPurposeFreshness.failures(evidence, "PROTECTION_RECOMMENDATION",
                List.of("OFFICIAL_AD_SPEND", "AVAILABILITY")).isEmpty()) { return OneSidedDangerProof.none(); }
        boolean everyVariantUnavailable = states.values().stream()
                .anyMatch(state -> "UNAVAILABLE".equals(state.availabilityState()));
        if (!everyVariantUnavailable) {
            return OneSidedDangerProof.none();
        }
        return OneSidedDangerProof.of("PROMOTED_VARIANT_UNAVAILABLE",
                List.of("CONFIRMED_AFFECTED_VARIANT_UNAVAILABLE", "OFFICIAL_SPEND_CONTINUING"),
                List.of("AD_LINKED_CONVERSION"), false);
    }

    /**
     * A resolved, negative contribution profit with spend still flowing.
     *
     * <p>Unlike the two above, this proof needs the profit to be <em>resolved</em>.
     * An unresolved profit could go either way once its missing component
     * arrives, so it cannot prove a direction and the ladder must not treat it as
     * if it did.
     */
    private static OneSidedDangerProof economicHarm(AdvertisingEvidenceGatherer.Evidence evidence,
            AdvertisingContributionProfit profit, AdMeasure officialSpend, AdLinkedConversion conversion) {
        if (!AdvertisingPurposeFreshness.failures(evidence, "PROTECTION_RECOMMENDATION",
                List.of("OFFICIAL_AD_SPEND", "COST_AND_FEE", "AD_LINKED_SALE_EVENT")).isEmpty()) {
            return OneSidedDangerProof.none();
        }
        if (!profit.provenLoss() || !profit.absoluteProfit().sufficientForWrite() || !officialSpend.present()
                || officialSpend.value().signum() <= 0 || !officialSpend.sufficientForWrite()) {
            return OneSidedDangerProof.none();
        }
        return OneSidedDangerProof.of("PROVEN_ADVERTISING_LOSS",
                List.of("RESOLVED_NEGATIVE_CONTRIBUTION_PROFIT", "OFFICIAL_SPEND_CONTINUING"),
                conversion.writeGrade() ? List.of() : List.of("AD_LINKED_CONVERSION"), false);
    }

    // ----------------------------------------------------------------------
    // Defects and qualification
    // ----------------------------------------------------------------------

    private static AdvertisingCause dataDefectOf(
            AdvertisingEvidenceGatherer.Evidence evidence,
            AffectedSet affectedSet,
            AdvertisingContributionProfit profit,
            AdLinkedConversion conversion,
            AdMeasure attributionGap,
            List<String> blockers) {
        if (evidence.authorities().criticalSignals().unknown()) {
            blockers.add("CRITICAL_SALES_GUARD_EVIDENCE_UNRESOLVED");
            return AdvertisingCause.OFFICIAL_AD_FACT_DEFECT;
        }
        if (!affectedSet.sufficientForWrite()) {
            blockers.addAll(affectedSet.unresolvedReasonCodes());
            return AdvertisingCause.AFFECTED_SET_UNRESOLVED;
        }
        if (!evidence.object().independentlyControllable()) {
            blockers.add("ADVERTISING_OBJECT_NOT_INDEPENDENTLY_CONTROLLABLE");
            return AdvertisingCause.OBJECT_NOT_INDEPENDENTLY_CONTROLLABLE;
        }
        if (evidence.objectFacts().isEmpty()) {
            blockers.add("OFFICIAL_AD_FACT_ABSENT");
            return AdvertisingCause.OFFICIAL_AD_FACT_DEFECT;
        }
        if (evidence.conversion().isEmpty() || evidence.allowableCpa().isEmpty()
                || evidence.writeQualification().isEmpty()) {
            blockers.add("ADVERTISING_DECISION_POLICY_UNRESOLVED");
            return AdvertisingCause.DECISION_POLICY_UNRESOLVED;
        }
        var definition = evidence.conversion().get();
        if (attributionGap.present()
                && attributionGap.value().compareTo(definition.maximumAttributionGapRatio()) > 0) {
            blockers.add("PROVIDER_TO_CANONICAL_ATTRIBUTION_GAP_MATERIAL");
            return AdvertisingCause.ATTRIBUTION_GAP_MATERIAL;
        }
        if (!profit.resolved()) {
            blockers.addAll(profit.missingComponentCodes());
            return AdvertisingCause.PROFIT_ECONOMICS_BLOCKED;
        }
        boolean immature = evidence.objectFacts().map(facts -> !facts.everyWindowComplete()
                || facts.anyCorrectionWindowOpen()).orElse(false)
                || conversion.linkedEventCount() < definition.minimumSampleEvents();
        if (!conversion.writeGrade() && !immature) {
            blockers.add("AD_LINKED_CONVERSION_NOT_WRITE_GRADE");
            return AdvertisingCause.AD_LINKAGE_COVERAGE_INSUFFICIENT;
        }
        return null;
    }

    private static boolean optimizationQualified(AdvertisingEvidenceGatherer.Evidence evidence,
            AdLinkedConversion conversion, AdMeasure officialSpend, AdvertisingContributionProfit profit) {
        return evidence.taskQualification().map(policy -> qualificationConditions(evidence, policy, conversion,
                officialSpend, profit) && evidence.authorities().sustainedPeriods().getOrDefault(policy.id(), 0) + 1
                        >= policy.minimumSustainedPeriods()).orElse(false);
    }

    static boolean qualificationConditions(AdvertisingEvidenceGatherer.Evidence evidence,
            AdvertisingPolicyRepository.QualificationPolicy required, AdLinkedConversion conversion,
            AdMeasure officialSpend, AdvertisingContributionProfit profit) {
        if (!conversion.writeGrade() || !profit.resolved() || !officialSpend.present()
                || !java.util.Objects.equals(required.currencyCode(), evidence.objectFacts()
                        .map(AdvertisingEvidenceRepository.ObjectFactAggregate::currencyCode).orElse(null))) {
            return false;
        }
        String purpose = "OPTIMIZATION_BID_WRITE".equals(required.purposeTier())
                ? "OPTIMIZATION_BID_WRITE" : "TASK_ACTIVATION";
        if (!AdvertisingPurposeFreshness.failures(evidence, purpose, List.of("OFFICIAL_AD_SPEND",
                "OFFICIAL_AD_TRAFFIC", "AD_LINKED_SALE_EVENT", "COST_AND_FEE", "AFFECTED_SET")).isEmpty()) {
            return false;
        }
        long completed = evidence.authorities().canonicalCompletedEventCount()!=null
                ? evidence.authorities().canonicalCompletedEventCount() : evidence.completedSales().filter(sales -> !sales.lines().isEmpty()
                && sales.lines().getFirst().saleStage().equals(SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE.name()))
                .map(AdvertisingEvidenceRepository.LinkedSaleAggregate::eventCount).orElse(0L);
        long retained = evidence.retainedSales().map(AdvertisingEvidenceRepository.LinkedSaleAggregate::eventCount).orElse(0L);
        BigDecimal sourceCoverage = evidence.objectFacts().map(AdvertisingEvidenceRepository.ObjectFactAggregate::coverageRatio).orElse(null);
        boolean window = Duration.between(evidence.windowStart(), evidence.asOf()).equals(
                Duration.ofDays(required.eligibleObservationWindowDays()));
        boolean confidence = switch (required.minimumConfidenceState()) {
            case "CANONICAL_CONFIRMED" -> profit.absoluteProfit().evidenceState()==AdEvidenceState.CANONICAL_CONFIRMED;
            case "CANONICAL_PENDING_SETTLEMENT", "OPERATIONAL" -> !profit.absoluteProfit().evidenceState().blocked();
            default -> false;
        };
        return window && confidence && sourceCoverage != null
                && sourceCoverage.compareTo(required.minimumSourceCoverageRatio()) >= 0
                && sourceCoverage.compareTo(BigDecimal.ONE) <= 0
                && conversion.affectedSetCoverageRatio() != null
                && conversion.affectedSetCoverageRatio().compareTo(required.minimumAffectedSetCoverageRatio()) >= 0
                && completed >= required.minimumCompletedSaleEvents() && retained >= required.minimumRetainedSaleEvents()
                && conversion.eligibleTrafficCount() >= required.minimumTrafficDenominator()
                && officialSpend.value().compareTo(required.minimumSpendAmount()) >= 0
                && (!required.requiresCorrectionWindowClosed() || evidence.objectFacts().map(facts -> !facts.anyCorrectionWindowOpen()).orElse(false))
                && (!required.requiresComparableBaseline() || evidence.authorities().comparableBaseline());
    }

    private static boolean optimizationMaterial(
            AdvertisingEvidenceGatherer.Evidence evidence, AdMeasure recoverable) {
        return evidence.taskQualification()
                .map(policy -> recoverable.present()
                        && recoverable.value().compareTo(policy.minimumRecoverableAmount()) >= 0)
                .orElse(false);
    }

    // ----------------------------------------------------------------------
    // Evidence, confidence, policy and ranking
    // ----------------------------------------------------------------------

    private static AdEvidenceState evidenceStateOf(
            AffectedSet affectedSet, AdMeasure officialSpend,
            AdvertisingContributionProfit profit, AdLinkedConversion conversion) {
        AdEvidenceState state = affectedSet.sufficientForWrite()
                ? AdEvidenceState.CANONICAL_CONFIRMED
                : AdEvidenceState.INCOMPLETE;
        state = state.weakest(officialSpend.evidenceState());
        state = state.weakest(profit.absoluteProfit().evidenceState());
        return state.weakest(conversion.evidenceState());
    }

    private static AdConfidence confidenceOf(
            AdEvidenceState evidenceState, AdLinkedConversion conversion,
            AdvertisingContributionProfit profit) {
        if (evidenceState.sufficientForWrite() && conversion.writeGrade() && profit.resolved()) {
            return AdConfidence.HIGH;
        }
        if (profit.resolved() || conversion.rate().present()) {
            return AdConfidence.MEDIUM;
        }
        return evidenceState.blocked() ? AdConfidence.UNUSABLE : AdConfidence.LOW;
    }

    private static AdPolicySet policySetOf(AdvertisingEvidenceGatherer.Evidence evidence) {
        List<String> versions = new ArrayList<>();
        List<AdPolicySet.InputReference> refs = new ArrayList<>();
        evidence.authorities().cpaByVariant().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            versions.add("variantCpa:" + entry.getKey() + "=" + entry.getValue().id() + ":" + entry.getValue().version());
            refs.add(new AdPolicySet.InputReference("ALLOWABLE_CPA_DEFINITION", entry.getValue().id(), entry.getValue().version(), null, evidence.asOf()));
        });
        evidence.authorities().freshness().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            versions.add("freshness:" + entry.getKey() + "=" + entry.getValue().id() + ":" + entry.getValue().version());
            refs.add(new AdPolicySet.InputReference("FRESHNESS_PROFILE", entry.getValue().id(), entry.getValue().version(), null, evidence.asOf()));
        });
        evidence.economics().values().stream().flatMap(value -> value.lineage().stream()).distinct().forEach(value -> {
            versions.add("metric:" + value.metricCode() + ":" + value.definitionVersion());
            refs.add(new AdPolicySet.InputReference("PROFIT_ECONOMICS", value.metricValueId(), value.definitionVersion(), value.inputDigest(), value.computedAt()));
        });
        evidence.completedSales().stream().flatMap(value -> value.lines().stream()).forEach(line ->
                refs.add(new AdPolicySet.InputReference("AD_LINKED_SALE", line.id(), null, null, line.recordedAt())));
        evidence.objectFacts().ifPresent(value -> refs.add(new AdPolicySet.InputReference("OFFICIAL_SPEND", value.latestFactId(), null, null, value.acceptedAt())));
        evidence.configuration().ifPresent(value -> refs.add(new AdPolicySet.InputReference("OBJECT_CONFIGURATION", value.id(), null, null, value.observedAt())));
        evidence.authorities().criticalSignals().observationIds().forEach(id -> refs.add(
                new AdPolicySet.InputReference("CRITICAL_SALES_GUARD", id, null, null, evidence.asOf())));
        return new AdPolicySet(
                evidence.conversion().map(c -> c.id()).orElse(null),
                evidence.conversion().map(c -> c.version()).orElse(null),
                evidence.allowableCpa().map(c -> c.id()).orElse(null),
                evidence.allowableCpa().map(c -> c.version()).orElse(null),
                evidence.writeQualification().map(q -> q.id()).orElse(null),
                evidence.writeQualification().map(q -> q.version()).orElse(null),
                evidence.priority().map(p -> p.id()).orElse(null),
                evidence.priority().map(p -> p.version()).orElse(null),
                null, null, null, null, null, null, null, null,
                evidence.object().semanticProfileId(), null,
                null, null, versions.stream().distinct().sorted().toList(), refs);
    }

    private AdCaseCalculation.ScoredCase score(
            AdvertisingEvidenceGatherer.Evidence evidence,
            AdLaneResolver.Decision decision,
            AdPolicySet policies,
            AdvertisingContributionProfit profit,
            AdMeasure officialSpend,
            AdMeasure eligibleTraffic,
            AdLinkedConversion conversion,
            MaxCpc maxCpc,
            AdMeasure attributionGap,
            AdMeasure currentBid,
            AdMeasure recoverable,
            String currency) {
        AdCaseIdentity identity = new AdCaseIdentity(
                evidence.object().organizationId(), evidence.object().id(),
                evidence.object().lineageGeneration(), decision.cause());

        AdMeasure lossRate = profit.provenLoss()
                ? AdMeasure.available(profit.absoluteProfit().value().abs().divide(BigDecimal.valueOf(
                        Math.max(1, Duration.between(evidence.windowStart(), evidence.asOf()).toSeconds()))
                        .divide(BigDecimal.valueOf(86400), CONTEXT), CONTEXT),
                        profit.absoluteProfit().evidenceState())
                : AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE);
        var rankContext = evidence.authorities().rankContexts().getOrDefault(identity.caseKey(),
                evidence.authorities().rankContexts().get("__OBJECT_DEPENDENCIES__"));
        BigDecimal ageDays = caseAgeDays(rankContext, evidence.asOf());
        BigDecimal maturity = evidence.objectFacts().filter(facts -> facts.everyWindowComplete()
                        && !facts.anyCorrectionWindowOpen())
                .map(AdvertisingEvidenceRepository.ObjectFactAggregate::coverageRatio).orElse(null);
        BigDecimal urgency = rankContext == null || rankContext.earliestDueAt() == null ? null
                : BigDecimal.valueOf(rankContext.earliestDueAt().getEpochSecond()).negate();

        AdPriorityPolicy.Inputs inputs = new AdPriorityPolicy.Inputs(
                decision.lane(), decision.protectionTier(), lossRate, officialSpend,
                evidence.authorities().criticalSignals().exposure() == null ? AdMeasure.notAvailable(AdEvidenceState.UNKNOWN)
                        : AdMeasure.available(evidence.authorities().criticalSignals().exposure(), AdEvidenceState.CANONICAL_CONFIRMED), recoverable,
                maturity, ageDays, decision.confidence(), urgency,
                rankContext == null ? null : BigDecimal.valueOf(rankContext.blockedProtection()),
                evidence.affectedSet().filter(affected->"COMPLETE".equals(affected.resolutionState()))
                        .map(affected->BigDecimal.valueOf(affected.productVariantIds().size())).orElse(null),
                rankContext == null ? null : BigDecimal.valueOf(rankContext.blockedWork()), evidence.authorities().criticalSignals().dualAxisGap(),
                evidence.authorities().criticalSignals().headroom(), evidence.authorities().criticalSignals().perRubGap());

        AdPriorityPolicy.Ranking ranking = evidence.priority()
                .map(weights -> AdPriorityPolicy.rank(inputs, new AdPriorityPolicy.Weights(
                        weights.profitLossWeight(), weights.spendExposureWeight(),
                        weights.criticalSalesWeight(), weights.recoverableProfitWeight(),
                        weights.evidenceMaturityWeight(), weights.ageWeight(),
                        weights.confidenceWeight())))
                .orElseGet(() -> AdPriorityPolicy.unranked(
                        decision.lane(), decision.protectionTier()));

        return new AdCaseCalculation.ScoredCase(
                identity, decision, ranking,
                profit.absoluteProfit(), profit.profitPerAdRub(), officialSpend,
                eligibleTraffic, conversion, maxCpc, attributionGap, currentBid, recoverable,
                currency, variantDiagnostics(evidence));
    }

    private static BigDecimal caseAgeDays(AdvertisingEvidenceRepository.RankContext context, Instant asOf) {
        if (context == null) { return BigDecimal.ZERO; }
        Instant firstRaised = context.firstRaisedAt();
        if (firstRaised == null) { return null; }
        if (firstRaised.isAfter(asOf)) {
            // JDBC/PostgreSQL persist timestamptz at rounded microsecond precision.
            // Re-reading that same origin can place it < 1 microsecond after the
            // original Java Instant; Duration.toSeconds would then produce -1.
            Instant persistedAsOf = asOf.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                    .plusNanos(asOf.getNano() % 1000 >= 500 ? 1000 : 0);
            // Only the identical representable instant is age zero. A genuinely
            // future origin stays unknown, rather than inventing an ordinary age.
            return firstRaised.equals(persistedAsOf) ? BigDecimal.ZERO : null;
        }
        return BigDecimal.valueOf(Duration.between(firstRaised, asOf).toSeconds())
                .divide(BigDecimal.valueOf(86400), CONTEXT);
    }

    private static List<AdCaseCalculation.VariantDiagnostic> variantDiagnostics(
            AdvertisingEvidenceGatherer.Evidence evidence) {
        List<AdCaseCalculation.VariantDiagnostic> diagnostics =
                new ArrayList<>(evidence.variantShares().size());
        for (var share : evidence.variantShares()) {
            var state = evidence.variantAvailability()
                    .getOrDefault(share.productVariantId(),
                            AdvertisingEvidenceGatherer.VariantAvailability.unknown());
            diagnostics.add(new AdCaseCalculation.VariantDiagnostic(
                    share.productVariantId(), share.platformListingVariantId(),
                    "OFFICIAL_OBSERVATION".equals(share.basis()),
                    share.confidenceState(), share.spendAmount(), share.clicks(),
                    null, share.currencyCode(),
                    state.sellabilityState(), state.availabilityState(), false));
        }
        return List.copyOf(diagnostics);
    }
}
