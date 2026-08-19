package com.mimococo.marketops.shared;

import java.util.UUID;

/**
 * A metadata operation refused by a business rule, a state machine, or a
 * relational constraint.
 *
 * <p>The exception carries only values that are safe to return and to record:
 * the stable error code, and optionally the audit domain, the entity context
 * and the identifier of a conflicting resource. It never carries caller input,
 * SQL, or any secret-capable text, so a boundary can render and audit it
 * without a redaction step.
 */
public final class OperationRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final String auditDomain;
    private final String entityType;
    private final UUID entityId;
    private final String entityCode;
    private final UUID conflictingResourceId;

    private OperationRejectedException(ErrorCode errorCode,
                                       String auditDomain,
                                       String entityType,
                                       UUID entityId,
                                       String entityCode,
                                       UUID conflictingResourceId) {
        super(errorCode.safeMessage());
        this.errorCode = errorCode;
        this.auditDomain = auditDomain;
        this.entityType = entityType;
        this.entityId = entityId;
        this.entityCode = entityCode;
        this.conflictingResourceId = conflictingResourceId;
    }

    /** Refusal with no entity context, used by cross-cutting guards. */
    public static OperationRejectedException of(ErrorCode errorCode) {
        return new OperationRejectedException(errorCode, null, null, null, null, null);
    }

    /** Refusal attributed to a metadata domain and entity. */
    public static OperationRejectedException forEntity(ErrorCode errorCode,
                                                       String auditDomain,
                                                       String entityType,
                                                       UUID entityId,
                                                       String entityCode) {
        return new OperationRejectedException(
                errorCode, auditDomain, entityType, entityId, entityCode, null);
    }

    /** Duplicate-identity refusal that names the already-existing resource. */
    public static OperationRejectedException duplicate(String auditDomain,
                                                       String entityType,
                                                       String entityCode,
                                                       UUID conflictingResourceId) {
        return new OperationRejectedException(
                ErrorCode.DUPLICATE_IDENTITY, auditDomain, entityType, null,
                entityCode, conflictingResourceId);
    }

    /** Stable code identifying the refusal. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Metadata domain the refusal belongs to, or {@code null}. */
    public String auditDomain() {
        return auditDomain;
    }

    /** Entity type of the refused operation's target, or {@code null}. */
    public String entityType() {
        return entityType;
    }

    /** Entity identifier of the refused operation's target, or {@code null}. */
    public UUID entityId() {
        return entityId;
    }

    /** Entity business code of the refused operation's target, or {@code null}. */
    public String entityCode() {
        return entityCode;
    }

    /** Identifier of the resource a duplicate collided with, or {@code null}. */
    public UUID conflictingResourceId() {
        return conflictingResourceId;
    }
}
