package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.StoreFindingsQuery;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.StoreListingFindingsRepository;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.StoreListingFindingsRepository.FindingRow;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.StoreListingFindingsRepository.MetricRow;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.StoreListingFindingsRepository.RunRow;
import com.mimococo.marketops.shared.JsonValues;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** The newest completed run of a store, read the way the store diagnosis reads it. */
@Service
class StoreFindingsService implements StoreFindingsQuery {

    private final StoreListingFindingsRepository runs;
    private final ObjectMapper objectMapper;

    StoreFindingsService(StoreListingFindingsRepository runs, ObjectMapper objectMapper) {
        this.runs = runs;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoreRun> latest(UUID organizationId, UUID storeId, MetricWindow window,
                                     Set<MetricCode> metricCodes) {
        Optional<RunRow> found = runs.latestRun(organizationId, storeId, window.name());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RunRow run = found.get();
        Map<UUID, List<Finding>> findings = new LinkedHashMap<>();
        for (FindingRow row : runs.triggeredFindings(run, window.name())) {
            findings.computeIfAbsent(row.subjectId(), subject -> new ArrayList<>())
                    .add(new Finding(row.id(), row.ruleCode(), row.severity(), detail(row.detail())));
        }
        Map<UUID, Map<MetricCode, Value>> values = new LinkedHashMap<>();
        if (!metricCodes.isEmpty()) {
            List<String> codes = metricCodes.stream().map(MetricCode::name).toList();
            for (MetricRow row : runs.metricValues(run.id(), codes)) {
                MetricCode code = MetricCode.valueOf(row.metricCode());
                values.computeIfAbsent(row.subjectId(), subject -> new EnumMap<>(MetricCode.class))
                        .put(code, new Value(row.valueId(), code, ValueState.valueOf(row.valueState()),
                                row.numericValue(), row.currencyCode()));
            }
        }
        Set<UUID> subjects = new LinkedHashSet<>(values.keySet());
        subjects.addAll(findings.keySet());
        List<ListingResult> listings = subjects.stream()
                .map(subject -> new ListingResult(subject, findings.getOrDefault(subject, List.of()),
                        values.getOrDefault(subject, Map.of())))
                .toList();
        return Optional.of(new StoreRun(run.id(), run.periodStart(), run.periodEnd(), listings));
    }

    private Map<String, String> detail(String json) {
        Map<String, String> detail = new LinkedHashMap<>();
        try {
            JsonValues.object(JsonValues.read(objectMapper, json)).forEach((key, value) -> {
                if (value != null) {
                    detail.put(key, String.valueOf(value));
                }
            });
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
        return detail;
    }
}
