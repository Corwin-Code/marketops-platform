package com.mimococo.marketops.listingconversion.internal.domain;

import com.mimococo.marketops.listingconversion.NodeVerdict;
import com.mimococo.marketops.listingconversion.ProtectionVerdict;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The protections a node result carries, each answered on its own.
 *
 * <p>No protection compensates another: one FAIL fails the vector, one
 * UNDETERMINED with no FAIL leaves it undetermined, and only all-PASS passes.
 * An unmet primary target never becomes a failed protection, and "no harm
 * proven" is not a pass.
 */
public final class ProtectionVector {

    public static final List<String> REQUIRED = List.of(
            "DIRECT_CONTRIBUTION_PROFIT", "LINKED_SCOPE_PROFIT", "OVERALL_RETURN_RATE",
            "CRITICAL_VARIANT_RETURN", "SUPPLY_COVERAGE");

    private ProtectionVector() {
    }

    /** One protection compared against its non-worsening bound. */
    public static ProtectionVerdict compare(BigDecimal observed, BigDecimal bound, boolean higherIsWorse) {
        if (observed == null || bound == null) {
            return ProtectionVerdict.UNDETERMINED;
        }
        int comparison = observed.compareTo(bound);
        boolean worse = higherIsWorse ? comparison > 0 : comparison < 0;
        return worse ? ProtectionVerdict.FAIL : ProtectionVerdict.PASS;
    }

    /** The vector's own verdict. */
    public static ProtectionVerdict verdictOf(Map<String, ProtectionVerdict> vector) {
        if (vector == null) return ProtectionVerdict.UNDETERMINED;
        if (vector.containsValue(ProtectionVerdict.FAIL)) {
            return ProtectionVerdict.FAIL;
        }
        for (String required : REQUIRED) {
            if (!vector.containsKey(required)) {
                return ProtectionVerdict.UNDETERMINED;
            }
        }
        if (vector.containsValue(null) || vector.containsValue(ProtectionVerdict.UNDETERMINED)) {
            return ProtectionVerdict.UNDETERMINED;
        }
        return ProtectionVerdict.PASS;
    }

    /** The node verdict from a conservative bound and the accepted threshold. */
    public static NodeVerdict nodeVerdict(BigDecimal primaryRatio, BigDecimal conservativeBound,
                                          BigDecimal acceptedThreshold, boolean maturityReached) {
        if (primaryRatio == null || conservativeBound == null || acceptedThreshold == null || !maturityReached) {
            return NodeVerdict.UNDETERMINED;
        }
        return conservativeBound.compareTo(acceptedThreshold) >= 0 ? NodeVerdict.MET : NodeVerdict.NOT_MET;
    }

    /**
     * Whether the effect-shortfall stop rule triggers.
     *
     * <p>Failure to establish improvement is not evidence of futility. The
     * independently admitted stop method must establish an upper improvement
     * bound below its frozen minimum meaningful effect at the exact stop node.
     */
    public static boolean stopTriggered(BigDecimal qualifiedUpperImprovement, BigDecimal futilityThreshold,
                                        boolean stopNode, boolean maturityReached, boolean stopMethodQualified) {
        return stopNode && maturityReached && stopMethodQualified
                && qualifiedUpperImprovement != null && futilityThreshold != null
                && qualifiedUpperImprovement.compareTo(futilityThreshold) < 0;
    }

    public static Map<String, String> toStrings(Map<String, ProtectionVerdict> vector) {
        Map<String, String> out = new LinkedHashMap<>();
        vector.forEach((key, value) -> out.put(key, value.name()));
        return out;
    }
}
