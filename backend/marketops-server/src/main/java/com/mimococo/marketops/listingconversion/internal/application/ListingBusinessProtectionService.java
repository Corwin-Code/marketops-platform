package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.availabilityrisk.SupplyCoverageQuery;
import com.mimococo.marketops.analyticsdecision.CanonicalScopeMetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.listingconversion.internal.domain.AffectedSetResolution;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.shared.Digest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Current protection consumption; each owning domain retains its facts and arithmetic. */
@Service
class ListingBusinessProtectionService {
    private final ListingFactRepository facts;
    private final SupplyCoverageQuery supply;
    private final ObjectMapper json;
    private final CanonicalScopeMetricQuery metrics;
    private final ListingActionRepository actions;
    private final com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.EvaluationRepository evaluations;
    private final ListingSimulationInputEvidence simulationInputs;
    private final CalibrationService calibrationSources;

    ListingBusinessProtectionService(ListingFactRepository facts,SupplyCoverageQuery supply,ObjectMapper json,
                                     CanonicalScopeMetricQuery metrics, ListingActionRepository actions,
                                     com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.EvaluationRepository evaluations,
                                     ListingSimulationInputEvidence simulationInputs, CalibrationService calibrationSources) {
        this.facts=facts;this.supply=supply;this.json=json;this.metrics=metrics;
        this.actions=actions;this.evaluations=evaluations;this.simulationInputs=simulationInputs;this.calibrationSources=calibrationSources;
    }

    Map<String,String> assess(ListingActionRepository.ActionRow action,CalibrationService.Outcome calibration,Instant at) {
        var detail=new LinkedHashMap<String,String>();
        detail.put("model","LC_CURRENT_BUSINESS_PROTECTION_1");
        detail.put("currentProfitVerdict","UNDETERMINED");
        detail.put("currentReturnVerdict","UNDETERMINED");
        detail.put("assessedAt",at.toString());
        var gaps=new ArrayList<String>();
        var snapshot=facts.identitySnapshot(action.listingId(),at);
        var nativeScope=snapshot.identityLineage().path("nativeScope");
        var nativeReasons=new ArrayList<String>();
        nativeScope.path("reasonCodes").forEach(reason->nativeReasons.add(reason.asText()));
        var scope=AffectedSetResolution.resolve(ListingFactRepository.snapshotMembers(snapshot.identityLineage()),
                nativeScope.path("state").asText(),nativeReasons);
        if (!"COMPLETE".equals(scope.state()) || !snapshot.digest().equals(action.affectedSetDigest()))
            gaps.add("PROTECTION_SCOPE_CHANGED_OR_UNQUALIFIED");
        if (!calibration.ok()) gaps.add("PROTECTION_CALIBRATION_UNRESOLVED");
        if (gaps.isEmpty()) currentFinancialInputs(action,scope,calibration.resolved(),at,detail);
        else {
            detail.put("financialInputState","UNDETERMINED");
            detail.put("financialInputReasonCodes",String.join(",",gaps));
        }
        var projections=new ArrayList<Map<String,Object>>();
        boolean failed=false;
        if (gaps.isEmpty()) {
            var rule=calibration.resolved().values().get("DEMAND_SCENARIO_SET");
            var scenarios=rule==null || rule.json()==null?null:rule.json().get("supplyScenarios");
            if (scenarios==null || !scenarios.isArray() || scenarios.isEmpty()) gaps.add("SUPPLY_SCENARIOS_UNQUALIFIED");
            else for (UUID product:scope.productVariantIds()) {
                var codes=new java.util.HashSet<String>();
                for (var scenario:scenarios) {
                    if (!product.toString().equals(scenario.path("productVariantId").asText())) continue;
                    String code=scenario.path("code").asText("");
                    var rate=scenario.path("companyDailyFulfillmentUnits");
                    var days=scenario.path("coverageDays");
                    if (code.isBlank() || !codes.add(code) || !rate.isNumber() || rate.decimalValue().signum()<0
                            || !days.isIntegralNumber() || !days.canConvertToInt() || days.asInt()<=0
                            || scenario.path("evidenceReference").asText("").isBlank()) {
                        gaps.add("SUPPLY_SCENARIO_INVALID:"+product);continue;
                    }
                    var projection=supply.project(new SupplyCoverageQuery.Scenario(action.organizationId(),product,
                            rate.decimalValue(),days.asInt(),at));
                    projections.add(Map.of("code",code,"evidenceReference",scenario.path("evidenceReference").asText(),
                            "projection",projection));
                    failed|="FAIL".equals(projection.verdict());
                    if (!"PASS".equals(projection.verdict())) gaps.add("SUPPLY_SCENARIO_"+projection.verdict()+":"+product);
                    projection.gaps().forEach(gap->gaps.add("SUPPLY:"+product+":"+gap));
                }
                if (codes.isEmpty()) gaps.add("SUPPLY_SCENARIO_MISSING:"+product);
            }
        }
        detail.put("supplyVerdict",failed?"FAIL":gaps.isEmpty()?"PASS":"UNDETERMINED");
        detail.put("supplyProjectionDigest",Digest.ofText(json.writeValueAsString(projections)));
        detail.put("supplySourceDigests",json.writeValueAsString(projections.stream()
                .map(projection->((SupplyCoverageQuery.Projection)projection.get("projection")).sourceDigest()).toList()));
        detail.put("supplyEvidenceReferences",json.writeValueAsString(projections.stream()
                .flatMap(value->((SupplyCoverageQuery.Projection)value.get("projection")).supplyEvidence().stream())
                .map(SupplyCoverageQuery.SupplyEvidence::provenanceId).filter(java.util.Objects::nonNull).distinct().sorted().toList()));
        detail.put("supplyScenarios",json.writeValueAsString(projections.stream().map(value->{
            var projection=(SupplyCoverageQuery.Projection)value.get("projection");
            return Map.of("productVariantId",projection.scenario().productVariantId(),"code",value.get("code"),
                    "verdict",projection.verdict(),"sourceDigest",projection.sourceDigest());
        }).toList()));
        // Shared workflow disclosure carries qualification and references, not unscoped inventory quantities.
        detail.put("supplyReasonCodes",String.join(",",gaps.stream().distinct().toList()));
        recheckSelectedSimulation(action,scope,at,detail);
        return Map.copyOf(detail);
    }

    private void recheckSelectedSimulation(ListingActionRepository.ActionRow action,AffectedSetResolution.Resolution scope,
            Instant at,Map<String,String> detail) {
        var selected=actions.selectedSimulation(action.id());
        if (!selected.declared()) {
            if ("LISTING_PROMOTION_ACTION".equals(action.actionKind())) {
                detail.put("selectedSimulationInputState","MISSING");
                detail.put("selectedSimulationInvalidatedInputs","QUALIFIED_PROMOTION_SIMULATION_REQUIRED");
            }
            return;
        }
        if (selected.invalidated()) {
            detail.put("selectedSimulationInputState","INVALIDATED");
            detail.put("selectedSimulationInvalidatedInputs","BINDING");
            return;
        }
        detail.put("selectedSimulationId",selected.id().toString());
        var material=evaluations.simulation(action.candidateId(),selected.id()).orElse(null);
        if (material==null || material.inputSnapshot()==null || !"COMPLETE".equals(scope.state())
                || actions.matchingSimulationDigest(selected.id(),action.organizationId(),action.candidateId(),
                    action.affectedSetDigest(),action.promotionTermsDigest(),action.purposeCode(),
                    action.calibrationPackageId(),action.calibrationVersion(),at).isEmpty()) {
            detail.put("selectedSimulationInputState","INVALIDATED");
            return;
        }
        var snapshot=material.inputSnapshot();
        var decoded=ListingSimulationInputEvidence.declaredMaterial(snapshot,json);
        if (decoded.isEmpty()) {
            detail.put("selectedSimulationInputState","INVALIDATED");
            detail.put("selectedSimulationInvalidatedInputs","SIMULATION_MATERIAL_UNREADABLE");
            return;
        }
        var inputs=decoded.get().inputs();
        var context=decoded.get().context();
        var scenarios=decoded.get().scenarios();
        var currentPromotionContext=actions.currentPromotionContext(action.organizationId(),action.listingId(),
                context.periodStart(),context.periodEnd(),at);
        String frozenContextDigest=snapshot.path("knownPromotionContext").path("digest").asText("");
        String currentContextDigest=currentPromotionContext.path("digest").asText("");
        detail.put("knownPromotionContextDigest",currentContextDigest);
        detail.put("knownPromotionContextCoverage",currentPromotionContext.path("coverage").asText());
        if (frozenContextDigest.isBlank() || !frozenContextDigest.equals(currentContextDigest)) {
            detail.put("selectedSimulationInputState","INVALIDATED");
            detail.put("selectedSimulationInvalidatedInputs","KNOWN_PROMOTION_CONTEXT_CHANGED_OR_UNBOUND");
            return;
        }
        var listing=facts.listing(action.listingId()).orElseThrow();
        var bound=action.calibrationPackageId()==null || action.calibrationVersion()==null
                ?new CalibrationService.Outcome(null,"BOUND_CALIBRATION_UNRESOLVED")
                :calibrationSources.resolveBound(action.organizationId(),listing.platformCode(),action.storeId(),
                    action.calibrationPackageId(),action.calibrationVersion(),action.createdAt());
        var revenueEvidence=simulationInputs.revenue(listing,inputs,context,action.promotionTermsDigest(),at);
        var profitReferenceEvidence=CalibrationService.profitReferenceEvidence(bound,action.listingId(),context,
                snapshot.path("referenceProfitLine").isNumber()?snapshot.path("referenceProfitLine").decimalValue():null,
                inputs.currencyCode(),at);
        var current=Map.of(
            "variableFeeEvidence",simulationInputs.read(listing,inputs,context,at,revenueEvidence),
            "fixedFeeEvidence",simulationInputs.fixedFee(listing,inputs,context,at),
            "revenueEvidence",revenueEvidence,
            "currentCostEvidence",simulationInputs.periodCosts(action.organizationId(),scope.productVariantIds(),inputs,context,at),
            "demandEvidence",CalibrationService.demandEvidence(bound,action.listingId(),context,scenarios,at),
            "profitReferenceEvidence",profitReferenceEvidence);
        var qualifiedStates=Map.of("variableFeeEvidence","VARIABLE_FEES_MATCH_PROFILE","fixedFeeEvidence","ACTIVITY_FIXED_FEE_MATCH","revenueEvidence","COMMERCIAL_REVENUE_MATCH",
                "currentCostEvidence","PERIOD_MEMBER_COST_BOUND","demandEvidence","ACCEPTED_NECESSARY_SCENARIOS_MATCH",
                "profitReferenceEvidence","ACCEPTED_PROFIT_REFERENCE_BOUND");
        var invalidated=new ArrayList<String>();
        for (String input:List.of("variableFeeEvidence","fixedFeeEvidence","revenueEvidence","currentCostEvidence","demandEvidence","profitReferenceEvidence")) {
            var evidence=current.get(input);
            String currentState=String.valueOf(evidence.get("state"));
            detail.put("selectedSimulation:"+input,currentState);
            detail.put("selectedSimulation:"+input+":reasonCodes",json.writeValueAsString(evidence.get("gaps")));
            var references=new LinkedHashMap<String,Object>();
            for (String field:List.of("profileId","profileVersion","evidenceReference","verifiedAt","fulfillmentMode",
                    "componentIds","componentPriceBases","asOf","members","packageId","packageVersion","acceptedAt","financeInputVersionId","provenanceId","effectiveFrom","financeInputVersionIds","provenanceIds")) {
                if (evidence.containsKey(field)) references.put(field,evidence.get(field));
            }
            detail.put("selectedSimulation:"+input+":references",json.writeValueAsString(references));
            if (!qualifiedStates.get(input).equals(currentState)) invalidated.add(input);
        }
        var currentCalculation=com.mimococo.marketops.listingconversion.internal.domain.PromotionSimulator.simulate(inputs,scenarios,
                snapshot.path("referenceProfitLine").isNumber()?snapshot.path("referenceProfitLine").decimalValue():null);
        String currentQualification=ListingSimulationInputEvidence.qualification(current.get("variableFeeEvidence"),
                current.get("fixedFeeEvidence"),current.get("revenueEvidence"),current.get("currentCostEvidence"),
                current.get("demandEvidence"),current.get("profitReferenceEvidence"),currentPromotionContext,
                "COMPLETE".equals(scope.state()),!scope.productVariantIds().isEmpty(),currentCalculation.conditionalScenariosPassed());
        boolean wasQualified=ListingSimulationInputEvidence.QUALIFIED.equals(snapshot.path("qualificationState").asText());
        if (!wasQualified) invalidated.add("ORIGINAL_SIMULATION_UNQUALIFIED");
        if (!ListingSimulationInputEvidence.QUALIFIED.equals(currentQualification)) invalidated.add("CURRENT_ECONOMICS_UNQUALIFIED");
        detail.put("selectedSimulationQualification",currentQualification);
        detail.put("selectedSimulationInputState",invalidated.isEmpty()?"QUALIFIED_CURRENT":"INVALIDATED");
        detail.put("selectedSimulationInvalidatedInputs",String.join(",",invalidated));
        // A qualified state is a conjunction over the same current sources, context and arithmetic.
    }
    private void currentFinancialInputs(ListingActionRepository.ActionRow action,
            AffectedSetResolution.Resolution scope,CalibrationService.Resolved calibration,
            Instant at,Map<String,String> detail) {
        var profitRule=calibration.values().get("NON_WORSENING_PROFIT_BOUND");
        var freshness=calibration.values().get("FRESHNESS_RULE");
        var rule=freshness==null || freshness.json()==null?null:freshness.json().get("businessProtection");
        detail.put("financialInputState","UNDETERMINED");
        if (profitRule==null || profitRule.windowDays()==null || !List.of(7,14,30).contains(profitRule.windowDays())
                || rule==null || !rule.path("maximumVerificationAgeSeconds").isIntegralNumber()
                || !rule.path("maximumVerificationAgeSeconds").canConvertToLong()
                || rule.path("maximumVerificationAgeSeconds").asLong()<=0
                || !rule.path("maximumPeriodEndAgeSeconds").isIntegralNumber()
                || !rule.path("maximumPeriodEndAgeSeconds").canConvertToLong()
                || rule.path("maximumPeriodEndAgeSeconds").asLong()<=0) {
            detail.put("financialInputReasonCodes","CURRENT_FINANCIAL_INPUT_RULE_UNQUALIFIED");return;
        }
        var projection=metrics.current(new CanonicalScopeMetricQuery.CurrentScope(action.organizationId(),action.storeId(),
                scope.listingVariantIds(),MetricWindow.valueOf("D"+profitRule.windowDays()),at,
                CanonicalScopeMetricQuery.ProfitBasis.OPERATIONAL));
        if (projection.isEmpty()) {
            detail.put("financialInputReasonCodes","CURRENT_FINANCIAL_PERIOD_UNAVAILABLE");return;
        }
        var value=projection.get();
        var gaps=new ArrayList<String>();
        var references=new ArrayList<MetricValueView>();
        var dimensions=new LinkedHashMap<String,CanonicalScopeMetricQuery.Observation>();
        dimensions.put("DIRECT_PROFIT",value.contributionProfit());
        dimensions.put("OVERALL_RETURN",value.returnRate());
        var unitVerdicts=new LinkedHashMap<String,String>();
        for (UUID member:scope.listingVariantIds()) {
            var input=value.unitProfitInputs().get(member);
            var verdict=com.mimococo.marketops.listingconversion.internal.domain.CanonicalAccountingComparison.unitProfit(input,at);
            unitVerdicts.put(member.toString(),verdict.name());
            if (verdict==com.mimococo.marketops.listingconversion.ProtectionVerdict.UNDETERMINED)
                gaps.add("UNIT_PROFIT_FLOOR:"+member+":"+verdict.name());
            if (input!=null && input.contributionProfit()!=null && input.unitCount()!=null && input.requiredProfitPerUnit()!=null) {
                var components=List.of(input.contributionProfit(),input.unitCount(),input.requiredProfitPerUnit());
                dimensions.put("UNIT_PROFIT_FLOOR_INPUT:"+member,new CanonicalScopeMetricQuery.Observation(
                        input.contributionProfit().numericValue(),input.contributionProfit().currencyCode(),List.of(),components));
            }
        }
        detail.put("unitProfitFloorVerdicts",json.writeValueAsString(unitVerdicts));
        detail.put("unitProfitFloorVerdict",unitVerdicts.containsValue("FAIL")?"FAIL"
                :unitVerdicts.isEmpty() || unitVerdicts.containsValue("UNDETERMINED")?"UNDETERMINED":"PASS");
        var critical=calibration.values().get("CRITICAL_GROUP_RULE");
        var scopeBasis=critical==null || critical.json()==null?null:critical.json().path("protectionScopeBases").get(action.listingId().toString());
        var scopeEvidence=new ArrayList<Map<String,Object>>();
        var parsed=com.mimococo.marketops.listingconversion.internal.domain.ProtectionScopeBasis.parse(scopeBasis,scope.listingVariantIds());
        if (parsed.isEmpty()) gaps.add("PROTECTION_SCOPE_BASIS_UNQUALIFIED");
        else {
            for (var linked:parsed.get().linkedProfitScopes()) {
                var linkedProjection=metrics.project(new CanonicalScopeMetricQuery.Scope(action.organizationId(),linked.storeId(),
                        linked.listingVariantIds(),value.scope().window(),value.scope().periodStart(),value.scope().periodEnd(),at,value.scope().profitBasis()));
                dimensions.put("LINKED_PROFIT:"+linked.code(),linkedProjection.contributionProfit());
                scopeEvidence.add(Map.of("kind","LINKED_PROFIT","code",linked.code(),"projection",linkedProjection,
                        "evidenceReference",linked.evidenceReference()));
            }
            for (UUID member:parsed.get().criticalReturnVariantIds()) {
                var memberProjection=metrics.project(new CanonicalScopeMetricQuery.Scope(action.organizationId(),action.storeId(),
                        List.of(member),value.scope().window(),value.scope().periodStart(),value.scope().periodEnd(),at,value.scope().profitBasis()));
                dimensions.put("CRITICAL_RETURN:"+member,memberProjection.returnRate());
                scopeEvidence.add(Map.of("kind","CRITICAL_RETURN","projection",memberProjection));
            }
            detail.put("protectionScopeBasisDigest",Digest.ofText(json.writeValueAsString(scopeBasis)));
        }
        for (var dimension:dimensions.entrySet()) {
            var observation=dimension.getValue();
            observation.gaps().forEach(gap->gaps.add(dimension.getKey()+":"+gap));
            if (!observation.available() || observation.components().isEmpty()) gaps.add(dimension.getKey()+":INPUT_UNAVAILABLE");
            for (var component:observation.components()) {
                references.add(component);
                if (!component.available() || component.numericValue()==null || component.estimated()
                        || component.confidenceState()!=ConfidenceState.CANONICAL_CONFIRMED
                        || component.inputDigest()==null || component.inputDigest().isBlank() || component.evidenceRefs().isEmpty())
                    gaps.add(dimension.getKey()+":SOURCE_UNQUALIFIED");
                if (component.computedAt()==null || component.computedAt().isAfter(at)
                        || component.verifiedAt()==null || component.verificationRunId()==null
                        || component.verifiedAt().isAfter(at)
                        || java.time.Duration.between(component.verifiedAt(),at).compareTo(java.time.Duration.ofSeconds(
                                rule.path("maximumVerificationAgeSeconds").asLong()))>0)
                    gaps.add(dimension.getKey()+":VERIFICATION_UNQUALIFIED");
            }
        }
        if (java.time.Duration.between(value.scope().periodEnd(),at).compareTo(java.time.Duration.ofSeconds(
                rule.path("maximumPeriodEndAgeSeconds").asLong()))>0) gaps.add("CURRENT_FINANCIAL_PERIOD_STALE");
        if (parsed.isPresent()) {
            var qualifiedTargets=new LinkedHashMap<String,CanonicalScopeMetricQuery.Observation>();
            dimensions.forEach((dimension,observation)->{
                boolean unqualified=gaps.contains("CURRENT_FINANCIAL_PERIOD_STALE")
                        || gaps.stream().anyMatch(gap->gap.startsWith(dimension+":"));
                qualifiedTargets.put(dimension,unqualified?new CanonicalScopeMetricQuery.Observation(null,
                        observation.currencyCode(),List.of("CURRENT_INPUT_UNQUALIFIED"),observation.components()):observation);
            });
            compareCurrentAccounting(action,scope,calibration,value,parsed.get(),qualifiedTargets,at,detail);
        }
        detail.put("financialInputState",gaps.isEmpty()?"CANONICAL_INPUT_AVAILABLE":"UNDETERMINED");
        detail.put("financialInputReasonCodes",String.join(",",gaps.stream().distinct().sorted().toList()));
        detail.put("financialInputBasis",value.scope().profitBasis().name());
        detail.put("financialInputPeriodStart",value.scope().periodStart().toString());
        detail.put("financialInputPeriodEnd",value.scope().periodEnd().toString());
        detail.put("financialInputDigest",Digest.ofText(json.writeValueAsString(Map.of("direct",value,"independentScopes",scopeEvidence))));
        detail.put("financialInputDimensions",json.writeValueAsString(dimensions.keySet()));
        detail.put("financialMetricValueIds",json.writeValueAsString(references.stream().map(MetricValueView::metricValueId).distinct().sorted().toList()));
        detail.put("financialVerificationRunIds",json.writeValueAsString(references.stream().map(MetricValueView::verificationRunId)
                .filter(java.util.Objects::nonNull).distinct().sorted().toList()));
        // This qualifies current inputs only. It does not prove prospective economics,
        // independent non-worsening comparisons or formal Outcome qualification.
    }

    private void compareCurrentAccounting(ListingActionRepository.ActionRow action,
            AffectedSetResolution.Resolution scope,CalibrationService.Resolved calibration,
            CanonicalScopeMetricQuery.Projection current,
            com.mimococo.marketops.listingconversion.internal.domain.ProtectionScopeBasis scopeBasis,
            Map<String,CanonicalScopeMetricQuery.Observation> targets,Instant at,Map<String,String> detail) {
        var profitRule=calibration.values().get("NON_WORSENING_PROFIT_BOUND");
        var returnRule=calibration.values().get("NON_WORSENING_RETURN_BOUND");
        var basis=profitRule==null || profitRule.json()==null?null
                :profitRule.json().path("currentAccountingComparisons").get(action.listingId().toString());
        if (basis==null || !basis.isObject() || !basis.path("evidenceReference").isTextual()
                || basis.path("evidenceReference").asText().isBlank()
                || !basis.path("periodStart").isTextual() || !basis.path("periodEnd").isTextual()
                || returnRule==null || !"RATIO".equals(profitRule.unitCode()) || !"RATIO".equals(returnRule.unitCode())
                || !java.util.Objects.equals(profitRule.windowDays(),returnRule.windowDays())) {
            detail.put("currentAccountingReason","ACCEPTED_REFERENCE_UNQUALIFIED"); return;
        }
        Instant from,to;
        try {
            from=Instant.parse(basis.path("periodStart").asText());
            to=Instant.parse(basis.path("periodEnd").asText());
        } catch (java.time.format.DateTimeParseException invalidPeriod) {
            detail.put("currentAccountingReason","ACCEPTED_REFERENCE_PERIOD_INVALID"); return;
        }
        if (!from.isBefore(to) || to.isAfter(current.scope().periodStart())
                || !java.time.Duration.between(from,to).equals(
                        java.time.Duration.between(current.scope().periodStart(),current.scope().periodEnd()))) {
            detail.put("currentAccountingReason","ACCEPTED_REFERENCE_PERIOD_INAPPLICABLE"); return;
        }
        var references=new LinkedHashMap<String,CanonicalScopeMetricQuery.Observation>();
        var direct=metrics.project(new CanonicalScopeMetricQuery.Scope(action.organizationId(),action.storeId(),
                scope.listingVariantIds(),current.scope().window(),from,to,at,current.scope().profitBasis()));
        references.put("DIRECT_PROFIT",direct.contributionProfit());
        references.put("OVERALL_RETURN",direct.returnRate());
        for (var linked:scopeBasis.linkedProfitScopes()) {
            var reference=metrics.project(new CanonicalScopeMetricQuery.Scope(action.organizationId(),linked.storeId(),
                    linked.listingVariantIds(),current.scope().window(),from,to,at,current.scope().profitBasis()));
            references.put("LINKED_PROFIT:"+linked.code(),reference.contributionProfit());
        }
        for (UUID member:scopeBasis.criticalReturnVariantIds()) {
            var reference=metrics.project(new CanonicalScopeMetricQuery.Scope(action.organizationId(),action.storeId(),
                    List.of(member),current.scope().window(),from,to,at,current.scope().profitBasis()));
            references.put("CRITICAL_RETURN:"+member,reference.returnRate());
        }
        var profitVerdicts=new ArrayList<String>();
        var returnVerdicts=new ArrayList<String>();
        var verdicts=new LinkedHashMap<String,String>();
        references.forEach((dimension,reference)->{
            boolean profit=dimension.equals("DIRECT_PROFIT") || dimension.startsWith("LINKED_PROFIT:");
            var verdict=profit
                    ?com.mimococo.marketops.listingconversion.internal.domain.CanonicalAccountingComparison.profit(
                        reference,targets.get(dimension),profitRule.numeric())
                    :com.mimococo.marketops.listingconversion.internal.domain.CanonicalAccountingComparison.returns(
                        reference,targets.get(dimension),returnRule.numeric());
            verdicts.put(dimension,verdict.name());
            (profit?profitVerdicts:returnVerdicts).add(verdict.name());
        });
        detail.put("currentProfitVerdict",independentVerdict(profitVerdicts));
        detail.put("currentReturnVerdict",independentVerdict(returnVerdicts));
        detail.put("currentAccountingVerdicts",json.writeValueAsString(verdicts));
        detail.put("currentAccountingReferenceBasis",json.writeValueAsString(basis));
        detail.put("currentAccountingReferenceDigest",Digest.ofText(json.writeValueAsString(references)));
        detail.put("currentAccountingReferenceMetricIds",json.writeValueAsString(references.values().stream()
                .flatMap(v->v.components().stream()).map(MetricValueView::metricValueId).distinct().sorted().toList()));
        // This is current accounting protection, not a prospective promotion forecast or formal causal Outcome.
    }

    private static String independentVerdict(List<String> verdicts) {
        return verdicts.contains("FAIL")?"FAIL":verdicts.isEmpty() || verdicts.contains("UNDETERMINED")
                ?"UNDETERMINED":"PASS";
    }

}
