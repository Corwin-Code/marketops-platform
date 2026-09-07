package com.mimococo.marketops.advertisingefficiency.internal.domain;

import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.BidDirection;
import java.util.Optional;

/**
 * Which causes justify moving a bid, and which way.
 *
 * <p>Most causes justify nothing. A data defect is somebody's work, not a bid
 * change; an unresolved policy is a reason to stop rather than to act; an
 * outcome regression is handled by the compensation the original action already
 * owns, not by a new decision on top of it.
 *
 * <p>The two that read as if they should move a bid and deliberately do not are
 * worth naming. {@code CRITICAL_SALES_UNIT_AT_RISK} is a unit whose sales the
 * business cannot afford to lose; lowering its bid is exactly the thing the
 * sales-preservation axis exists to refuse, so it produces no candidate and
 * stays a case a person owns. {@code ACTION_OUTCOME_REGRESSION} means a change
 * this product already made went the wrong way, and the answer to that is the
 * exact prior bid, restored inside the original lineage — never a fresh
 * decision computed from the state the regression produced.
 */
public final class BidDirectionForCause {

    private BidDirectionForCause() {
    }

    /** The direction this cause justifies, if it justifies one at all. */
    public static Optional<BidDirection> of(AdvertisingCause cause) {
        if (cause == null) {
            return Optional.empty();
        }
        return switch (cause) {
            case PROVEN_ADVERTISING_LOSS,
                 PROMOTED_VARIANT_NOT_SELLABLE,
                 PROMOTED_VARIANT_UNAVAILABLE -> Optional.of(BidDirection.PROTECTION_DECREASE);
            case RECOVERABLE_ADVERTISING_PROFIT ->
                    Optional.of(BidDirection.OPTIMIZATION_INCREASE);
            case CRITICAL_SALES_UNIT_AT_RISK,
                 ACTION_OUTCOME_REGRESSION,
                 OFFICIAL_AD_FACT_DEFECT,
                 AFFECTED_SET_UNRESOLVED,
                 AD_LINKAGE_COVERAGE_INSUFFICIENT,
                 ATTRIBUTION_GAP_MATERIAL,
                 PROFIT_ECONOMICS_BLOCKED,
                 DECISION_POLICY_UNRESOLVED,
                 NATIVE_SEMANTICS_UNKNOWN,
                 OBJECT_NOT_INDEPENDENTLY_CONTROLLABLE,
                 IMMATURE_SIGNAL,
                 NONE -> Optional.empty();
        };
    }
}
