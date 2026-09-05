package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.analyticsdecision.internal.domain.ComputedMetric;
import com.mimococo.marketops.analyticsdecision.internal.domain.MetricInput;
import com.mimococo.marketops.analyticsdecision.internal.domain.RuleOutcome;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.DiagnosisRepository;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.MetricRepository;
import com.mimococo.marketops.operatingfacts.FactWindow;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.organizationaccount.StoreRef;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the metric engine and the diagnosis rules over a store, and stores what
 * they produced.
 *
 * <p>Computation and storage are separate concerns and stay separate: the engine
 * is a pure function that could be run against any facts, and this service is
 * what decides which subjects to run it over and what to do with the answers.
 * That separation is what lets a golden-case test exercise the arithmetic
 * without a database.
 *
 * <p>Subjects come from facts rather than from the catalogue. A run is therefore
 * proportional to what actually happened in the window instead of to how many
 * listings exist, and a listing nobody has heard from does not produce a page of
 * empty metrics.
 *
 * <p>The definition set is digested into the run. A value computed before a
 * definition changed can then be told from one computed after, which is the
 * difference between "the business moved" and "we changed what we measure".
 */
@Service
public class AnalyticsCalculationService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsCalculationService.class);

    /** How many subjects one run examines. */
    private static final int SUBJECT_LIMIT = 2_000;

    private final MetricEngine metricEngine;
    private final DiagnosisEngine diagnosisEngine;
    private final MetricRepository metrics;
    private final DiagnosisRepository diagnoses;
    private final OperatingFactQuery facts;
    private final OrganizationDirectory organizationDirectory;
    private final IdGenerator idGenerator;
    private final Clock clock;

    AnalyticsCalculationService(MetricEngine metricEngine,
                                DiagnosisEngine diagnosisEngine,
                                MetricRepository metrics,
                                DiagnosisRepository diagnoses,
                                OperatingFactQuery facts,
                                OrganizationDirectory organizationDirectory,
                                IdGenerator idGenerator,
                                Clock clock) {
        this.metricEngine = metricEngine;
        this.diagnosisEngine = diagnosisEngine;
        this.metrics = metrics;
        this.diagnoses = diagnoses;
        this.facts = facts;
        this.organizationDirectory = organizationDirectory;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Compute every metric and evaluate every rule for one store and window.
     *
     * @param storeId the store to run over
     * @param window the observation window
     * @param triggerKind why the run happened
     * @param requestedByUserId who asked, or {@code null} when scheduled
     */
    @Transactional
    public RunSummary run(UUID storeId,
                          MetricWindow window,
                          String triggerKind,
                          UUID requestedByUserId) {
        return runForWindow(storeId,window,FactWindow.alignedEndingAt(clock.instant(),window.length()),triggerKind,requestedByUserId);
    }

    /** Re-evaluate an exact historical business window through the same Metric writer. */
    @Transactional
    public RunSummary runForWindow(UUID storeId,
                                   MetricWindow window,
                                   FactWindow factWindow,
                                   String triggerKind,
                                   UUID requestedByUserId) {
        Instant now = clock.instant();
        if(window==null || factWindow==null
                || !java.time.Duration.between(factWindow.periodStart(),factWindow.periodEnd()).equals(window.length())
                || !factWindow.periodStart().equals(factWindow.periodStart().truncatedTo(java.time.temporal.ChronoUnit.HOURS))
                || !factWindow.periodEnd().equals(factWindow.periodEnd().truncatedTo(java.time.temporal.ChronoUnit.HOURS))
                || factWindow.periodEnd().isAfter(now)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        StoreRef store = organizationDirectory.store(storeId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));

        UUID runId = idGenerator.newId();
        metrics.openRun(runId, store.organizationId(), triggerKind, "STORE", storeId,
                window.name(), factWindow.periodStart(), factWindow.periodEnd(),
                definitionSetDigest(), requestedByUserId, now, CorrelationId.current());

        List<UUID> subjects = facts.listingVariantsWithActivity(storeId, factWindow,
                SUBJECT_LIMIT);
        int valuesStored = 0;
        int findingsStored = 0;
        for (UUID subjectId : subjects) {
            Map<MetricCode, ComputedMetric> computed = metricEngine.compute(
                    store.organizationId(), storeId, subjectId, window, factWindow);
            Map<MetricCode, UUID> storedValueIds = storeValues(runId, store.organizationId(),
                    subjectId, window, factWindow, computed, now);
            valuesStored += storedValueIds.size();

            List<RuleOutcome> outcomes = diagnosisEngine.evaluate(computed);
            findingsStored += storeFindings(runId, store.organizationId(), subjectId, window,
                    factWindow, computed, storedValueIds, outcomes, now);
        }

        metrics.closeRun(runId, "SUCCEEDED", subjects.size(), valuesStored, null,
                clock.instant());
        log.atInfo()
                .addKeyValue("event", "analytics_run_completed")
                .addKeyValue("storeId", storeId.toString())
                .addKeyValue("window", window.name())
                .addKeyValue("subjectCount", subjects.size())
                .addKeyValue("valueCount", valuesStored)
                .addKeyValue("findingCount", findingsStored)
                .addKeyValue("correlationId", CorrelationId.current())
                .log("An analytics run completed");
        return new RunSummary(runId, subjects.size(), valuesStored, findingsStored);
    }

    private Map<MetricCode, UUID> storeValues(UUID runId,
                                              UUID organizationId,
                                              UUID subjectId,
                                              MetricWindow window,
                                              FactWindow factWindow,
                                              Map<MetricCode, ComputedMetric> computed,
                                              Instant computedAt) {
        Map<MetricCode, UUID> stored = new java.util.EnumMap<>(MetricCode.class);
        computed.forEach((code, metric) -> {
            String digest = metric.inputDigest(MetricCode.DEFINITION_VERSION,
                    SubjectKind.PLATFORM_LISTING_VARIANT.name(), subjectId, window.name(),
                    factWindow.periodStart(), factWindow.periodEnd());
            UUID valueId = metrics.recordValue(idGenerator.newId(), organizationId, runId, code,
                    MetricCode.DEFINITION_VERSION, SubjectKind.PLATFORM_LISTING_VARIANT,
                    subjectId, window, factWindow.periodStart(), factWindow.periodEnd(),
                    metric.valueState(), metric.numericValue(), metric.currencyCode(),
                    metric.confidenceState(), metric.estimated(), metric.oldestSourceTime(),
                    metric.freshnessSeconds(computedAt), digest, computedAt);
            for (MetricInput input : metric.inputs()) {
                metrics.recordInput(idGenerator.newId(), valueId, input.kind().name(),
                        input.referenceId());
            }
            stored.put(code, valueId);
        });
        return stored;
    }

    private int storeFindings(UUID runId,
                              UUID organizationId,
                              UUID subjectId,
                              MetricWindow window,
                              FactWindow factWindow,
                              Map<MetricCode, ComputedMetric> computed,
                              Map<MetricCode, UUID> storedValueIds,
                              List<RuleOutcome> outcomes,
                              Instant evaluatedAt) {
        int stored = 0;
        for (RuleOutcome outcome : outcomes) {
            List<String> readDigests = outcome.readMetricCodes().stream()
                    .map(computed::get)
                    .map(metric -> metric.inputDigest(MetricCode.DEFINITION_VERSION,
                            SubjectKind.PLATFORM_LISTING_VARIANT.name(), subjectId,
                            window.name(), factWindow.periodStart(), factWindow.periodEnd()))
                    .toList();
            String digest = outcome.inputDigest(DiagnosisEngine.RULE_VERSION,
                    SubjectKind.PLATFORM_LISTING_VARIANT.name(), subjectId, window.name(),
                    readDigests);
            UUID findingId = diagnoses.recordFinding(idGenerator.newId(), organizationId, runId,
                    outcome.ruleCode(), DiagnosisEngine.RULE_VERSION,
                    SubjectKind.PLATFORM_LISTING_VARIANT, subjectId, window,
                    factWindow.periodStart(), factWindow.periodEnd(), outcome.outcome(),
                    outcome.severity(), outcome.declineReason(), outcome.detail(), digest,
                    evaluatedAt);
            for (MetricCode read : outcome.readMetricCodes()) {
                UUID valueId = storedValueIds.get(read);
                if (valueId != null) {
                    diagnoses.recordFindingInput(idGenerator.newId(), findingId, valueId,
                            "SUBJECT");
                }
            }
            stored++;
        }
        return stored;
    }

    /**
     * A digest of the definition set this run used.
     *
     * <p>It covers the metric codes and their version and the rule version, so a
     * value computed before a definition changed is distinguishable from one
     * computed after — which is the difference between the business moving and
     * the measurement moving.
     */
    private static String definitionSetDigest() {
        List<String> components = new ArrayList<>();
        components.add(Integer.toString(MetricCode.DEFINITION_VERSION));
        components.add(Integer.toString(DiagnosisEngine.RULE_VERSION));
        Arrays.stream(MetricCode.values()).map(Enum::name).sorted().forEach(components::add);
        return Digest.ofComponents(components);
    }

    /**
     * What one analytics run produced.
     *
     * @param calculationRunId the run
     * @param subjectCount how many subjects it examined
     * @param valueCount how many canonical values it stored
     * @param findingCount how many findings it stored
     */
    public record RunSummary(
            UUID calculationRunId, int subjectCount, int valueCount, int findingCount) {
    }
}
