package com.mimococo.marketops.listingconversion;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Every axis of the allowance that would apply to one listing, with what is occupied now. */
public record AllowanceView(UUID platformListingId, List<Axis> axes, boolean resolved) {

    public record Axis(UUID allowanceId, String axisCode, String scopeKind, BigDecimal limitValue,
                       BigDecimal reserveValue, BigDecimal occupiedValue, BigDecimal requestedValue,
                       BigDecimal headroom, boolean sufficient, String unitCode) {
    }

    public AllowanceView {
        axes = List.copyOf(axes == null ? List.of() : axes);
    }
}
