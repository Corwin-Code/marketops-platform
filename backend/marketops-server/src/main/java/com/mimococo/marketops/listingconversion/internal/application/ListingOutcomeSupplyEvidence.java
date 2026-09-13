package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.availabilityrisk.SupplyCoverageQuery;
import com.mimococo.marketops.listingconversion.ProtectionVerdict;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Historical Outcome supply protection from the action's frozen scenario set and current as-of facts. */
@Service
class ListingOutcomeSupplyEvidence {
    private final SupplyCoverageQuery supply;
    private final ListingActionRepository actions;

    ListingOutcomeSupplyEvidence(SupplyCoverageQuery supply, ListingActionRepository actions) {
        this.supply=supply;
        this.actions=actions;
    }

    record Evidence(ProtectionVerdict verdict, Map<String,Object> references, List<String> gaps) {
        Evidence { references=Map.copyOf(references); gaps=List.copyOf(gaps); }
    }

    Evidence assess(ListingActionRepository.ActionRow action, Instant at) {
        var gaps=new ArrayList<String>();
        var references=new LinkedHashMap<String,Object>();
        var products=actions.frozenDirectProductVariants(action.id());
        JsonNode dependencies=actions.frozenCalibrationDependencies(action.id()).orElse(null);
        JsonNode scenarios=dependencies==null?null:dependencies.path("supplyScenarios");
        references.put("frozenProductVariantIds",products);
        references.put("assessedAt",at.toString());
        if (products.isEmpty()) gaps.add("FROZEN_SUPPLY_SCOPE_UNQUALIFIED");
        if (scenarios==null || !scenarios.isArray() || scenarios.isEmpty()) gaps.add("FROZEN_SUPPLY_SCENARIOS_UNQUALIFIED");
        var results=new ArrayList<Map<String,Object>>();
        boolean failed=false;
        var seen=new HashSet<String>();
        var coveredProducts=new HashSet<UUID>();
        if (gaps.isEmpty()) for (JsonNode scenario:scenarios) {
            UUID product;
            String code=scenario.path("code").asText("");
            try { product=UUID.fromString(scenario.path("productVariantId").asText()); }
            catch (IllegalArgumentException invalid) { gaps.add("FROZEN_SUPPLY_SCENARIO_INVALID"); continue; }
            JsonNode rate=scenario.path("companyDailyFulfillmentUnits"),days=scenario.path("coverageDays");
            String evidenceReference=scenario.path("evidenceReference").asText("");
            String key=product+":"+code;
            if (!products.contains(product) || code.isBlank() || code.length()>64 || !seen.add(key)
                    || !rate.isNumber() || rate.decimalValue().signum()<0
                    || !days.isIntegralNumber() || !days.canConvertToInt() || days.intValue()<=0 || days.intValue()>3660
                    || evidenceReference.isBlank()) {
                gaps.add("FROZEN_SUPPLY_SCENARIO_INVALID:"+product);
                continue;
            }
            coveredProducts.add(product);
            var projection=supply.project(new SupplyCoverageQuery.Scenario(action.organizationId(),product,
                    rate.decimalValue(),days.intValue(),at));
            boolean projectionPass="PASS".equals(projection.verdict()) && projection.gaps().isEmpty()
                    && projection.sourceDigest()!=null && !projection.sourceDigest().isBlank();
            failed|="FAIL".equals(projection.verdict());
            if (!projectionPass) gaps.add("SUPPLY_SCENARIO_"+projection.verdict()+":"+product+":"+code);
            projection.gaps().forEach(gap->gaps.add("SUPPLY:"+product+":"+code+":"+gap));
            var result=new LinkedHashMap<String,Object>();
            result.put("productVariantId",product);
            result.put("code",code);
            result.put("demandEvidenceReference",evidenceReference);
            result.put("verdict",projection.verdict());
            result.put("sourceDigest",projection.sourceDigest());
            result.put("policyVersions",projection.policyVersions());
            result.put("requiredThrough",projection.requiredThrough());
            result.put("supplyEvidenceIds",projection.supplyEvidence().stream()
                    .map(SupplyCoverageQuery.SupplyEvidence::provenanceId).filter(java.util.Objects::nonNull).distinct().sorted().toList());
            results.add(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result)));
        }
        for (UUID product:products) if (!coveredProducts.contains(product)) gaps.add("FROZEN_SUPPLY_SCENARIO_MISSING:"+product);
        references.put("scenarioResults",List.copyOf(results));
        List<String> distinct=gaps.stream().distinct().toList();
        ProtectionVerdict verdict=failed?ProtectionVerdict.FAIL:distinct.isEmpty()?ProtectionVerdict.PASS:ProtectionVerdict.UNDETERMINED;
        references.put("state",verdict.name());
        references.put("gaps",distinct);
        return new Evidence(verdict,references,distinct);
    }
}
