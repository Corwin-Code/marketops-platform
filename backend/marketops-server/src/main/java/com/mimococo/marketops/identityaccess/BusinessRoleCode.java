package com.mimococo.marketops.identityaccess;

/**
 * The named business roles this product recognises.
 *
 * <p>Roles are deliberately few and coarse. Their purpose is to carry the
 * reviewed action matrix, not to model an organisation chart; the fine-grained
 * question of which stores and which entities a person may act on is answered
 * by a scope grant, not by inventing another role.
 */
public enum BusinessRoleCode {

    /** Accountable for commercial policy, approvals and enablement decisions. */
    OWNER,

    /** Runs the daily diagnostic loop, mapping, tasks and price proposals. */
    OPERATIONS,

    /** Owns cost, fee and settlement facts and reviews profit evidence. */
    FINANCE,

    /** Reads diagnosis and evidence without changing any operating state. */
    READ_ONLY,

    /**
     * Restores channel availability for an exact listing, store and mode.
     *
     * <p>The availability roles below are finer than the original four because
     * the Contract routes each kind of availability failure to a different
     * accountable person. They remain coarse in the same way: which stores and
     * which variants they may act on is still a scope grant, not a role.
     */
    MARKETPLACE_OPERATOR,

    /** Owns inbound attestation and the lead-time and safety policy. */
    PRODUCT_PROCUREMENT,

    /** Repairs stock, mapping, ownership and source defects. */
    TECH_DATA,

    /** Resolves the profit and cost-data blockers behind an availability decision. */
    FINANCE_ANALYST,

    /** Approves bounded accepted risk and owns the operating response. */
    OPS_LEAD,

    /** Owner-designated approver for critical, repeated or material accepted risk. */
    RISK_AUTHORITY,

    /** Reads availability risk, cases and decisions without changing any state. */
    AUDITOR
}
