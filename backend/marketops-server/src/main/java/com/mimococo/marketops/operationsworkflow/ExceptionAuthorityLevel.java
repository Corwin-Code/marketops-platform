package com.mimococo.marketops.operationsworkflow;

import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import java.util.Set;

/**
 * How much authority it takes to accept a calculated risk.
 *
 * <p>Approval is proportional because the alternative is not. One level would
 * either make a trivial acceptance ceremonial or a material one casual, and it
 * is the material one that ends up unexamined.
 */
public enum ExceptionAuthorityLevel {

    /** A bounded, non-repeated, immaterial acceptance in one domain. */
    DOMAIN_LEAD(1),

    /** An ordinary operational acceptance. */
    OPS_LEAD(2),

    /** A critical, repeated or material acceptance. */
    RISK_AUTHORITY(3);

    private final int rank;

    ExceptionAuthorityLevel(int rank) {
        this.rank = rank;
    }

    /** Whether this level covers what {@code required} demands. */
    public boolean satisfies(ExceptionAuthorityLevel required) {
        return rank >= required.rank;
    }

    /** The higher of two levels. */
    public ExceptionAuthorityLevel raisedTo(ExceptionAuthorityLevel other) {
        return other.rank > rank ? other : this;
    }

    /**
     * The levels a business role may decide at.
     *
     * <p>The mapping is deliberately narrow. A marketplace operator has no
     * acceptance authority at all: the person who reports a risk is not the
     * person who decides the business may live with it.
     *
     * <p>The Owner sits with the Risk Authority they designate rather than
     * below it. Granting the Owner an approval action they could never exercise
     * would be a contradiction in the reviewed matrix, and separation is
     * enforced on identity rather than on role, so it still holds for them.
     */
    public static Set<ExceptionAuthorityLevel> levelsFor(BusinessRoleCode role) {
        return switch (role) {
            case RISK_AUTHORITY, OWNER -> Set.of(DOMAIN_LEAD, OPS_LEAD, RISK_AUTHORITY);
            case OPS_LEAD -> Set.of(DOMAIN_LEAD, OPS_LEAD);
            case PRODUCT_PROCUREMENT, TECH_DATA, FINANCE_ANALYST -> Set.of(DOMAIN_LEAD);
            default -> Set.of();
        };
    }
}
