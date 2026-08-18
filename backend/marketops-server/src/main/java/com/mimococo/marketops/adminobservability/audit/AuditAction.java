package com.mimococo.marketops.adminobservability.audit;

/** The kind of metadata event an audit row records. */
public enum AuditAction {
    CREATE,
    UPDATE,
    STATUS_CHANGE,
    GRANT,
    REVOKE,
    VERIFICATION_CHANGE,
    DENIED
}
