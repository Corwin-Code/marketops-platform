package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.analyticsdecision.CalculationRunLedger;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.listingconversion.ConversionMeasurementView;
import com.mimococo.marketops.listingconversion.EvaluationView;
import com.mimococo.marketops.listingconversion.NodeVerdict;
import com.mimococo.marketops.listingconversion.ProtectionVerdict;
import com.mimococo.marketops.listingconversion.RatioState;
import com.mimococo.marketops.listingconversion.SimulationView;
import com.mimococo.marketops.listingconversion.SimulationAssumptions;
import com.mimococo.marketops.listingconversion.internal.domain.ExactTrafficComparison;
import com.mimococo.marketops.listingconversion.internal.domain.FixedTrafficComparison;
import com.mimococo.marketops.listingconversion.internal.domain.FrozenComparisonMethod;
import com.mimococo.marketops.listingconversion.internal.domain.FrozenFutilityRule;
import com.mimococo.marketops.listingconversion.internal.domain.FrozenNodeWindow;
import com.mimococo.marketops.listingconversion.internal.domain.PromotionSimulator;
import com.mimococo.marketops.listingconversion.internal.domain.ProtectionVector;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.EvaluationRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingHealthRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ManualPathRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.MeasurementEvidenceRepository;
import com.mimococo.marketops.operationsworkflow.ListingActionIntake;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Freezes the evaluation plan before launch and records node results after it.
 *
 * <p>A node result carries the primary ratio, its conservative bound, the
 * accepted threshold and the protection vector; the database derives the
 * protection verdict and refuses a MET that the bound does not support. Late
 * facts append a revision under the original plan; the original stays readable.
 */
@Service
public class EvaluationService {

    private final EvaluationRepository evaluations;
    private final ListingActionRepository actions;
    private final ListingHealthRepository measurements;
    private final ListingFactRepository facts;
    private final CalibrationService calibration;
    private final CalculationRunLedger ledger;
    private final ListingActionIntake intake;
    private final IdGenerator ids;
    private final Clock clock;
    private final ListingScopeAuthorization scopes;
    private final ListingDisclosureService disclosure;
    private final ObjectMapper json;
    private final MeasurementEvidenceRepository measurementEvidence;
    private final ListingOutcomeMetricEvidence outcomeMetrics;
    private final ListingOutcomeSupplyEvidence outcomeSupply;
    private final ManualPathRepository manualPaths;
    private final ListingSimulationInputEvidence simulationFees;

    EvaluationService(EvaluationRepository evaluations, ListingActionRepository actions, ListingHealthRepository measurements,
                      ListingFactRepository facts, CalibrationService calibration, CalculationRunLedger ledger,
                      ListingActionIntake intake, IdGenerator ids, Clock clock,
                      ListingScopeAuthorization scopes, ListingDisclosureService disclosure, ObjectMapper json,
                      MeasurementEvidenceRepository measurementEvidence, ListingOutcomeMetricEvidence outcomeMetrics,
                      ListingOutcomeSupplyEvidence outcomeSupply, ManualPathRepository manualPaths,
                      ListingSimulationInputEvidence simulationFees) {
        this.evaluations = evaluations;
        this.actions = actions;
        this.measurements = measurements;
        this.facts = facts;
        this.calibration = calibration;
        this.ledger = ledger;
        this.intake = intake;
        this.ids = ids;
        this.clock = clock;
        this.scopes = scopes;
        this.disclosure = disclosure;
        this.json = json;
        this.measurementEvidence=measurementEvidence;
        this.outcomeMetrics=outcomeMetrics;
        this.outcomeSupply=outcomeSupply;
        this.manualPaths=manualPaths;
        this.simulationFees=simulationFees;
    }

    /** Freeze the plan from the bound calibration package, once. */
    @Transactional
    public UUID freezePlan(ListingActionRepository.ActionRow action) {
        if (action.purposeCode()!=null && !com.mimococo.marketops.listingconversion.ListingActionPurpose.valueOf(action.purposeCode()).requiresFormalEvaluation())
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        Optional<UUID> existing = actions.planId(action.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!"DRAFT".equals(action.state())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (action.calibrationPackageId() == null) {
            throw OperationRejectedException.of(ErrorCode.CALIBRATION_UNRESOLVED);
        }
        ListingFactRepository.ListingContext listing = facts.listing(action.listingId())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        Instant now = actions.databaseNow();
        CalibrationService.Outcome resolved = calibration.resolve(listing.organizationId(), listing.platformCode(),
                listing.storeId(), now, action.purposeCode()==null?"LISTING_CONVERSION":action.purposeCode());
        if (!resolved.ok() || !resolved.resolved().packageId().equals(action.calibrationPackageId())
                || !Integer.valueOf(resolved.resolved().version()).equals(action.calibrationVersion())) {
            throw OperationRejectedException.of(ErrorCode.CALIBRATION_UNRESOLVED);
        }
        List<Map<String, Object>> nodes = CalibrationService.formalNodes(resolved.resolved());
        Map<String, Object> stopRule = CalibrationService.stopRule(resolved.resolved());
        Integer crossPeriod = CalibrationService.crossPeriodWindowDays(resolved.resolved()).orElse(null);
        if (nodes.isEmpty() || crossPeriod == null || crossPeriod < 0
                || !CalibrationService.hasExplicitStopRule(resolved.resolved())) {
            throw OperationRejectedException.of(ErrorCode.CALIBRATION_UNRESOLVED);
        }
        Map<String, String> coverage = new LinkedHashMap<>();
        coverage.put("actionKind", action.actionKind());
        coverage.put("priorTextDigest", String.valueOf(action.currentTextDigest()));
        coverage.put("targetTextDigest", String.valueOf(action.targetTextDigest()));
        coverage.put("affectedSetDigest", action.affectedSetDigest());
        if (action.promotionTermsDigest()!=null) coverage.put("promotionTermsDigest",action.promotionTermsDigest());
        int longestMaturity = 0;
        for (Map<String, Object> node : nodes) {
            Object value = node.get("maturityDays");
            if (!(value instanceof JsonNode days)
                    || !java.util.Set.of("7", "14", "30").contains(days.asText())) {
                throw OperationRejectedException.of(ErrorCode.CALIBRATION_UNRESOLVED);
            }
            longestMaturity = Math.max(longestMaturity, Integer.parseInt(days.asText()));
        }
        Instant boundary = now.plus(Duration.ofDays((long) longestMaturity + crossPeriod));
        List<JsonNode> groups = CalibrationService.criticalGroupRules(resolved.resolved());
        List<Map<String,Object>> frozenNodeRows = new ArrayList<>();
        nodes.forEach(node->frozenNodeRows.add(new LinkedHashMap<>(node)));
        JsonNode frozenNodes = json.valueToTree(frozenNodeRows);
        JsonNode frozenGroups = json.valueToTree(groups);
        boolean registeredMethod = nodes.stream().anyMatch(node -> node.get("method") instanceof JsonNode method
                && FrozenComparisonMethod.CODE.equals(method.asText()));
        if (registeredMethod) {
            int lastScheduledDay = 0;
            for (int index=0;index<frozenNodeRows.size();index++) {
                JsonNode node=frozenNodes.get(index);
                var method = FrozenComparisonMethod.resolve(frozenNodes, frozenGroups, node.path("nodeCode").asText())
                        .orElseThrow(() -> OperationRejectedException.of(ErrorCode.CALIBRATION_UNRESOLVED));
                frozenNodeRows.get(index).put("comparisonReference",freezeComparisonReference(action,method,frozenGroups,now));
                lastScheduledDay = Math.max(lastScheduledDay, method.lastDay());
            }
            frozenNodes=json.valueToTree(frozenNodeRows);
            boundary = now.plus(Duration.ofDays((long) lastScheduledDay + crossPeriod));
        }
        if (!stopRule.isEmpty() && FrozenFutilityRule.resolve(json.valueToTree(stopRule),frozenNodes).isEmpty())
            throw OperationRejectedException.of(ErrorCode.CALIBRATION_UNRESOLVED);
        String digest = Digest.ofComponents(List.of("lc-frozen-plan-3", action.id().toString(),
                json.writeValueAsString(coverage), json.writeValueAsString(frozenNodeRows), json.writeValueAsString(stopRule),
                json.writeValueAsString(groups), "PRIOR_VERSION_WINDOW", "EXCLUDE_TRANSITION_DAYS",
                boundary.toString(), now.toString(), Integer.toString(crossPeriod),
                action.calibrationPackageId() + ":" + action.calibrationVersion()));
        UUID planId = ids.newId();
        actions.insertPlan(planId, action.organizationId(), action.id(), action.calibrationPackageId(), action.calibrationVersion(),
                coverage, boundary, frozenNodeRows, stopRule, groups,
                "PRIOR_VERSION_WINDOW", crossPeriod, digest, now);
        return planId;
    }

    /** Fixes one actual pre-action cohort and its source mix before the action can launch. */
    private Map<String,Object> freezeComparisonReference(ListingActionRepository.ActionRow action,
                                                          FrozenComparisonMethod method,JsonNode groups,Instant at) {
        var result=new LinkedHashMap<String,Object>();
        var gaps=new ArrayList<String>();
        result.put("windowDurationDays",method.windowEndDay()-method.windowStartDay());
        result.put("retentionDays",method.maturityDays());
        var reference=measurementEvidence.latestReferenceSourceStrata(action.listingId(),
                method.windowEndDay()-method.windowStartDay(),method.maturityDays(),at).orElse(null);
        if (reference==null) {
            gaps.add("PRE_ACTION_REFERENCE_MEASUREMENT_MISSING");
        } else {
            result.put("referenceMeasurementId",reference.measurementId().toString());
            result.put("referenceWindowStart",reference.windowStart().toString());
            result.put("referenceWindowEnd",reference.windowEnd().toString());
            result.put("definitionVersion",reference.definitionVersion());
            result.put("evidencePath",reference.evidencePath().name());
            result.put("canonicalInputDigest",reference.canonicalInputDigest());
            result.put("sourceTime",string(reference.sourceTime()));
            result.put("acquisitionTime",string(reference.acquisitionTime()));
            result.put("computedAt",string(reference.computedAt()));
            if (!reference.qualified())
                gaps.add("PRE_ACTION_REFERENCE_SOURCE_UNQUALIFIED");
            if (reference.windowEnd().isAfter(at) || reference.computedAt()==null || reference.computedAt().isAfter(at)
                    || reference.sourceTime()==null || reference.acquisitionTime()==null
                    || reference.sourceTime().isAfter(reference.acquisitionTime())
                    || reference.acquisitionTime().isAfter(reference.computedAt())
                    || reference.sourceTime().isBefore(reference.windowEnd().plus(Duration.ofDays(reference.retentionDays()))))
                gaps.add("PRE_ACTION_REFERENCE_CHRONOLOGY_UNQUALIFIED");
            if (reference.canonicalInputDigest()==null
                    || !reference.canonicalInputDigest().matches("[0-9a-f]{64}"))
                gaps.add("PRE_ACTION_REFERENCE_LINEAGE_UNQUALIFIED");
            var weights=weightsFrom(sourceCounts(reference.counts()));
            if (weights.isEmpty()) gaps.add("PRE_ACTION_SOURCE_WEIGHTS_UNQUALIFIED");
            else result.put("sourceWeights",weights);
            if ("LISTING_DESCRIPTION_CHANGE".equals(action.actionKind())) {
                boolean covered=exactVersionCoverage(reference.versionCoverage(),action.currentTextDigest());
                result.put("referenceVersionCoverage",versionCoverageEvidence(reference.versionCoverage(),action.currentTextDigest()));
                if (!covered) gaps.add("PRE_ACTION_DESCRIPTION_VERSION_UNQUALIFIED");
            } else if ("LISTING_PROMOTION_ACTION".equals(action.actionKind())) {
                JsonNode context=manualPaths.currentPromotionContext(action.organizationId(),action.listingId(),
                        reference.windowStart(),reference.windowEnd(),at);
                result.put("referencePromotionContext",promotionContextEvidence(context));
                if (!"QUALIFIED_COMPLETE".equals(context.path("coverage").asText()))
                    gaps.add("PRE_ACTION_PROMOTION_CONTEXT_UNQUALIFIED");
            } else {
                gaps.add("ACTION_KIND_UNSUPPORTED_BY_FORMAL_COMPARISON");
            }
        }
        var groupReferences=new LinkedHashMap<String,Object>();
        for (JsonNode group:groups) {
            String code=group.path("code").asText();
            var groupReference=new LinkedHashMap<String,Object>();
            var counts=reference==null?Map.<String,FixedTrafficComparison.Count>of()
                    :sourceCounts(reference.criticalGroupCounts().path(code));
            var weights=weightsFrom(counts);
            groupReference.put("state",weights.isEmpty()?"UNQUALIFIED":"FROZEN_GROUP_REFERENCE");
            groupReference.put("sourceWeights",weights);
            groupReferences.put(code,groupReference);
        }
        result.put("criticalGroups",groupReferences);
        List<String> distinct=gaps.stream().distinct().toList();
        result.put("state",distinct.isEmpty()?"FROZEN_REFERENCE":"UNQUALIFIED");
        result.put("qualificationGaps",distinct);
        return result;
    }

    private static Map<String,Object> versionCoverageEvidence(JsonNode coverage,String expectedDigest) {
        var result=new LinkedHashMap<String,Object>();
        result.put("state",coverage.path("state").asText("UNQUALIFIED"));
        result.put("coveredTextDigest",coverage.path("coveredTextDigest").isTextual()
                ?coverage.path("coveredTextDigest").asText():null);
        result.put("expectedTextDigest",expectedDigest);
        result.put("excludedTransitionDays",coverage.path("excludedTransitionDays").deepCopy());
        result.put("uncoveredDays",coverage.path("uncoveredDays").deepCopy());
        return result;
    }

    private static boolean exactVersionCoverage(JsonNode coverage,String expectedDigest) {
        return expectedDigest!=null && "FULL_SINGLE_VERSION_COVERAGE".equals(coverage.path("state").asText())
                && expectedDigest.equals(coverage.path("coveredTextDigest").asText());
    }

    private static Map<String,Object> promotionContextEvidence(JsonNode context) {
        var result=new LinkedHashMap<String,Object>();
        for (String field:List.of("coverage","organizationId","listingId","periodStart","periodEnd","observationId",
                "observedAt","acquiredAt","coverageFrom","coverageUntil","verificationExpiresAt","contextDigest",
                "knownRecordsDigest","digest"))
            if (!context.path(field).isMissingNode() && !context.path(field).isNull())
                result.put(field,context.path(field).asText());
        result.put("gaps",context.path("gaps").deepCopy());
        return result;
    }

    private static Map<String,BigDecimal> weightsFrom(Map<String,FixedTrafficComparison.Count> counts) {
        if (!counts.keySet().equals(java.util.Set.of("ADVERTISING","ORGANIC"))) return Map.of();
        BigDecimal advertising=BigDecimal.valueOf(counts.get("ADVERTISING").visits());
        BigDecimal organic=BigDecimal.valueOf(counts.get("ORGANIC").visits());
        BigDecimal total=advertising.add(organic);
        if (total.signum()<=0) return Map.of();
        BigDecimal advertisingWeight=advertising.divide(total,24,RoundingMode.HALF_EVEN);
        var result=new LinkedHashMap<String,BigDecimal>();
        result.put("ADVERTISING",advertisingWeight);
        result.put("ORGANIC",BigDecimal.ONE.subtract(advertisingWeight));
        return result;
    }

    private static String string(Instant value) { return value==null?null:value.toString(); }

    @Transactional(readOnly = true)
    public Optional<String> frozenPlanDigest(UUID actionId) {
        return evaluations.plan(actionId).map(EvaluationRepository.PlanRow::planDigest);
    }

    /** Legacy request fields retained for wire compatibility; none is authoritative Outcome evidence. */
    public record ProtectionInputs(BigDecimal directContributionProfit, BigDecimal linkedScopeProfit,
                                   BigDecimal overallReturnRate, BigDecimal criticalVariantReturnRate,
                                   BigDecimal supplyCoverageDays, Map<String, BigDecimal> criticalGroupRatios,
                                   BigDecimal priorContributionProfit, BigDecimal priorLinkedScopeProfit,
                                   BigDecimal priorReturnRate, BigDecimal minimumSupplyCoverageDays) {
    }

    record RecalculationReceipt(int assessed, List<UUID> resultIds) { }

    private record EvaluationWrite(EvaluationView view, UUID resultId) { }

    /** Re-run only formal outcomes whose already-admitted measurement definition was refreshed. */
    @Transactional
    RecalculationReceipt reviseCurrent(UUID listingId, List<UUID> measurementIds, String queueReference) {
        List<EvaluationRepository.RecalculationTarget> targets =
                evaluations.recalculationTargets(listingId, measurementIds);
        List<UUID> results = new ArrayList<>();
        for (var target : targets) {
            ListingActionRepository.ActionRow action = actions.action(target.actionId())
                    .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
            EvaluationWrite write = evaluateNode(action, null, target.nodeCode(), target.stage(),
                    target.measurementId(), queueReference);
            if (write.resultId() != null) results.add(write.resultId());
        }
        return new RecalculationReceipt(targets.size(), List.copyOf(results));
    }

    @Transactional
    public EvaluationView evaluateNode(AuthenticatedActor actor, UUID actionId, String nodeCode, String stage,
                                       UUID measurementId, BigDecimal conservativeBound, ProtectionInputs protections,
                                       String lateFactReference) {
        ListingActionRepository.ActionRow action = actions.action(actionId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        scopes.require(actor, action.listingId(), ActionScopeCode.LISTING_OUTCOME_EVALUATE);
        return evaluateNode(action, actor, nodeCode, stage, measurementId, lateFactReference).view();
    }

    private EvaluationWrite evaluateNode(ListingActionRepository.ActionRow action, AuthenticatedActor actor,
                                         String nodeCode, String stage, UUID measurementId,
                                         String lateFactReference) {
        if (lateFactReference != null) MetadataFieldPolicy.requireText("lateFactReference", lateFactReference);
        EvaluationRepository.PlanRow plan = evaluations.plan(action.id())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION));
        evaluations.lockPlan(plan.id());
        // Scope grants can expire while this request waits behind another result writer.
        if (actor != null) scopes.require(actor, action.listingId(), ActionScopeCode.LISTING_OUTCOME_EVALUATE);
        JsonNode node = null;
        for (JsonNode candidate : plan.formalNodes()) {
            if (candidate.path("nodeCode").asText().equals(nodeCode)) {
                node = candidate;
            }
        }
        if (node == null || (!"OPERATIONAL".equals(stage) && !"SETTLED".equals(stage))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        BigDecimal threshold;
        try {
            threshold = new BigDecimal(node.path("threshold").asText());
            if (threshold.signum()<=0 || threshold.compareTo(BigDecimal.ONE)>0)
                throw new NumberFormatException("threshold outside unit interval");
        } catch (NumberFormatException invalid) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Instant now = actions.databaseNow();
        Optional<ConversionMeasurementView> measurement = measurementId == null ? Optional.empty()
                : measurements.measurement(measurementId);
        if (measurementId != null && (measurement.isEmpty()
                || !measurement.get().platformListingId().equals(action.listingId()))) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        BigDecimal ratio = measurement.filter(m -> m.ratioState() == RatioState.DEFINED)
                .map(ConversionMeasurementView::primaryRatio).orElse(null);
        var method = FrozenComparisonMethod.resolve(plan.formalNodes(), plan.criticalGroups(), nodeCode).orElse(null);
        var launchedAt = actions.launch(action.id()).map(com.mimococo.marketops.listingconversion.ListingActionView.Launch::launchedAt).orElse(null);
        var window = FrozenNodeWindow.assess(method, plan.frozenAt(), plan.latestBoundary(), launchedAt,
                measurement.orElse(null), now, false);
        if (measurementId!=null && window.gaps().stream().anyMatch(List.of("MEASUREMENT_OUTSIDE_FROZEN_WINDOW",
                "MEASUREMENT_RETENTION_MISMATCH","MEASUREMENT_FROM_FUTURE","MEASUREMENT_ACQUISITION_FROM_FUTURE")::contains))
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        if (method != null && evaluations.previouslyAdmittedWindow(plan.id(), nodeCode, stage,
                window.windowStart(), window.windowEnd(), window.retentionDays())) {
            window = FrozenNodeWindow.assess(method, plan.frozenAt(), plan.latestBoundary(), launchedAt,
                    measurement.orElse(null), now, true);
        }
        boolean maturity = window.admitted();
        Map<String,Object> evaluationEvidence = new LinkedHashMap<>();
        evaluationEvidence.put("planDigest", plan.planDigest());
        evaluationEvidence.put("nodeCode", nodeCode);
        evaluationEvidence.put("requestedStage", stage);
        evaluationEvidence.put("method", node.path("method").asText());
        evaluationEvidence.put("nodeWindowQualified", window.admitted());
        evaluationEvidence.put("withinInitialEvaluationPeriod", window.notBefore()!=null
                && !now.isBefore(window.notBefore()) && !now.isAfter(window.lastEvaluation()) && !now.isAfter(plan.latestBoundary()));
        evaluationEvidence.put("measurementId", measurementId==null?null:measurementId.toString());
        evaluationEvidence.put("measurementAcquiredAt",measurement.map(ConversionMeasurementView::acquisitionTime)
                .map(Instant::toString).orElse(null));
        evaluationEvidence.put("measurementComputedAt",measurement.map(ConversionMeasurementView::computedAt)
                .map(Instant::toString).orElse(null));
        evaluationEvidence.put("frozenWindowStart", window.windowStart()==null?null:window.windowStart().toString());
        evaluationEvidence.put("frozenWindowEnd", window.windowEnd()==null?null:window.windowEnd().toString());
        evaluationEvidence.put("evaluatedByUserId",actor == null ? null : actor.userId().toString());
        evaluationEvidence.put("evaluationAuthority",actor == null
                ? "RECALCULATION_QUEUE" : "CURRENT_BUSINESS_AUTHORITY");
        var traffic=formalTrafficComparison(action,plan,node,method,measurementId,now,window.admitted());
        evaluationEvidence.put("formalTrafficComparison",traffic.evidence());
        Instant protectionFrom=window.windowStart();
        Instant protectionTo=window.windowEnd()==null?null:window.windowEnd().plus(Duration.ofDays(plan.crossPeriodWindowDays()));
        var canonicalMetrics=outcomeMetrics.read(action,protectionFrom,protectionTo,
                node.path("maturityDays").asInt(),stage,now);
        evaluationEvidence.put("canonicalProtectionInputs",canonicalMetrics.references());
        var accountingComparison=outcomeMetrics.compare(action,canonicalMetrics,protectionFrom,protectionTo,
                node.path("maturityDays").asInt(),stage,now,plan.frozenAt(),node,
                window.admitted() && canonicalMetrics.gaps().isEmpty());
        evaluationEvidence.put("canonicalAccountingComparison",accountingComparison.evidence());
        var supplyEvidence=method==null
                ?new ListingOutcomeSupplyEvidence.Evidence(ProtectionVerdict.UNDETERMINED,
                    Map.of("state","UNDETERMINED"),List.of("FROZEN_METHOD_OR_SCHEDULE_UNQUALIFIED"))
                :outcomeSupply.assess(action,now);
        evaluationEvidence.put("supplyCoverage",supplyEvidence.references());
        List<String> qualificationGaps = new ArrayList<>(window.gaps());
        qualificationGaps.addAll(traffic.gaps());
        qualificationGaps.addAll(canonicalMetrics.gaps());
        qualificationGaps.addAll(supplyEvidence.gaps());
        if (!"ACCOUNTING_COMPARISON_COMPUTED".equals(accountingComparison.evidence().get("state")))
            qualificationGaps.add("CANONICAL_PROTECTION_COMPARISON_UNQUALIFIED");
        evaluationEvidence.put("qualificationGaps", qualificationGaps.stream().distinct().toList());
        // The request number remains wire-compatible only. The formal lower
        // improvement bound is produced from the frozen reference and target facts.
        BigDecimal bound = traffic.lowerDifference();
        NodeVerdict verdict = ProtectionVector.nodeVerdict(ratio, bound, threshold, maturity);

        Map<String, ProtectionVerdict> vector = new LinkedHashMap<>();
        ProtectionVector.REQUIRED.forEach(code -> vector.put(code, ProtectionVerdict.UNDETERMINED));
        vector.putAll(accountingComparison.verdicts());
        vector.putIfAbsent("UNIT_PROFIT_FLOOR",ProtectionVerdict.UNDETERMINED);
        vector.put("SUPPLY_COVERAGE",supplyEvidence.verdict());
        traffic.criticalGroupVerdicts().forEach((code,value)->vector.put("CRITICAL_GROUP_"+code,value));
        ProtectionVerdict protection = ProtectionVector.verdictOf(vector);
        var futility=futility(plan,nodeCode,traffic.upperDifference(),maturity);
        evaluationEvidence.put("futility",futility.evidence());
        boolean stop=futility.triggered();

        if (evaluations.latestResultMatches(plan.id(),nodeCode,stage,measurementId,ratio,bound,threshold,verdict,
                ProtectionVector.toStrings(vector),protection,stop,maturity,
                measurement.map(ConversionMeasurementView::sourceTime).orElse(null),evaluationEvidence)) {
            return new EvaluationWrite(viewAuthorized(action.id()).orElseThrow(), null);
        }

        UUID runId = ledger.recordCompletedRun(new CalculationRunLedger.CompletedRun(action.organizationId(), action.storeId(),
                lateFactReference == null ? "MANUAL" : "LATE_DATA",
                MetricWindow.valueOf("D"+node.path("maturityDays").asInt()),
                window.windowStart()!=null?window.windowStart():measurement.map(ConversionMeasurementView::windowStart).orElse(plan.frozenAt()),
                window.windowEnd()!=null?window.windowEnd():measurement.map(ConversionMeasurementView::windowEnd).orElse(plan.latestBoundary()),
                Digest.ofText("lc-node-1"), 1, 1, true, null, now, actor == null ? null : actor.userId()));
        Optional<UUID> original = evaluations.latestResult(plan.id(), nodeCode, stage);
        int revision = evaluations.nextRevision(plan.id(), nodeCode, stage);
        UUID resultId = ids.newId();
        evaluations.insertResult(resultId, action.organizationId(), plan.id(), nodeCode, stage, revision, measurementId, runId,
                ratio, bound, threshold, verdict, ProtectionVector.toStrings(vector), protection, stop, maturity,
                measurement.map(ConversionMeasurementView::sourceTime).orElse(null), now, evaluationEvidence);
        if (original.isPresent()) {
            evaluations.insertRevision(ids.newId(), action.organizationId(), plan.id(), original.get(), resultId,
                    lateFactReference == null ? "CORRECTION" : "LATE_FACT",
                    lateFactReference == null ? "re-evaluation:" + resultId : MetadataFieldPolicy.requireText("lateFactReference", lateFactReference),
                    now);
        }
        // A requested stage is not proof that the underlying business evidence
        // has reached that stage. Keep the responsibility journal honest too.
        intake.recordTaskOutcome(action.recommendationId(), ProtectionVector.taskOutcome(verdict,protection,maturity,stage,original.isPresent()),
                "lc-node-result:" + resultId, "node " + nodeCode + " requested " + stage + " " + verdict + " protections " + protection);
        return new EvaluationWrite(viewAuthorized(action.id()).orElseThrow(), resultId);
    }

    private record FormalTraffic(BigDecimal lowerDifference,BigDecimal upperDifference,
                                 Map<String,ProtectionVerdict> criticalGroupVerdicts,
                                 Map<String,Object> evidence,List<String> gaps) { }

    /** Executes only the exact method and pre-action cohort already frozen into this node. */
    private FormalTraffic formalTrafficComparison(ListingActionRepository.ActionRow action,
            EvaluationRepository.PlanRow plan,JsonNode node,FrozenComparisonMethod method,UUID measurementId,
            Instant at,boolean windowAdmitted) {
        var evidence=new LinkedHashMap<String,Object>();
        var commonGaps=new ArrayList<String>();
        JsonNode frozen=node.path("comparisonReference");
        evidence.put("method",node.path("method").asText());
        evidence.put("qualificationRef",method==null?null:method.qualificationReference());
        evidence.put("referenceMeasurementId",frozen.path("referenceMeasurementId").isTextual()
                ?frozen.path("referenceMeasurementId").asText():null);
        evidence.put("targetMeasurementId",measurementId==null?null:measurementId.toString());
        if (method==null) commonGaps.add("FROZEN_METHOD_OR_SCHEDULE_UNQUALIFIED");
        if (!windowAdmitted) commonGaps.add("FROZEN_NODE_WINDOW_UNQUALIFIED");
        if (!"FROZEN_REFERENCE".equals(frozen.path("state").asText()))
            commonGaps.add("FROZEN_REFERENCE_UNQUALIFIED");

        UUID referenceId=uuid(frozen.path("referenceMeasurementId"));
        MeasurementEvidenceRepository.MeasuredSourceStrata reference=referenceId==null?null
                :measurementEvidence.measuredSourceStrata(referenceId,action.listingId()).orElse(null);
        MeasurementEvidenceRepository.MeasuredSourceStrata target=measurementId==null?null
                :measurementEvidence.measuredSourceStrata(measurementId,action.listingId()).orElse(null);
        if (reference==null) commonGaps.add("REFERENCE_MEASUREMENT_UNAVAILABLE");
        if (target==null) commonGaps.add("TARGET_MEASUREMENT_UNAVAILABLE");
        if (reference!=null) {
            evidence.put("referenceInputDigest",reference.canonicalInputDigest());
            boolean identity=reference.qualified()
                    && frozen.path("definitionVersion").asInt(-1)==reference.definitionVersion()
                    && frozen.path("retentionDays").asInt(-1)==reference.retentionDays()
                    && reference.canonicalInputDigest()!=null
                    && reference.canonicalInputDigest().equals(frozen.path("canonicalInputDigest").asText())
                    && reference.windowStart().toString().equals(frozen.path("referenceWindowStart").asText())
                    && reference.windowEnd().toString().equals(frozen.path("referenceWindowEnd").asText());
            if (!identity) commonGaps.add("REFERENCE_MEASUREMENT_IDENTITY_OR_SOURCE_UNQUALIFIED");
            if (reference.computedAt()==null || reference.computedAt().isAfter(plan.frozenAt())
                    || reference.windowEnd().isAfter(plan.frozenAt()) || reference.sourceTime()==null
                    || reference.acquisitionTime()==null || reference.sourceTime().isAfter(reference.acquisitionTime())
                    || reference.acquisitionTime().isAfter(reference.computedAt())
                    || reference.sourceTime().isBefore(reference.windowEnd().plus(Duration.ofDays(reference.retentionDays()))))
                commonGaps.add("REFERENCE_MEASUREMENT_CHRONOLOGY_UNQUALIFIED");
        }
        if (target!=null) {
            evidence.put("targetInputDigest",target.canonicalInputDigest());
            boolean identity=method!=null && target.qualified()
                    && target.windowStart().equals(plan.frozenAt().plus(Duration.ofDays(method.windowStartDay())))
                    && target.windowEnd().equals(plan.frozenAt().plus(Duration.ofDays(method.windowEndDay())))
                    && target.retentionDays()==method.maturityDays();
            if (reference!=null) identity=identity && target.definitionVersion()==reference.definitionVersion();
            if (!identity) commonGaps.add("TARGET_MEASUREMENT_IDENTITY_OR_SOURCE_UNQUALIFIED");
            if (target.computedAt()==null || target.computedAt().isAfter(at) || target.sourceTime()==null
                    || target.acquisitionTime()==null || target.sourceTime().isAfter(target.acquisitionTime())
                    || target.acquisitionTime().isAfter(target.computedAt())
                    || target.sourceTime().isBefore(target.windowEnd().plus(Duration.ofDays(target.retentionDays()))))
                commonGaps.add("TARGET_MEASUREMENT_CHRONOLOGY_UNQUALIFIED");
        }

        if (reference!=null && target!=null) {
            if ("LISTING_DESCRIPTION_CHANGE".equals(action.actionKind())) {
                evidence.put("referenceVersionCoverage",versionCoverageEvidence(reference.versionCoverage(),action.currentTextDigest()));
                evidence.put("targetVersionCoverage",versionCoverageEvidence(target.versionCoverage(),action.targetTextDigest()));
                if (!exactVersionCoverage(reference.versionCoverage(),action.currentTextDigest()))
                    commonGaps.add("REFERENCE_DESCRIPTION_VERSION_UNQUALIFIED");
                if (!exactVersionCoverage(target.versionCoverage(),action.targetTextDigest()))
                    commonGaps.add("TARGET_DESCRIPTION_VERSION_UNQUALIFIED");
            } else if ("LISTING_PROMOTION_ACTION".equals(action.actionKind())) {
                JsonNode referenceContext=manualPaths.currentPromotionContext(action.organizationId(),action.listingId(),
                        reference.windowStart(),reference.windowEnd(),at);
                JsonNode targetContext=manualPaths.currentPromotionContext(action.organizationId(),action.listingId(),
                        target.windowStart(),target.windowEnd(),at);
                evidence.put("referencePromotionContext",promotionContextEvidence(referenceContext));
                var targetPromotion=targetPromotionCoverage(action,targetContext,target.windowStart(),target.windowEnd());
                evidence.put("targetPromotionContext",targetPromotion.evidence());
                if (!"QUALIFIED_COMPLETE".equals(referenceContext.path("coverage").asText())
                        || !referenceContext.path("digest").asText().equals(
                            frozen.path("referencePromotionContext").path("digest").asText()))
                    commonGaps.add("REFERENCE_PROMOTION_CONTEXT_UNQUALIFIED");
                commonGaps.addAll(targetPromotion.gaps());
            } else {
                commonGaps.add("ACTION_KIND_UNSUPPORTED_BY_FORMAL_COMPARISON");
            }
        }

        var primaryGaps=new ArrayList<>(commonGaps);
        Map<String,FixedTrafficComparison.Count> referenceCounts=reference==null?Map.of():sourceCounts(reference.counts());
        Map<String,FixedTrafficComparison.Count> targetCounts=target==null?Map.of():sourceCounts(target.counts());
        Map<String,BigDecimal> weights=frozenWeights(frozen.path("sourceWeights"));
        if (weights.isEmpty() || referenceCounts.isEmpty() || !sameWeights(weights,weightsFrom(referenceCounts)))
            primaryGaps.add("FROZEN_SOURCE_WEIGHTS_UNQUALIFIED");
        if (targetCounts.isEmpty()) primaryGaps.add("TARGET_SOURCE_STRATIFICATION_INCOMPLETE");
        ExactTrafficComparison.Result arithmetic=ExactTrafficComparison.compare(weights,referenceCounts,targetCounts,method);
        evidence.put("frozenSourceWeights",weights);
        evidence.put("referenceStandardized",arithmetic.referenceStandardized());
        evidence.put("targetStandardized",arithmetic.targetStandardized());
        evidence.put("observedDifference",arithmetic.observedDifference());
        evidence.put("computedLowerDifference",arithmetic.lowerDifference());
        evidence.put("computedUpperDifference",arithmetic.upperDifference());
        evidence.put("arithmeticStatus",arithmetic.arithmeticStatus());
        if (!"COMPUTED_UNDER_FROZEN_ASSUMPTIONS".equals(arithmetic.arithmeticStatus()))
            primaryGaps.add(arithmetic.arithmeticStatus());
        boolean primaryQualified=primaryGaps.isEmpty()
                && "COMPUTED_UNDER_FROZEN_ASSUMPTIONS".equals(arithmetic.arithmeticStatus());
        BigDecimal lower=primaryQualified?arithmetic.lowerDifference():null;
        BigDecimal upper=primaryQualified?arithmetic.upperDifference():null;
        evidence.put("lowerDifference",lower);
        evidence.put("upperDifference",upper);

        var groupVerdicts=new LinkedHashMap<String,ProtectionVerdict>();
        var groupEvidence=new LinkedHashMap<String,Object>();
        var allGaps=new ArrayList<>(primaryGaps);
        for (JsonNode group:plan.criticalGroups()) {
            String code=group.isObject()?group.path("code").asText():group.asText();
            var gaps=new ArrayList<>(commonGaps);
            BigDecimal maximumDecline=null;
            try { maximumDecline=new BigDecimal(group.path("bound").asText()); }
            catch (NumberFormatException invalid) { gaps.add("CRITICAL_GROUP_BOUND_UNQUALIFIED"); }
            JsonNode frozenGroup=frozen.path("criticalGroups").path(code);
            Map<String,BigDecimal> groupWeights=frozenWeights(frozenGroup.path("sourceWeights"));
            Map<String,FixedTrafficComparison.Count> referenceGroup=reference==null?Map.of()
                    :sourceCounts(reference.criticalGroupCounts().path(code));
            Map<String,FixedTrafficComparison.Count> targetGroup=target==null?Map.of()
                    :sourceCounts(target.criticalGroupCounts().path(code));
            if (!"FROZEN_GROUP_REFERENCE".equals(frozenGroup.path("state").asText()) || groupWeights.isEmpty()
                    || referenceGroup.isEmpty() || !sameWeights(groupWeights,weightsFrom(referenceGroup)))
                gaps.add("CRITICAL_GROUP_REFERENCE_UNQUALIFIED");
            if (targetGroup.isEmpty()) gaps.add("CRITICAL_GROUP_TARGET_UNQUALIFIED");
            var groupArithmetic=ExactTrafficComparison.compare(groupWeights,referenceGroup,targetGroup,method);
            if (!"COMPUTED_UNDER_FROZEN_ASSUMPTIONS".equals(groupArithmetic.arithmeticStatus()))
                gaps.add(groupArithmetic.arithmeticStatus());
            List<String> distinct=gaps.stream().distinct().toList();
            BigDecimal groupLower=distinct.isEmpty()?groupArithmetic.lowerDifference():null;
            BigDecimal groupUpper=distinct.isEmpty()?groupArithmetic.upperDifference():null;
            ProtectionVerdict groupVerdict=ProtectionVector.intervalNonWorsening(groupLower,groupUpper,maximumDecline);
            groupVerdicts.put(code,groupVerdict);
            var detail=new LinkedHashMap<String,Object>();
            detail.put("state",distinct.isEmpty()?"QUALIFIED_INDEPENDENT_COMPARISON":"UNQUALIFIED");
            detail.put("maximumDecline",maximumDecline);
            detail.put("frozenSourceWeights",groupWeights);
            detail.put("referenceStandardized",groupArithmetic.referenceStandardized());
            detail.put("targetStandardized",groupArithmetic.targetStandardized());
            detail.put("observedDifference",groupArithmetic.observedDifference());
            detail.put("lowerDifference",groupLower);
            detail.put("upperDifference",groupUpper);
            detail.put("verdict",groupVerdict.name());
            detail.put("qualificationGaps",distinct);
            groupEvidence.put(code,detail);
            distinct.forEach(gap->allGaps.add("CRITICAL_GROUP_"+code+":"+gap));
        }
        List<String> distinct=allGaps.stream().distinct().toList();
        evidence.put("state",primaryQualified?"QUALIFIED_FORMAL_COMPARISON":"UNQUALIFIED");
        evidence.put("criticalGroups",groupEvidence);
        evidence.put("qualificationGaps",distinct);
        return new FormalTraffic(lower,upper,groupVerdicts,evidence,distinct);
    }

    private record PromotionCoverage(Map<String,Object> evidence,List<String> gaps) { }

    private static PromotionCoverage targetPromotionCoverage(ListingActionRepository.ActionRow action,JsonNode context,
                                                               Instant from,Instant to) {
        var evidence=new LinkedHashMap<>(promotionContextEvidence(context));
        var gaps=new ArrayList<String>();
        if (!"QUALIFIED_COMPLETE".equals(context.path("coverage").asText()))
            gaps.add("TARGET_PROMOTION_CONTEXT_UNQUALIFIED");
        JsonNode match=null;
        int matches=0;
        if (action.promotionTermsDigest()==null || !action.promotionTermsDigest().matches("[0-9a-f]{64}")) {
            gaps.add("TARGET_PROMOTION_TERMS_UNBOUND");
        } else if (context.path("records").isArray()) {
            for (JsonNode record:context.path("records"))
                if (action.promotionTermsDigest().equals(record.path("promotionTermsDigest").asText())) {
                    match=record;
                    matches++;
                }
        }
        if (matches!=1) gaps.add("TARGET_PROMOTION_VERSION_UNQUALIFIED");
        if (match!=null) {
            evidence.put("promotionTermsDigest",action.promotionTermsDigest());
            evidence.put("participationState",match.path("participationState").asText());
            evidence.put("newTransactionsState",match.path("newTransactionsState").asText());
            evidence.put("residualObligationState",match.path("residualObligationState").asText());
            evidence.put("commonApplicableFrom",match.path("commonApplicableFrom").asText(null));
            evidence.put("commonApplicableUntil",match.path("commonApplicableUntil").asText(null));
            evidence.put("localEngagementId",match.path("localEngagementId").asText(null));
            evidence.put("localEngagementState",match.path("localEngagementState").asText(null));
            evidence.put("localEngagementCreatedAt",match.path("localEngagementCreatedAt").asText(null));
            evidence.put("localNewTransactionsStoppedAt",match.path("localNewTransactionsStoppedAt").asText(null));
            String participation=match.path("participationState").asText();
            String transactions=match.path("newTransactionsState").asText();
            String residual=match.path("residualObligationState").asText();
            Instant createdAt=instant(match.path("localEngagementCreatedAt"));
            JsonNode stoppedValue=match.path("localNewTransactionsStoppedAt");
            Instant stoppedAt=instant(stoppedValue);
            boolean stoppedShape=stoppedAt!=null
                    || ((stoppedValue.isMissingNode() || stoppedValue.isNull()) && "OPEN".equals(transactions));
            boolean full=!"UNKNOWN".equals(participation) && !"UNKNOWN".equals(transactions)
                    && !"UNKNOWN".equals(residual) && uuid(match.path("localEngagementId"))!=null
                    && createdAt!=null && !createdAt.isAfter(from) && stoppedShape
                    && (stoppedAt==null || !stoppedAt.isBefore(to))
                    && sameInstant(from,match.path("commonApplicableFrom"))
                    && sameInstant(to,match.path("commonApplicableUntil"));
            if (!full) gaps.add("TARGET_PROMOTION_WINDOW_UNQUALIFIED");
        }
        evidence.put("matchingRecordCount",matches);
        List<String> distinct=gaps.stream().distinct().toList();
        evidence.put("state",distinct.isEmpty()?"FULL_TARGET_PROMOTION_COVERAGE":"UNQUALIFIED");
        evidence.put("qualificationGaps",distinct);
        return new PromotionCoverage(evidence,distinct);
    }

    private record Futility(boolean triggered,Map<String,Object> evidence) { }

    private static Futility futility(EvaluationRepository.PlanRow plan,String nodeCode,
                                     BigDecimal qualifiedUpperImprovement,boolean maturity) {
        var evidence=new LinkedHashMap<String,Object>();
        if (plan.stopRule()==null || plan.stopRule().isEmpty()) {
            evidence.put("state","DISABLED");
            return new Futility(false,evidence);
        }
        var rule=FrozenFutilityRule.resolve(plan.stopRule(),plan.formalNodes()).orElse(null);
        if (rule==null) {
            evidence.put("state","UNQUALIFIED");
            return new Futility(false,evidence);
        }
        evidence.put("nodeCode",rule.nodeCode());
        evidence.put("minimumEffect",rule.minimumEffect());
        evidence.put("qualificationRef",rule.qualificationReference());
        evidence.put("qualifiedUpperImprovement",qualifiedUpperImprovement);
        if (!rule.nodeCode().equals(nodeCode)) {
            evidence.put("state","NOT_APPLICABLE_AT_NODE");
            return new Futility(false,evidence);
        }
        if (!maturity || qualifiedUpperImprovement==null) {
            evidence.put("state","UNQUALIFIED");
            return new Futility(false,evidence);
        }
        boolean triggered=ProtectionVector.stopTriggered(qualifiedUpperImprovement,rule.minimumEffect(),true,true,true);
        evidence.put("state",triggered?"TRIGGERED":"QUALIFIED_NOT_TRIGGERED");
        return new Futility(triggered,evidence);
    }

    private static UUID uuid(JsonNode value) {
        try { return value.isTextual()?UUID.fromString(value.asText()):null; }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private static Instant instant(JsonNode value) {
        try { return value.isTextual()?Instant.parse(value.asText()):null; }
        catch (java.time.format.DateTimeParseException invalid) { return null; }
    }

    private static boolean sameInstant(Instant expected,JsonNode value) {
        try { return value.isTextual() && expected.equals(Instant.parse(value.asText())); }
        catch (java.time.format.DateTimeParseException invalid) { return false; }
    }

    private static Map<String,BigDecimal> frozenWeights(JsonNode declared) {
        if (!declared.isObject() || declared.size()!=2) return Map.of();
        var result=new LinkedHashMap<String,BigDecimal>();
        try {
            for (String source:List.of("ADVERTISING","ORGANIC")) {
                JsonNode value=declared.path(source);
                if (!value.isNumber() && !value.isTextual()) return Map.of();
                BigDecimal weight=new BigDecimal(value.asText());
                if (weight.signum()<0) return Map.of();
                result.put(source,weight);
            }
            if (result.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(BigDecimal.ONE)!=0)
                return Map.of();
            return result;
        } catch (NumberFormatException invalid) { return Map.of(); }
    }

    private static boolean sameWeights(Map<String,BigDecimal> left,Map<String,BigDecimal> right) {
        return left.keySet().equals(right.keySet()) && left.entrySet().stream()
                .allMatch(entry->entry.getValue().compareTo(right.get(entry.getKey()))==0);
    }

    private static Map<String,FixedTrafficComparison.Count> sourceCounts(JsonNode counts) {
        var result=new LinkedHashMap<String,FixedTrafficComparison.Count>();
        try {
            for (String source:List.of("ADVERTISING","ORGANIC")) {
                var count=counts.path(source);
                if (!count.path("visits").isIntegralNumber() || !count.path("visits").canConvertToLong()
                        || !count.path("retained").isIntegralNumber() || !count.path("retained").canConvertToLong()) return Map.of();
                result.put(source,new FixedTrafficComparison.Count(
                        count.path("visits").longValue(),count.path("retained").longValue()));
            }
        } catch (IllegalArgumentException invalid) { return Map.of(); }
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<EvaluationView> view(AuthenticatedActor actor, UUID actionId) {
        var action = actions.action(actionId).orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        scopes.require(actor, action.listingId(), ActionScopeCode.LISTING_CONVERSION_VIEW);
        return viewAuthorized(actionId);
    }

    private Optional<EvaluationView> viewAuthorized(UUID actionId) {
        return evaluations.plan(actionId).map(plan -> {
            List<EvaluationView.Node> nodes = new ArrayList<>();
            plan.formalNodes().forEach(node -> nodes.add(new EvaluationView.Node(node.path("nodeCode").asText(),
                    node.path("maturityDays").asInt(), node.path("method").asText(), new BigDecimal(node.path("threshold").asText()))));
            List<String> groups = new ArrayList<>();
            plan.criticalGroups().forEach(group -> groups.add(group.isObject() ? group.path("code").asText() : group.asText()));
            Map<String, String> stop = new LinkedHashMap<>();
            plan.stopRule().properties().forEach(entry -> stop.put(entry.getKey(), displayValue(entry.getValue())));
            Map<String, String> coverage = new LinkedHashMap<>();
            plan.versionCoverage().properties().forEach(entry -> coverage.put(entry.getKey(), displayValue(entry.getValue())));
            return new EvaluationView(plan.id(), plan.actionId(), plan.calibrationPackageId(), plan.calibrationVersion(), coverage,
                    "EXCLUDE_TRANSITION_DAYS", plan.latestBoundary(), nodes, stop, groups, plan.comparisonBasis(),
                    plan.crossPeriodWindowDays(), plan.planDigest(), plan.frozenAt(),
                    Map.of("versionCoverage",plan.versionCoverage().deepCopy(),"formalNodes",plan.formalNodes().deepCopy(),
                            "stopRule",plan.stopRule().deepCopy(),"criticalGroups",plan.criticalGroups().deepCopy()),
                    evaluations.results(plan.id()),
                    evaluations.revisions(plan.id()));
        });
    }

    private static String displayValue(JsonNode value) {
        return value.isValueNode() ? value.asText() : value.toString();
    }

    // ------------------------------------------------------------------ simulation

    @Transactional
    public SimulationView simulate(AuthenticatedActor actor, UUID candidateId, PromotionSimulator.Inputs inputs,
                                   List<PromotionSimulator.Scenario> scenarios, BigDecimal referenceProfitLine,
                                   SimulationAssumptions context,
                                   com.mimococo.marketops.listingconversion.ListingActionPurpose purpose) {
        var candidate = actions.candidate(candidateId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        scopes.require(actor, candidate.platformListingId(), ActionScopeCode.LISTING_ACTION_PREPARE);
        ListingFactRepository.ListingContext listing = facts.listing(candidate.platformListingId()).orElseThrow();
        var intendedPurpose=purpose==null
                ?com.mimococo.marketops.listingconversion.ListingActionPurpose.PROMOTION:purpose;
        if (!java.util.Set.of(com.mimococo.marketops.listingconversion.ListingActionPurpose.PROMOTION,
                        com.mimococo.marketops.listingconversion.ListingActionPurpose.BOUNDED_EXPLORATION).contains(intendedPurpose))
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        Instant now = actions.databaseNow();
        if (context == null) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        PromotionSimulator.Simulation simulation = PromotionSimulator.simulate(inputs, scenarios, referenceProfitLine);
        List<Map<String, Object>> scenarioRows = new ArrayList<>();
        scenarios.forEach(s -> scenarioRows.add(Map.of("code", s.code(), "quantity", String.valueOf(s.quantity()),
                "necessary", s.necessary(), "conservative", s.conservative())));
        List<Map<String, Object>> resultRows = new ArrayList<>();
        simulation.scenarios().forEach(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", r.code());
            row.put("state", r.state());
            row.put("quantity", r.quantity() == null ? null : r.quantity().toPlainString());
            row.put("netRevenue", r.netRevenue() == null ? null : r.netRevenue().toPlainString());
            row.put("contributionProfit", r.contributionProfit() == null ? null : r.contributionProfit().toPlainString());
            row.put("missingInputs", r.missingInputs());
            resultRows.add(row);
        });
        UUID id = ids.newId();
        var identitySnapshot = facts.identitySnapshot(candidate.platformListingId(), now);
        var members = ListingFactRepository.snapshotMembers(identitySnapshot.identityLineage());
        boolean nativeComplete = "COMPLETE".equals(identitySnapshot.identityLineage().path("nativeScope").path("state").asText());
        List<UUID> evidenceScope = nativeComplete && !members.isEmpty() && members.stream().allMatch(m -> !m.conflictOpen() && m.productVariantId() != null)
                ? members.stream().map(m -> m.productVariantId()).distinct().toList() : List.of();
        String declaredTermsDigest=context.commercialDeclaration()==null?null:actions.promotionTermsDigest(context.commercialDeclaration());
        var currentCalibration=calibration.resolve(listing.organizationId(),listing.platformCode(),listing.storeId(),now,
                intendedPurpose.name());
        var promotionContext=actions.currentPromotionContext(listing.organizationId(),listing.id(),
                context.periodStart(),context.periodEnd(),now);
        var revenueEvidence=simulationFees.revenue(listing,inputs,context,declaredTermsDigest,now);
        var variableFeeEvidence=simulationFees.read(listing,inputs,context,now,revenueEvidence);
        var fixedFeeEvidence=simulationFees.fixedFee(listing,inputs,context,now);
        var costEvidence=simulationFees.periodCosts(listing.organizationId(),evidenceScope,inputs,context,now);
        var demandEvidence=CalibrationService.demandEvidence(currentCalibration,listing.id(),context,scenarios,now);
        var profitReferenceEvidence=CalibrationService.profitReferenceEvidence(currentCalibration,listing.id(),context,
                referenceProfitLine,inputs.currencyCode(),now);
        String qualification=ListingSimulationInputEvidence.qualification(variableFeeEvidence,fixedFeeEvidence,revenueEvidence,
                costEvidence,demandEvidence,profitReferenceEvidence,promotionContext,nativeComplete,!evidenceScope.isEmpty(),
                simulation.conditionalScenariosPassed());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("modelVersion", PromotionSimulator.MODEL_VERSION);
        snapshot.put("sourceKind", ListingSimulationInputEvidence.QUALIFIED.equals(qualification)
                ?"QUALIFIED_MATCHED_INPUTS":"DECLARED_CONDITIONAL_INPUTS");
        snapshot.put("qualificationState",qualification);
        snapshot.put("knownPromotionContext",promotionContext);
        snapshot.put("variableFeeEvidence",variableFeeEvidence);
        snapshot.put("fixedFeeEvidence",fixedFeeEvidence);
        snapshot.put("revenueEvidence",revenueEvidence);
        snapshot.put("currentCostEvidence",costEvidence);
        snapshot.put("demandEvidence",demandEvidence);
        snapshot.put("profitReferenceEvidence",profitReferenceEvidence);
        snapshot.put("organizationId", listing.organizationId());
        snapshot.put("listingId", candidate.platformListingId());
        snapshot.put("candidateId", candidateId);
        snapshot.put("purposeCode",intendedPurpose.name());
        snapshot.put("calibrationPackageId",currentCalibration.ok()?currentCalibration.resolved().packageId():null);
        snapshot.put("calibrationVersion",currentCalibration.ok()?currentCalibration.resolved().version():null);
        snapshot.put("submittedBy", actor.userId());
        snapshot.put("submittedAt", now);
        snapshot.put("inputs", ListingSimulationInputEvidence.inputSnapshot(inputs));
        snapshot.put("context", context);
        snapshot.put("promotionTermsDigest",declaredTermsDigest);
        snapshot.put("commercialDeclarationState", context.commercialDeclaration() == null
                ? "UNBOUND" : "EXACT_DECLARATION_ONLY");
        snapshot.put("scenarios", scenarios);
        snapshot.put("referenceProfitLine", referenceProfitLine);
        snapshot.put("observedMembers", members);
        snapshot.put("nativeUniverseQualification", nativeComplete ? "COMPLETE_IDENTITY_SOURCE" : "INCOMPLETE_IDENTITY_SOURCE");
        snapshot.put("nativeIdentityLineage", identitySnapshot.identityLineage());
        snapshot.put("nativeIdentityDigest", identitySnapshot.digest());
        // A conditional calculator does not publish a D30 metric or confer demand admission.
        evaluations.insertSimulation(id, listing.organizationId(), candidateId, scenarioRows, resultRows,
                simulation.inverseMinimumQuantity(), simulation.inverseState(), now, evidenceScope,
                PromotionSimulator.MODEL_VERSION, snapshot, simulation.conditionalScenariosPassed());
        return disclosure.simulation(actor, candidate.platformListingId(),
                evaluations.simulations(candidateId).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<SimulationView> simulations(AuthenticatedActor actor, UUID candidateId) {
        var candidate = actions.candidate(candidateId).orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        scopes.require(actor, candidate.platformListingId(), ActionScopeCode.LISTING_CONVERSION_VIEW);
        return evaluations.simulations(candidateId).stream()
                .map(row -> disclosure.simulation(actor, candidate.platformListingId(), row)).toList();
    }

    @Transactional(readOnly = true)
    public SimulationView simulation(AuthenticatedActor actor,UUID candidateId,UUID simulationId) {
        var candidate=actions.candidate(candidateId).orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        scopes.require(actor,candidate.platformListingId(),ActionScopeCode.LISTING_CONVERSION_VIEW);
        var selected=evaluations.simulation(candidateId,simulationId)
                .orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        return disclosure.simulation(actor,candidate.platformListingId(),selected);
    }

    static ActionScopeCode viewScope() {
        return ActionScopeCode.LISTING_CONVERSION_VIEW;
    }
}
