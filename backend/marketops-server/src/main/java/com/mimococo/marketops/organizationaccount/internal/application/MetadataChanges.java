package com.mimococo.marketops.organizationaccount.internal.application;

import com.mimococo.marketops.adminobservability.audit.FieldChange;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Collects the field-level before/after document of one mutation.
 *
 * <p>Only fields that actually changed are recorded, so an audit reader sees
 * the difference, not a full-row dump. Values are rendered as text; every value
 * that reaches this collector has already passed field validation and the
 * secret-material guard.
 */
public final class MetadataChanges {

    private final Map<String, FieldChange> changes = new LinkedHashMap<>();

    /** Record {@code field} when the rendered values differ. */
    public MetadataChanges compare(String field, Object previous, Object current) {
        if (!Objects.equals(previous, current)) {
            changes.put(field, new FieldChange(render(previous), render(current)));
        }
        return this;
    }

    /** Record {@code field} unconditionally, as on creation. */
    public MetadataChanges set(String field, Object current) {
        changes.put(field, new FieldChange(null, render(current)));
        return this;
    }

    /** The collected document. */
    public Map<String, FieldChange> asMap() {
        return Map.copyOf(changes);
    }

    private static String render(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
