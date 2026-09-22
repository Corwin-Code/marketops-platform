package com.mimococo.marketops.listingconversion.internal.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Whether a launch fits the cumulative allowance, axis by axis.
 *
 * <p>Occupied plus requested must not exceed the limit minus the disposal
 * reserve on each axis. Surplus on one axis never offsets another, and no axis
 * at all is not "unlimited" but unresolved. This is the preview the console
 * shows; the database function is the authority that acquires.
 */
public final class AllowanceCheck {

    private AllowanceCheck() {
    }

    public record Axis(String axisCode, BigDecimal limit, BigDecimal reserve, BigDecimal occupied,
                       BigDecimal requested) {
        public Axis {
            Objects.requireNonNull(axisCode, "axisCode");
            Objects.requireNonNull(limit, "limit");
            Objects.requireNonNull(reserve, "reserve");
            Objects.requireNonNull(occupied, "occupied");
        }

        public BigDecimal headroom() {
            return limit.subtract(reserve).subtract(occupied);
        }

        public boolean sufficient() {
            return requested != null && requested.signum() >= 0 && requested.compareTo(headroom()) <= 0;
        }
    }

    /** The axes that cannot absorb the request; empty means every axis can. */
    public static List<String> insufficientAxes(List<Axis> axes) {
        List<String> short_ = new ArrayList<>();
        if (axes.isEmpty()) {
            short_.add("ALLOWANCE_UNRESOLVED");
            return short_;
        }
        for (Axis axis : axes) {
            if (axis.requested() == null) {
                short_.add(axis.axisCode() + ":REQUEST_UNSTATED");
            } else if (!axis.sufficient()) {
                short_.add(axis.axisCode());
            }
        }
        return List.copyOf(short_);
    }
}
