package com.mimococo.marketops.availabilityrisk;

import com.mimococo.marketops.identityaccess.BusinessRoleCode;

/**
 * Why a risk needs somebody, and which role that somebody holds.
 *
 * <p>The cause is the case identity. Recalculating the same cause a thousand
 * times updates one case; two different causes on the same variant are two
 * pieces of work for two different people. Routing follows the cause owner and
 * never the viewer, which is why the accountable role is carried here rather
 * than decided at render time.
 */
public enum RiskCause {

    /** An exact listing and mode is out of stock or unsellable now. */
    CHANNEL_OUT_OF_STOCK(BusinessRoleCode.MARKETPLACE_OPERATOR, true),

    /** An exact listing and mode will run out inside its horizon. */
    CHANNEL_COVER_SHORT(BusinessRoleCode.MARKETPLACE_OPERATOR, true),

    /** The listing is not sellable for a reason the channel owner can repair. */
    CHANNEL_NOT_SELLABLE(BusinessRoleCode.MARKETPLACE_OPERATOR, true),

    /** The company runs out inside lead time plus safety. */
    COMPANY_SUPPLY_SHORT(BusinessRoleCode.PRODUCT_PROCUREMENT, true),

    /** Inbound that risk depended on has expired, slipped or been cancelled. */
    COMPANY_INBOUND_LAPSED(BusinessRoleCode.PRODUCT_PROCUREMENT, true),

    /** Stock, mapping or ownership evidence is missing or contradictory. */
    STOCK_DATA_DEFECT(BusinessRoleCode.TECH_DATA, true),

    /** Platform and internal stock cannot be proven physically distinct. */
    OWNERSHIP_UNDECLARED(BusinessRoleCode.TECH_DATA, true),

    /** No valid lead-time or safety policy resolves for this variant. */
    LEAD_TIME_POLICY_MISSING(BusinessRoleCode.PRODUCT_PROCUREMENT, true),

    /** No valid demand policy version resolves for this organization. */
    DEMAND_POLICY_MISSING(BusinessRoleCode.PRODUCT_PROCUREMENT, true),

    /** Demand cannot be observed and the carry-forward period has expired. */
    DEMAND_UNOBSERVABLE(BusinessRoleCode.TECH_DATA, true),

    /** Profit evidence is stale, incomplete or conflicted. */
    PROFIT_DATA_BLOCKED(BusinessRoleCode.FINANCE_ANALYST, true),

    /** Return, refusal or defect evidence needs a quality judgement. */
    RETURN_QUALITY_REVIEW(BusinessRoleCode.PRODUCT_PROCUREMENT, true),

    /** Nothing is wrong. Present so that a cleared child still names its cause. */
    NONE(null, false);

    private final BusinessRoleCode accountableRole;
    private final boolean actionable;

    RiskCause(BusinessRoleCode accountableRole, boolean actionable) {
        this.accountableRole = accountableRole;
        this.actionable = actionable;
    }

    /** The role accountable for repairing this cause, or {@code null} for {@link #NONE}. */
    public BusinessRoleCode accountableRole() {
        return accountableRole;
    }

    /** Whether this cause can be worked on independently of any other. */
    public boolean actionable() {
        return actionable;
    }

    /**
     * Whether this cause is a defect in the evidence rather than a shortage.
     *
     * <p>A blocker gets a cause-specific remediation task. Sending a data defect
     * to a procurement queue as ordinary restock work is exactly the misleading
     * routing the Contract forbids.
     */
    public boolean blocker() {
        return this == STOCK_DATA_DEFECT
                || this == OWNERSHIP_UNDECLARED
                || this == LEAD_TIME_POLICY_MISSING
                || this == DEMAND_POLICY_MISSING
                || this == DEMAND_UNOBSERVABLE
                || this == PROFIT_DATA_BLOCKED
                || this == RETURN_QUALITY_REVIEW;
    }

    /** The verification that proves this cause is actually repaired. */
    public VerificationKind verification() {
        return switch (this) {
            case CHANNEL_OUT_OF_STOCK, CHANNEL_COVER_SHORT, CHANNEL_NOT_SELLABLE ->
                    VerificationKind.CHANNEL_FRESH_AND_SELLABLE;
            case COMPANY_SUPPLY_SHORT, COMPANY_INBOUND_LAPSED ->
                    VerificationKind.COMPANY_RISK_BELOW_THRESHOLD;
            case LEAD_TIME_POLICY_MISSING, DEMAND_POLICY_MISSING ->
                    VerificationKind.UNIQUE_POLICY_RESOLVED;
            case RETURN_QUALITY_REVIEW -> VerificationKind.QUALITY_DISPOSITION_RECOMPUTED;
            default -> VerificationKind.SOURCE_RECOVERED;
        };
    }
}
