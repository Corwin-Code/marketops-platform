package com.mimococo.marketops.adminobservability.audit;

/**
 * The safe before/after representation of one changed field.
 *
 * <p>Both sides are already-validated metadata values rendered as text; a field
 * that is subject to the secret-material guard was validated before it could
 * reach a change record, so the journal never stores refused input.
 *
 * @param previous value before the change, or {@code null} on creation
 * @param current value after the change, or {@code null} when cleared
 */
public record FieldChange(String previous, String current) {
}
