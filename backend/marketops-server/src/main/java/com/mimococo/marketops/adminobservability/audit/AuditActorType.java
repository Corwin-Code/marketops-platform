package com.mimococo.marketops.adminobservability.audit;

/**
 * Who an audit event is attributed to.
 *
 * <p>{@code OPERATOR} names the human whose validated attribution accompanied
 * the mutation. {@code SYSTEM} names a fixed application component and is used
 * when no trustworthy human attribution exists — a refusal for missing or
 * invalid attribution is recorded as the component that observed it, never as
 * a fabricated person.
 */
public enum AuditActorType {
    OPERATOR,
    SYSTEM
}
