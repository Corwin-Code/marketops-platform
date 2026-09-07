package com.mimococo.marketops.advertisingefficiency;

import com.mimococo.marketops.identityaccess.BusinessRoleCode;

/**
 * Why one advertising case exists, and who is accountable for it.
 *
 * <p>The cause is the case's identity. Recalculating one cause a thousand times
 * updates one case; two different causes on the same object are two cases with
 * two owners, because the person who repairs a broken spend feed is not the
 * person who lowers a bid.
 *
 * <p>Routing follows the cause owner, never the viewer. An operations lead
 * looking at a mapping defect does not thereby become responsible for it.
 */
public enum AdvertisingCause {

    /** Official spend continues while the object produces proven negative contribution. */
    PROVEN_ADVERTISING_LOSS(BusinessRoleCode.MARKETPLACE_OPERATOR, true, false),

    /** The promoted variants cannot be sold, so every rouble spent is certainly wasted. */
    PROMOTED_VARIANT_NOT_SELLABLE(BusinessRoleCode.MARKETPLACE_OPERATOR, true, false),

    /** The promoted variants are out of stock or about to be, and spend continues. */
    PROMOTED_VARIANT_UNAVAILABLE(BusinessRoleCode.MARKETPLACE_OPERATOR, true, false),

    /** A frozen required critical sales unit is at proven risk from this object. */
    CRITICAL_SALES_UNIT_AT_RISK(BusinessRoleCode.OPS_LEAD, true, false),

    /** A prior action's outcome regressed, or its execution state is unresolved. */
    ACTION_OUTCOME_REGRESSION(BusinessRoleCode.OPS_LEAD, true, false),

    /** Official advertising spend or traffic is missing, stale or conflicted. */
    OFFICIAL_AD_FACT_DEFECT(BusinessRoleCode.TECH_DATA, true, true),

    /** The complete affected variant set cannot be resolved for this object. */
    AFFECTED_SET_UNRESOLVED(BusinessRoleCode.TECH_DATA, true, true),

    /** Product mapping or ad-linked sale linkage coverage is below the definition's floor. */
    AD_LINKAGE_COVERAGE_INSUFFICIENT(BusinessRoleCode.TECH_DATA, true, true),

    /** Provider-attributed and canonical sale counts disagree beyond the accepted gap. */
    ATTRIBUTION_GAP_MATERIAL(BusinessRoleCode.FINANCE_ANALYST, true, true),

    /** COGS, fee, fulfillment, return or settlement inputs cannot support a profit answer. */
    PROFIT_ECONOMICS_BLOCKED(BusinessRoleCode.FINANCE_ANALYST, true, true),

    /** A governing policy version is missing, expired or conflicted for a consumed purpose. */
    DECISION_POLICY_UNRESOLVED(BusinessRoleCode.OPS_LEAD, true, true),

    /** The platform's own object, field, mode, unit or state semantics are unknown. */
    NATIVE_SEMANTICS_UNKNOWN(BusinessRoleCode.TECH_DATA, true, true),

    /** This object is not independently controllable, so no bid decision can apply to it. */
    OBJECT_NOT_INDEPENDENTLY_CONTROLLABLE(BusinessRoleCode.TECH_DATA, false, true),

    /** Recoverable contribution profit is available on complete, sustained evidence. */
    RECOVERABLE_ADVERTISING_PROFIT(BusinessRoleCode.MARKETPLACE_OPERATOR, true, false),

    /** A signal exists but is immature, unsustained or immaterial. */
    IMMATURE_SIGNAL(null, false, false),

    /** Nothing is wrong and nothing is recoverable on the evidence available. */
    NONE(null, false, false);

    private final BusinessRoleCode accountableRole;
    private final boolean actionable;
    private final boolean dataDefect;

    AdvertisingCause(BusinessRoleCode accountableRole, boolean actionable, boolean dataDefect) {
        this.accountableRole = accountableRole;
        this.actionable = actionable;
        this.dataDefect = dataDefect;
    }

    /**
     * The role a task for this cause is routed to, or {@code null} when the cause
     * raises no task.
     */
    public BusinessRoleCode accountableRole() {
        return accountableRole;
    }

    /** Whether this cause can produce an accountable task at all. */
    public boolean actionable() {
        return actionable;
    }

    /**
     * Whether this cause is a defect in the evidence rather than in the business.
     *
     * <p>This is what separates {@code DATA_REPAIR} from a quiet {@code WATCH}.
     * A material defect is somebody's job; it is not a smaller version of an
     * opportunity.
     */
    public boolean dataDefect() {
        return dataDefect;
    }
}
