package com.mimococo.marketops.advertisingefficiency.internal.domain;

import java.util.List;
import java.util.Set;

/** A proven one-sided decrease does not turn missing profitability evidence into a fact. */
public final class AdActionDependencyPolicy {
    private static final Set<String> FINANCIAL_UNCERTAINTY=Set.of(
            "AD_LINKED_QUANTITY_LINEAGE_UNAVAILABLE", "MIXED_OR_UNRESOLVED_SALES_CURRENCY",
            "AD_LINKED_CONVERSION_NOT_WRITE_GRADE", "PROVIDER_TO_CANONICAL_ATTRIBUTION_GAP_MATERIAL");
    private AdActionDependencyPolicy() { }

    public static List<String> actionBlockers(String basis,String cause,List<String> allBlockers) {
        if(!"CAUSE_BOUND_PROTECTION_STEP".equals(basis)
                || !("PROMOTED_VARIANT_NOT_SELLABLE".equals(cause)
                    || "PROMOTED_VARIANT_UNAVAILABLE".equals(cause))) return List.copyOf(allBlockers);
        return allBlockers.stream().filter(code -> !financialUncertainty(code)).toList();
    }

    private static boolean financialUncertainty(String code) {
        if(FINANCIAL_UNCERTAINTY.contains(code)) return true;
        return code.matches("(?:LINE_ECONOMICS_OR_MAPPING_UNRESOLVED|LINE_COST_COMPONENT_UNAVAILABLE):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
}
