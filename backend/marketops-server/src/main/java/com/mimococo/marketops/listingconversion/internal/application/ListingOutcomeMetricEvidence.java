package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.analyticsdecision.CanonicalScopeMetricQuery;
import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Actual period/stage inputs. Availability alone is not a non-worsening comparison. */
@Service
class ListingOutcomeMetricEvidence {
    private final CanonicalScopeMetricQuery metrics;
    private final ListingActionRepository actions;

    ListingOutcomeMetricEvidence(CanonicalScopeMetricQuery metrics, ListingActionRepository actions) {
        this.metrics=metrics;
        this.actions=actions;
    }

    record Evidence(Map<String,Object> references,List<String> gaps,
                    Map<String,CanonicalScopeMetricQuery.Observation> observations,
                    Map<java.util.UUID,CanonicalScopeMetricQuery.UnitProfitInput> unitProfitInputs) {
        Evidence { references=Map.copyOf(references); gaps=List.copyOf(gaps); observations=Map.copyOf(observations); unitProfitInputs=Map.copyOf(unitProfitInputs); }
        Evidence(Map<String,Object> references,List<String> gaps) { this(references,gaps,Map.of(),Map.of()); }
    }

    Evidence read(ListingActionRepository.ActionRow action, Instant from, Instant to, int retentionDays,
                  String stage, Instant at) {
        if (from==null || to==null || !from.isBefore(to) || to.isAfter(at)
                || !List.of(7,14,30).contains(retentionDays))
            return new Evidence(Map.of(),List.of("PROTECTION_METRIC_PERIOD_UNQUALIFIED"));
        var members=actions.frozenDirectListingVariants(action.id());
        if (members.isEmpty()) return new Evidence(Map.of(),List.of("PROTECTION_DIRECT_SCOPE_UNQUALIFIED"));
        var basis=CanonicalScopeMetricQuery.ProfitBasis.valueOf(stage);
        var projection=metrics.project(new CanonicalScopeMetricQuery.Scope(action.organizationId(),action.storeId(),
                members,MetricWindow.valueOf("D"+retentionDays),from,to,at,basis));
        var gaps=new ArrayList<String>();
        var references=new LinkedHashMap<String,Object>();
        var observations=new LinkedHashMap<String,CanonicalScopeMetricQuery.Observation>();
        observations.put("DIRECT_CONTRIBUTION_PROFIT",projection.contributionProfit());
        observations.put("OVERALL_RETURN_RATE",projection.returnRate());
        references.put("profitBasis",basis.name());
        references.put("periodStart",from.toString());
        references.put("periodEnd",to.toString());
        references.put("directListingVariantIds",members);
        references.put("directProfit",qualify("DIRECT_PROFIT",projection.contributionProfit(),at,gaps));
        references.put("overallReturns",qualify("OVERALL_RETURN",projection.returnRate(),at,gaps));
        var frozenBasis=actions.frozenProtectionScopeBasis(action.id()).orElse(null);
        var parsed=com.mimococo.marketops.listingconversion.internal.domain.ProtectionScopeBasis.parse(frozenBasis,members);
        if (parsed.isEmpty()) gaps.add("FROZEN_PROTECTION_SCOPE_BASIS_UNQUALIFIED");
        else {
            references.put("protectionScopeEvidenceReference",parsed.get().evidenceReference());
            var linkedEvidence=new LinkedHashMap<String,Object>();
            for (var linked:parsed.get().linkedProfitScopes()) {
                var input=metrics.project(new CanonicalScopeMetricQuery.Scope(action.organizationId(),linked.storeId(),
                        linked.listingVariantIds(),MetricWindow.valueOf("D"+retentionDays),from,to,at,basis));
                observations.put("LINKED_SCOPE_PROFIT:"+linked.code(),input.contributionProfit());
                linkedEvidence.put(linked.code(),Map.of("evidenceReference",linked.evidenceReference(),
                        "storeId",linked.storeId(),"listingVariantIds",linked.listingVariantIds(),
                        "input",qualify("LINKED_PROFIT:"+linked.code(),input.contributionProfit(),at,gaps)));
            }
            references.put("linkedProfitInputs",linkedEvidence);
            var criticalEvidence=new LinkedHashMap<String,Object>();
            for (var member:parsed.get().criticalReturnVariantIds()) {
                var input=metrics.project(new CanonicalScopeMetricQuery.Scope(action.organizationId(),action.storeId(),
                        List.of(member),MetricWindow.valueOf("D"+retentionDays),from,to,at,basis));
                observations.put("CRITICAL_VARIANT_RETURN:"+member,input.returnRate());
                criticalEvidence.put(member.toString(),qualify("CRITICAL_RETURN:"+member,input.returnRate(),at,gaps));
            }
            references.put("criticalReturnInputs",criticalEvidence);
        }
                var unitSources=new LinkedHashMap<String,Object>();
        projection.unitProfitInputs().forEach((member,input)->{
            var ids=new ArrayList<java.util.UUID>();
            for (var component:new MetricValueView[]{input.contributionProfit(),input.unitCount(),input.requiredProfitPerUnit()})
                if (component!=null) ids.add(component.metricValueId());
            unitSources.put(member.toString(),ids);
        });
        references.put("unitProfitMetricValueIds",unitSources);
        return new Evidence(references,gaps.stream().distinct().toList(),observations,projection.unitProfitInputs());
    }

    record Comparison(Map<String,Object> evidence,Map<String,com.mimococo.marketops.listingconversion.ProtectionVerdict> verdicts) { }

    Comparison compare(ListingActionRepository.ActionRow action,Evidence target,Instant from,Instant to,int days,
                       String stage,Instant at,Instant frozenAt,tools.jackson.databind.JsonNode node,boolean windowAdmitted) {
        var evidence=new LinkedHashMap<String,Object>();
        var verdicts=new LinkedHashMap<String,com.mimococo.marketops.listingconversion.ProtectionVerdict>();
        evidence.put("state","UNDETERMINED");
        var method=node.path("protectionComparison");
        if (!windowAdmitted || !"CANONICAL_ACCOUNTING_CHANGE_V1".equals(method.path("method").asText())
                || !method.path("qualificationRef").isTextual() || method.path("qualificationRef").asText().isBlank())
            return new Comparison(evidence,verdicts);
        Instant referenceFrom,referenceTo;
        try {
            referenceFrom=Instant.parse(method.path("referencePeriodStart").asText());
            referenceTo=Instant.parse(method.path("referencePeriodEnd").asText());
        } catch (java.time.format.DateTimeParseException invalidPeriod) { return new Comparison(evidence,verdicts); }
        if (!referenceFrom.isBefore(referenceTo) || referenceTo.isAfter(frozenAt) || referenceTo.isAfter(from)
                || !java.time.Duration.between(referenceFrom,referenceTo).equals(java.time.Duration.between(from,to)))
            return new Comparison(evidence,verdicts);
        var dependencies=actions.frozenCalibrationDependencies(action.id()).orElse(null);
        if (dependencies==null) return new Comparison(evidence,verdicts);
        var profitRule=dependencies.path("values").path("NON_WORSENING_PROFIT_BOUND");
        var returnRule=dependencies.path("values").path("NON_WORSENING_RETURN_BOUND");
        if (!"RATIO".equals(profitRule.path("unit").asText()) || !profitRule.path("numeric").isNumber()
                || !"RATIO".equals(returnRule.path("unit").asText()) || !returnRule.path("numeric").isNumber())
            return new Comparison(evidence,verdicts);
        var reference=read(action,referenceFrom,referenceTo,days,stage,at);
        evidence.put("referenceInputs",reference.references());
        evidence.put("referenceGaps",reference.gaps());
        for (var dimension:target.observations().entrySet()) {
            boolean returns=dimension.getKey().contains("RETURN");
            var previous=reference.observations().get(dimension.getKey());
            if (!verifiedAt(previous,at) || !verifiedAt(dimension.getValue(),at)) {
                verdicts.put(dimension.getKey(),com.mimococo.marketops.listingconversion.ProtectionVerdict.UNDETERMINED);
                continue;
            }
            verdicts.put(dimension.getKey(),returns
                    ?com.mimococo.marketops.listingconversion.internal.domain.CanonicalAccountingComparison.returns(
                        reference.observations().get(dimension.getKey()),dimension.getValue(),returnRule.path("numeric").decimalValue())
                    :com.mimococo.marketops.listingconversion.internal.domain.CanonicalAccountingComparison.profit(
                        reference.observations().get(dimension.getKey()),dimension.getValue(),profitRule.path("numeric").decimalValue()));
        }
        if (target.references().containsKey("linkedProfitInputs") && reference.references().containsKey("linkedProfitInputs"))
            verdicts.put("LINKED_SCOPE_PROFIT",independentVerdict(verdicts,"LINKED_SCOPE_PROFIT:"));
        if (target.references().containsKey("criticalReturnInputs") && reference.references().containsKey("criticalReturnInputs"))
            verdicts.put("CRITICAL_VARIANT_RETURN",independentVerdict(verdicts,"CRITICAL_VARIANT_RETURN:"));
        var unitMembers=actions.frozenDirectListingVariants(action.id());
        // Accounting facts may revise later; the rule floor must already have existed at freeze.
        var frozenFloors=unitMembers.isEmpty()?Map.<java.util.UUID,CanonicalScopeMetricQuery.UnitProfitInput>of()
                :metrics.project(new CanonicalScopeMetricQuery.Scope(action.organizationId(),action.storeId(),unitMembers,
                    MetricWindow.valueOf("D"+days),referenceFrom,referenceTo,frozenAt,
                    CanonicalScopeMetricQuery.ProfitBasis.valueOf(stage))).unitProfitInputs();
        var floorEvidence=new LinkedHashMap<String,Object>();
        for (var member:unitMembers) {
            var current=target.unitProfitInputs().get(member);
            var previous=frozenFloors.get(member);
            var floor=previous==null?null:previous.requiredProfitPerUnit();
            var floorGaps=new ArrayList<String>();
            if (floor!=null) floorEvidence.put(member.toString(),qualify("FROZEN_UNIT_FLOOR",
                    new CanonicalScopeMetricQuery.Observation(floor.numericValue(),floor.currencyCode(),List.of(),List.of(floor)),
                    frozenAt,floorGaps));
            else { floorGaps.add("FROZEN_UNIT_FLOOR_UNAVAILABLE");floorEvidence.put(member.toString(),Map.of("gaps",floorGaps)); }
            var input=current==null || floor==null || !floorGaps.isEmpty()?null:new CanonicalScopeMetricQuery.UnitProfitInput(
                    current.contributionProfit(),current.unitCount(),floor);
            verdicts.put("UNIT_PROFIT_FLOOR:"+member,
                    com.mimococo.marketops.listingconversion.internal.domain.CanonicalAccountingComparison.unitProfit(
                            input,at,referenceFrom,referenceTo));
        }
        evidence.put("unitFloorFrozenAt",frozenAt.toString());
        evidence.put("frozenUnitFloorInputs",floorEvidence);
        verdicts.put("UNIT_PROFIT_FLOOR",unitMembers.isEmpty()
                ?com.mimococo.marketops.listingconversion.ProtectionVerdict.UNDETERMINED:independentVerdict(verdicts,"UNIT_PROFIT_FLOOR:"));
        evidence.put("state","ACCOUNTING_COMPARISON_COMPUTED");
        evidence.put("method",method.path("method").asText());
        evidence.put("qualificationRef",method.path("qualificationRef").asText());
        evidence.put("dimensionVerdicts",Map.copyOf(verdicts));
        return new Comparison(evidence,Map.copyOf(verdicts));
    }

    private static boolean verifiedAt(CanonicalScopeMetricQuery.Observation value,Instant at) {
        return value!=null && value.components().stream().allMatch(component->component.computedAt()!=null
                && !component.computedAt().isAfter(at) && component.verifiedAt()!=null && !component.verifiedAt().isAfter(at));
    }

    private static com.mimococo.marketops.listingconversion.ProtectionVerdict independentVerdict(
            Map<String,com.mimococo.marketops.listingconversion.ProtectionVerdict> verdicts,String prefix) {
        var values=verdicts.entrySet().stream().filter(entry->entry.getKey().startsWith(prefix)).map(Map.Entry::getValue).toList();
        if (values.contains(com.mimococo.marketops.listingconversion.ProtectionVerdict.FAIL))
            return com.mimococo.marketops.listingconversion.ProtectionVerdict.FAIL;
        if (values.contains(com.mimococo.marketops.listingconversion.ProtectionVerdict.UNDETERMINED))
            return com.mimococo.marketops.listingconversion.ProtectionVerdict.UNDETERMINED;
        return com.mimococo.marketops.listingconversion.ProtectionVerdict.PASS;
    }

    private Map<String,Object> qualify(String dimension,CanonicalScopeMetricQuery.Observation observation,
                                      Instant at,List<String> allGaps) {
        var gaps=new ArrayList<>(observation.gaps());
        if (!observation.available() || observation.components().isEmpty()) gaps.add("CANONICAL_VALUE_UNAVAILABLE");
        for (MetricValueView value:observation.components()) {
            if (value.confidenceState()!=ConfidenceState.CANONICAL_CONFIRMED || value.estimated())
                gaps.add("CANONICAL_CONFIDENCE_UNQUALIFIED");
            if (value.inputDigest()==null || value.evidenceRefs().isEmpty()) gaps.add("SOURCE_LINEAGE_UNAVAILABLE");
            if (value.computedAt()==null || value.computedAt().isAfter(at)
                    || value.verifiedAt()==null || value.verifiedAt().isAfter(at)
                    || value.verificationRunId()==null) gaps.add("CURRENT_VERIFICATION_UNAVAILABLE");
        }
        gaps.stream().distinct().forEach(gap->allGaps.add(dimension+":"+gap));
        var result=new LinkedHashMap<String,Object>();
        result.put("state",gaps.isEmpty()?"CANONICAL_INPUT_AVAILABLE":"UNDETERMINED");
        result.put("gaps",gaps.stream().distinct().toList());
        // Numeric money and return inputs stay in their permissioned owning-domain projection.
        result.put("components",observation.components().stream().map(value->{
            var reference=new LinkedHashMap<String,Object>();
            reference.put("metricValueId",value.metricValueId());
            reference.put("metricCode",value.metricCode().name());
            reference.put("definitionVersion",value.definitionVersion());
            reference.put("inputDigest",value.inputDigest());
            reference.put("evidenceRefs",value.evidenceRefs());
            reference.put("verificationRunId",value.verificationRunId());
            reference.put("verifiedAt",value.verifiedAt());
            return reference;
        }).toList());
        return result;
    }
}
