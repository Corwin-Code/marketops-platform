package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.analyticsdecision.CalculationRunLedger;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.listingconversion.ConversionMeasurementView;
import com.mimococo.marketops.listingconversion.EvidencePath;
import com.mimococo.marketops.listingconversion.RatioState;
import com.mimococo.marketops.listingconversion.internal.domain.EvidencePathQualification;
import com.mimococo.marketops.listingconversion.internal.domain.VersionWindow;
import com.mimococo.marketops.listingconversion.internal.domain.VisitConversion;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingHealthRepository;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Measures retained-visit conversion for one listing, window and evidence path.
 *
 * <p>The detail path computes the set ratio from visits and links; the official
 * summary path reads the platform's counts and qualifies them only under a
 * proven equivalence profile. Neither path ever reads the platform's label.
 */
@Service
public class ConversionMeasurementService {

    static final int DEFINITION_VERSION = 1;
    static final String SUMMARY_KIND = "VISITS_AND_RETAINED_PURCHASES";

    private final ListingFactRepository facts;
    private final ListingHealthRepository measurements;
    private final CalculationRunLedger ledger;
    private final IdGenerator ids;
    private final Clock clock;
    private final tools.jackson.databind.ObjectMapper json;
    private final com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.MeasurementEvidenceRepository evidence;

    ConversionMeasurementService(ListingFactRepository facts, ListingHealthRepository measurements,
                                 CalculationRunLedger ledger, IdGenerator ids, Clock clock,
                                 com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.MeasurementEvidenceRepository evidence, tools.jackson.databind.ObjectMapper json) {
        this.facts = facts;
        this.measurements = measurements;
        this.ledger = ledger;
        this.ids = ids;
        this.clock = clock;
        this.evidence = evidence;
        this.json = json;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ConversionMeasurementView measure(UUID listingId, Instant windowStart, Instant windowEnd, int retentionDays,
                                             EvidencePath path, String triggerKind, UUID requestedByUserId) {
        ListingFactRepository.ListingContext listing = facts.listing(listingId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (windowStart == null || windowEnd == null || path == null || !windowStart.isBefore(windowEnd) || (retentionDays != 7 && retentionDays != 14 && retentionDays != 30)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Instant now = facts.databaseNow();
        var coverage = evidence.coverage(listingId, path, windowStart, windowEnd, retentionDays, now);
        var timezone = evidence.timezone(listingId).map(java.time.ZoneId::of);
        var displayEvidence = evidence.displaySnapshot(listingId,windowStart,windowEnd,now);
        List<VersionWindow.Display> knownDisplays = new java.util.ArrayList<>();
        for (var display : displayEvidence) {
            boolean displayed="DISPLAYED".equals(display.path("display_state").asText())
                    && display.path("displayed_text_digest").isTextual()
                    && !display.path("displayed_text_digest").asText().isBlank();
            knownDisplays.add(new VersionWindow.Display(displayed?display.path("displayed_text_digest").asText():null,
                    java.time.OffsetDateTime.parse(display.path("observed_at").asText()).toInstant(),displayed));
        }
        VersionWindow.Attribution attribution = VersionWindow.attribute(
                knownDisplays, windowStart, windowEnd,
                timezone.orElse(java.time.ZoneOffset.UTC));
        var detail = evidence.detailSnapshot(listingId, windowStart, windowEnd, now);
        var summary = coverage.filter(c -> c.summaryId() != null)
                .flatMap(c -> evidence.summarySnapshot(listingId, c.summaryId(), windowStart, windowEnd, retentionDays, now));
        String actualDigest = path == EvidencePath.DETAIL ? detail.digest() : summary.map(s -> s.digest()).orElse(null);
        boolean complete = coverage.isPresent() && coverage.get().inputDigest().equals(actualDigest);
        boolean maturity = coverage.map(c -> !c.sourceThrough().isBefore(windowEnd.plus(java.time.Duration.ofDays(retentionDays))))
                .orElse(false);
        List<VisitConversion.Visit> allVisits = new java.util.ArrayList<>();
        List<VisitConversion.Visit> visits = new java.util.ArrayList<>();
        Map<String,List<VisitConversion.Visit>> criticalGroupVisits = new java.util.TreeMap<>();
        for (var row : detail.inputs().path("visits")) {
            var visit = new VisitConversion.Visit(row.path("visit_key").asText(),
                    row.path("sellable_at_visit").asText(), row.path("source_channel").asText());
            allVisits.add(visit);
            if (!VersionWindow.excluded(attribution, java.time.OffsetDateTime.parse(row.path("visited_at").asText()).toInstant())) {
                visits.add(visit);
                String group=row.path("key_group_code").asText("");
                if (!group.isBlank()) criticalGroupVisits.computeIfAbsent(group,ignored->new java.util.ArrayList<>()).add(visit);
            }
        }
        boolean stratified = path == EvidencePath.DETAIL && !visits.isEmpty()
                && visits.stream().noneMatch(v -> "UNKNOWN".equals(v.sourceChannel()));
        var boundProfile = coverage.flatMap(c -> evidence.profile(c.profileId()));
        EvidencePathQualification.SummaryProfile profile = boundProfile.map(p ->
                new EvidencePathQualification.SummaryProfile(true, "PROVEN".equals(p.path("proof_state").asText()),
                        p.path("covers_numerator").asBoolean(),p.path("covers_denominator").asBoolean(),
                        p.path("covers_time_attribution").asBoolean(),p.path("covers_maturity").asBoolean(),
                        p.path("covers_revision").asBoolean())).orElse(EvidencePathQualification.SummaryProfile.absent());
        List<String> disqualifications = new java.util.ArrayList<>(EvidencePathQualification.disqualifications(
                path, complete, complete, stratified, profile));
        if (!complete && path == EvidencePath.OFFICIAL_SUMMARY) disqualifications.add("SUMMARY_WINDOW_INCOMPLETE");
        if (timezone.isEmpty() && !attribution.excludedDays().isEmpty()) disqualifications.add("SOURCE_TIMEZONE_UNRESOLVED");
        if (path == EvidencePath.OFFICIAL_SUMMARY && !attribution.excludedDays().isEmpty()) {
            disqualifications.add("SUMMARY_TRANSITION_WINDOW_UNSPLITTABLE");
        }
        boolean qualified = disqualifications.isEmpty();
        Long visitCount;
        Long retainedCount;
        BigDecimal ratio;
        RatioState state;
        Map<String, String> split;
        tools.jackson.databind.node.ObjectNode lineage = json.createObjectNode();
        lineage.set("displayObservations",displayEvidence);
        lineage.put("displayObservationAsOf",now.toString());
        var versionCoverage=lineage.putObject("versionCoverage");
        versionCoverage.put("timezone",timezone.map(java.time.ZoneId::getId).orElse("UNRESOLVED"));
        versionCoverage.set("excludedTransitionDays",json.valueToTree(attribution.excludedDays()));
        versionCoverage.set("uncoveredDays",json.valueToTree(attribution.uncoveredDays()));
        versionCoverage.set("includedDayDigests",json.valueToTree(attribution.includedDayDigests()));
        var coveredDigests=new java.util.TreeSet<>(attribution.includedDayDigests().values());
        boolean singleVersionCoverage=timezone.isPresent() && attribution.uncoveredDays().isEmpty()
                && !attribution.includedDayDigests().isEmpty() && coveredDigests.size()==1;
        versionCoverage.put("state",singleVersionCoverage?"FULL_SINGLE_VERSION_COVERAGE":"UNQUALIFIED");
        versionCoverage.put("coveredTextDigest",singleVersionCoverage?coveredDigests.first():null);
        lineage.put("fullTargetVersionCoverageQualified",singleVersionCoverage);
        lineage.set("equivalenceProfile", boundProfile.orElseGet(json::createObjectNode));
        lineage.set("sourceInputs", path == EvidencePath.DETAIL ? detail.inputs()
                : summary.map(s -> s.inputs()).orElseGet(() -> json.createObjectNode()));
        if (path == EvidencePath.DETAIL) {
            var sales = facts.retainedEvidence(listingId, windowStart, windowEnd, retentionDays, now);
            if (sales.conflicted()) {
                disqualifications.add("SALE_REVISION_CONFLICTED");
                qualified = false;
            }
            lineage.set("salesEvidence",sales.lineage());
            List<String> retained = sales.visitKeys();
            lineage.set("sourceStrata",json.valueToTree(VisitConversion.sourceCounts(visits,retained)));
            var groupStrata=lineage.putObject("criticalGroupSourceStrata");
            criticalGroupVisits.forEach((code,members)->groupStrata.set(code,
                    json.valueToTree(VisitConversion.sourceCounts(members,retained))));
            lineage.put("sourceStrataQualified",qualified && maturity && stratified);
            lineage.put("criticalGroupSourceStrataQualified",qualified && maturity && stratified);
            VisitConversion.Result whole = VisitConversion.compute(allVisits, retained, maturity, qualified);
            VisitConversion.Result result = VisitConversion.compute(visits, retained, maturity, qualified);
            lineage.put("wholeWindowVisitCount", whole.visitCount());
            lineage.put("wholeWindowRetainedCount", whole.retainedPurchaseVisitCount());
            lineage.put("wholeWindowRatio", whole.ratio() == null ? null : whole.ratio().toPlainString());
            var retainedKeys = lineage.putArray("effectiveRetainedVisitKeys");
            retained.stream().sorted().forEach(retainedKeys::add);
            visitCount = qualified ? result.visitCount() : null;
            retainedCount = qualified && maturity ? result.retainedPurchaseVisitCount() : null;
            ratio = result.ratio();
            state = result.state();
            split = result.sellableSplit();
        } else {
            var source = summary.map(s -> s.inputs());
            visitCount = source.filter(row -> row.path("reported_visits").isNumber())
                    .map(row -> row.path("reported_visits").longValue()).orElse(null);
            retainedCount = source.filter(row -> row.path("reported_retained_purchases").isNumber())
                    .map(row -> row.path("reported_retained_purchases").longValue()).orElse(null);
            split = Map.of();
            boolean contradiction = visitCount != null && retainedCount != null
                    && (visitCount < 0 || retainedCount < 0 || retainedCount > visitCount);
            if (contradiction) {
                disqualifications.add("SUMMARY_COUNTS_CONFLICTED");
                qualified = false;
                // Preserve the actual contradictory source above; do not fabricate a corrected numerator.
                visitCount = null;
                retainedCount = null;
            }
            if (!qualified || !maturity || visitCount == null || retainedCount == null) {
                ratio = null;
                state = RatioState.NOT_AVAILABLE;
            } else if (visitCount == 0) {
                ratio = null;
                state = RatioState.UNDEFINED;
            } else {
                ratio = BigDecimal.valueOf(retainedCount).divide(BigDecimal.valueOf(visitCount), 6, RoundingMode.DOWN);
                state = RatioState.DEFINED;
            }
        }
        UUID runId = ledger.recordCompletedRun(new CalculationRunLedger.CompletedRun(listing.organizationId(),
                listing.storeId(), triggerKind, windowOf(retentionDays), windowStart, windowEnd,
                Digest.ofText("lc-conversion-" + DEFINITION_VERSION), 1, 1, true, null, now, requestedByUserId));
        UUID id = ids.newId();
        measurements.insertMeasurement(id, listing.organizationId(), listing.storeId(), listingId, runId,
                DEFINITION_VERSION, windowStart, windowEnd, retentionDays, path, qualified, disqualifications,
                visitCount, retainedCount, ratio, state, maturity, stratified, split, attribution.excludedDays(),
                coverage.map(c -> c.sourceThrough()).orElse(null), coverage.map(c -> c.acquiredAt()).orElse(null), now);
        evidence.lineage(id, coverage.map(c -> c.id()).orElse(null), lineage,
                timezone.map(java.time.ZoneId::getId).orElse("UNRESOLVED"), now);
        return measurements.measurement(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<ConversionMeasurementView> history(UUID listingId, int limit) {
        return measurements.measurements(listingId, limit);
    }

    /**
     * Refresh only measurement definitions that a person or accepted plan has
     * already established.  The bound prevents one source event from turning
     * historical measurement rows into an unbounded recalculation loop.
     */
    public List<UUID> remeasureCurrent(UUID listingId, String triggerKind) {
        List<UUID> refreshed = new java.util.ArrayList<>();
        for (var definition : measurements.currentMeasurementDefinitions(listingId, 24)) {
            refreshed.add(measure(listingId,definition.windowStart(),definition.windowEnd(),
                    definition.retentionDays(),definition.evidencePath(),triggerKind,null).id());
        }
        return List.copyOf(refreshed);
    }

    private static MetricWindow windowOf(int retentionDays) {
        return switch (retentionDays) {
            case 7 -> MetricWindow.D7;
            case 14 -> MetricWindow.D14;
            default -> MetricWindow.D30;
        };
    }
}
