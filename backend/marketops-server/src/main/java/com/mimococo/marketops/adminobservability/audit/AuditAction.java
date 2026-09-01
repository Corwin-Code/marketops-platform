package com.mimococo.marketops.adminobservability.audit;

/**
 * The kind of event an audit row records.
 *
 * <p>The set covers metadata maintenance and the operating decisions that carry
 * the same accountability requirement: an import that changed cost, a mapping
 * confirmation that unblocked a profit figure, an approval, a policy change, a
 * command transition and a kill-switch movement are all attributable acts, and
 * they belong in one journal rather than in five.
 */
public enum AuditAction {
    READ,
    CREATE,
    UPDATE,
    STATUS_CHANGE,
    GRANT,
    REVOKE,
    VERIFICATION_CHANGE,
    DENIED,
    IMPORT,
    MAPPING_DECISION,
    APPROVAL_DECISION,
    POLICY_CHANGE,
    COMMAND_TRANSITION,
    KILL_SWITCH,
    AI_INVOCATION,
    EXPORT
}
