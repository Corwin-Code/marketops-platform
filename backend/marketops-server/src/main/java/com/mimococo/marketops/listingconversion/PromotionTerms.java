package com.mimococo.marketops.listingconversion;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.Map;
import java.util.Set;

/** Exact proposed commercial declaration; matching it is not independent platform verification. */
public record PromotionTerms(String engagementKind, String nativePromotionKey, Map<String,String> terms,
                             Boolean priceFreeze, Boolean autoParticipation, String termsEvidenceReference,
                             Map<String,String> obligations) {
    public PromotionTerms {
        if (engagementKind==null || !Set.of("OFFICIAL_PROMOTION_PARTICIPATION","SELLER_DIRECT_DISCOUNT").contains(engagementKind)
                || priceFreeze==null || autoParticipation==null
                || nativePromotionKey==null || nativePromotionKey.length()>128) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        // Validate for metadata/secret safety, but keep the exact declared values.
        MetadataFieldPolicy.requireText("nativePromotionKey",nativePromotionKey);
        MetadataFieldPolicy.requireText("termsEvidenceReference",termsEvidenceReference);
        terms=exactMap(terms); obligations=exactMap(obligations);
    }

    private static Map<String,String> exactMap(Map<String,String> values) {
        if (values==null || values.isEmpty() || values.size()>64) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        values.forEach((key,value)->{
            if (key==null || key.length()>128) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            MetadataFieldPolicy.requireText("promotionTermKey",key);
            MetadataFieldPolicy.requireText(key,value);
        });
        return Map.copyOf(values);
    }
}
