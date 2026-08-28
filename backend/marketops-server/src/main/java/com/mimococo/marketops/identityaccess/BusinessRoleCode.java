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
    READ_ONLY
}
