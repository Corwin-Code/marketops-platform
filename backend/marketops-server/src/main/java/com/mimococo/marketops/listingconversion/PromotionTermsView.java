package com.mimococo.marketops.listingconversion;

import java.util.UUID;

/** Current financial access controls disclosure; the immutable digest remains visible. */
public record PromotionTermsView(UUID actionId, String digest, PromotionTerms terms, boolean fullDisclosure) { }
