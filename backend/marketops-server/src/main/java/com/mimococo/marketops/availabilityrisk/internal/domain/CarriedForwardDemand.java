package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The last demand answer that was eligible, and when it was true.
 *
 * <p>Carrying an answer forward is only defensible while the operator can see
 * how old it is, which is why the source instant travels with the rate rather
 * than being reconstructed from a calculation timestamp.
 *
 * @param rate the units-per-day that was eligible
 * @param window the window it came from
 * @param observedAt the end of the period it described
 */
public record CarriedForwardDemand(BigDecimal rate, DemandWindow window, Instant observedAt) {
    public CarriedForwardDemand {
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(observedAt, "observedAt");
        if (rate.signum() < 0) {
            throw new IllegalArgumentException("rate cannot be negative");
        }
    }
}
