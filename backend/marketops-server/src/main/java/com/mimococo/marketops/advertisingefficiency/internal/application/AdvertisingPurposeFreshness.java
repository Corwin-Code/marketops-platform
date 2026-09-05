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
                        // Every live segment contributes to the total. A newer segment
                        // cannot refresh the source or acceptance of an older one.
                        source = facts.earliestSourceTime(); accepted = facts.oldestAcceptedAt();
                        if(facts.latestSourceTime()==null || facts.latestSourceTime().isAfter(evidence.asOf())
                                || facts.acceptedAt()==null || facts.acceptedAt().isAfter(evidence.asOf())) {
                            failures.add("FRESHNESS_INPUT_TIME_UNRESOLVED:"+purpose+":"+kind);
                        }
                        complete = facts.everyWindowComplete() && facts.latestSourceTime()!=null
                                && !facts.latestSourceTime().isAfter(evidence.asOf()) && ("OFFICIAL_AD_SPEND".equals(kind)
                                ? facts.spendAmount() != null && facts.currencyCode() != null : facts.clicks() != null);
                        closed = !facts.anyCorrectionWindowOpen();
                        coverage = facts.coverageRatio();
                    }
                }
                case "AD_LINKED_SALE_EVENT" -> {
                    var lines = evidence.completedSales().map(value -> value.lines()).orElse(List.of());
                    source = lines.stream().map(value -> value.sourceTime()).filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
                    accepted = lines.stream().map(value -> value.recordedAt()).filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
                    complete = !lines.isEmpty() && lines.stream().allMatch(line -> line.productVariantId() != null
                            && line.sourceTime()!=null && !line.sourceTime().isAfter(evidence.asOf())
                            && line.recordedAt()!=null && !line.recordedAt().isAfter(evidence.asOf()));
                    if(lines.stream().anyMatch(line->line.sourceTime()==null || line.sourceTime().isAfter(evidence.asOf())
                            || line.recordedAt()==null || line.recordedAt().isAfter(evidence.asOf()))) {
                        failures.add("FRESHNESS_INPUT_TIME_UNRESOLVED:"+purpose+":"+kind);
                    }
                    closed = evidence.objectFacts().map(value -> !value.anyCorrectionWindowOpen()).orElse(false);
                    coverage = evidence.objectFacts().map(value -> value.coverageRatio()).orElse(null);
                }
                case "COST_AND_FEE" -> {
                    var values = evidence.economics().values().stream().flatMap(value -> value.lineage().stream()).toList();
                    // An effective dated cost fact may remain valid for months.
                    // The canonical Metric evaluation is the dated proof that
                    // those exact inputs still apply to this subject/window;
                    // aging the oldest transaction would falsely stale it.
                    source = values.stream().map(value -> value.verifiedAt()).filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
                    accepted = source;
                    complete = !values.isEmpty() && values.stream().allMatch(value -> value.available()
                            && value.confidenceState().name().equals("CANONICAL_CONFIRMED")
                            && value.verifiedAt()!=null && !value.verifiedAt().isAfter(evidence.asOf()));
                    if(values.stream().anyMatch(value->value.verifiedAt()==null || value.verifiedAt().isAfter(evidence.asOf()))) {
                        failures.add("FRESHNESS_INPUT_TIME_UNRESOLVED:"+purpose+":"+kind);
                    }
                    closed = complete;
                    coverage = complete ? BigDecimal.ONE : null;
                }
                case "AD_OBJECT_CONFIGURATION" -> {
                    if (evidence.configuration().isPresent()) {
                        var configuration = evidence.configuration().get();
                        source = configuration.sourceTime(); accepted = configuration.acceptedAt();
                        complete = configuration.observedBidAmount() != null
                                && List.of("OFFICIAL_API_READBACK", "OFFICIAL_CONFIGURATION_EXPORT")
                                        .contains(configuration.evidenceGrade());
                        closed = complete; coverage = complete ? BigDecimal.ONE : null;
                    }
                }
                case "AFFECTED_SET" -> {
                    if (evidence.affectedSet().isPresent()) {
                        var set = evidence.affectedSet().get(); source = set.resolvedAt(); accepted = set.acceptedAt();
                        complete = "COMPLETE".equals(set.resolutionState()); closed = complete;
                        coverage = complete ? BigDecimal.ONE : null;
                    }
                }
                case "SELLABILITY", "AVAILABILITY" -> {
                    var values = evidence.variantAvailability().values();
                    source = values.stream().map(value -> value.observedAt()).filter(java.util.Objects::nonNull)
                            .min(Instant::compareTo).orElse(null);
                    accepted = source;
                    complete = evidence.affectedSet().filter(set -> "COMPLETE".equals(set.resolutionState())
                            && !set.productVariantIds().isEmpty()
                            && evidence.variantAvailability().keySet().containsAll(set.productVariantIds())).isPresent()
                            && !values.isEmpty() && values.stream().allMatch(value ->
                            "CANONICAL_CONFIRMED".equals(value.evidenceState()) && value.observedAt() != null
                            && ("SELLABILITY".equals(kind) ? List.of("SELLABLE", "NOT_SELLABLE").contains(value.sellabilityState())
                                    : List.of("AVAILABLE", "UNAVAILABLE").contains(value.availabilityState())));
                    closed = complete; coverage = complete ? BigDecimal.ONE : null;
                }
                default -> { /* The owner of this evidence must provide a timestamped proof. */ }
            }
            if (profile.providerIncidentBlocks() && evidence.authorities().providerIncidentOpen()) {
                failures.add("PROVIDER_INCIDENT_BLOCKS:" + purpose + ":" + kind);
            }
            failures.addAll(bounds(profile,source,accepted,complete,closed,coverage,evidence.asOf()));
            Instant expiry = expires(profile,source,accepted);
            result.add(new com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation.PurposeEvidence(
                    purpose, kind, profile.id(), source, accepted, expiry, failures.isEmpty(), List.copyOf(failures)));
        }
        return List.copyOf(result);
    }

    static List<String> bounds(FreshnessProfile profile, Instant source, Instant accepted,
            boolean complete, boolean closed, BigDecimal coverage, Instant at) {
        List<String> failures=new ArrayList<>();
        boolean agePass=within(source,profile.sourceMaxAgeMinutes(),at)
                && within(accepted,profile.acceptedFactMaxAgeMinutes(),at);
        boolean coveragePass=profile.minimumCoverageRatio()==null || coverage!=null
                && coverage.compareTo(profile.minimumCoverageRatio())>=0 && coverage.compareTo(BigDecimal.ONE)<=0;
        boolean windowPass=(!profile.requiresWindowComplete() || complete)
                && (!profile.requiresCorrectionWindowClosed() || closed);
        boolean confidencePass=!"CANONICAL_CONFIRMED".equals(profile.minimumConfidenceState()) || complete && closed;
        if(!agePass || !coveragePass || !windowPass || !confidencePass) {
            failures.add("FRESHNESS_BOUND_UNMET:"+profile.decisionPurpose()+":"+profile.evidenceKind());
        }
        Instant expiry=expires(profile,source,accepted);
        if(expiry==null || !expiry.isAfter(at)) failures.add("FRESHNESS_DEADLINE_UNRESOLVED_OR_EXPIRED:"
                +profile.decisionPurpose()+":"+profile.evidenceKind());
        return List.copyOf(failures);
    }

    static Instant expires(FreshnessProfile profile,Instant source,Instant accepted) {
        Instant sourceExpiry=source==null || profile.sourceMaxAgeMinutes()==null ? null
                : source.plus(Duration.ofMinutes(profile.sourceMaxAgeMinutes()));
        Instant acceptedExpiry=accepted==null || profile.acceptedFactMaxAgeMinutes()==null ? null
                : accepted.plus(Duration.ofMinutes(profile.acceptedFactMaxAgeMinutes()));
        return java.util.stream.Stream.of(sourceExpiry,acceptedExpiry,profile.effectiveTo())
                .filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
    }

    private static boolean within(Instant time, Integer maximumMinutes, Instant at) {
        return time != null && !time.isAfter(at) && (maximumMinutes == null
                || Duration.between(time, at).compareTo(Duration.ofMinutes(maximumMinutes)) <= 0);
    }
}
