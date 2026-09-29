package com.mimococo.marketops.analyticsdecision.internal.web;

import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.StoreListingFindingsRepository;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.StoreListingFindingsRepository.FindingRow;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.StoreListingFindingsRepository.MetricRow;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.StoreListingFindingsRepository.RunRow;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.shared.ConsoleApi;
import com.mimococo.marketops.shared.JsonValues;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * What the newest completed calculation run of a store concluded about each of
 * its listings: the triggered findings with the values they compared, and the
 * estimated unit economics, for the store diagnosis.
 *
 * <p>Nothing is computed here: every number is the run's own, so the page
 * shows exactly what the rules saw and what an explanation may cite.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/diagnosis")
class StoreListingFindingsController {

    /** The metric values the store diagnosis shows per listing. */
    private static final List<String> METRICS = List.of(
            MetricCode.OBSERVED_SELLING_PRICE.name(), MetricCode.UNIT_COST.name(),
            MetricCode.PLATFORM_COMPETITOR_MIN_PRICE.name(), MetricCode.PROJECTED_UNIT_PROFIT.name(),
            MetricCode.PROJECTED_UNIT_MARGIN.name(), MetricCode.PROJECTED_BREAK_EVEN_PRICE.name(),
            MetricCode.TARGET_MARGIN_PRICE.name(), MetricCode.SEARCH_USERS.name(),
            MetricCode.ORDERED_UNITS.name(), MetricCode.CONTENT_RATING.name());

    private final StoreListingFindingsRepository findings;
    private final BusinessAuthorization authorization;
    private final ObjectMapper objectMapper;

    StoreListingFindingsController(StoreListingFindingsRepository findings,
                                   BusinessAuthorization authorization,
                                   ObjectMapper objectMapper) {
        this.findings = findings;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
    }

    /** The newest completed run's findings and unit economics for every listing of the store. */
    @GetMapping(value = "/stores/{storeId}/listing-findings", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional(readOnly = true)
    StoreFindings storeFindings(AuthenticatedActor actor, @PathVariable UUID storeId,
                                @RequestParam(required = false, defaultValue = "D7") MetricWindow window) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        Optional<RunRow> run = findings.latestRun(actor.organizationId(), storeId, window.name());
        if (run.isEmpty()) {
            return new StoreFindings(storeId, window.name(), null, List.of(), List.of());
        }
        List<FindingRow> triggered = findings.triggeredFindings(run.get(), window.name());
        Map<UUID, List<Finding>> bySubject = new LinkedHashMap<>();
        for (FindingRow row : triggered) {
            bySubject.computeIfAbsent(row.subjectId(), subject -> new ArrayList<>())
                    .add(new Finding(row.id(), row.ruleCode(), row.severity(), detail(row.detail())));
        }
        Map<UUID, Map<String, MetricView>> metrics = new LinkedHashMap<>();
        for (MetricRow row : findings.metricValues(run.get().id(), METRICS)) {
            metrics.computeIfAbsent(row.subjectId(), subject -> new LinkedHashMap<>())
                    .put(row.metricCode(), new MetricView(row.valueState(),
                            row.numericValue() == null ? null : row.numericValue().stripTrailingZeros().toPlainString(),
                            row.currencyCode(), row.confidenceState()));
        }
        Set<UUID> subjects = new LinkedHashSet<>(metrics.keySet());
        subjects.addAll(bySubject.keySet());
        List<ListingFindings> listings = subjects.stream()
                .map(subject -> new ListingFindings(subject, bySubject.getOrDefault(subject, List.of()),
                        metrics.getOrDefault(subject, Map.of())))
                .toList();
        List<RuleCount> summary = triggered.stream()
                .collect(Collectors.groupingBy(FindingRow::ruleCode, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(entry -> new RuleCount(entry.getKey(), entry.getValue().getFirst().severity(),
                        entry.getValue().size()))
                .toList();
        RunRow found = run.get();
        return new StoreFindings(storeId, window.name(),
                new Run(found.id(), found.periodStart(), found.periodEnd(), found.completedAt(), found.subjectCount()),
                summary, listings);
    }

    private Map<String, Object> detail(String json) {
        try {
            return JsonValues.object(JsonValues.read(objectMapper, json));
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
    }

    /**
     * The newest completed run's conclusions.
     *
     * @param run {@code null} when the store has never been calculated over the window
     */
    record StoreFindings(UUID storeId, String window, Run run, List<RuleCount> summary,
                         List<ListingFindings> listings) {
    }

    /** One completed run. */
    record Run(UUID runId, Instant periodStart, Instant periodEnd, Instant completedAt, Integer subjectCount) {
    }

    /** How many listings one rule triggered for. */
    record RuleCount(String ruleCode, String severity, int listings) {
    }

    /** One listing's triggered findings and metric values. */
    record ListingFindings(UUID listingVariantId, List<Finding> findings, Map<String, MetricView> metrics) {
    }

    /** One triggered finding with the values it compared. */
    record Finding(UUID findingId, String ruleCode, String severity, Map<String, Object> detail) {
    }

    /** One metric value as decimal text, with its state and confidence. */
    record MetricView(String valueState, String value, String currencyCode, String confidenceState) {
    }
}
