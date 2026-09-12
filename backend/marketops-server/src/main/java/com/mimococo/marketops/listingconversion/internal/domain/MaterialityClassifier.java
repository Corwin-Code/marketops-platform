package com.mimococo.marketops.listingconversion.internal.domain;

import com.mimococo.marketops.listingconversion.MaterialityRoute;
import java.math.BigDecimal;

/**
 * Two independent axes, each against its own published trigger.
 *
 * <p>Content meaning is measured as the share of the text that changes;
 * commercial exposure as the affected variants' share of retained net sales.
 * Either axis crossing its material trigger routes final approval to the
 * Owner. A missing trigger on either axis leaves materiality unresolved, and
 * an unresolved action never leaves draft; there is no default trigger.
 */
public final class MaterialityClassifier {

    private MaterialityClassifier() {
    }

    /** The published triggers, or {@code null} where the package lacks one. */
    public record Triggers(BigDecimal ordinaryContent, BigDecimal materialContent,
                           BigDecimal ordinaryExposure, BigDecimal materialExposure) {
        public boolean complete() {
            return ordinaryContent != null && materialContent != null
                    && ordinaryExposure != null && materialExposure != null;
        }
    }

    /** The classification, with each axis answered on its own. */
    public record Classification(MaterialityRoute route, Boolean contentAxisMaterial,
                                 Boolean exposureAxisMaterial) {
    }

    /**
     * Classify one action.
     *
     * @param contentChangeShare the share of the text that differs, 0..1, or {@code null} when unknown
     * @param exposureShare the affected variants' share of retained net sales, 0..1, or {@code null}
     */
    public static Classification classify(Triggers triggers, BigDecimal contentChangeShare,
                                          BigDecimal exposureShare) {
        if (triggers == null || !triggers.complete() || contentChangeShare == null || exposureShare == null) {
            return new Classification(MaterialityRoute.MATERIALITY_UNRESOLVED, null, null);
        }
        return classifyWithExposureAxis(triggers,contentChangeShare,exposureShare.compareTo(triggers.materialExposure())>=0);
    }

    /** The Metric owner compares the actual ratio before display rounding. */
    public static Classification classifyWithExposureAxis(Triggers triggers,BigDecimal contentChangeShare,Boolean exposure) {
        if (triggers==null || !triggers.complete() || contentChangeShare==null || exposure==null) {
            return new Classification(MaterialityRoute.MATERIALITY_UNRESOLVED,null,null);
        }
        boolean content=contentChangeShare.compareTo(triggers.materialContent())>=0;
        return new Classification(content || exposure?MaterialityRoute.MATERIAL_IMPACT:MaterialityRoute.ORDINARY_IMPACT,
                content,exposure);
    }

    /**
     * The share of characters that differ between two texts, as a conservative
     * measure of how much the meaning could have changed.
     */
    public static BigDecimal contentChangeShare(String current, String target) {
        if (current == null || target == null) {
            return null;
        }
        if (current.isEmpty() && target.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int common = commonPrefix(current, target) + commonSuffix(current, target, commonPrefix(current, target));
        int longest = Math.max(current.length(), target.length());
        int changed = Math.max(0, longest - common);
        return BigDecimal.valueOf(changed).divide(BigDecimal.valueOf(longest), 6, java.math.RoundingMode.UP);
    }

    private static int commonPrefix(String a, String b) {
        int limit = Math.min(a.length(), b.length());
        int index = 0;
        while (index < limit && a.charAt(index) == b.charAt(index)) {
            index++;
        }
        return index;
    }

    private static int commonSuffix(String a, String b, int prefix) {
        int limit = Math.min(a.length(), b.length()) - prefix;
        int index = 0;
        while (index < limit && a.charAt(a.length() - 1 - index) == b.charAt(b.length() - 1 - index)) {
            index++;
        }
        return index;
    }
}
