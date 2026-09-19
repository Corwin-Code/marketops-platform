package com.mimococo.marketops.listingconversion;

import java.time.Instant;
import java.util.List;

/** Proposed evidence and use boundaries; independent review and safety gates qualify them separately. */
public record ListingPurposeBasis(String evidenceReference, List<String> useConditions,
                                  List<String> endConditions, Instant useUntil) {
    public ListingPurposeBasis {
        useConditions=List.copyOf(useConditions==null?List.of():useConditions);
        endConditions=List.copyOf(endConditions==null?List.of():endConditions);
    }
}
