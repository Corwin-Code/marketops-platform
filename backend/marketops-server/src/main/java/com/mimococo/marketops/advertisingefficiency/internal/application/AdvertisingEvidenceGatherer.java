package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.availabilityrisk.AvailabilityCardView;
import com.mimococo.marketops.availabilityrisk.AvailabilityRiskQuery;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Gathers everything one advertising calculation needs, and decides nothing.
 *
 * <p>The split matters more here than it looks. The calculator that follows is a
 * pure function of what this class returns, which is what makes the targeted
 * path and the hourly sweep provably equivalent: they call the same gatherer and
 * the same calculator, so the only way they could differ is if this class read
 * something time-dependent that was not the {@code asOf} it was given.
 *
 * <p>The canonical evaluation uses the complete evidence set. This class passes
 * the object's own store and the whole affected variant set as the read scope,
 * because a narrower viewer must never shrink the business gate — disclosure
 * narrowing happens at the console boundary, not here.
 */
@Component
class AdvertisingEvidenceGatherer {

    private static final MathContext CONTEXT = new MathContext(20, RoundingMode.HALF_UP);

    private final AdvertisingEvidenceRepository facts;
    private final AdvertisingPolicyRepository policies;
    private final MetricQuery metrics;
    private final AvailabilityRiskQuery availability;

    AdvertisingEvidenceGatherer(
            AdvertisingEvidenceRepository facts,
            AdvertisingPolicyRepository policies,
            MetricQuery metrics,
            AvailabilityRiskQuery availability) {
        this.facts = facts;
        this.policies = policies;
        this.metrics = metrics;
        this.availability = availability;
    }

    /** Everything one calculation reads, as one immutable value. */
    record Evidence(
            AdvertisingEvidenceRepository.ObjectRow object,
            Optional<AdvertisingEvidenceRepository.AffectedSetRow> affectedSet,
            Optional<AdvertisingEvidenceRepository.ConfigurationRow> configuration,
            Optional<AdvertisingEvidenceRepository.ObjectFactAggregate> objectFacts,
            Optional<AdvertisingEvidenceRepository.LinkedSaleAggregate> completedSales,
            Optional<AdvertisingEvidenceRepository.LinkedSaleAggregate> retainedSales,
            List<AdvertisingEvidenceRepository.VariantShareRow> variantShares,
            AdvertisingEvidenceRepository.ContainmentRow containment,
            Optional<AdvertisingPolicyRepository.ConversionDefinition> conversion,
            Optional<AdvertisingPolicyRepository.AllowableCpaDefinition> allowableCpa,
            Optional<AdvertisingPolicyRepository.QualificationPolicy> writeQualification,
            Optional<AdvertisingPolicyRepository.QualificationPolicy> taskQualification,
            Optional<AdvertisingPolicyRepository.PriorityWeights> priority,
            Map<UUID, VariantEconomics> economics,
            Map<UUID, VariantAvailability> variantAvailability,
            Instant windowStart,
            Instant asOf) {
    }

    /** The per-unit variable economics for one variant, each independently absent-able. */
    record VariantEconomics(
            AdMeasure unitCost, AdMeasure platformFeesPerUnit, AdMeasure returnLossPerUnit,
            AdMeasure variableTaxPerUnit, String currencyCode) {
    }

    /** What the availability vertical says about one variant. */
    record VariantAvailability(String sellabilityState, String availabilityState) {

        static VariantAvailability unknown() {
            return new VariantAvailability("UNKNOWN", "UNKNOWN");
        }
    }

    /**
     * Read everything for one object at one instant.
     *
     * <p>The observation window comes from the resolved conversion definition. If
     * no definition resolves, a thirty-day default is used <em>only</em> to bound
     * the reads; every purpose that consumes the conversion still fails closed,
     * so the default never becomes a decision.
     */
    Optional<Evidence> gather(UUID organizationId, UUID objectId, Instant asOf) {
        Optional<AdvertisingEvidenceRepository.ObjectRow> found = facts.object(organizationId, objectId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AdvertisingEvidenceRepository.ObjectRow object = found.get();

        var affectedSet = facts.affectedSet(organizationId, objectId);
        var configuration = facts.currentConfiguration(organizationId, objectId);

        var completedConversion = policies.resolveConversion(organizationId, object.platformCode(),
                object.storeId(), SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE.name(), asOf);
        int windowDays = completedConversion
                .map(AdvertisingPolicyRepository.ConversionDefinition::observationWindowDays)
                .orElse(30);
        Instant windowStart = asOf.minus(Duration.ofDays(windowDays));

        var objectFacts = facts.objectFacts(organizationId, objectId, windowStart, asOf);
        var completedSales = facts.linkedSales(organizationId, objectId,
                SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE.name(), windowStart, asOf);
        var retainedSales = facts.linkedSales(organizationId, objectId,
                SaleStage.CANONICAL_AD_LINKED_RETAINED_SALE.name(), windowStart, asOf);
        var shares = facts.variantShares(organizationId, objectId, windowStart, asOf);

        List<UUID> variantIds = affectedSet
                .map(AdvertisingEvidenceRepository.AffectedSetRow::productVariantIds)
                .orElse(List.of());
        String digest = affectedSet
                .map(AdvertisingEvidenceRepository.AffectedSetRow::digest)
                .orElse("0".repeat(64));

        return Optional.of(new Evidence(
                object,
                affectedSet,
                configuration,
                objectFacts,
                completedSales,
                retainedSales,
                shares,
                facts.containment(organizationId, objectId, digest),
                completedConversion,
                policies.resolveAllowableCpa(organizationId, object.platformCode(),
                        object.storeId(), variantIds.isEmpty() ? null : variantIds.getFirst(),
                        SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE.name(), asOf),
                policies.resolveQualification(organizationId, object.platformCode(),
                        object.storeId(), "OPTIMIZATION_BID_WRITE", asOf),
                policies.resolveQualification(organizationId, object.platformCode(),
                        object.storeId(), "OPTIMIZATION_TASK", asOf),
                policies.resolvePriority(organizationId, asOf),
                economicsFor(variantIds),
                availabilityFor(organizationId, object.storeId(), variantIds),
                windowStart,
                asOf));
    }

    /**
     * The per-unit variable economics for each affected variant.
     *
     * <p>Read through the published metric contract rather than from the ledger,
     * so the number an advertising decision uses is the same number the price
     * decision and the console use, computed once by the authority that owns it.
     */
    private Map<UUID, VariantEconomics> economicsFor(List<UUID> variantIds) {
        Map<UUID, VariantEconomics> economics = new HashMap<>();
        for (UUID variantId : variantIds) {
            Map<MetricCode, MetricValueView> values = metrics.currentValues(
                    SubjectKind.PRODUCT_VARIANT, variantId, MetricWindow.D30);
            economics.put(variantId, new VariantEconomics(
                    measure(values.get(MetricCode.UNIT_COST)),
                    measure(values.get(MetricCode.PLATFORM_FEES_PER_UNIT)),
                    measure(values.get(MetricCode.RETURN_LOSS_PER_UNIT)),
                    measure(values.get(MetricCode.VARIABLE_TAX_PER_UNIT)),
                    currencyOf(values)));
        }
        return Map.copyOf(economics);
    }

    /**
     * Sellability and availability for each affected variant.
     *
     * <p>An advertising object promoting an unsellable variant is spending money
     * with certainty and earning nothing, which is the clearest one-sided danger
     * this Slice has. Read through the availability module's published contract,
     * because it owns that answer and a second opinion would be a second
     * authority.
     */
    private Map<UUID, VariantAvailability> availabilityFor(
            UUID organizationId, UUID storeId, List<UUID> variantIds) {
        Map<UUID, VariantAvailability> states = new HashMap<>();
        for (UUID variantId : variantIds) {
            Optional<AvailabilityCardView> card = availability.card(
                    organizationId, variantId, List.of(storeId), List.of(variantId));
            states.put(variantId, card
                    .map(AdvertisingEvidenceGatherer::availabilityFromCard)
                    .orElseGet(VariantAvailability::unknown));
        }
        return Map.copyOf(states);
    }

    /**
     * Translate one availability card into the two states advertising consumes.
     *
     * <p>Deliberately conservative in one direction only: a card whose evidence
     * is limited yields {@code UNKNOWN} rather than {@code AVAILABLE}, because an
     * unknown availability must never be read as a reason to keep spending.
     */
    private static VariantAvailability availabilityFromCard(AvailabilityCardView card) {
        String lane = card.lane();
        String availabilityState = switch (lane == null ? "" : lane) {
            case "HEALTHY" -> "AVAILABLE";
            case "WATCH", "HIGH" -> "AT_RISK";
            case "CRITICAL" -> "UNAVAILABLE";
            default -> "UNKNOWN";
        };
        boolean notSellable = card.children().stream()
                .anyMatch(child -> "CHANNEL_NOT_SELLABLE".equals(child.causeCode()));
        boolean sellabilityKnown = card.children().stream()
                .anyMatch(child -> child.causeCode() != null);
        String sellabilityState = notSellable ? "NOT_SELLABLE"
                : sellabilityKnown ? "SELLABLE" : "UNKNOWN";
        return new VariantAvailability(sellabilityState, availabilityState);
    }

    /** A metric value as a measure, carrying the confidence the authority attached. */
    private static AdMeasure measure(MetricValueView view) {
        if (view == null || view.valueState() != ValueState.AVAILABLE) {
            return AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE);
        }
        return AdMeasure.available(view.numericValue(), evidenceOf(view));
    }

    /**
     * Map a metric's confidence onto the advertising evidence vocabulary.
     *
     * <p>The mapping is one-way and lossy on purpose. Anything that is not
     * confirmed or pending-settlement lands somewhere that fails a write gate, so
     * a confidence this build does not recognise degrades rather than passes.
     */
    private static AdEvidenceState evidenceOf(MetricValueView view) {
        return switch (view.confidenceState()) {
            case CANONICAL_CONFIRMED -> AdEvidenceState.CANONICAL_CONFIRMED;
            case CANONICAL_PENDING_SETTLEMENT -> AdEvidenceState.OPERATIONAL;
            case ESTIMATED_EXPLAINED -> AdEvidenceState.PROVISIONAL_OR_ESTIMATED;
            case STALE -> AdEvidenceState.STALE;
            case INCOMPLETE -> AdEvidenceState.INCOMPLETE;
            case CONFLICTED -> AdEvidenceState.CONFLICTED;
            case UNKNOWN -> AdEvidenceState.UNKNOWN;
        };
    }

    private static String currencyOf(Map<MetricCode, MetricValueView> values) {
        return values.values().stream()
                .map(MetricValueView::currencyCode)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** Sum a per-variant measure across the affected set, blocking if any is absent. */
    static AdMeasure sumAcross(
            List<UUID> variantIds,
            Map<UUID, VariantEconomics> economics,
            java.util.function.Function<VariantEconomics, AdMeasure> component) {
        if (variantIds.isEmpty()) {
            return AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE);
        }
        BigDecimal total = BigDecimal.ZERO;
        AdEvidenceState weakest = AdEvidenceState.CANONICAL_CONFIRMED;
        for (UUID variantId : variantIds) {
            VariantEconomics variant = economics.get(variantId);
            AdMeasure measure = variant == null ? null : component.apply(variant);
            if (measure == null || !measure.present()) {
                return AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED);
            }
            total = total.add(measure.value(), CONTEXT);
            weakest = weakest.weakest(measure.evidenceState());
        }
        // A mean rather than a sum: these are per-unit figures, and the object's
        // ad-linked units are counted once across the whole affected set.
        BigDecimal mean = total.divide(BigDecimal.valueOf(variantIds.size()), CONTEXT);
        return AdMeasure.available(mean, weakest);
    }
}
