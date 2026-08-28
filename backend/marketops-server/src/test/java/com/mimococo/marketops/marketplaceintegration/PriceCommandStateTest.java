package com.mimococo.marketops.marketplaceintegration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * How a command's state classifies for the rest of the product.
 *
 * <p>The allowed transitions live in the database as data and are asserted
 * against it. What is asserted here is the classification the application acts
 * on: whether a command is finished, whether a worker still holds it, and
 * whether it is waiting for a person. Getting one of those wrong would let a
 * command that needs an operator disappear from the queue that shows them.
 */
class PriceCommandStateTest {

    @Nested
    @DisplayName("TC-CMD-001 not knowing is its own outcome")
    class UnknownOutcomes {

        @Test
        void anUnknownResultIsNeitherFinishedNorAutomaticallyRetried() {
            assertThat(PriceCommandState.UNKNOWN_REQUIRES_READBACK.terminal()).isFalse();
            assertThat(PriceCommandState.UNKNOWN_REQUIRES_READBACK.leaseHeld()).isFalse();
            assertThat(PriceCommandState.UNKNOWN_REQUIRES_READBACK.needsOperator()).isTrue();
        }

        @Test
        void aReadbackMismatchIsAFactAboutTheWorldRatherThanAFailure() {
            assertThat(PriceCommandState.READBACK_MISMATCH.terminal()).isFalse();
            assertThat(PriceCommandState.READBACK_MISMATCH.needsOperator()).isTrue();
        }

        @Test
        void everyStateNeedingAPersonIsUnfinished() {
            for (PriceCommandState state : PriceCommandState.values()) {
                if (state.needsOperator()) {
                    assertThat(state.terminal())
                            .describedAs("%s needs an operator", state)
                            .isFalse();
                }
            }
        }
    }

    @Nested
    @DisplayName("TC-CMD-002 a finished command is finished")
    class Terminality {

        @ParameterizedTest
        @EnumSource(value = PriceCommandState.class,
                names = {"SUCCEEDED", "FAILED_FINAL", "COMPENSATED", "COMPENSATION_FAILED"})
        void aTerminalStateHoldsNoLeaseAndNeedsNobody(PriceCommandState state) {
            assertThat(state.terminal()).isTrue();
            assertThat(state.leaseHeld()).isFalse();
            assertThat(state.needsOperator()).isFalse();
        }

        @Test
        void exactlyFourStatesAreTerminal() {
            long terminal = java.util.Arrays.stream(PriceCommandState.values())
                    .filter(PriceCommandState::terminal)
                    .count();

            assertThat(terminal).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("TC-CMD-003 a claim and a wait are different things")
    class Leasing {

        @ParameterizedTest
        @EnumSource(value = PriceCommandState.class,
                names = {"LEASED", "EXECUTING", "PLATFORM_PENDING", "READBACK_PENDING",
                        "COMPENSATION_PENDING"})
        void anActivelyWorkedStateHoldsALease(PriceCommandState state) {
            assertThat(state.leaseHeld()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = PriceCommandState.class, names = {"PENDING", "RETRY_WAIT"})
        void aQueuedStateHoldsNoLease(PriceCommandState state) {
            assertThat(state.leaseHeld()).isFalse();
            assertThat(state.terminal()).isFalse();
            assertThat(state.needsOperator()).isFalse();
        }
    }
}
