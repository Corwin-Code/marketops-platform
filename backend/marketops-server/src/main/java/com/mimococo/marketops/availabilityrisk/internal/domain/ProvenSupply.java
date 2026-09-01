package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.util.List;
import java.util.Objects;

/**
 * The supply a calculation is allowed to rely on, and everything it refused.
 *
 * <p>{@code provenUnits} is a lower bound by construction: it contains only
 * units that are owned, physically distinct from units already counted, fresh
 * and currently usable. That is what makes a conservative danger proof possible
 * — if even the lower bound runs out inside the horizon, the risk is real
 * regardless of what the unclassifiable units turn out to be.
 *
 * @param provenUnits units that survived every ownership and freshness test
 * @param components every observation considered, counted or not
 */
public record ProvenSupply(int provenUnits, List<SupplyComponent> components) {

    public ProvenSupply {
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        if (provenUnits < 0) {
            throw new IllegalArgumentException("provenUnits cannot be negative");
        }
    }

    /** Build from components, summing exactly the ones that counted. */
    public static ProvenSupply of(List<SupplyComponent> components) {
        int total = components.stream().filter(SupplyComponent::counted)
                .mapToInt(SupplyComponent::units).sum();
        return new ProvenSupply(total, components);
    }

    /** An answer no source contributed to. */
    public static ProvenSupply none() {
        return new ProvenSupply(0, List.of());
    }

    /**
     * Whether every observed unit could be classified.
     *
     * <p>When this is false the total is still a valid lower bound, but it is
     * not the whole picture, and a company answer built on it can never be
     * {@code HEALTHY}.
     */
    public boolean complete() {
        return components.stream().noneMatch(SupplyComponent::underminesCompleteness);
    }

    /** Whether any source contributed at all. */
    public boolean present() {
        return !components.isEmpty();
    }

    /** The components that were observed but deliberately left out. */
    public List<SupplyComponent> excluded() {
        return components.stream().filter(component -> !component.counted()).toList();
    }
}
