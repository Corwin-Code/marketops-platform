package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.aicopilot.AiCopilot;
import com.mimococo.marketops.aicopilot.AiDiagnosis;
import com.mimococo.marketops.aicopilot.WeeklyReviewInput;
import com.mimococo.marketops.operatingfacts.FactWindow;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.StoreOrderTotals;
import com.mimococo.marketops.operationsworkflow.internal.application.ActionOutcomeTracker.FollowedAction;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ActionOutcomeRepository.Outcome;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ActionOutcomeRepository.Reading;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WeeklyReviewRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WeeklyReviewRepository.Row;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The weekly review of a store's followed actions (P10, Owner decisions 2026-10-01).
 *
 * <p>A week's snapshot keeps the store's newest seven days of daily order facts, how many followed
 * actions took effect and how many readings came in during the week, how many actions still wait for
 * their verdict, how the final verdicts stand, and the actions themselves, most telling first: a
 * final reading of the week, a preliminary one, an action of the week, then the ones still observed.
 * A model then writes what worked and what to adjust from it; only the platform's counts, ratios and
 * verdicts leave.
 *
 * <p>Every Monday the scheduler takes the week just ended; a person may ask for the week still
 * running, which is provisional and is taken again until a snapshot is taken after the week ended.
 */
@Service
public class WeeklyReviewService {

    /** How many followed actions a week's snapshot reads. */
    private static final int ACTIONS_READ = 200;

    /** How many actions a snapshot keeps. */
    private static final int ACTIONS_KEPT = 20;

    private static final TypeReference<List<SnapshotAction>> ACTIONS = new TypeReference<>() {
    };

    private final WeeklyReviewRepository reviews;
    private final ActionOutcomeTracker outcomes;
    private final OperatingFactQuery facts;
    private final AiCopilot copilot;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    WeeklyReviewService(WeeklyReviewRepository reviews, ActionOutcomeTracker outcomes, OperatingFactQuery facts,
                        AiCopilot copilot, ObjectMapper objectMapper, IdGenerator idGenerator, Clock clock) {
        this.reviews = reviews;
        this.outcomes = outcomes;
        this.facts = facts;
        this.copilot = copilot;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** The Monday (UTC) of the week a day belongs to. */
    public static LocalDate weekOf(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * Take a week's snapshot, unless a final one is kept, and ask the model about it when it names an
     * action; a week without one is kept without asking.
     */
    public Review review(UUID requestedByUserId, UUID organizationId, UUID storeId, LocalDate weekStart) {
        Row row = compile(organizationId, storeId, weekStart);
        List<SnapshotAction> actions = actions(row);
        if (!actions.isEmpty() && row.aiInvocationId() == null) {
            AiDiagnosis answer = copilot.reviewWeek(requestedByUserId, organizationId, storeId, input(row, actions));
            reviews.pointAt(row.id(), answer.invocationId());
            row = reviews.find(storeId, weekStart).orElseThrow();
        }
        return view(row);
    }

    /**
     * Whether a week's review is done: a final snapshot is kept and the model was asked about it, or it
     * names no action to ask about.
     */
    public boolean done(UUID storeId, LocalDate weekStart) {
        return reviews.find(storeId, weekStart)
                .filter(WeeklyReviewService::isFinal)
                .filter(row -> row.aiInvocationId() != null || actions(row).isEmpty())
                .isPresent();
    }

    /** The store's newest snapshots, newest week first. */
    public List<Review> recent(UUID organizationId, UUID storeId, int limit) {
        return reviews.recent(organizationId, storeId, Math.max(1, Math.min(limit, 52))).stream()
                .map(this::view).toList();
    }

    /** The kept snapshot of a week when it is final; otherwise a snapshot taken now. */
    private Row compile(UUID organizationId, UUID storeId, LocalDate weekStart) {
        java.util.Optional<Row> kept = reviews.find(storeId, weekStart);
        if (kept.isPresent() && isFinal(kept.get())) {
            return kept.get();
        }
        Row taken = take(organizationId, storeId, weekStart);
        if (kept.isEmpty()) {
            reviews.insert(taken);
        } else {
            reviews.retake(taken, finalFrom(weekStart));
        }
        return reviews.find(storeId, weekStart).orElseThrow();
    }

    /** A snapshot is final once it was taken after its week ended. */
    private static boolean isFinal(Row row) {
        return !row.compiledAt().isBefore(finalFrom(row.weekStart()));
    }

    private static Instant finalFrom(LocalDate weekStart) {
        return weekStart.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** What the store's facts and followed actions say about a week now. */
    private Row take(UUID organizationId, UUID storeId, LocalDate weekStart) {
        LocalDate weekEnd = weekStart.plusDays(6);
        Instant now = clock.instant();
        List<FollowedAction> followed = outcomes.forStore(organizationId, storeId, ACTIONS_READ);
        int acted = (int) followed.stream().filter(action -> within(action.outcome().actedOn(), weekStart, weekEnd))
                .count();
        int readings = (int) followed.stream()
                .flatMap(action -> java.util.stream.Stream.of(action.preliminary(), action.finalReading()))
                .filter(java.util.Objects::nonNull)
                .filter(reading -> within(day(reading.computedAt()), weekStart, weekEnd))
                .count();
        int observing = (int) followed.stream()
                .filter(action -> action.finalReading() == null && !action.outcome().actedOn().isAfter(weekEnd))
                .count();
        List<Reading> finals = followed.stream().map(FollowedAction::finalReading)
                .filter(java.util.Objects::nonNull)
                .filter(reading -> !day(reading.computedAt()).isAfter(weekEnd))
                .toList();
        List<SnapshotAction> actions = followed.stream()
                .filter(action -> !action.outcome().actedOn().isAfter(weekEnd))
                .filter(action -> rank(action, weekStart, weekEnd) < 4)
                .sorted(Comparator.comparingInt((FollowedAction action) -> rank(action, weekStart, weekEnd))
                        .thenComparing(action -> action.outcome().actedAt(), Comparator.reverseOrder()))
                .limit(ACTIONS_KEPT)
                .map(action -> snapshot(action, weekEnd))
                .toList();

        // The newest seven days of daily order facts up to the week's end; they arrive two days late.
        LocalDate ordersTo = facts.storeOrderDays(storeId, days(weekStart.minusDays(7), weekEnd)).stream()
                .max(Comparator.naturalOrder()).orElse(null);
        LocalDate ordersFrom = ordersTo == null ? null : ordersTo.minusDays(6);
        StoreOrderTotals orders = ordersTo == null ? new StoreOrderTotals(0, null, null)
                : facts.storeOrders(storeId, days(ordersFrom, ordersTo));
        return new Row(idGenerator.newId(), organizationId, storeId, weekStart, weekEnd, ordersFrom, ordersTo,
                orders.daysCovered(), orders.orderedUnits(), orders.listingsWithOrders(), acted, readings,
                observing, count(finals, "IMPROVED"), count(finals, "UNCHANGED"), count(finals, "REGRESSED"),
                count(finals, "INDETERMINATE"), objectMapper.writeValueAsString(actions), null, now);
    }

    /**
     * How telling an action is for the week: 0 a final reading of the week, 1 a preliminary one, 2 an
     * action that took effect in the week, 3 one still observed; 4 one judged before the week (left out).
     */
    private static int rank(FollowedAction action, LocalDate weekStart, LocalDate weekEnd) {
        if (action.finalReading() != null && within(day(action.finalReading().computedAt()), weekStart, weekEnd)) {
            return 0;
        }
        if (action.preliminary() != null && within(day(action.preliminary().computedAt()), weekStart, weekEnd)) {
            return 1;
        }
        if (within(action.outcome().actedOn(), weekStart, weekEnd)) {
            return 2;
        }
        return action.finalReading() == null || day(action.finalReading().computedAt()).isAfter(weekEnd) ? 3 : 4;
    }

    /** One action as the snapshot keeps it: its newest reading up to the week's end. */
    private static SnapshotAction snapshot(FollowedAction action, LocalDate weekEnd) {
        Outcome outcome = action.outcome();
        Reading reading = action.finalReading() != null && !day(action.finalReading().computedAt()).isAfter(weekEnd)
                ? action.finalReading()
                : action.preliminary() != null && !day(action.preliminary().computedAt()).isAfter(weekEnd)
                        ? action.preliminary() : null;
        BigDecimal priceChange = outcome.priorPrice() == null || outcome.targetPrice() == null
                || outcome.priorPrice().signum() <= 0 ? null
                : outcome.targetPrice().divide(outcome.priorPrice(), 6, RoundingMode.HALF_UP)
                        .subtract(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
        BigDecimal searchChange = reading == null || reading.baselineSearchUsers() == null
                || reading.observationSearchUsers() == null || reading.baselineSearchUsers() <= 0 ? null
                : BigDecimal.valueOf(reading.observationSearchUsers())
                        .divide(BigDecimal.valueOf(reading.baselineSearchUsers()), 6, RoundingMode.HALF_UP)
                        .subtract(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
        return new SnapshotAction(outcome.id(), outcome.listingVariantId(), outcome.offerId(), outcome.title(),
                outcome.actionKind(), outcome.actionSource(), outcome.actedOn(), outcome.priorPrice(),
                outcome.targetPrice(), outcome.currencyCode(), priceChange,
                reading == null ? "NONE" : reading.stage(), reading == null ? "OBSERVING" : reading.verdict(),
                reading == null ? null : reading.leadingSignal(),
                reading == null ? List.of() : reading.reasonCodes(),
                reading == null ? null : reading.baselineOrderedUnits(),
                reading == null ? null : reading.observationOrderedUnits(),
                reading == null ? null : reading.baselineDaysCovered(),
                reading == null ? null : reading.observationDaysCovered(),
                reading == null ? null : reading.baselineSearchUsers(),
                reading == null ? null : reading.observationSearchUsers(), searchChange,
                reading == null ? null : reading.baselinePriceIndex(),
                reading == null ? null : reading.observationPriceIndex(),
                action.preliminaryDueOn(), action.finalDueOn());
    }

    private static WeeklyReviewInput input(Row row, List<SnapshotAction> actions) {
        return new WeeklyReviewInput(row.weekStart(), row.weekEnd(), isFinal(row), row.ordersFrom(), row.ordersTo(),
                row.ordersDaysCovered(), row.orderedUnits(), row.listingsWithOrders(), row.actionsActed(),
                row.readingsRecorded(), row.actionsObserving(), row.improvedCount(), row.unchangedCount(),
                row.regressedCount(), row.indeterminateCount(),
                actions.stream().map(action -> new WeeklyReviewInput.Action(action.actionRef(),
                        action.listingVariantId(), action.actionKind(), action.source(), action.actedOn(),
                        action.priceChangeRate(), action.stage(), action.verdict(), action.leadingSignal(),
                        action.reasons(), action.ordersBefore(), action.ordersAfter(), action.daysBefore(),
                        action.daysAfter(), action.searchChangeRate(), action.priceIndexBefore(),
                        action.priceIndexAfter(), action.preliminaryDueOn(), action.finalDueOn())).toList());
    }

    private List<SnapshotAction> actions(Row row) {
        return objectMapper.readValue(row.actionsJson(), ACTIONS);
    }

    private Review view(Row row) {
        return new Review(row.id(), row.weekStart(), row.weekEnd(), isFinal(row), row.ordersFrom(), row.ordersTo(),
                row.ordersDaysCovered(), row.orderedUnits(), row.listingsWithOrders(), row.actionsActed(),
                row.readingsRecorded(), row.actionsObserving(), row.improvedCount(), row.unchangedCount(),
                row.regressedCount(), row.indeterminateCount(), actions(row), row.aiInvocationId(), row.compiledAt());
    }

    private static int count(List<Reading> readings, String verdict) {
        return (int) readings.stream().filter(reading -> verdict.equals(reading.verdict())).count();
    }

    private static boolean within(LocalDate day, LocalDate first, LocalDate last) {
        return !day.isBefore(first) && !day.isAfter(last);
    }

    private static LocalDate day(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }

    private static FactWindow days(LocalDate first, LocalDate last) {
        return new FactWindow(first.atStartOfDay(ZoneOffset.UTC).toInstant(),
                last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /** One action as a week's snapshot keeps it. */
    public record SnapshotAction(UUID actionRef, UUID listingVariantId, String offerId, String title,
                                 String actionKind, String source, LocalDate actedOn, BigDecimal priorPrice,
                                 BigDecimal targetPrice, String currencyCode, BigDecimal priceChangeRate,
                                 String stage, String verdict, String leadingSignal, List<String> reasons,
                                 Long ordersBefore, Long ordersAfter, Integer daysBefore, Integer daysAfter,
                                 Long searchUsersBefore, Long searchUsersAfter, BigDecimal searchChangeRate,
                                 String priceIndexBefore, String priceIndexAfter, LocalDate preliminaryDueOn,
                                 LocalDate finalDueOn) {
    }

    /** One week's snapshot, with the model answer it points at. */
    public record Review(UUID id, LocalDate weekStart, LocalDate weekEnd, boolean weekComplete,
                         LocalDate ordersFrom, LocalDate ordersTo, int ordersDaysCovered, Long orderedUnits,
                         Integer listingsWithOrders, int actionsActed, int readingsRecorded, int actionsObserving,
                         int improvedCount, int unchangedCount, int regressedCount, int indeterminateCount,
                         List<SnapshotAction> actions, UUID aiInvocationId, Instant compiledAt) {
    }
}
