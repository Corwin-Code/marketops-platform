package com.mimococo.marketops.operationsworkflow;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Exact accepted values supplied by the Listing owner when its Task is first raised. */
public record ListingResponsibilityBasis(UUID calibrationPackageId, Integer calibrationVersion,
                                         JsonNode slo, JsonNode coverage) {
    public ListingResponsibilityBasis {
        slo = slo == null ? null : slo.deepCopy();
        coverage = coverage == null ? null : coverage.deepCopy();
    }
}
