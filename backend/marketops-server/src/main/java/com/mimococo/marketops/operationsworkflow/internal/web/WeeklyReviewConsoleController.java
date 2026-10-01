package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.internal.application.WeeklyReviewService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The weekly review (P10): a store's weekly snapshots of followed actions, and asking for the week
 * still running. The model's answer is read like every other one, from the explanations it is
 * recorded under.
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/outcomes")
class WeeklyReviewConsoleController {

    private final WeeklyReviewService reviews;
    private final BusinessAuthorization authorization;
    private final Clock clock;

    WeeklyReviewConsoleController(WeeklyReviewService reviews, BusinessAuthorization authorization, Clock clock) {
        this.reviews = reviews;
        this.authorization = authorization;
        this.clock = clock;
    }

    /** The store's newest weekly snapshots, newest week first. */
    @GetMapping(value = "/stores/{storeId}/weekly-reviews", produces = MediaType.APPLICATION_JSON_VALUE)
    List<WeeklyReviewService.Review> list(AuthenticatedActor actor, @PathVariable UUID storeId,
                                          @RequestParam(required = false, defaultValue = "8") int limit) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        return reviews.recent(actor.organizationId(), storeId, limit);
    }

    /**
     * Review the week still running now: a provisional snapshot, taken again on every request until the
     * week has ended, and the model asked about it. Monday's run keeps the final one.
     */
    @PostMapping(value = "/stores/{storeId}/weekly-reviews", produces = MediaType.APPLICATION_JSON_VALUE)
    WeeklyReviewService.Review reviewCurrentWeek(AuthenticatedActor actor, @PathVariable UUID storeId) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        LocalDate week = WeeklyReviewService.weekOf(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
        return reviews.review(actor.userId(), actor.organizationId(), storeId, week);
    }
}
