package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.aicopilot.AiCopilot;
import com.mimococo.marketops.aicopilot.AiDiagnosis;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.StoreRecalculation;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.marketplaceintegration.IngestionJobDirectory;
import com.mimococo.marketops.marketplaceintegration.IngestionJobView;
import com.mimococo.marketops.marketplaceintegration.ScheduledAcquisition;
import com.mimococo.marketops.marketplaceintegration.ScheduledAcquisition.RunRecord;
import com.mimococo.marketops.operatingfacts.FactNormalization;
import com.mimococo.marketops.operationsworkflow.internal.application.CollectionPlanner.Cadence;
import com.mimococo.marketops.operationsworkflow.internal.application.CollectionPlanner.Target;
import com.mimococo.marketops.operationsworkflow.internal.config.ScheduledCollectionProperties;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ScheduledCollectionRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ScheduledCollectionRepository.Event;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ScheduledCollectionRepository.Policy;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.JsonValues;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Scheduled read-only collection under an Owner's standing authorization (P4).
 *
 * <p>One pass works through every store with a policy in force. For each scheduled job it finishes
 * the run a previous pass left waiting, or starts the run its cadence has due, executes it and
 * normalizes what it stored. Once nothing of the store is running or waiting any more, and new facts
 * are older than the current full hour, the store's diagnosis is recalculated. Every step that
 * changed something is recorded, so each automatic action can be read back.
 *
 * <p>Nothing here decides whether a call is allowed: every call of a scheduled run is authorised by
 * the database exactly like a manual one. A job whose evidence has lapsed is skipped rather than run
 * into BLOCKED, and a BLOCKED run is left for a person, because it holds the job's only live slot.
 */
@Service
public class ScheduledCollectionService {

    /** Who the audit and the run records name for scheduled actions. */
    static final String ACTOR = "scheduled-collection";

    private static final String POLICY_ENTITY_TYPE = "scheduled-collection-policy";

    /** The calculation windows kept current: the home page reads D7, the SKU diagnosis D30. */
    private static final List<MetricWindow> WINDOWS = List.of(MetricWindow.D7, MetricWindow.D30);

    /** A calculation is repeated at least this often, so its window keeps moving. */
    private static final Duration RECALCULATION_MAX_AGE = Duration.ofHours(24);

    /** How long after a failed recalculation the scheduler tries again. */
    private static final Duration RECALCULATION_BACKOFF = Duration.ofHours(1);

    /** A run still leased this long after it last moved has lost its worker and is taken over. */
    private static final Duration ABANDONED_AFTER = Duration.ofMinutes(10);

    /** Least time between two runs of one dataset: the analytics method allows one call a minute. */
    private static final Map<String, Duration> SPACING = Map.of("TRAFFIC", Duration.ofSeconds(90));

    /** How long after a failed weekly store summary the scheduler asks again. */
    private static final Duration INTERPRETATION_BACKOFF = Duration.ofHours(3);

    /** How many records the console shows. */
    private static final int RECENT_EVENTS = 30;

    private static final List<String> ORDER = List.copyOf(CollectionPlanner.CADENCES.keySet());

    private static final Logger log = LoggerFactory.getLogger(ScheduledCollectionService.class);

    private final ScheduledCollectionRepository repository;
    private final IngestionJobDirectory jobs;
    private final ScheduledAcquisition acquisition;
    private final FactNormalization normalization;
    private final StoreRecalculation recalculation;
    private final AiCopilot copilot;
    private final PriceSuggestionService priceSuggestions;
    private final MetadataAuditRecorder auditRecorder;
    private final ScheduledCollectionProperties properties;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    ScheduledCollectionService(ScheduledCollectionRepository repository,
                               IngestionJobDirectory jobs,
                               ScheduledAcquisition acquisition,
                               FactNormalization normalization,
                               StoreRecalculation recalculation,
                               AiCopilot copilot,
                               PriceSuggestionService priceSuggestions,
                               MetadataAuditRecorder auditRecorder,
                               ScheduledCollectionProperties properties,
                               ObjectMapper objectMapper,
                               IdGenerator idGenerator,
                               Clock clock) {
        this.repository = repository;
        this.jobs = jobs;
        this.acquisition = acquisition;
        this.normalization = normalization;
        this.recalculation = recalculation;
        this.copilot = copilot;
        this.priceSuggestions = priceSuggestions;
        this.auditRecorder = auditRecorder;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    // -----------------------------------------------------------------------
    // The Owner's policy
    // -----------------------------------------------------------------------

    /**
     * Put scheduled collection in force for the store, replacing the policy in force. The next pass
     * collects what is due.
     */
    @Transactional
    public Policy enable(AuthenticatedActor actor, UUID storeId, String reason) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        Instant now = clock.instant();
        Optional<Policy> current = repository.findActivePolicy(actor.organizationId(), storeId);
        if (current.isPresent()) {
            String replaced = "replaced: " + validReason;
            if (!repository.retirePolicy(current.get().id(), actor.userId(), now, replaced,
                    current.get().version())) {
                throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
            }
            audit(actor, current.get().id(), AuditAction.STATUS_CHANGE,
                    Map.of("status", new FieldChange("ACTIVE", "RETIRED")), replaced);
        }
        Policy policy = new Policy(idGenerator.newId(), actor.organizationId(), storeId, actor.userId(), now,
                validReason, 0L);
        repository.insertPolicy(policy);
        audit(actor, policy.id(), AuditAction.CREATE, Map.of("status", new FieldChange(null, "ACTIVE")),
                validReason);
        return policy;
    }

    /** Take scheduled collection for the store out of force; every run and fact stays in place. */
    @Transactional
    public void retire(AuthenticatedActor actor, UUID storeId, String reason, long expectedVersion) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        Policy current = repository.findActivePolicy(actor.organizationId(), storeId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!repository.retirePolicy(current.id(), actor.userId(), clock.instant(), validReason, expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        audit(actor, current.id(), AuditAction.STATUS_CHANGE, Map.of("status", new FieldChange("ACTIVE", "RETIRED")),
                validReason);
    }

    private void audit(AuthenticatedActor actor, UUID policyId, AuditAction action, Map<String, FieldChange> changes,
                       String reason) {
        auditRecorder.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW,
                actor.userId().toString(), action, POLICY_ENTITY_TYPE, policyId, null, changes, reason, null));
    }

    // -----------------------------------------------------------------------
    // One pass
    // -----------------------------------------------------------------------

    /**
     * One pass over every store with a policy in force. Not transactional: every run, normalization
     * pass, calculation and record is its own transaction, so a failure stops only its own step.
     */
    public PassSummary runPass() {
        int budget = properties.getRunsPerPass();
        int executed = 0;
        int recalculated = 0;
        for (Policy policy : repository.activePolicies()) {
            StoreOutcome outcome = collectStore(policy, budget - executed);
            executed += outcome.executed();
            recalculated += outcome.recalculated();
        }
        return new PassSummary(executed, recalculated);
    }

    private StoreOutcome collectStore(Policy policy, int budget) {
        Instant now = clock.instant();
        int executed = 0;
        boolean settled = true;
        Instant lastCollected = null;
        for (IngestionJobView job : scheduledJobs(policy.organizationId(), policy.storeId())) {
            Cadence cadence = CollectionPlanner.CADENCES.get(job.datasetKind());
            List<RunRecord> runs = acquisition.runsSince(job.jobId(), now.minus(CollectionPlanner.HISTORY));
            lastCollected = later(lastCollected, runs.stream().filter(RunRecord::succeeded)
                    .map(RunRecord::updatedAt).max(Comparator.naturalOrder()).orElse(null));

            Optional<RunRecord> live = acquisition.liveRun(job.jobId());
            if (live.isPresent()) {
                RunRecord run = live.get();
                if ("BLOCKED".equals(run.state())) {
                    recordOnce(policy, job, "BLOCKED", keyOf(cadence, run), run.runId(),
                            detail("state", run.state(), "failureCode", run.failureCode()));
                    continue;
                }
                settled = false;
                if (executed < budget && runnable(run, now)) {
                    if (acquisition.evidenceValidUntil(job.jobId()).isEmpty()) {
                        recordOnce(policy, job, "SKIPPED", keyOf(cadence, run), run.runId(),
                                detail("reason", "EVIDENCE_NOT_CURRENT"));
                        continue;
                    }
                    execute(policy, job, run, keyOf(cadence, run));
                    executed++;
                }
                continue;
            }

            Optional<Target> due = CollectionPlanner.due(cadence, now, properties.getDailyAt(), runs);
            if (due.isEmpty()) {
                continue;
            }
            Target target = due.get();
            List<RunRecord> attempts = CollectionPlanner.attempts(target, runs);
            if (attempts.size() >= CollectionPlanner.MAX_ATTEMPTS) {
                recordOnce(policy, job, "FAILED", target.key(), attempts.getFirst().runId(),
                        detail("reason", "ATTEMPTS_EXHAUSTED", "attempts", attempts.size()));
                continue;
            }
            if (acquisition.evidenceValidUntil(job.jobId()).isEmpty()) {
                // A run would only end BLOCKED and hold the job's slot until somebody resolves it.
                recordOnce(policy, job, "SKIPPED", target.key(), null, detail("reason", "EVIDENCE_NOT_CURRENT"));
                continue;
            }
            settled = false;
            if (!attempts.isEmpty() && attempts.getFirst().failed()
                    && attempts.getFirst().updatedAt().isAfter(now.minus(CollectionPlanner.FAILURE_BACKOFF))) {
                continue;
            }
            Duration spacing = SPACING.get(job.datasetKind());
            if (spacing != null && !runs.isEmpty() && runs.getFirst().updatedAt().isAfter(now.minus(spacing))) {
                continue;
            }
            if (executed >= budget) {
                continue;
            }
            RunRecord queued;
            try {
                queued = acquisition.enqueueScheduled(job.jobId(), target.windowFrom(), target.windowTo(), ACTOR);
            } catch (OperationRejectedException refused) {
                // Somebody started a run of the job meanwhile; the next pass sees it.
                continue;
            }
            execute(policy, job, queued, target.key());
            executed++;
        }
        int recalculated = 0;
        if (executed == 0 && settled) {
            recalculated = recalculate(policy, lastCollected, now);
            interpretWeekly(policy, now);
        }
        return new StoreOutcome(executed, recalculated);
    }

    /** The store's active jobs of a scheduled dataset, catalogue first. */
    private List<IngestionJobView> scheduledJobs(UUID organizationId, UUID storeId) {
        return jobs.jobs(organizationId).stream()
                .filter(job -> storeId.equals(job.storeId()) && job.active()
                        && CollectionPlanner.CADENCES.containsKey(job.datasetKind()))
                .sorted(Comparator.comparingInt((IngestionJobView job) -> ORDER.indexOf(job.datasetKind()))
                        .thenComparing(IngestionJobView::jobCode))
                .toList();
    }

    /** Whether a live run can be executed now: queued, due to retry, or abandoned by its worker. */
    private static boolean runnable(RunRecord run, Instant now) {
        return switch (run.state()) {
            case "QUEUED" -> true;
            case "RETRY_WAIT" -> run.nextAttemptAt() == null || !run.nextAttemptAt().isAfter(now);
            case "LEASED", "RUNNING" -> !run.updatedAt().isAfter(now.minus(ABANDONED_AFTER));
            default -> false;
        };
    }

    private void execute(Policy policy, IngestionJobView job, RunRecord run, String targetKey) {
        ScheduledAcquisition.Execution execution;
        try {
            execution = acquisition.execute(run.runId(), ACTOR);
        } catch (RuntimeException failed) {
            record(policy, job, "FAILED", targetKey, run.runId(), null,
                    detail("reason", "EXECUTION_FAILED", "failureType", failed.getClass().getSimpleName()));
            return;
        }
        RunRecord after = execution.run();
        Map<String, Object> outcome = detail("state", after.state(), "pagesStored", execution.pagesStored(),
                "reason", execution.reason(), "windowFrom", text(after.windowFrom()), "windowTo", text(after.windowTo()));
        switch (after.state()) {
            case "SUCCEEDED" -> normalize(policy, job, after, targetKey, outcome);
            case "BLOCKED" -> {
                outcome.put("failureCode", after.failureCode());
                record(policy, job, "BLOCKED", targetKey, after.runId(), null, outcome);
            }
            case "FAILED_TERMINAL" -> {
                outcome.put("failureCode", after.failureCode());
                record(policy, job, "FAILED", targetKey, after.runId(), null, outcome);
            }
            default -> {
                outcome.put("nextAttemptAt", text(after.nextAttemptAt()));
                record(policy, job, "WAITING", targetKey, after.runId(), null, outcome);
            }
        }
    }

    private void normalize(Policy policy, IngestionJobView job, RunRecord run, String targetKey,
                           Map<String, Object> outcome) {
        FactNormalization.Outcome normalized;
        try {
            normalized = normalization.normalizeJob(job.jobId());
        } catch (RuntimeException failed) {
            outcome.put("normalization", "FAILED");
            record(policy, job, "COLLECTED", targetKey, run.runId(), null, outcome);
            record(policy, job, "NORMALIZATION_STOPPED", targetKey, run.runId(), null,
                    detail("reason", "NORMALIZATION_FAILED", "failureType", failed.getClass().getSimpleName()));
            return;
        }
        outcome.put("factsRecorded", normalized.factsRecorded());
        outcome.put("recordsRejected", normalized.recordsRejected());
        outcome.put("normalization", normalized.lastReason());
        if (normalized.masterDataAutomationRan() || normalized.masterDataAutomationFailed()) {
            outcome.put("masterDataAutomation", normalized.masterDataAutomationFailed() ? "FAILED" : "RAN");
        }
        record(policy, job, "COLLECTED", targetKey, run.runId(), null, outcome);
        if (normalized.stopped()) {
            record(policy, job, "NORMALIZATION_STOPPED", targetKey, run.runId(), null,
                    detail("reason", normalized.lastReason()));
        }
    }

    /**
     * Recalculate every kept window whose newest calculation does not count the newest facts yet,
     * or is a day old. A window ends at the previous full hour, so facts collected within the
     * current hour wait for the next one.
     */
    private int recalculate(Policy policy, Instant lastCollected, Instant now) {
        Instant boundary = now.truncatedTo(ChronoUnit.HOURS);
        int recalculated = 0;
        for (MetricWindow window : WINDOWS) {
            Optional<Instant> latest = recalculation.latestPeriodEnd(policy.storeId(), window);
            boolean needed = latest.isEmpty()
                    || (latest.get().isBefore(boundary)
                        && ((lastCollected != null && lastCollected.isAfter(latest.get())
                                && lastCollected.isBefore(boundary))
                            || !latest.get().isAfter(now.minus(RECALCULATION_MAX_AGE))));
            if (!needed) {
                continue;
            }
            Optional<Event> failed = repository.latestStoreEvent(policy.storeId(), "RECALCULATION_FAILED",
                    window.name());
            if (failed.isPresent() && failed.get().occurredAt().isAfter(now.minus(RECALCULATION_BACKOFF))) {
                continue;
            }
            try {
                StoreRecalculation.Result result = recalculation.recalculate(policy.storeId(), window);
                PriceSuggestionService.Result suggested = window == MetricWindow.D7 ? suggestPrices(policy) : null;
                record(policy, null, "RECALCULATED", window.name(), null, result.calculationRunId(),
                        detail("window", window.name(), "periodEnd", boundary.toString(),
                                "subjectCount", result.subjectCount(), "valueCount", result.valueCount(),
                                "findingCount", result.findingCount(),
                                "priceSuggestions", suggested == null ? null : suggested.proposed(),
                                "priceSuggestionsRefreshed", suggested == null ? null : suggested.refreshed(),
                                "priceSuggestionsWithdrawn", suggested == null ? null : suggested.withdrawn()));
                recalculated++;
            } catch (RuntimeException failedRun) {
                log.atWarn().addKeyValue("event", "scheduled_recalculation_failed")
                        .addKeyValue("storeId", policy.storeId()).addKeyValue("window", window.name())
                        .log("A scheduled recalculation failed");
                record(policy, null, "RECALCULATION_FAILED", window.name(), null, null,
                        detail("window", window.name(), "failureType", failedRun.getClass().getSimpleName()));
            }
        }
        return recalculated;
    }

    /**
     * Price suggestions from the fresh seven-day findings (P8). A failure here is logged and leaves the
     * recalculation recorded as done: the suggestions are asked for again after the next one.
     *
     * @return what the pass did (new, refreshed and withdrawn suggestions), or {@code null} when it failed
     */
    private PriceSuggestionService.Result suggestPrices(Policy policy) {
        try {
            return priceSuggestions.generate(policy.organizationId(), policy.storeId());
        } catch (RuntimeException failed) {
            log.atWarn().addKeyValue("event", "scheduled_price_suggestions_failed")
                    .addKeyValue("storeId", policy.storeId())
                    .addKeyValue("failureType", failed.getClass().getSimpleName())
                    .log("Price suggestions after a scheduled recalculation failed");
            return null;
        }
    }

    /**
     * The weekly store summary (Owner decision 2026-09-29): on Mondays (UTC), once the seven-day
     * calculation covers the day's collection, the model summarizes the store once. Whether this
     * week's is done is read from the records rather than from the summary's own time, because an
     * unchanged store hands out last week's summary again. A failed attempt is retried after a pause.
     */
    private void interpretWeekly(Policy policy, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant slot = CollectionPlanner.slotStart(now, properties.getDailyAt());
        if (today.getDayOfWeek() != java.time.DayOfWeek.MONDAY
                || !LocalDate.ofInstant(slot, ZoneOffset.UTC).equals(today)) {
            return;
        }
        Optional<Instant> calculated = recalculation.latestPeriodEnd(policy.storeId(), MetricWindow.D7);
        if (calculated.isEmpty() || calculated.get().isBefore(slot)) {
            return;
        }
        Optional<Event> done = repository.latestStoreEvent(policy.storeId(), "INTERPRETED", MetricWindow.D7.name());
        if (done.isPresent() && !done.get().occurredAt().isBefore(slot)) {
            return;
        }
        Optional<Event> failed = repository.latestStoreEvent(policy.storeId(), "INTERPRETATION_FAILED",
                MetricWindow.D7.name());
        if (failed.isPresent() && failed.get().occurredAt().isAfter(now.minus(INTERPRETATION_BACKOFF))) {
            return;
        }
        try {
            AiDiagnosis summary = copilot.explainStore(null, policy.organizationId(), policy.storeId(), MetricWindow.D7);
            boolean usable = "SUCCEEDED".equals(summary.state()) || "PARTIAL_OUTPUT_REJECTED".equals(summary.state());
            record(policy, null, usable ? "INTERPRETED" : "INTERPRETATION_FAILED", MetricWindow.D7.name(), null, null,
                    detail("state", summary.state(), "reused", summary.reused(),
                            "invocationId", summary.invocationId().toString(), "failureCode", summary.failureCode()));
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("event", "scheduled_store_interpretation_failed")
                    .addKeyValue("storeId", policy.storeId())
                    .addKeyValue("failureType", failure.getClass().getSimpleName())
                    .log("The weekly store summary failed");
            record(policy, null, "INTERPRETATION_FAILED", MetricWindow.D7.name(), null, null,
                    detail("failureType", failure.getClass().getSimpleName()));
        }
    }

    /** The target a live run was made for, named like the planner names it. */
    private String keyOf(Cadence cadence, RunRecord run) {
        return switch (cadence) {
            case DAILY_SNAPSHOT -> "snapshot:" + LocalDate.ofInstant(
                    CollectionPlanner.slotStart(run.createdAt(), properties.getDailyAt()), ZoneOffset.UTC);
            case DAILY_WINDOW -> "day:" + LocalDate.ofInstant(run.windowFrom(), ZoneOffset.UTC);
            case WEEKLY_WINDOW -> "week:" + LocalDate.ofInstant(run.windowFrom(), ZoneOffset.UTC);
        };
    }

    /** Record a step, unless the job's newest record already says exactly this. */
    private void recordOnce(Policy policy, IngestionJobView job, String kind, String targetKey, UUID runId,
                            Map<String, Object> detail) {
        Optional<Event> latest = repository.latestJobEvent(job.jobId());
        if (latest.isPresent() && kind.equals(latest.get().kind()) && targetKey.equals(latest.get().targetKey())
                && java.util.Objects.equals(runId, latest.get().ingestionRunId())) {
            return;
        }
        record(policy, job, kind, targetKey, runId, null, detail);
    }

    private void record(Policy policy, IngestionJobView job, String kind, String targetKey, UUID ingestionRunId,
                        UUID calculationRunId, Map<String, Object> detail) {
        repository.insertEvent(new Event(idGenerator.newId(), policy.organizationId(), policy.storeId(), policy.id(),
                job == null ? null : job.jobId(), job == null ? null : job.datasetKind(), kind, targetKey,
                objectMapper.writeValueAsString(detail), ingestionRunId, calculationRunId, clock.instant()));
    }

    // -----------------------------------------------------------------------
    // What the console shows
    // -----------------------------------------------------------------------

    /** The store's policy, every scheduled job's state, the kept calculations and the newest records. */
    @Transactional(readOnly = true)
    public Status status(UUID organizationId, UUID storeId) {
        Instant now = clock.instant();
        Optional<Policy> policy = repository.findActivePolicy(organizationId, storeId);
        List<JobStatus> jobStatuses = new ArrayList<>();
        Instant credentialExpiresAt = null;
        for (IngestionJobView job : scheduledJobs(organizationId, storeId)) {
            Optional<Instant> expiry = acquisition.credentialExpiresAt(job.jobId());
            if (expiry.isPresent() && (credentialExpiresAt == null || expiry.get().isBefore(credentialExpiresAt))) {
                credentialExpiresAt = expiry.get();
            }
            Optional<RunRecord> live = acquisition.liveRun(job.jobId());
            Cadence cadence = CollectionPlanner.CADENCES.get(job.datasetKind());
            List<RunRecord> runs = acquisition.runsSince(job.jobId(), now.minus(CollectionPlanner.HISTORY));
            Optional<Target> due = CollectionPlanner.due(cadence, now, properties.getDailyAt(), runs);
            Optional<Target> upcoming = CollectionPlanner.upcoming(cadence, now, properties.getDailyAt(), runs);
            jobStatuses.add(new JobStatus(job.jobId(), job.datasetKind(), job.jobCode(), cadence.name(),
                    runs.stream().filter(RunRecord::succeeded).findFirst().map(RunView::of).orElse(null),
                    live.map(RunView::of).orElse(null),
                    live.flatMap(run -> acquisition.lastAnswerStatus(run.runId())).orElse(null),
                    acquisition.evidenceValidUntil(job.jobId()).orElse(null),
                    due.map(TargetView::of).orElse(null),
                    due.map(target -> CollectionPlanner.attempts(target, runs).size()).orElse(0),
                    upcoming.map(TargetView::of).orElse(null),
                    repository.latestJobEvent(job.jobId()).map(this::view).orElse(null)));
        }
        List<CalculationStatus> calculations = WINDOWS.stream()
                .map(window -> new CalculationStatus(window.name(),
                        recalculation.latestPeriodEnd(storeId, window).orElse(null)))
                .toList();
        return new Status(storeId, properties.isEnabled(), properties.getDailyAt().toString(),
                policy.map(PolicyView::of).orElse(null), credentialExpiresAt, jobStatuses, calculations,
                repository.recentEvents(storeId, RECENT_EVENTS).stream().map(this::view).toList());
    }

    private EventView view(Event event) {
        Map<String, Object> detail;
        try {
            detail = JsonValues.object(JsonValues.read(objectMapper, event.detail()));
        } catch (RuntimeException unreadable) {
            detail = Map.of();
        }
        return new EventView(event.id(), event.jobId(), event.datasetKind(), event.kind(), event.targetKey(), detail,
                event.ingestionRunId(), event.calculationRunId(), event.occurredAt());
    }

    private static Map<String, Object> detail(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            if (pairs[index + 1] != null) {
                detail.put((String) pairs[index], pairs[index + 1]);
            }
        }
        return detail;
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Instant later(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        return right == null || left.isAfter(right) ? left : right;
    }

    /** What one pass did. */
    public record PassSummary(int executed, int recalculated) {
    }

    private record StoreOutcome(int executed, int recalculated) {
    }

    /**
     * The collection state of one store.
     *
     * @param schedulerEnabled whether this backend's timer is switched on at all
     * @param dailyAtUtc when each UTC day's collection slot starts
     * @param policy {@code null} when scheduled collection is not in force
     * @param credentialExpiresAt until when the read credential is in force, or {@code null} when none is
     */
    public record Status(UUID storeId, boolean schedulerEnabled, String dailyAtUtc, PolicyView policy,
                         Instant credentialExpiresAt, List<JobStatus> jobs, List<CalculationStatus> calculations,
                         List<EventView> events) {
    }

    /** The policy in force. */
    public record PolicyView(UUID policyId, UUID authorizedByUserId, Instant authorizedAt, String reason,
                             long version) {

        static PolicyView of(Policy policy) {
            return new PolicyView(policy.id(), policy.authorizedByUserId(), policy.authorizedAt(), policy.reason(),
                    policy.version());
        }
    }

    /**
     * One scheduled job.
     *
     * @param cadence DAILY_SNAPSHOT, DAILY_WINDOW or WEEKLY_WINDOW
     * @param lastSucceeded the newest run that succeeded, of any kind, or {@code null}
     * @param liveRun the run that has not come to rest, or {@code null}
     * @param liveRunLastAnswer the native status of the live run's newest answer ("HTTP 403"), or
     *        {@code null}; it says why a blocked run stopped
     * @param evidenceValidUntil {@code null} when no evidence is current: nothing is collected then
     * @param due what the current slot still asks for, or {@code null}
     * @param attempts how many scheduled runs were made for {@code due}
     * @param upcoming what a later slot asks for next, or {@code null}
     */
    public record JobStatus(UUID jobId, String datasetKind, String jobCode, String cadence, RunView lastSucceeded,
                            RunView liveRun, String liveRunLastAnswer, Instant evidenceValidUntil, TargetView due,
                            int attempts,
                            TargetView upcoming, EventView lastEvent) {
    }

    /** One run as the console shows it. */
    public record RunView(UUID runId, String runKind, String state, Instant windowFrom, Instant windowTo,
                          String failureCode, Instant createdAt, Instant updatedAt, Instant nextAttemptAt) {

        static RunView of(RunRecord run) {
            return new RunView(run.runId(), run.runKind(), run.state(), run.windowFrom(), run.windowTo(),
                    run.failureCode(), run.createdAt(), run.updatedAt(), run.nextAttemptAt());
        }
    }

    /** What a slot asks for. */
    public record TargetView(String key, Instant slotStart, Instant windowFrom, Instant windowTo) {

        static TargetView of(Target target) {
            return new TargetView(target.key(), target.slotStart(), target.windowFrom(), target.windowTo());
        }
    }

    /** Where the store's newest calculation over a window ended, or {@code null}. */
    public record CalculationStatus(String window, Instant latestPeriodEnd) {
    }

    /** One record of what the scheduler did. */
    public record EventView(UUID eventId, UUID jobId, String datasetKind, String kind, String targetKey,
                            Map<String, Object> detail, UUID ingestionRunId, UUID calculationRunId,
                            Instant occurredAt) {
    }
}
