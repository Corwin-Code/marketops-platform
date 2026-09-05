package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.BidDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which causes may move a real bid. */
class BidDirectionForCauseTest {

    @Test
    @DisplayName("TC-AD-DIR-001 only proven harm lowers a bid and only proven recovery raises one")
    void onlyProvenCausesMoveABid() {
        assertThat(BidDirectionForCause.of(AdvertisingCause.PROVEN_ADVERTISING_LOSS))
                .contains(BidDirection.PROTECTION_DECREASE);
        assertThat(BidDirectionForCause.of(AdvertisingCause.PROMOTED_VARIANT_NOT_SELLABLE))
                .contains(BidDirection.PROTECTION_DECREASE);
        assertThat(BidDirectionForCause.of(AdvertisingCause.PROMOTED_VARIANT_UNAVAILABLE))
                .contains(BidDirection.PROTECTION_DECREASE);
        assertThat(BidDirectionForCause.of(AdvertisingCause.RECOVERABLE_ADVERTISING_PROFIT))
                .contains(BidDirection.OPTIMIZATION_INCREASE);
    }

    @Test
    @DisplayName("TC-AD-DIR-002 a unit whose sales matter is never cut automatically")
    void criticalSalesUnitIsNeverCut() {
        // The sales-preservation axis exists for exactly this case. Lowering the
        // bid on a unit the business cannot afford to lose sales on is the
        // mistake, not the remedy.
        assertThat(BidDirectionForCause.of(AdvertisingCause.CRITICAL_SALES_UNIT_AT_RISK))
                .isEmpty();
    }

    @Test
    @DisplayName("TC-AD-DIR-003 a regression is answered by the prior bid, not a fresh decision")
    void regressionProducesNoFreshDecision() {
        assertThat(BidDirectionForCause.of(AdvertisingCause.ACTION_OUTCOME_REGRESSION)).isEmpty();
    }

    @Test
    @DisplayName("TC-AD-DIR-004 no data defect and no unresolved policy moves a bid")
    void defectsAndUnresolvedPolicyMoveNothing() {
        for (AdvertisingCause cause : AdvertisingCause.values()) {
            if (cause.dataDefect()) {
                assertThat(BidDirectionForCause.of(cause))
                        .describedAs("%s", cause)
                        .isEmpty();
            }
        }
        assertThat(BidDirectionForCause.of(null)).isEmpty();
    }

    @Test
    @DisplayName("TC-AD-DIR-005 compensation is never produced from a cause")
    void compensationIsNeverProducedFromACause() {
        // A compensation belongs to the lineage of the action it undoes. If a
        // cause could produce one, a command could be created to restore a bid
        // no command in this product had changed.
        for (AdvertisingCause cause : AdvertisingCause.values()) {
            assertThat(BidDirectionForCause.of(cause))
                    .describedAs("%s", cause)
                    .isNotEqualTo(java.util.Optional.of(
                            BidDirection.EXACT_PRIOR_BID_COMPENSATION));
        }
    }
}
