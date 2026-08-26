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
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
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
@RequestMapping("/api/v1/console/diagnosis")
class DiagnosisConsoleController {

    private final MetricQuery metricQuery;
    private final DiagnosisQuery diagnosisQuery;
    private final AnalyticsCalculationService calculation;
    private final BusinessAuthorization authorization;

    DiagnosisConsoleController(MetricQuery metricQuery,
                               DiagnosisQuery diagnosisQuery,
                               AnalyticsCalculationService calculation,
                               BusinessAuthorization authorization) {
        this.metricQuery = metricQuery;
        this.diagnosisQuery = diagnosisQuery;
        this.calculation = calculation;
        this.authorization = authorization;
    }

    /** The store's daily work list, most urgent first. */
    @GetMapping(value = "/stores/{storeId}/queue", produces = MediaType.APPLICATION_JSON_VALUE)
    List<PrioritySubjectView> queue(AuthenticatedActor actor,
                                    @PathVariable UUID storeId,
                                    @RequestParam(required = false, defaultValue = "D30")
                                    MetricWindow window,
                                    @RequestParam(required = false, defaultValue = "50")
                                    int limit) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(storeId));
        return diagnosisQuery.priorityQueue(storeId, window, limit);
    }

    /** Everything currently known about one listing variant. */
    @GetMapping(value = "/listing-variants/{subjectId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    SubjectDiagnosis subject(AuthenticatedActor actor,
                             @PathVariable UUID subjectId,
                             @RequestParam UUID storeId,
                             @RequestParam(required = false, defaultValue = "D30")
                             MetricWindow window) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(storeId));
        return new SubjectDiagnosis(
                subjectId,
                storeId,
                window,
                metricQuery.currentValues(SubjectKind.PLATFORM_LISTING_VARIANT, subjectId,
                        window),
                diagnosisQuery.currentFindings(SubjectKind.PLATFORM_LISTING_VARIANT, subjectId,
                        window));
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
        authorization.require(actor, ActionScopeCode.EVIDENCE_VIEW,
                ResourceScope.store(storeId));
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
     */
    record SubjectDiagnosis(
            UUID subjectId,
            UUID storeId,
            MetricWindow window,
            Map<MetricCode, MetricValueView> metrics,
            List<DiagnosisFindingView> findings) {
    }
}
