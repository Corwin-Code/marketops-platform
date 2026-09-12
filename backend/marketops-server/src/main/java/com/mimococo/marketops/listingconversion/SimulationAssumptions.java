package com.mimococo.marketops.listingconversion;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Instant;
import java.util.Map;

/** Declared conditional context. References identify assumptions; they do not qualify their sources. */
public record SimulationAssumptions(Instant periodStart, Instant periodEnd,
                                    Map<String, String> sourceReferences, String assumptions) {
    public SimulationAssumptions {
        if (periodStart == null || periodEnd == null || !periodStart.isBefore(periodEnd)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        MetadataFieldPolicy.requireText("simulationAssumptions", assumptions);
        sourceReferences = Map.copyOf(sourceReferences == null ? Map.of() : sourceReferences);
        if (sourceReferences.size() > 64) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        sourceReferences.forEach((key, value) -> {
            if (key.length() > 64) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            MetadataFieldPolicy.requireText("simulationSourceName", key);
            MetadataFieldPolicy.requireText("simulationSourceReference", value);
        });
    }
}
