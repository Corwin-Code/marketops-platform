package com.mimococo.marketops.identityaccess;

/**
 * The closed set of business actions this product authorizes.
 *
 * <p>The set is closed on purpose. An action that is not named here has no
 * grant that could permit it, so a new operating capability cannot be reached
 * before somebody has decided which roles may hold it and recorded that
 * decision in the reviewed role matrix.
 *
 * <p>{@code stepUpRequired} marks the actions whose consequence is external or
 * financially material. For those, holding the grant is not enough: the person
 * must have authenticated recently enough for the identity provider's recorded
 * maximum authentication age.
 */
public enum ActionScopeCode {

    /** Read the priority queue, SKU diagnosis and canonical metrics. */
    DIAGNOSTIC_VIEW(false),

    /** Open source-evidence references behind a canonical fact. */
    EVIDENCE_VIEW(false),

    /** Confirm, reject or supersede a listing-to-SKU mapping candidate. */
    MAPPING_RESOLVE(false),

    /** Enter or import cost, stock and finance facts. */
    INTERNAL_FACT_INTAKE(false),

    /** Validate, cancel or expire a recommendation and its tasks. */
    RECOMMENDATION_MANAGE(false),

    /** Assign and reassign operating tasks. */
    TASK_ASSIGN(false),

    /** Approve a price command or consume a bounded policy authorization. */
    PRICE_CHANGE_APPROVE(true),

    /** Publish or retire a commercial policy version or scoped override. */
    COMMERCIAL_POLICY_MANAGE(true),

    /** Resolve an unknown result, readback mismatch or compensation. */
    COMMAND_RESOLVE(true),

    /** Disable or re-enable a write capability scope. */
    KILL_SWITCH_OPERATE(true),

    /** Read the stockout and availability queue, its children and its evidence. */
    AVAILABILITY_VIEW(false),

    /** Record, amend or cancel an evidence-backed inbound supply attestation. */
    INBOUND_ATTEST(false),

    /**
     * Publish or retire lead-time, safety, demand, ownership and activation policy.
     *
     * <p>Step-up is required because a published policy version silently changes
     * every risk calculated after it: a shortened lead time can clear a queue
     * without a single unit moving.
     */
    SUPPLY_POLICY_MANAGE(true),

    /** Record structured action evidence against an accountable availability case. */
    AVAILABILITY_TASK_ACT(false),

    /** Request a scoped, expiring accepted exception against a calculated risk. */
    AVAILABILITY_EXCEPTION_REQUEST(false),

    /**
     * Decide a scoped, expiring accepted exception at the authorised lane.
     *
     * <p>Step-up is required for the same reason a price approval needs it: the
     * consequence is that a real risk stops raising work.
     */
    AVAILABILITY_EXCEPTION_APPROVE(true);

    private final boolean stepUpRequired;

    ActionScopeCode(boolean stepUpRequired) {
        this.stepUpRequired = stepUpRequired;
    }

    /** Whether this action demands a recent authentication as well as a grant. */
    public boolean stepUpRequired() {
        return stepUpRequired;
    }
}
