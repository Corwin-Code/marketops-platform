package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository.FreshnessProfile;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Purpose-specific bounds; absence, future timestamps and unmet maturity remain explicit. */
final class AdvertisingPurposeFreshness {
    private AdvertisingPurposeFreshness() { }

    static List<String> failures(AdvertisingEvidenceGatherer.Evidence evidence, String purpose,
                                List<String> kinds) {
        return assess(evidence, purpose, kinds).stream().flatMap(value -> value.reasonCodes().stream()).toList();
    }

    static List<com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation.PurposeEvidence> assess(
            AdvertisingEvidenceGatherer.Evidence evidence, String purpose, List<String> kinds) {
        List<com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation.PurposeEvidence> result = new ArrayList<>();
        for (String kind : kinds) {
            List<String> failures = new ArrayList<>();
            FreshnessProfile profile = evidence.authorities().freshness().get(purpose + ":" + kind);
            if (profile == null) {
                result.add(new com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation.PurposeEvidence(
                        purpose, kind, null, null, null, null, false,
                        List.of("FRESHNESS_PROFILE_UNRESOLVED:" + purpose + ":" + kind)));
                continue;
            }
            Instant source = null;
            Instant accepted = null;
            boolean complete = false;
            boolean closed = false;
            BigDecimal coverage = null;
            switch (kind) {
                case "OFFICIAL_AD_SPEND", "OFFICIAL_AD_TRAFFIC" -> {
                    if (evidence.objectFacts().isPresent()) {
                        var facts = evidence.objectFacts().get();
                        source = facts.latestSourceTime(); accepted = facts.acceptedAt();
                        complete = facts.everyWindowComplete() && ("OFFICIAL_AD_SPEND".equals(kind)
                                ? facts.spendAmount() != null && facts.currencyCode() != null : facts.clicks() != null);
                        closed = !facts.anyCorrectionWindowOpen();
                        coverage = facts.coverageRatio();
                    }
                }
                case "AD_LINKED_SALE_EVENT" -> {
                    var lines = evidence.completedSales().map(value -> value.lines()).orElse(List.of());
                    source = lines.stream().map(value -> value.sourceTime()).min(Instant::compareTo).orElse(null);
                    accepted = lines.stream().map(value -> value.recordedAt()).min(Instant::compareTo).orElse(null);
                    complete = !lines.isEmpty() && lines.stream().allMatch(line -> line.productVariantId() != null);
                    closed = evidence.objectFacts().map(value -> !value.anyCorrectionWindowOpen()).orElse(false);
                    coverage = evidence.objectFacts().map(value -> value.coverageRatio()).orElse(null);
                }
                case "COST_AND_FEE" -> {
                    var values = evidence.economics().values().stream().flatMap(value -> value.lineage().stream()).toList();
                    // An effective dated cost fact may remain valid for months.
                    // The canonical Metric evaluation is the dated proof that
                    // those exact inputs still apply to this subject/window;
                    // aging the oldest transaction would falsely stale it.
                    source = values.stream().map(value -> value.computedAt()).min(Instant::compareTo).orElse(null);
                    accepted = source;
                    complete = !values.isEmpty() && values.stream().allMatch(value -> value.available()
                            && value.confidenceState().name().equals("CANONICAL_CONFIRMED"));
                    closed = complete;
                    coverage = complete ? BigDecimal.ONE : null;
                }
                case "AD_OBJECT_CONFIGURATION" -> {
                    if (evidence.configuration().isPresent()) {
                        var configuration = evidence.configuration().get();
                        source = configuration.sourceTime(); accepted = configuration.observedAt();
                        complete = configuration.observedBidAmount() != null
                                && List.of("OFFICIAL_API_READBACK", "OFFICIAL_CONFIGURATION_EXPORT")
                                        .contains(configuration.evidenceGrade());
                        closed = complete; coverage = complete ? BigDecimal.ONE : null;
                    }
                }
                case "AFFECTED_SET" -> {
                    if (evidence.affectedSet().isPresent()) {
                        var set = evidence.affectedSet().get(); source = set.resolvedAt(); accepted = set.resolvedAt();
                        complete = "COMPLETE".equals(set.resolutionState()); closed = complete;
                        coverage = complete ? BigDecimal.ONE : null;
                    }
                }
                case "SELLABILITY", "AVAILABILITY" -> {
                    var values = evidence.variantAvailability().values();
                    source = values.stream().map(value -> value.observedAt()).filter(java.util.Objects::nonNull)
                            .min(Instant::compareTo).orElse(null);
                    accepted = source;
                    complete = !values.isEmpty() && values.stream().allMatch(value ->
                            "CANONICAL_CONFIRMED".equals(value.evidenceState()) && value.observedAt() != null);
                    closed = complete; coverage = complete ? BigDecimal.ONE : null;
                }
                default -> { /* The owner of this evidence must provide a timestamped proof. */ }
            }
            if (profile.providerIncidentBlocks() && evidence.authorities().providerIncidentOpen()) {
                failures.add("PROVIDER_INCIDENT_BLOCKS:" + purpose + ":" + kind);
            }
            boolean agePass = within(source, profile.sourceMaxAgeMinutes(), evidence.asOf())
                    && within(accepted, profile.acceptedFactMaxAgeMinutes(), evidence.asOf());
            boolean coveragePass = profile.minimumCoverageRatio() == null
                    || coverage != null && coverage.compareTo(profile.minimumCoverageRatio()) >= 0
                    && coverage.compareTo(BigDecimal.ONE) <= 0;
            boolean windowPass = (!profile.requiresWindowComplete() || complete)
                    && (!profile.requiresCorrectionWindowClosed() || closed);
            boolean confidencePass = !"CANONICAL_CONFIRMED".equals(profile.minimumConfidenceState()) || complete && closed;
            if (!agePass || !coveragePass || !windowPass || !confidencePass) {
                failures.add("FRESHNESS_BOUND_UNMET:" + purpose + ":" + kind);
            }
            Instant sourceExpiry = source == null || profile.sourceMaxAgeMinutes() == null ? null
                    : source.plus(Duration.ofMinutes(profile.sourceMaxAgeMinutes()));
            Instant acceptedExpiry = accepted == null || profile.acceptedFactMaxAgeMinutes() == null ? null
                    : accepted.plus(Duration.ofMinutes(profile.acceptedFactMaxAgeMinutes()));
            Instant expiry = java.util.stream.Stream.of(sourceExpiry, acceptedExpiry, profile.effectiveTo())
                    .filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
            if (expiry == null || !expiry.isAfter(evidence.asOf())) {
                failures.add("FRESHNESS_DEADLINE_UNRESOLVED_OR_EXPIRED:" + purpose + ":" + kind);
            }
            result.add(new com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation.PurposeEvidence(
                    purpose, kind, profile.id(), source, accepted, expiry, failures.isEmpty(), List.copyOf(failures)));
        }
        return List.copyOf(result);
    }

    private static boolean within(Instant time, Integer maximumMinutes, Instant at) {
        return maximumMinutes == null || time != null && !time.isAfter(at)
                && Duration.between(time, at).compareTo(Duration.ofMinutes(maximumMinutes)) <= 0;
    }
}
