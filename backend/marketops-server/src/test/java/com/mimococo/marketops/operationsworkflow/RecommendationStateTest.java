package com.mimococo.marketops.operationsworkflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The moves a proposal is allowed to make.
 *
 * <p>These are asserted as properties of the whole machine rather than one
 * transition at a time, because the guarantees that matter are shaped like "no
 * path exists" rather than "this path exists". A machine tested only by its
 * happy path stays correct right up until somebody adds an edge.
 */
class RecommendationStateTest {

    @Nested
    @DisplayName("TC-WF-001 a write is only ever authorized deliberately")
    class AuthorizationPaths {

        @Test
        void nothingReachesAnAuthorizedStateWithoutBeingReviewed() {
            Set<RecommendationState> reachingApproved = Set.of(
                    RecommendationState.values()).stream()
                    .filter(state -> state.mayMoveTo(RecommendationState.APPROVED)
                            || state.mayMoveTo(RecommendationState.POLICY_AUTHORIZED))
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(reachingApproved)
                    .containsExactly(RecommendationState.READY_FOR_REVIEW);
        }

        @Test
        void aTaskOnlyProposalCanNeverBecomeAuthorized() {
            assertThat(RecommendationState.TASK_ONLY.allowedNext())
                    .doesNotContain(RecommendationState.APPROVED,
                            RecommendationState.POLICY_AUTHORIZED,
                            RecommendationState.COMMAND_CREATED);
        }

        @Test
        void onlyAnAuthorizedProposalReachesACommand() {
            Set<RecommendationState> reachingCommand = Set.of(
                    RecommendationState.values()).stream()
                    .filter(state -> state.mayMoveTo(RecommendationState.COMMAND_CREATED))
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(reachingCommand).containsExactlyInAnyOrder(
                    RecommendationState.APPROVED, RecommendationState.POLICY_AUTHORIZED);
        }

        @Test
        void aRejectionIsNotAnAuthorization() {
            assertThat(RecommendationState.REJECTED.authorized()).isFalse();
            assertThat(RecommendationState.APPROVED.authorized()).isTrue();
            assertThat(RecommendationState.POLICY_AUTHORIZED.authorized()).isTrue();
        }
    }

    @Nested
    @DisplayName("TC-WF-002 a finished proposal stays finished")
    class Terminality {

        @ParameterizedTest
        @EnumSource(value = RecommendationState.class,
                names = {"REJECTED", "EXPIRED", "CANCELLED", "CLOSED"})
        void aTerminalStateHasNowhereToGo(RecommendationState state) {
            assertThat(state.terminal()).isTrue();
            assertThat(state.allowedNext()).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(value = RecommendationState.class,
                names = {"REJECTED", "EXPIRED", "CANCELLED", "CLOSED"})
        void nothingCanReviveATerminalProposal(RecommendationState terminal) {
            assertThat(terminal.mayMoveTo(RecommendationState.READY_FOR_REVIEW)).isFalse();
            assertThat(terminal.mayMoveTo(RecommendationState.APPROVED)).isFalse();
        }

        @Test
        void aCommandThatExistsCannotBeExpiredAway() {
            // Expiry is a sweep. A proposal whose command may already have
            // reached a marketplace must not be quietly closed by one.
            assertThat(RecommendationState.COMMAND_CREATED.allowedNext())
                    .doesNotContain(RecommendationState.EXPIRED);
            assertThat(RecommendationState.EXECUTION_TRACKING.allowedNext())
                    .doesNotContain(RecommendationState.EXPIRED,
                            RecommendationState.CANCELLED);
        }
    }

    @Nested
    @DisplayName("TC-WF-003 every state is reachable and self-consistent")
    class Wholeness {

        @ParameterizedTest
        @EnumSource(RecommendationState.class)
        void noStateMovesToItself(RecommendationState state) {
            assertThat(state.allowedNext()).doesNotContain(state);
        }

        @ParameterizedTest
        @EnumSource(RecommendationState.class)
        void everyStateIsEitherTerminalOrHasSomewhereToGo(RecommendationState state) {
            assertThat(state.terminal() || !state.allowedNext().isEmpty()).isTrue();
        }

        @Test
        void everyStateExceptTheStartIsReachable() {
            Set<RecommendationState> reachable = Set.of(RecommendationState.values()).stream()
                    .flatMap(state -> state.allowedNext().stream())
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(reachable).containsAll(
                    Set.of(RecommendationState.values()).stream()
                            .filter(state -> state != RecommendationState.DRAFT)
                            .collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Nested
    @DisplayName("TC-WF-004 exactly two actions have a platform write behind them")
    class ActionCapability {

        @Test
        void priceAndBidAreTheOnlyWriteCapableActions() {
            Set<ActionKind> writeCapable = Set.of(ActionKind.values()).stream()
                    .filter(ActionKind::writeCapable)
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(writeCapable)
                    .containsExactlyInAnyOrder(ActionKind.PRICE_CHANGE, ActionKind.AD_BID_CHANGE);
        }

        @Test
        void advertisingReviewCarriesNoWrite() {
            // This is where a budget change, a pause and a targeting change
            // live. If the review ever became write-capable, an approval of one
            // of them could produce a command, and this product writes no such
            // thing. The bid change is a separate action for exactly that
            // reason.
            assertThat(ActionKind.ADVERTISING_REVIEW.writeCapable()).isFalse();
        }
    }
}
