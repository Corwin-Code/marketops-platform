package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.internal.application.ActionOutcomeTracker;
import com.mimococo.marketops.operationsworkflow.internal.application.ActionOutcomeTracker.FollowedAction;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ActionOutcomeRepository.Outcome;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ActionOutcomeRepository.Reading;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The effect of executed actions (P10): every followed action of a store with its before/after
 * readings, and the one of a single price command for its timeline.
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/outcomes")
class ActionOutcomeConsoleController {

    private final ActionOutcomeTracker tracker;
    private final BusinessAuthorization authorization;

    ActionOutcomeConsoleController(ActionOutcomeTracker tracker, BusinessAuthorization authorization) {
        this.tracker = tracker;
        this.authorization = authorization;
    }

    /** The store's followed actions, newest first. */
    @GetMapping(value = "/stores/{storeId}", produces = MediaType.APPLICATION_JSON_VALUE)
    List<FollowedActionView> store(AuthenticatedActor actor, @PathVariable UUID storeId,
                                   @RequestParam(required = false, defaultValue = "100") int limit) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        return tracker.forStore(actor.organizationId(), storeId, limit).stream().map(FollowedActionView::of).toList();
    }

    /** The followed action of one price command; {@code followed} is false while it is not followed. */
    @GetMapping(value = "/price-commands/{commandId}", produces = MediaType.APPLICATION_JSON_VALUE)
    CommandOutcomeView priceCommand(AuthenticatedActor actor, @PathVariable UUID commandId) {
        return tracker.forPriceCommand(commandId)
                .map(found -> {
                    authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                            ResourceScope.store(found.outcome().storeId()));
                    return new CommandOutcomeView(true, FollowedActionView.of(found));
                })
                .orElseGet(() -> new CommandOutcomeView(false, null));
    }

    record CommandOutcomeView(boolean followed, FollowedActionView action) {
    }

    record FollowedActionView(UUID id, String actionSource, String actionKind, UUID sourceId, UUID recommendationId,
                              String proposalState, UUID listingVariantId, String offerId, String title,
                              Instant actedAt, LocalDate actedOn, BigDecimal priorPrice, BigDecimal targetPrice,
                              String currencyCode, LocalDate preliminaryDueOn, LocalDate finalDueOn,
                              ReadingView preliminary, ReadingView finalReading) {

        static FollowedActionView of(FollowedAction action) {
            Outcome outcome = action.outcome();
            return new FollowedActionView(outcome.id(), outcome.actionSource(), outcome.actionKind(),
                    outcome.sourceId(), outcome.recommendationId(), action.proposalState(),
                    outcome.listingVariantId(), outcome.offerId(), outcome.title(), outcome.actedAt(),
                    outcome.actedOn(), outcome.priorPrice(), outcome.targetPrice(), outcome.currencyCode(),
                    action.preliminaryDueOn(), action.finalDueOn(), ReadingView.of(action.preliminary()),
                    ReadingView.of(action.finalReading()));
        }
    }

    record ReadingView(String stage, int windowDays, LocalDate baselineFrom, LocalDate baselineTo,
                       LocalDate observationFrom, LocalDate observationTo, int baselineDaysCovered,
                       int observationDaysCovered, Long baselineOrderedUnits, Long observationOrderedUnits,
                       Long baselineSearchUsers, Long observationSearchUsers, String baselinePriceIndex,
                       String observationPriceIndex, BigDecimal observationBuyerPriceMin,
                       BigDecimal observationBuyerPriceMax, boolean stockoutObserved, boolean promotionObserved,
                       Boolean priceHeld, boolean otherActionObserved, String verdict, String leadingSignal,
                       List<String> reasonCodes, int ruleVersion, Instant computedAt) {

        static ReadingView of(Reading reading) {
            if (reading == null) {
                return null;
            }
            return new ReadingView(reading.stage(), reading.windowDays(), reading.baselineFrom(),
                    reading.baselineTo(), reading.observationFrom(), reading.observationTo(),
                    reading.baselineDaysCovered(), reading.observationDaysCovered(),
                    reading.baselineOrderedUnits(), reading.observationOrderedUnits(),
                    reading.baselineSearchUsers(), reading.observationSearchUsers(),
                    reading.baselinePriceIndex(), reading.observationPriceIndex(),
                    reading.observationBuyerPriceMin(), reading.observationBuyerPriceMax(),
                    reading.stockoutObserved(), reading.promotionObserved(), reading.priceHeld(),
                    reading.otherActionObserved(), reading.verdict(), reading.leadingSignal(),
                    reading.reasonCodes(), reading.ruleVersion(), reading.computedAt());
        }
    }
}
