package com.mimococo.marketops.listingconversion.internal.domain;

import com.mimococo.marketops.listingconversion.MaterialityRoute;

/** Meaning comes from exact independent evidence; exposure comes from the Metric owner. */
public final class MaterialityClassifier {
    private MaterialityClassifier() { }

    public record Classification(MaterialityRoute route, Boolean contentAxisMaterial, Boolean exposureAxisMaterial) { }

    /** Either known material axis needs the Owner; ordinary requires two known ordinary axes. */
    public static Classification classify(Boolean meaning, Boolean exposure) {
        var route=Boolean.TRUE.equals(meaning) || Boolean.TRUE.equals(exposure)?MaterialityRoute.MATERIAL_IMPACT
                : meaning==null || exposure==null?MaterialityRoute.MATERIALITY_UNRESOLVED:MaterialityRoute.ORDINARY_IMPACT;
        return new Classification(route,meaning,exposure);
    }
}
