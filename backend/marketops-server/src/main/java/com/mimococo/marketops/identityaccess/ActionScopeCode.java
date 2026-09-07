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
    AVAILABILITY_EXCEPTION_APPROVE(true),

    /** Read the advertising control queue, its cases, evidence and outcomes. */
    ADVERTISING_VIEW(false),

    /** Read financial decision evidence for every member of an advertising affected set. */
    ADVERTISING_DECISION_EVIDENCE_VIEW(false),

    ADVERTISING_MANUAL_EXECUTE(true),
    ADVERTISING_MANUAL_VERIFY(true),
    ADVERTISING_MANUAL_ENDORSE(true),
    ADVERTISING_MANUAL_APPROVE(true),

    ADVERTISING_TECHNICAL_STOP(true),
    ADVERTISING_TECHNICAL_ATTEST(true),

    /** Record structured action evidence against an accountable advertising case. */
    ADVERTISING_TASK_ACT(false),

    /** Request a scoped, expiring accepted exception against a calculated advertising risk. */
    ADVERTISING_EXCEPTION_REQUEST(false),

    /**
     * Give the distinct operational endorsement a bid change requires.
     *
     * <p>Separate from the final approval on purpose. The Contract's material
     * route needs two different people, and two different actions are how a
     * single grant cannot silently satisfy both halves of it.
     */
    AD_BID_CHANGE_ENDORSE(true),

    /** Give the final per-command approval for an exact, bounded advertising bid change. */
    AD_BID_CHANGE_APPROVE(true),

    /**
     * Publish or retire advertising decision policy.
     *
     * <p>Step-up for the same reason the supply policy needs it: a published
     * freshness, qualification, target or outcome version silently changes every
     * advertising answer calculated after it, and a relaxed threshold can turn a
     * blocked write into an available one without a single fact changing.
     */
    ADVERTISING_POLICY_MANAGE(true);

    private final boolean stepUpRequired;

    ActionScopeCode(boolean stepUpRequired) {
        this.stepUpRequired = stepUpRequired;
    }

    /** Whether this action demands a recent authentication as well as a grant. */
    public boolean stepUpRequired() {
        return stepUpRequired;
    }
}
