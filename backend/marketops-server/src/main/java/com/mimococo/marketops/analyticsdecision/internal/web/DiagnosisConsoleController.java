package com.mimococo.marketops.analyticsdecision.internal.web;

import com.mimococo.marketops.analyticsdecision.DiagnosisFindingView;
import com.mimococo.marketops.analyticsdecision.DiagnosisQuery;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.PrioritySubjectView;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.analyticsdecision.internal.application.AnalyticsCalculationService;
import com.mimococo.marketops.analyticsdecision.internal.application.AnalyticsQueryService;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.identityaccess.OwnedResource;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.SubjectIdentity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The diagnostic surface: what to look at first, and everything known about one
 * subject.
 *
 * <p>Every response carries state alongside value. A caller receives whether a
 * metric was available, how confident it is and how fresh it is, because a
 * console that rendered numbers without those would let an operator act on a
 * figure the product does not stand behind.
 *
 * <p>Recalculation is an explicit, authorized action rather than a side effect
 * of reading. Reads stay cheap and predictable, and a recomputation appears in
 * the run journal with the person who asked for it.
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/diagnosis")
class DiagnosisConsoleController {

    private final MetricQuery metricQuery;
    private final DiagnosisQuery diagnosisQuery;
    private final AnalyticsCalculationService calculation;
    private final BusinessAuthorization authorization;
    private final AnalyticsQueryService queries;
    private final ListingIdentityDirectory identities;

    DiagnosisConsoleController(MetricQuery metricQuery,
                               DiagnosisQuery diagnosisQuery,
                               AnalyticsCalculationService calculation,
                               BusinessAuthorization authorization,
                               AnalyticsQueryService queries,
                               ListingIdentityDirectory identities) {
        this.metricQuery = metricQuery;
        this.diagnosisQuery = diagnosisQuery;
        this.calculation = calculation;
        this.authorization = authorization;
        this.queries = queries;
        this.identities = identities;
    }

    /** Source edges of one exact metric version, with independent evidence permission. */
    @GetMapping(value = "/listing-variants/{subjectId}/metrics/{metricValueId}/inputs",
            produces = MediaType.APPLICATION_JSON_VALUE)
    AnalyticsQueryService.InputPage inputs(AuthenticatedActor actor, @PathVariable UUID subjectId,
                                           @PathVariable UUID metricValueId,
                                           @RequestParam UUID storeId) {
        authorization.requireOwned(actor, ActionScopeCode.EVIDENCE_VIEW,
                new OwnedResource(OwnedResource.Kind.LISTING_VARIANT, subjectId, storeId));
        return queries.inputs(subjectId, metricValueId);
    }

    /** The store's daily work list, most urgent first. */
    @GetMapping(value = "/stores/{storeId}/queue", produces = MediaType.APPLICATION_JSON_VALUE)
    List<QueueItem> queue(AuthenticatedActor actor,
                                    @PathVariable UUID storeId,
                                    @RequestParam(required = false, defaultValue = "D30")
                                    MetricWindow window,
                                    @RequestParam(required = false, defaultValue = "50")
                                    int limit) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(storeId));
        List<PrioritySubjectView> queue = diagnosisQuery.priorityQueue(storeId, window, limit);
        // One identity read for the whole page; the order stays the queue's own.
        Map<UUID, SubjectIdentity> names = identities.identities(actor.organizationId(),
                queue.stream()
                        .filter(item -> item.subjectKind() == SubjectKind.PLATFORM_LISTING_VARIANT)
                        .map(PrioritySubjectView::subjectId)
                        .toList());
        return queue.stream()
                .map(item -> QueueItem.of(item,
                        item.subjectKind() == SubjectKind.PLATFORM_LISTING_VARIANT
                                ? names.get(item.subjectId()) : null))
                .toList();
    }

    /** Everything currently known about one listing variant. */
    @GetMapping(value = "/listing-variants/{subjectId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    SubjectDiagnosis subject(AuthenticatedActor actor,
                             @PathVariable UUID subjectId,
                             @RequestParam UUID storeId,
                             @RequestParam(required = false, defaultValue = "D30")
                             MetricWindow window) {
        authorization.requireOwned(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                new OwnedResource(OwnedResource.Kind.LISTING_VARIANT, subjectId, storeId));
        return new SubjectDiagnosis(
                subjectId,
                storeId,
                window,
                metricQuery.currentValues(SubjectKind.PLATFORM_LISTING_VARIANT, subjectId,
                        window),
                diagnosisQuery.currentFindings(SubjectKind.PLATFORM_LISTING_VARIANT, subjectId,
                        window),
                identities.identities(actor.organizationId(), List.of(subjectId))
                        .get(subjectId));
    }

    /** How one metric moved for one subject. */
    @GetMapping(value = "/listing-variants/{subjectId}/metrics/{metricCode}/history",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<MetricValueView> history(AuthenticatedActor actor,
                                  @PathVariable UUID subjectId,
                                  @PathVariable MetricCode metricCode,
                                  @RequestParam UUID storeId,
                                  @RequestParam(required = false, defaultValue = "D30")
                                  MetricWindow window,
                                  @RequestParam(required = false, defaultValue = "20")
                                  int limit) {
        authorization.requireOwned(actor, ActionScopeCode.EVIDENCE_VIEW,
                new OwnedResource(OwnedResource.Kind.LISTING_VARIANT, subjectId, storeId));
        return metricQuery.history(metricCode, SubjectKind.PLATFORM_LISTING_VARIANT, subjectId,
                window, limit);
    }

    /** Recompute a store's metrics and findings now. */
    @PostMapping(value = "/stores/{storeId}/recalculation",
            produces = MediaType.APPLICATION_JSON_VALUE)
    AnalyticsCalculationService.RunSummary recalculate(
            AuthenticatedActor actor,
            @PathVariable UUID storeId,
            @RequestParam(required = false, defaultValue = "D30") MetricWindow window) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(storeId));
        return calculation.run(storeId, window, "MANUAL", actor.userId());
    }

    /**
     * One subject's complete diagnostic picture.
     *
     * @param subjectId the listing variant
     * @param storeId store it sits on
     * @param window the observation window
     * @param metrics every current canonical value
     * @param findings every current rule outcome, in rule order
     * @param identity what an operator calls the subject, or {@code null}
     */
    record SubjectDiagnosis(
            UUID subjectId,
            UUID storeId,
            MetricWindow window,
            Map<MetricCode, MetricValueView> metrics,
            List<DiagnosisFindingView> findings,
            SubjectIdentity identity) {
    }

    /**
     * One work-list entry as the console receives it: the published view's
     * members, unchanged and in the same order, plus display names.
     *
     * @param identity what an operator calls the subject, or {@code null} when
     *                 it is not a listing variant or cannot be named
     */
    record QueueItem(
            SubjectKind subjectKind,
            UUID subjectId,
            UUID storeId,
            BigDecimal priorityScore,
            int criticalFindingCount,
            int warningFindingCount,
            int declinedRuleCount,
            BigDecimal netSales,
            BigDecimal contributionProfit,
            String currencyCode,
            List<String> blockingRuleCodes,
            SubjectIdentity identity) {

        static QueueItem of(PrioritySubjectView view, SubjectIdentity identity) {
            return new QueueItem(view.subjectKind(), view.subjectId(), view.storeId(),
                    view.priorityScore(), view.criticalFindingCount(),
                    view.warningFindingCount(), view.declinedRuleCount(), view.netSales(),
                    view.contributionProfit(), view.currencyCode(), view.blockingRuleCodes(),
                    identity);
        }
    }
}
