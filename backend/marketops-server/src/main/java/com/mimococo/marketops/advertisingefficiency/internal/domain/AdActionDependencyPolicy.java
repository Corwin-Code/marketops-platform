package com.mimococo.marketops.advertisingefficiency.internal.domain;

import java.util.List;
import java.util.Set;

/** A proven one-sided decrease does not turn missing profitability evidence into a fact. */
public final class AdActionDependencyPolicy {
    private static final Set<String> FINANCIAL_UNCERTAINTY=Set.of(
            "AD_LINKED_QUANTITY_LINEAGE_UNAVAILABLE", "MIXED_OR_UNRESOLVED_SALES_CURRENCY",
            "AD_LINKED_CONVERSION_NOT_WRITE_GRADE", "PROVIDER_TO_CANONICAL_ATTRIBUTION_GAP_MATERIAL");
    private AdActionDependencyPolicy() { }

    /** Exact dependencies of the candidate basis, also enforced by the SQL authority. */
    public static List<String> requiredEvidenceKinds(String basis, String cause) {
        if ("MAX_CPC_BOUNDED".equals(basis)) return List.of("OFFICIAL_AD_SPEND", "OFFICIAL_AD_TRAFFIC",
                "AD_LINKED_SALE_EVENT", "COST_AND_FEE", "AD_OBJECT_CONFIGURATION", "AFFECTED_SET");
        if (!"CAUSE_BOUND_PROTECTION_STEP".equals(basis) || cause == null) return List.of();
        return switch (cause) {
            case "PROMOTED_VARIANT_NOT_SELLABLE" -> List.of("OFFICIAL_AD_SPEND", "AD_OBJECT_CONFIGURATION", "AFFECTED_SET", "SELLABILITY");
            case "PROMOTED_VARIANT_UNAVAILABLE" -> List.of("OFFICIAL_AD_SPEND", "AD_OBJECT_CONFIGURATION", "AFFECTED_SET", "AVAILABILITY");
            case "PROVEN_ADVERTISING_LOSS" -> List.of("OFFICIAL_AD_SPEND", "AD_OBJECT_CONFIGURATION", "AFFECTED_SET", "AD_LINKED_SALE_EVENT", "COST_AND_FEE", "SELLABILITY", "AVAILABILITY");
            default -> List.of();
        };
    }

    public static List<String> actionBlockers(String basis,String cause,List<String> allBlockers) {
        if ("CAUSE_BOUND_PROTECTION_STEP".equals(basis) && requiredEvidenceKinds(basis,cause).isEmpty()) {
            var blockers=new java.util.ArrayList<>(allBlockers);
            blockers.add("CAUSE_BOUND_CAUSE_UNSUPPORTED");
            return List.copyOf(blockers);
        }
        if ("CAUSE_BOUND_PROTECTION_STEP".equals(basis) && "PROVEN_ADVERTISING_LOSS".equals(cause)) {
            // Missing rate/traffic cannot change a complete observed negative profit.
            // Missing quantity, currency, revenue or cost still can, and remain blockers.
            return allBlockers.stream().filter(code -> !"AD_LINKED_CONVERSION_NOT_WRITE_GRADE".equals(code)).toList();
        }
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
