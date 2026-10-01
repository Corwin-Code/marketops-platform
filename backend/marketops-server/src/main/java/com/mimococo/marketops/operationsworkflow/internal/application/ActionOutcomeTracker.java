package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.marketplaceintegration.PriceCommandGateway;
import com.mimococo.marketops.marketplaceintegration.PriceCommandView;
import com.mimococo.marketops.operatingfacts.FactWindow;
import com.mimococo.marketops.operatingfacts.ListingWindowRecord;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.SearchDemandSnapshot;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.domain.ActionOutcomeRules;
import com.mimococo.marketops.operationsworkflow.internal.domain.ActionOutcomeRules.During;
import com.mimococo.marketops.operationsworkflow.internal.domain.ActionOutcomeRules.Judgement;
import com.mimococo.marketops.operationsworkflow.internal.domain.ActionOutcomeRules.Side;
import com.mimococo.marketops.operationsworkflow.internal.domain.ActionOutcomeRules.Stage;
import com.mimococo.marketops.operationsworkflow.internal.domain.ActionOutcomeRules.Windows;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ActionOutcomeRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ActionOutcomeRepository.Outcome;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ActionOutcomeRepository.Reading;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Follows executed actions and judges them against the period before them (P10, Owner decisions
 * 2026-10-01).
 *
 * <p>Each pass of a store's scheduled collection, once the store's facts have settled:
 * <ol>
 *   <li>registers what was done: a price command that succeeded, a price changed by hand in the seller
 *       office, a promotion joined or left;</li>
 *   <li>moves the proposal a succeeded command executed from EXECUTION_TRACKING to OUTCOME_OBSERVATION;</li>
 *   <li>reads each stage that is due: the preliminary seven-against-seven days and the final
 *       fourteen-against-fourteen, once the store's daily order facts reach the window's last day, or
 *       a few days later whatever they hold;</li>
 *   <li>after the final reading, closes the proposal (reason OUTCOME_RECORDED), so the listing can be
 *       suggested again once its cooldown has passed.</li>
 * </ol>
 *
 * <p>Nothing here runs in one transaction: every insert is idempotent and every proposal move is its
 * own, so one listing that cannot be read or moved holds back none of the others.
 */
@Service
public class ActionOutcomeTracker {

    /** Audit actor of the proposal moves this makes. */
    static final String OPERATOR = "action-outcome";

    /** The terminal reason of a proposal closed after its effect was recorded. */
    static final String CLOSED_REASON = "OUTCOME_RECORDED";

    /** How many actions one pass looks at per store. */
    private static final int BATCH = 200;

    /** A stage is read this many days after its last day even when the order facts never reached it. */
    private static final int GRACE_DAYS = 4;

    private static final Logger log = LoggerFactory.getLogger(ActionOutcomeTracker.class);

    private final ActionOutcomeRepository outcomes;
    private final PriceCommandGateway commands;
    private final OperatingFactQuery facts;
    private final RecommendationService recommendations;
    private final IdGenerator idGenerator;
    private final Clock clock;

    ActionOutcomeTracker(ActionOutcomeRepository outcomes, PriceCommandGateway commands, OperatingFactQuery facts,
                         RecommendationService recommendations, IdGenerator idGenerator, Clock clock) {
        this.outcomes = outcomes;
        this.commands = commands;
        this.facts = facts;
        this.recommendations = recommendations;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * What one pass did.
     *
     * @param registered actions newly followed
     * @param observing proposals moved to OUTCOME_OBSERVATION
     * @param readings stage readings recorded
     * @param closed proposals closed after their final reading
     */
    public record Pass(int registered, int observing, int readings, int closed) {
    }

    /** Follow the store's actions one step further. */
    public Pass track(UUID organizationId, UUID storeId) {
        int registered = register(organizationId, storeId);
        int observing = observe(storeId);
        int readings = 0;
        int closed = 0;
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (Outcome outcome : outcomes.awaitingFinal(storeId, BATCH)) {
            try {
                Set<String> recorded = outcomes.readings(List.of(outcome.id())).stream()
                        .map(Reading::stage).collect(Collectors.toSet());
                for (Stage stage : Stage.values()) {
                    if (!recorded.contains(stage.name()) && due(storeId, outcome, stage, today)
                            && outcomes.recordReading(read(outcome, stage))) {
                        readings++;
                        if (stage == Stage.FINAL && close(outcome)) {
                            closed++;
                        }
                    }
                }
            } catch (RuntimeException failed) {
                log.atWarn().addKeyValue("event", "action_outcome_reading_failed")
                        .addKeyValue("outcomeId", outcome.id())
                        .addKeyValue("failureType", failed.getClass().getSimpleName())
                        .log("An action's effect could not be read");
            }
        }
        return new Pass(registered, observing, readings, closed);
    }

    /** Register the store's actions not followed yet. */
    private int register(UUID organizationId, UUID storeId) {
        Instant now = clock.instant();
        int registered = 0;
        for (PriceCommandView command : commands.succeeded(storeId, BATCH)) {
            if (command.terminalAt() == null || outcomes.registered("PRICE_COMMAND", command.id())) {
                continue;
            }
            if (outcomes.register(idGenerator.newId(), organizationId, storeId, "PRICE_COMMAND", command.id(),
                    command.recommendationId(), command.platformListingVariantId(), "PRICE_CHANGE",
                    command.terminalAt(), command.priorPrice(), command.targetPrice(), command.currencyCode(), now)) {
                registered++;
            }
        }
        for (ActionOutcomeRepository.DecisionSource decision : outcomes.unregisteredPriceDecisions(storeId)) {
            if (outcomes.register(idGenerator.newId(), decision.organizationId(), storeId, "PRICE_DECISION",
                    decision.id(), decision.recommendationId(), decision.listingVariantId(), decision.actionKind(),
                    decision.actedAt(), decision.priorPrice(), decision.targetPrice(), decision.currencyCode(), now)) {
                registered++;
            }
        }
        for (ActionOutcomeRepository.DecisionSource decision : outcomes.unregisteredPromotionDecisions(storeId)) {
            if (outcomes.register(idGenerator.newId(), decision.organizationId(), storeId, "PROMOTION_DECISION",
                    decision.id(), null, decision.listingVariantId(), decision.actionKind(), decision.actedAt(),
                    null, decision.targetPrice(), decision.currencyCode(), now)) {
                registered++;
            }
        }
        return registered;
    }

    /** Move the proposals whose succeeded command is now followed to OUTCOME_OBSERVATION. */
    private int observe(UUID storeId) {
        int moved = 0;
        for (RecommendationView proposal : recommendations.queue(storeId,
                List.of(RecommendationState.EXECUTION_TRACKING), BATCH)) {
            boolean followed = outcomes.forRecommendation(proposal.id())
                    .filter(outcome -> "PRICE_COMMAND".equals(outcome.actionSource()))
                    .isPresent();
            if (followed && move(proposal, RecommendationState.OUTCOME_OBSERVATION, null)) {
                moved++;
            }
        }
        return moved;
    }

    /** Close the proposal a followed command executed, once its final reading is recorded. */
    private boolean close(Outcome outcome) {
        if (!"PRICE_COMMAND".equals(outcome.actionSource()) || outcome.recommendationId() == null) {
            return false;
        }
        return recommendations.find(outcome.recommendationId())
                .filter(proposal -> proposal.state() == RecommendationState.OUTCOME_OBSERVATION
                        || proposal.state() == RecommendationState.EXECUTION_TRACKING)
                .map(proposal -> move(proposal, RecommendationState.CLOSED, CLOSED_REASON))
                .orElse(false);
    }

    private boolean move(RecommendationView proposal, RecommendationState to, String reason) {
        try {
            recommendations.transition(OPERATOR, proposal.id(), to, reason, proposal.version());
            return true;
        } catch (OperationRejectedException conflict) {
            // Somebody moved the proposal meanwhile; the next pass reads it again.
            return false;
        }
    }

    /**
     * Whether a stage can be read: its windows have ended and the store's daily order facts reach its
     * last day, or the grace days after it have passed whatever the facts hold.
     */
    private boolean due(UUID storeId, Outcome outcome, Stage stage, LocalDate today) {
        LocalDate last = Windows.around(outcome.actedOn(), stage).observationTo();
        if (!today.isAfter(last)) {
            return false;
        }
        return !today.isBefore(last.plusDays(GRACE_DAYS))
                || !facts.storeOrderDays(storeId, day(last, last)).isEmpty();
    }

    /** Read one stage of one followed action from the facts. */
    private Reading read(Outcome outcome, Stage stage) {
        Windows windows = Windows.around(outcome.actedOn(), stage);
        FactWindow before = day(windows.baselineFrom(), windows.baselineTo());
        FactWindow after = day(windows.observationFrom(), windows.observationTo());
        ListingWindowRecord beforeRecord = facts.windowRecord(outcome.listingVariantId(), before);
        ListingWindowRecord afterRecord = facts.windowRecord(outcome.listingVariantId(), after);
        Side beforeSide = side(outcome, before, beforeRecord);
        Side afterSide = side(outcome, after, afterRecord);

        boolean priceAction = "PRICE_CHANGE".equals(outcome.actionKind());
        List<ListingWindowRecord.PricePoint> prices = afterRecord.prices().stream()
                .filter(point -> point.buyerPrice() != null)
                .filter(point -> outcome.currencyCode() == null || outcome.currencyCode().equals(point.currencyCode()))
                .toList();
        BigDecimal priceMin = prices.stream().map(ListingWindowRecord.PricePoint::buyerPrice)
                .min(BigDecimal::compareTo).orElse(null);
        BigDecimal priceMax = prices.stream().map(ListingWindowRecord.PricePoint::buyerPrice)
                .max(BigDecimal::compareTo).orElse(null);
        Boolean priceHeld = !priceAction || prices.isEmpty() ? null
                : prices.stream().allMatch(point -> point.buyerPrice().compareTo(outcome.targetPrice()) == 0);
        boolean promotion = priceAction && afterRecord.prices().stream()
                .anyMatch(ListingWindowRecord.PricePoint::sellerPromotion);
        boolean stockout = afterRecord.stock().stream()
                        .anyMatch(point -> point.availableUnits() != null && point.availableUnits() <= 0)
                || afterRecord.sellability().stream().anyMatch(point -> "NO".equals(point.sellable()));
        boolean otherAction = outcomes.otherActionBetween(outcome.id(), outcome.listingVariantId(),
                windows.baselineFrom(), windows.observationTo());

        Judgement judgement = ActionOutcomeRules.judge(stage, beforeSide, afterSide,
                new During(stockout, promotion, priceHeld, otherAction));
        return new Reading(idGenerator.newId(), outcome.id(), outcome.organizationId(), stage.name(), stage.days(),
                windows.baselineFrom(), windows.baselineTo(), windows.observationFrom(), windows.observationTo(),
                beforeSide.daysCovered(), afterSide.daysCovered(), beforeSide.orderedUnits(),
                afterSide.orderedUnits(), beforeSide.searchUsers(), afterSide.searchUsers(),
                beforeSide.priceIndex(), afterSide.priceIndex(), priceMin, priceMax, stockout, promotion,
                priceHeld, otherAction, judgement.verdict(), judgement.leadingSignal(), judgement.reasons(),
                ActionOutcomeRules.RULE_VERSION, clock.instant());
    }

    /**
     * What one window held for the listing: the covered days are the store's, the units the listing's
     * over those days (a covered day without a record of the listing is a day nothing was reported for
     * it, as the store diagnosis counts it), the newest search period inside, the newest index class.
     */
    private Side side(Outcome outcome, FactWindow window, ListingWindowRecord record) {
        Set<LocalDate> covered = new HashSet<>(facts.storeOrderDays(outcome.storeId(), window));
        Long units = covered.isEmpty() ? null : record.orders().stream()
                .filter(day -> covered.contains(day.day()))
                .mapToLong(ListingWindowRecord.DayOrders::orderedUnits)
                .sum();
        List<SearchDemandSnapshot> searches = facts.searchDemandWithin(outcome.listingVariantId(), window);
        Long searchUsers = searches.isEmpty() ? null : searches.getLast().searchUsers();
        String index = record.prices().stream()
                .map(ListingWindowRecord.PricePoint::priceIndexNative)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .reduce((older, newer) -> newer)
                .orElse(null);
        return new Side(covered.size(), units, searchUsers, index);
    }

    /** The instants of an inclusive span of UTC days. */
    private static FactWindow day(LocalDate first, LocalDate last) {
        return new FactWindow(first.atStartOfDay(ZoneOffset.UTC).toInstant(),
                last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /** The store's followed actions with their readings, newest first. */
    public List<FollowedAction> forStore(UUID organizationId, UUID storeId, int limit) {
        return withReadings(outcomes.forStore(organizationId, storeId, Math.max(1, Math.min(limit, BATCH))));
    }

    /** The followed action of one price command, if it is followed. */
    public java.util.Optional<FollowedAction> forPriceCommand(UUID commandId) {
        return outcomes.bySource("PRICE_COMMAND", commandId).map(outcome -> withReadings(List.of(outcome)).getFirst());
    }

    private List<FollowedAction> withReadings(List<Outcome> found) {
        Map<UUID, List<Reading>> readings = outcomes.readings(found.stream().map(Outcome::id).toList()).stream()
                .collect(Collectors.groupingBy(Reading::outcomeId));
        return found.stream().map(outcome -> {
            Map<String, Reading> byStage = readings.getOrDefault(outcome.id(), List.of()).stream()
                    .collect(Collectors.toMap(Reading::stage, Function.identity()));
            String proposalState = outcome.recommendationId() == null ? null
                    : recommendations.find(outcome.recommendationId())
                            .map(proposal -> proposal.state().name()).orElse(null);
            return new FollowedAction(outcome, proposalState,
                    Windows.around(outcome.actedOn(), Stage.PRELIMINARY).observationTo().plusDays(2),
                    Windows.around(outcome.actedOn(), Stage.FINAL).observationTo().plusDays(2),
                    byStage.get(Stage.PRELIMINARY.name()), byStage.get(Stage.FINAL.name()));
        }).toList();
    }

    /**
     * One followed action with what is known of its effect.
     *
     * @param outcome the action
     * @param proposalState the state of the proposal it executed or answered, if any
     * @param preliminaryDueOn when the preliminary reading is expected (two days after its window)
     * @param finalDueOn when the final reading is expected
     * @param preliminary the preliminary reading, once recorded
     * @param finalReading the final reading, once recorded
     */
    public record FollowedAction(Outcome outcome, String proposalState, LocalDate preliminaryDueOn,
                                 LocalDate finalDueOn, Reading preliminary, Reading finalReading) {
    }
}
