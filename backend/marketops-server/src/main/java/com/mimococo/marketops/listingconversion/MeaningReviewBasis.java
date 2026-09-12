package com.mimococo.marketops.listingconversion;

import java.util.List;
import java.util.UUID;

/** Exact before/after Russian text and the accepted condition catalog; no hidden exposure amounts. */
public record MeaningReviewBasis(UUID actionId,String basisDigest,String ruleState,String currentText,String targetText,
                                PromotionTerms promotionTerms,List<Condition> conditions) {
    public record Condition(String code,String condition,String axis) { }
    public MeaningReviewBasis { conditions=List.copyOf(conditions); }
}
