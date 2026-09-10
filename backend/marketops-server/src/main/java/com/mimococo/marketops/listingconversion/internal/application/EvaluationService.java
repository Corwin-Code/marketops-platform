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
import com.mimococo.marketops.listingconversion.internal.domain.ProtectionVector;
import com.mimococo.marketops.listingconversion.internal.domain.PromotionSimulator;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.EvaluationRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingHealthRepository;
import com.mimococo.marketops.operationsworkflow.ListingActionIntake;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
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

    EvaluationService(EvaluationRepository evaluations, ListingActionRepository actions, ListingHealthRepository measurements,
                      ListingFactRepository facts, CalibrationService calibration, CalculationRunLedger ledger,
                      ListingActionIntake intake, IdGenerator ids, Clock clock,
                      ListingScopeAuthorization scopes, ListingDisclosureService disclosure, ObjectMapper json) {
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
    }

    /** Freeze the plan from the bound calibration package, once. */
    @Transactional
    public UUID freezePlan(ListingActionRepository.ActionRow action) {
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
        CalibrationService.Outcome resolved = calibration.resolve(listing.organizationId(), listing.platformCode(),
                listing.storeId(), clock.instant());
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
        Instant now = clock.instant();
        Map<String, String> coverage = new LinkedHashMap<>();
        coverage.put("priorTextDigest", String.valueOf(action.currentTextDigest()));
        coverage.put("targetTextDigest", String.valueOf(action.targetTextDigest()));
        coverage.put("affectedSetDigest", action.affectedSetDigest());
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
        String digest = Digest.ofComponents(List.of("lc-frozen-plan-2", action.id().toString(),
                json.writeValueAsString(coverage), json.writeValueAsString(nodes), json.writeValueAsString(stopRule),
                json.writeValueAsString(groups), "PRIOR_VERSION_WINDOW", "EXCLUDE_TRANSITION_DAYS",
                boundary.toString(), now.toString(), Integer.toString(crossPeriod),
                action.calibrationPackageId() + ":" + action.calibrationVersion()));
        UUID planId = ids.newId();
        actions.insertPlan(planId, action.organizationId(), action.id(), action.calibrationPackageId(), action.calibrationVersion(),
                coverage, boundary, nodes, stopRule, groups,
                "PRIOR_VERSION_WINDOW", crossPeriod, digest, now);
        return planId;
    }

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

    @Transactional
    public EvaluationView evaluateNode(AuthenticatedActor actor, UUID actionId, String nodeCode, String stage,
                                       UUID measurementId, BigDecimal conservativeBound, ProtectionInputs protections,
                                       String lateFactReference) {
        ListingActionRepository.ActionRow action = actions.action(actionId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        scopes.require(actor, action.listingId(), ActionScopeCode.LISTING_OUTCOME_EVALUATE);
        EvaluationRepository.PlanRow plan = evaluations.plan(actionId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION));
        JsonNode node = null;
        for (JsonNode candidate : plan.formalNodes()) {
            if (candidate.path("nodeCode").asText().equals(nodeCode)) {
                node = candidate;
            }
        }
        if (node == null || (!"OPERATIONAL".equals(stage) && !"SETTLED".equals(stage))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        BigDecimal threshold = new BigDecimal(node.path("threshold").asText());
        Instant now = clock.instant();
        Optional<ConversionMeasurementView> measurement = measurementId == null ? Optional.empty()
                : measurements.measurement(measurementId);
        if (measurementId != null && (measurement.isEmpty()
                || !measurement.get().platformListingId().equals(action.listingId()))) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        BigDecimal ratio = measurement.filter(m -> m.ratioState() == RatioState.DEFINED)
                .map(ConversionMeasurementView::primaryRatio).orElse(null);
        boolean maturity = measurement.map(ConversionMeasurementView::maturityReached).orElse(false);
        // A request number cannot be a qualified comparison bound. The measured
        // absolute ratio stays visible as a fact, separately from improvement.
        BigDecimal bound = null;
        NodeVerdict verdict = ProtectionVector.nodeVerdict(ratio, bound, threshold, maturity);

        // The frozen, canonical comparison and protection evidence must supply
        // these dimensions. Until resolved, caller assertions cannot turn any
        // missing dimension into PASS (or manufacture a FAIL).
        Map<String, ProtectionVerdict> vector = new LinkedHashMap<>();
        ProtectionVector.REQUIRED.forEach(code -> vector.put(code, ProtectionVerdict.UNDETERMINED));
        for (JsonNode group : plan.criticalGroups()) {
            String code = group.isObject() ? group.path("code").asText() : group.asText();
            vector.put("CRITICAL_GROUP_" + code, ProtectionVerdict.UNDETERMINED);
        }
        ProtectionVerdict protection = ProtectionVector.verdictOf(vector);
        boolean stopNode = nodeCode.equals(plan.stopRule().path("nodeCode").asText());
        // A lower bound that misses the target is not a futility proof. Until
        // the exact frozen comparison supplies a qualified upper bound, this
        // result cannot trigger the independent effect-shortfall stop rule.
        boolean stop = ProtectionVector.stopTriggered(null, threshold, stopNode, maturity, false);

        UUID runId = ledger.recordCompletedRun(new CalculationRunLedger.CompletedRun(action.organizationId(), action.storeId(),
                lateFactReference == null ? "MANUAL" : "LATE_DATA", MetricWindow.D30, now.minus(Duration.ofDays(30)), now,
                Digest.ofText("lc-node-1"), 1, 1, true, null, now, actor.userId()));
        Optional<UUID> original = evaluations.latestResult(plan.id(), nodeCode, stage);
        int revision = evaluations.nextRevision(plan.id(), nodeCode, stage);
        UUID resultId = ids.newId();
        evaluations.insertResult(resultId, action.organizationId(), plan.id(), nodeCode, stage, revision, measurementId, runId,
                ratio, bound, threshold, verdict, ProtectionVector.toStrings(vector), protection, stop, maturity,
                measurement.map(ConversionMeasurementView::sourceTime).orElse(null), now);
        if (original.isPresent()) {
            evaluations.insertRevision(ids.newId(), action.organizationId(), plan.id(), original.get(), resultId,
                    lateFactReference == null ? "CORRECTION" : "LATE_FACT",
                    lateFactReference == null ? "re-evaluation:" + resultId : MetadataFieldPolicy.requireText("lateFactReference", lateFactReference),
                    now);
        }
        intake.recordTaskOutcome(action.recommendationId(), "SETTLED".equals(stage) ? (original.isPresent() ? "SETTLED_REVISED" : "SETTLED") : "OPERATIONAL",
                "lc-node-result:" + resultId, "node " + nodeCode + " " + verdict + " protections " + protection);
        return viewAuthorized(actionId).orElseThrow();
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
            plan.stopRule().properties().forEach(entry -> stop.put(entry.getKey(), entry.getValue().asText()));
            Map<String, String> coverage = new LinkedHashMap<>();
            plan.versionCoverage().properties().forEach(entry -> coverage.put(entry.getKey(), entry.getValue().asText()));
            return new EvaluationView(plan.id(), plan.actionId(), plan.calibrationPackageId(), plan.calibrationVersion(), coverage,
                    "EXCLUDE_TRANSITION_DAYS", plan.latestBoundary(), nodes, stop, groups, plan.comparisonBasis(),
                    plan.crossPeriodWindowDays(), plan.planDigest(), plan.frozenAt(),
                    Map.of("versionCoverage",plan.versionCoverage().deepCopy(),"formalNodes",plan.formalNodes().deepCopy(),
                            "stopRule",plan.stopRule().deepCopy(),"criticalGroups",plan.criticalGroups().deepCopy()),
                    evaluations.results(plan.id()),
                    evaluations.revisions(plan.id()));
        });
    }

    // ------------------------------------------------------------------ simulation

    @Transactional
    public SimulationView simulate(AuthenticatedActor actor, UUID candidateId, PromotionSimulator.Inputs inputs,
                                   List<PromotionSimulator.Scenario> scenarios, BigDecimal referenceProfitLine) {
        var candidate = actions.candidate(candidateId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        scopes.require(actor, candidate.platformListingId(), ActionScopeCode.LISTING_ACTION_PREPARE);
        ListingFactRepository.ListingContext listing = facts.listing(candidate.platformListingId()).orElseThrow();
        Instant now = clock.instant();
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
        UUID runId = ledger.recordCompletedRun(new CalculationRunLedger.CompletedRun(listing.organizationId(), listing.storeId(),
                "MANUAL", MetricWindow.D30, now.minus(Duration.ofDays(30)), now, Digest.ofText("lc-simulation-1"), 1, 1, true,
                null, now, actor.userId()));
        String inputsDigest = Digest.ofComponents(List.of(inputs.toString(), scenarios.toString(), String.valueOf(referenceProfitLine)));
        UUID id = ids.newId();
        var members = facts.members(candidate.platformListingId(), now);
        List<UUID> evidenceScope = !members.isEmpty() && members.stream().allMatch(m -> !m.conflictOpen() && m.productVariantId() != null)
                ? members.stream().map(m -> m.productVariantId()).distinct().toList() : List.of();
        evaluations.insertSimulation(id, listing.organizationId(), candidateId, runId, scenarioRows, inputsDigest, resultRows,
                simulation.inverseMinimumQuantity(), simulation.inverseState(), simulation.demandGatePassed(), now, evidenceScope);
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

    static ActionScopeCode viewScope() {
        return ActionScopeCode.LISTING_CONVERSION_VIEW;
    }
}
