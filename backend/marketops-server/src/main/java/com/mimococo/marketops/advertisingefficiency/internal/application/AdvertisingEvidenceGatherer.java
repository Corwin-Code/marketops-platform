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
    private final com.mimococo.marketops.operationsworkflow.AdvertisingTaskSloQuery taskSlo;

    @org.springframework.beans.factory.annotation.Autowired
    AdvertisingEvidenceGatherer(
            AdvertisingEvidenceRepository facts,
            AdvertisingPolicyRepository policies,
            MetricQuery metrics,
            AvailabilityRiskQuery availability, com.mimococo.marketops.operationsworkflow.AdvertisingTaskSloQuery taskSlo) {
        this.facts = facts;
        this.policies = policies;
        this.metrics = metrics;
        this.availability = availability;
        this.taskSlo = taskSlo;
    }

    AdvertisingEvidenceGatherer(AdvertisingEvidenceRepository facts, AdvertisingPolicyRepository policies,
            MetricQuery metrics, AvailabilityRiskQuery availability) { this(facts,policies,metrics,availability,null); }

    private Map<String, AdvertisingEvidenceRepository.RankContext> rankContexts(UUID organization, UUID object, Instant at) {
        Map<String, AdvertisingEvidenceRepository.RankContext> result = new HashMap<>();
        facts.rankContexts(organization, object, at).forEach((key, value) -> {
            var status = taskSlo == null || value.caseId() == null ? Optional.<com.mimococo.marketops.operationsworkflow.AdvertisingTaskSloQuery.Status>empty()
                    : taskSlo.statusForCase(value.caseId(), at);
            Instant due = status.filter(slo -> !"PROFILE_OR_CALENDAR_MISSING".equals(slo.coverageState()))
                    .map(slo -> java.util.stream.Stream.of(slo.acknowledgedAt() == null ? slo.acknowledgementDueAt() : null,
                            slo.firstAttributableActionAt() == null && !slo.actionPaused() ? slo.actionDueAt() : null)
                            .filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null)).orElse(null);
            result.put(key, new AdvertisingEvidenceRepository.RankContext(value.firstRaisedAt(), due,
                    value.blockedProtection(), value.downstreamVariants(), value.blockedWork(), value.caseId()));
        });
        return Map.copyOf(result);
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
            Instant asOf, Authorities authorities) {
        Evidence(
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
            this(object, affectedSet, configuration, objectFacts, completedSales, retainedSales, variantShares, containment, conversion, allowableCpa, writeQualification, taskQualification, priority, economics, variantAvailability, windowStart, asOf, Authorities.unresolved());
        }
    }

    record Authorities(Map<UUID, AdvertisingPolicyRepository.AllowableCpaDefinition> cpaByVariant,
            Map<String, AdvertisingPolicyRepository.FreshnessProfile> freshness,
            Map<UUID, Integer> sustainedPeriods, boolean comparableBaseline, List<UUID> metricValueIds,
            Map<String, AdvertisingEvidenceRepository.RankContext> rankContexts, boolean compensationPending,
            boolean providerIncidentOpen, AdvertisingEvidenceRepository.CriticalSignals criticalSignals,
            Long canonicalCompletedEventCount) {
        Authorities(Map<UUID, AdvertisingPolicyRepository.AllowableCpaDefinition> cpas,
                Map<String, AdvertisingPolicyRepository.FreshnessProfile> freshness, Map<UUID, Integer> periods,
                boolean baseline, List<UUID> metrics, Map<String, AdvertisingEvidenceRepository.RankContext> ranks,
                boolean compensation, boolean incident, AdvertisingEvidenceRepository.CriticalSignals criticalSignals) {
            this(cpas,freshness,periods,baseline,metrics,ranks,compensation,incident,criticalSignals,null);
        }
        Authorities(Map<UUID, AdvertisingPolicyRepository.AllowableCpaDefinition> cpas,
                Map<String, AdvertisingPolicyRepository.FreshnessProfile> freshness, Map<UUID, Integer> periods,
                boolean baseline, List<UUID> metrics, Map<String, AdvertisingEvidenceRepository.RankContext> ranks,
                boolean compensation, boolean incident) {
            this(cpas,freshness,periods,baseline,metrics,ranks,compensation,incident,AdvertisingEvidenceRepository.CriticalSignals.absent());
        }
        Authorities(Map<UUID, AdvertisingPolicyRepository.AllowableCpaDefinition> cpas,
                Map<String, AdvertisingPolicyRepository.FreshnessProfile> freshness, Map<UUID, Integer> periods,
                boolean baseline, List<UUID> metrics, Map<String, AdvertisingEvidenceRepository.RankContext> ranks,
                boolean compensation) {
            this(cpas, freshness, periods, baseline, metrics, ranks, compensation, false);
        }
        static Authorities unresolved() { return new Authorities(Map.of(), Map.of(), Map.of(), false, List.of(), Map.of(), false, true); }
    }

    /** The per-unit variable economics for one variant, each independently absent-able. */
    record VariantEconomics(
            AdMeasure unitCost, AdMeasure platformFeesPerUnit, AdMeasure returnLossPerUnit,
            AdMeasure variableTaxPerUnit, String currencyCode, List<MetricValueView> lineage) {
        VariantEconomics(AdMeasure unitCost, AdMeasure fees, AdMeasure returns, AdMeasure tax, String currency) {
            this(unitCost, fees, returns, tax, currency, List.of());
        }
    }

    /** What the availability vertical says about one variant. */
    record VariantAvailability(String sellabilityState, String availabilityState, Instant observedAt,
            String evidenceState, List<UUID> evidenceIds) {
        VariantAvailability(String sellability, String availability) {
            this(sellability, availability, null, "UNKNOWN", List.of());
        }

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

        var affectedSet = facts.affectedSet(organizationId, objectId, asOf);
        var configuration = facts.currentConfiguration(organizationId, objectId, asOf);

        var completedConversion = policies.resolveObjectConversion(organizationId, object.platformCode(),
                object.storeId(), object.semanticProfileId(), object.nativeObjectKind(), asOf);
        int windowDays = completedConversion
                .map(AdvertisingPolicyRepository.ConversionDefinition::observationWindowDays)
                .orElse(30);
        Instant windowStart = asOf.minus(Duration.ofDays(windowDays));

        var objectFacts = facts.objectFacts(organizationId, objectId, windowStart, asOf);
        var completedSales = facts.linkedSales(organizationId, objectId,
                completedConversion.map(AdvertisingPolicyRepository.ConversionDefinition::saleStage)
                        .orElse("UNRESOLVED"), windowStart, asOf);
        long canonicalCompletedEvents = completedConversion.map(AdvertisingPolicyRepository.ConversionDefinition::saleStage)
                .filter(SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE.name()::equals).isPresent()
                ? completedSales.map(AdvertisingEvidenceRepository.LinkedSaleAggregate::eventCount).orElse(0L)
                : facts.linkedSales(organizationId,objectId,SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE.name(),windowStart,asOf)
                    .map(AdvertisingEvidenceRepository.LinkedSaleAggregate::eventCount).orElse(0L);
        var retainedSales = facts.linkedSales(organizationId, objectId,
                SaleStage.CANONICAL_AD_LINKED_RETAINED_SALE.name(), windowStart, asOf);
        var shares = facts.variantShares(organizationId, objectId, windowStart, asOf);

        List<UUID> variantIds = affectedSet
                .map(AdvertisingEvidenceRepository.AffectedSetRow::productVariantIds)
                .orElse(List.of());
        String digest = affectedSet
                .map(AdvertisingEvidenceRepository.AffectedSetRow::digest)
                .orElse("0".repeat(64));

        Map<UUID, AdvertisingPolicyRepository.AllowableCpaDefinition> cpas = new HashMap<>();
        if (completedConversion.isPresent()) {
            for (UUID variantId : variantIds) {
                policies.resolveAllowableCpa(organizationId, object.platformCode(), object.storeId(),
                        variantId, completedConversion.get().saleStage(), asOf)
                        .ifPresent(value -> cpas.put(variantId, value));
            }
        }
        Map<String, AdvertisingPolicyRepository.FreshnessProfile> freshness = new HashMap<>();
        for (String purpose : List.of("QUEUE_OBSERVATION", "TASK_ACTIVATION", "PROTECTION_RECOMMENDATION",
                "OPTIMIZATION_RECOMMENDATION", "PROTECTION_BID_WRITE", "OPTIMIZATION_BID_WRITE")) {
            for (String kind : List.of("OFFICIAL_AD_SPEND", "OFFICIAL_AD_TRAFFIC", "AD_LINKED_SALE_EVENT",
                    "COST_AND_FEE", "AD_OBJECT_CONFIGURATION", "AFFECTED_SET", "SELLABILITY", "AVAILABILITY")) {
                policies.resolveFreshness(organizationId, kind, purpose, object.platformCode(),
                        object.storeId(), object.semanticProfileId(), asOf)
                        .ifPresent(value -> freshness.put(purpose + ":" + kind, value));
            }
        }
        var writeQualification = policies.resolveQualification(organizationId, object.platformCode(),
                object.storeId(), "OPTIMIZATION_BID_WRITE", asOf);
        var taskQualification = policies.resolveQualification(organizationId, object.platformCode(),
                object.storeId(), "OPTIMIZATION_TASK", asOf);
        Map<UUID, Integer> sustained = new HashMap<>();
        java.util.stream.Stream.concat(writeQualification.stream(), taskQualification.stream())
                .forEach(policy -> sustained.put(policy.id(), facts.sustainedPeriods(
                        organizationId, objectId, policy.id(), windowStart)));
        Map<UUID, VariantEconomics> economics = economicsForSales(completedSales, windowStart, asOf);
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
                cpas.values().stream().sorted(java.util.Comparator.comparing(AdvertisingPolicyRepository.AllowableCpaDefinition::id)).findFirst(),
                writeQualification,
                taskQualification,
                policies.resolvePriority(organizationId, asOf),
                economics,
                availabilityFor(organizationId, object.storeId(), variantIds),
                windowStart,
                asOf, new Authorities(Map.copyOf(cpas), Map.copyOf(freshness),
                        Map.copyOf(sustained),
                        facts.comparableBaseline(organizationId, objectId, windowStart, asOf),
                        economics.values().stream().flatMap(value -> value.lineage().stream())
                                .map(MetricValueView::metricValueId).distinct().sorted().toList(),
                        rankContexts(organizationId, objectId, asOf), facts.compensationPending(organizationId, objectId),
                        facts.providerIncidentOpen(organizationId, objectId, asOf), facts.criticalSignals(organizationId, objectId, asOf),canonicalCompletedEvents)));
    }

    Map<UUID, VariantEconomics> economicsForSales(
            Optional<AdvertisingEvidenceRepository.LinkedSaleAggregate> sales, Instant from, Instant to) {
        return economicsForSales(sales, from, to, to);
    }

    Map<UUID, VariantEconomics> economicsForSales(Optional<AdvertisingEvidenceRepository.LinkedSaleAggregate> sales,
            Instant from, Instant to, Instant readAt) {
        Map<UUID, VariantEconomics> result = new HashMap<>();
        var window = java.util.Arrays.stream(MetricWindow.values())
                .filter(candidate -> from.plus(candidate.length()).equals(to)).findFirst()
                .or(() -> Optional.of(MetricWindow.D30));
        if (sales.isEmpty()) { return Map.of(); }
        var cohorts = sales.get().lines().stream().filter(line -> line.platformListingVariantId() != null)
                .collect(java.util.stream.Collectors.groupingBy(AdvertisingEvidenceRepository.LinkedSaleLine::platformListingVariantId));
        for (var cohort : cohorts.values()) {
            if (cohort.stream().anyMatch(line -> line.periodStart() == null || line.periodEnd() == null
                    || !line.periodStart().isBefore(line.periodEnd()))) continue;
            var line = cohort.getFirst();
            Instant cohortFrom = cohort.stream().map(AdvertisingEvidenceRepository.LinkedSaleLine::periodStart)
                    .min(Instant::compareTo).orElseThrow();
            Instant cohortTo = cohort.stream().map(AdvertisingEvidenceRepository.LinkedSaleLine::periodEnd)
                    .max(Instant::compareTo).orElseThrow();
            Map<MetricCode, MetricValueView> values = metrics.currentValuesCoveringAt(
                    SubjectKind.PLATFORM_LISTING_VARIANT, line.platformListingVariantId(), window.get(), cohortFrom, cohortTo, readAt);
            List<MetricCode> required = List.of(MetricCode.UNIT_COST, MetricCode.PLATFORM_FEES_PER_UNIT,
                    MetricCode.RETURN_LOSS_PER_UNIT, MetricCode.VARIABLE_TAX_PER_UNIT);
            boolean valid = required.stream().map(values::get).allMatch(value -> value != null
                    && value.definitionVersion() == MetricCode.DEFINITION_VERSION && value.subjectId().equals(line.platformListingVariantId())
                    && value.subjectKind() == SubjectKind.PLATFORM_LISTING_VARIANT
                    && value.periodStart().plus(window.get().length()).equals(value.periodEnd())
                    && !value.periodEnd().isAfter(readAt)
                    && !value.computedAt().isAfter(readAt) && value.inputDigest() != null
                    && !value.evidenceRefs().isEmpty() && cohort.stream().allMatch(consumed ->
                        !value.periodStart().isAfter(consumed.periodStart()) && !value.periodEnd().isBefore(consumed.periodEnd())
                        && java.util.Objects.equals(value.currencyCode(), consumed.currencyCode())));
            if (!valid) { continue; }
            result.put(line.platformListingVariantId(), new VariantEconomics(
                    measure(values.get(MetricCode.UNIT_COST)), measure(values.get(MetricCode.PLATFORM_FEES_PER_UNIT)),
                    measure(values.get(MetricCode.RETURN_LOSS_PER_UNIT)), measure(values.get(MetricCode.VARIABLE_TAX_PER_UNIT)),
                    line.currencyCode(), required.stream().map(values::get).toList()));
        }
        return Map.copyOf(result);
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
        var confirmed = card.children().stream().filter(child -> "CONFIRMED".equals(child.evidenceState())
                && child.blockerCodes().isEmpty()).toList();
        if (confirmed.isEmpty()) { return VariantAvailability.unknown(); }
        boolean unsellable = confirmed.stream().anyMatch(child -> "CHANNEL_NOT_SELLABLE".equals(child.causeCode()));
        boolean unavailable = confirmed.stream().anyMatch(child -> child.availableUnits() != null && child.availableUnits() <= 0);
        boolean stockKnown = confirmed.stream().allMatch(child -> child.availableUnits() != null);
        return new VariantAvailability(unsellable ? "NOT_SELLABLE" : "SELLABLE",
                unavailable ? "UNAVAILABLE" : stockKnown ? "AVAILABLE" : "UNKNOWN",
                confirmed.stream().map(child -> child.calculatedAt()).min(Instant::compareTo).orElse(null),
                "CANONICAL_CONFIRMED", confirmed.stream().map(child -> child.id()).toList());
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

}
