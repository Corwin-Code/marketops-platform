package com.mimococo.marketops.analyticsdecision;

import java.util.Objects;

/** Exact result of resolving one scoped economics profile. */
public record PriceEconomicsResolution(
        Status status,
        PriceEconomicsProfile profile,
        String detail) {

    public PriceEconomicsResolution {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
    }

    /** A single current, verified profile is the only usable resolution. */
    public boolean available() {
        return status == Status.AVAILABLE && profile != null;
    }

    /** Build a fail-closed result without fabricating a profile. */
    public static PriceEconomicsResolution unavailable(Status status, String detail) {
        return new PriceEconomicsResolution(status, null, detail);
    }

    public enum Status {
        AVAILABLE,
        MISSING,
        AMBIGUOUS,
        EXPIRED,
        UNVERIFIED,
        UNSUPPORTED
    }
}
