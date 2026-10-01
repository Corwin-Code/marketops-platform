package com.mimococo.marketops.aicopilot;

import com.mimococo.marketops.analyticsdecision.MetricWindow;
import java.util.Optional;
import java.util.UUID;

/**
 * Published access to model-assisted explanation.
 *
 * <p>The contract deliberately offers explanation and proposal, and nothing
 * else. There is no method here that writes a metric, approves anything or
 * reaches a marketplace, because a model in this product analyses and suggests
 * while every authority stays deterministic.
 *
 * <p>Every call is recorded whether or not a provider answered. An explanation
 * that is unavailable is a fact about the system's state, and the console shows
 * it as one rather than as an empty panel.
 */
public interface AiCopilot {

    /**
     * Ask a model to explain one subject's current diagnosis.
     *
     * <p>Returns a degraded result rather than failing when no eligible provider
     * exists, the provider does not answer, or the answer does not validate. The
     * deterministic diagnosis is unaffected in every one of those cases.
     */
    AiDiagnosis explain(UUID requestedByUserId,
                        UUID organizationId,
                        UUID listingVariantId,
                        MetricWindow window,
                        String lifecycleObjective);

    /** One recorded invocation and its claims. */
    Optional<AiDiagnosis> invocation(UUID invocationId);

    /**
     * Ask a model to summarize one store: why its listings do not sell and what to do first, from
     * the newest completed calculation over the window. An unchanged situation reuses the recorded
     * answer instead of calling the model again.
     *
     * @param requestedByUserId who asked, or {@code null} for the weekly scheduled summary
     */
    AiDiagnosis explainStore(UUID requestedByUserId, UUID organizationId, UUID storeId, MetricWindow window);

    /** The newest recorded summary of one store for a window, in any state; never calls a model. */
    Optional<AiDiagnosis> latestStoreInvocation(UUID organizationId, UUID storeId, MetricWindow window);

    /**
     * Ask a model which products to join, keep, skip or leave in a store's current promotions, from
     * the estimated margins at the promotions' prices and the products' demand over the window. The
     * platform joins and leaves nothing; an unchanged situation reuses the recorded answer.
     */
    AiDiagnosis reviewPromotions(UUID requestedByUserId, UUID organizationId, UUID storeId, MetricWindow window);

    /** The newest recorded promotion review of one store for a window, in any state; never calls a model. */
    Optional<AiDiagnosis> latestPromotionReview(UUID organizationId, UUID storeId, MetricWindow window);

    /**
     * Ask a model what came of a store's followed actions in one week and what to adjust (P10). The
     * answer authorises nothing; an unchanged week hands out the recorded answer again.
     */
    AiDiagnosis reviewWeek(UUID requestedByUserId, UUID organizationId, UUID storeId, WeeklyReviewInput input);

    /** The newest recorded weekly review of one store, in any state. */
    Optional<AiDiagnosis> latestWeeklyReview(UUID organizationId, UUID storeId);

    /**
     * The most recent recorded explanation of one listing variant for a window,
     * in whatever state it ended, or empty when nobody has asked yet.
     *
     * <p>A read of what an earlier request produced. It never calls a model and
     * never starts an invocation, so coming back to a subject costs nothing and
     * a new explanation is only ever requested on purpose.
     */
    Optional<AiDiagnosis> latestInvocation(UUID organizationId, UUID listingVariantId,
                                           MetricWindow window);

    /**
     * Ask a model for Russian drafts of one listing's title, description and the attributes the
     * content rating names to fill, from its card, content rating, content and search values and
     * findings, and top search terms. The drafts are for a person to review and copy into the
     * seller back office; they change nothing. An unchanged card reuses the recorded drafts.
     */
    AiDiagnosis draftListingContent(UUID requestedByUserId, UUID organizationId, UUID listingVariantId,
                                    MetricWindow window);

    /** The newest recorded content drafts of one listing variant for a window; never calls a model. */
    Optional<AiDiagnosis> latestContentDraft(UUID organizationId, UUID listingVariantId, MetricWindow window);

    /** Listing membership and current disclosure permission are supplied by the listing authority. */
    AiDiagnosis assistListing(UUID requestedByUserId, UUID organizationId, UUID listingId, UUID authorizedStoreId,
                              java.util.List<UUID> listingVariantIds, java.util.List<UUID> authorizedProductVariantIds, MetricWindow window,
                              ListingAssistancePurpose purpose);
    Optional<AiDiagnosis> listingInvocation(UUID invocationId,UUID organizationId,UUID listingId);
    record ListingInvocationScope(UUID storeId,java.util.List<UUID> productVariantIds) {
        public ListingInvocationScope { productVariantIds=java.util.List.copyOf(productVariantIds); }
    }
    Optional<ListingInvocationScope> listingInvocationScope(UUID invocationId,UUID organizationId,UUID listingId);

    /** One earlier listing-assistance request, without its content. */
    record ListingInvocationRecord(UUID invocationId, String windowCode, String state,
                                   java.time.Instant startedAt, java.time.Instant completedAt) {
    }

    /**
     * The listing-assistance requests recorded for one listing, newest first, without their content.
     *
     * <p>A read of the record only: it never calls a model. Opening one still goes through
     * {@link #listingInvocation} under the caller's checks of its original scope.
     */
    java.util.List<ListingInvocationRecord> listingInvocations(UUID organizationId, UUID listingId, int limit);
}
