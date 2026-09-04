package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingCandidateRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingContainmentRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingDecisionRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What stops an approved bid change from becoming a command.
 *
 * <p>The reasons are the product's answer to an operator asking "why can I not
 * do this?", so every one of them is exercised here rather than left to be
 * discovered in front of somebody. The service is deliberately the only place
 * that decides both the scope and the reasons, so the two cannot disagree —
 * these cases assert that too.
 */
class AdvertisingDecisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final UUID ID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");

    private final AdvertisingDecisionRepository decisions =
            mock(AdvertisingDecisionRepository.class);
    private final AdvertisingCandidateRepository candidates =
            mock(AdvertisingCandidateRepository.class);
    private final AdvertisingContainmentRepository reservations =
            mock(AdvertisingContainmentRepository.class);
    private final IdGenerator ids = mock(IdGenerator.class);

    private final AdvertisingDecisionService service = new AdvertisingDecisionService(
            decisions, candidates, reservations, ids,
            Clock.fixed(NOW, ZoneOffset.UTC));

    /** A row with everything resolved, which each case then spoils in one way. */
    private static AdvertisingDecisionRepository.DecisionRow complete() {
        return new AdvertisingDecisionRepository.DecisionRow(
                ID, ID, ID, ID, "APPROVED", NOW.plusSeconds(3600), 0L,
                ID, ID, "PROTECTION", "PROTECTION_DECREASE", "MAX_CPC_BOUNDED",
                new BigDecimal("30.0000"), new BigDecimal("20.0000"), "RUB", "CURRENCY_MAJOR",
                "PROVEN_INDEPENDENT", "ACTIVE", "OZON", new BigDecimal("30.0000"),
                ID, NOW.plusSeconds(7200), 3600, 1800);
    }

    private void resolving(AdvertisingDecisionRepository.DecisionRow row) {
        when(decisions.resolve(ID)).thenReturn(Optional.of(row));
        when(decisions.bundleIsAmbiguous(ID)).thenReturn(false);
    }

    @Nested
    @DisplayName("TC-AD-DECIDE-001 a complete decision resolves to a usable scope")
    class Complete {

        @Test
        @DisplayName("every element is carried through, and nothing is unresolved")
        void completeRowResolves() {
            resolving(complete());

            assertThat(service.unresolvedReasons(ID)).isEmpty();
            assertThat(service.decisionScope(ID)).hasValueSatisfying(scope -> {
                assertThat(scope.direction()).isEqualTo("PROTECTION_DECREASE");
                assertThat(scope.changeAmount()).isEqualByComparingTo("10");
                // The lease is the shorter of the policy window and the
                // approval's own expiry, so neither can extend the other.
                assertThat(scope.approvalExpiresAt())
                        .isEqualTo(NOW.plusSeconds(1800));
            });
        }

        @Test
        @DisplayName("a change of zero would use the ordinary lease rather than the material one")
        void ordinaryChangeUsesTheOrdinaryLease() {
            var row = complete();
            resolving(new AdvertisingDecisionRepository.DecisionRow(
                    row.recommendationId(), row.organizationId(), row.storeId(),
                    row.adNativeObjectId(), row.recommendationState(), row.validUntil(),
                    row.version(), row.candidateId(), row.caseId(), row.lane(),
                    row.direction(), row.candidateBasis(), new BigDecimal("30.0000"),
                    new BigDecimal("30.0000"), row.currencyCode(), row.bidUnitCode(),
                    row.controlGranularityState(), row.objectStatus(), row.platformCode(),
                    row.observedBidAmount(), row.bundleId(), row.approvalExpiresAt(),
                    row.leaseSeconds(), row.materialLeaseSeconds()));

            // Proposing the current bid is refused for its own reason, and the
            // lease arithmetic is not reached at all.
            assertThat(service.unresolvedReasons(ID)).contains("NO_CHANGE_PROPOSED");
        }
    }

    @Nested
    @DisplayName("TC-AD-DECIDE-002 every refusal names itself")
    class Refusals {

        private AdvertisingDecisionRepository.DecisionRow spoiled(
                java.util.function.UnaryOperator<AdvertisingDecisionRepository.DecisionRow> change) {
            return change.apply(complete());
        }

        private void assertRefusedWith(AdvertisingDecisionRepository.DecisionRow row,
                                       String reason) {
            resolving(row);

            assertThat(service.unresolvedReasons(ID)).contains(reason);
            assertThat(service.decisionScope(ID)).isEmpty();
        }

        @Test
        @DisplayName("a recommendation nobody approved")
        void approvalMissing() {
            assertRefusedWith(spoiled(row -> new AdvertisingDecisionRepository.DecisionRow(
                    row.recommendationId(), row.organizationId(), row.storeId(),
                    row.adNativeObjectId(), "READY_FOR_REVIEW", row.validUntil(), row.version(),
                    row.candidateId(), row.caseId(), row.lane(), row.direction(),
                    row.candidateBasis(), row.currentBidAmount(), row.targetBidAmount(),
                    row.currencyCode(), row.bidUnitCode(), row.controlGranularityState(),
                    row.objectStatus(), row.platformCode(), row.observedBidAmount(),
                    row.bundleId(), row.approvalExpiresAt(), row.leaseSeconds(),
                    row.materialLeaseSeconds())), "APPROVAL_MISSING");
        }

        @Test
        @DisplayName("a recommendation whose own validity has elapsed")
        void recommendationExpired() {
            assertRefusedWith(spoiled(row -> new AdvertisingDecisionRepository.DecisionRow(
                    row.recommendationId(), row.organizationId(), row.storeId(),
                    row.adNativeObjectId(), row.recommendationState(), NOW.minusSeconds(1),
                    row.version(), row.candidateId(), row.caseId(), row.lane(), row.direction(),
                    row.candidateBasis(), row.currentBidAmount(), row.targetBidAmount(),
                    row.currencyCode(), row.bidUnitCode(), row.controlGranularityState(),
                    row.objectStatus(), row.platformCode(), row.observedBidAmount(),
                    row.bundleId(), row.approvalExpiresAt(), row.leaseSeconds(),
                    row.materialLeaseSeconds())), "RECOMMENDATION_EXPIRED");
        }

        @Test
        @DisplayName("a candidate that no longer exists")
        void candidateUnresolved() {
            assertRefusedWith(spoiled(row -> new AdvertisingDecisionRepository.DecisionRow(
                    row.recommendationId(), row.organizationId(), row.storeId(),
                    row.adNativeObjectId(), row.recommendationState(), row.validUntil(),
                    row.version(), null, row.caseId(), row.lane(), row.direction(),
                    row.candidateBasis(), row.currentBidAmount(), row.targetBidAmount(),
                    row.currencyCode(), row.bidUnitCode(), row.controlGranularityState(),
                    row.objectStatus(), row.platformCode(), row.observedBidAmount(),
                    row.bundleId(), row.approvalExpiresAt(), row.leaseSeconds(),
                    row.materialLeaseSeconds())), "CANDIDATE_UNRESOLVED");
        }

        @Test
        @DisplayName("a bid nobody observed")
        void currentBidNotObserved() {
            assertRefusedWith(spoiled(row -> new AdvertisingDecisionRepository.DecisionRow(
                    row.recommendationId(), row.organizationId(), row.storeId(),
                    row.adNativeObjectId(), row.recommendationState(), row.validUntil(),
                    row.version(), row.candidateId(), row.caseId(), row.lane(), row.direction(),
                    row.candidateBasis(), row.currentBidAmount(), row.targetBidAmount(),
                    row.currencyCode(), row.bidUnitCode(), row.controlGranularityState(),
                    row.objectStatus(), row.platformCode(), null, row.bundleId(),
                    row.approvalExpiresAt(), row.leaseSeconds(), row.materialLeaseSeconds())),
                    "CURRENT_BID_NOT_OBSERVED");
        }

        @Test
        @DisplayName("a bid that moved since the candidate was computed against it")
        void bidMovedSinceCandidate() {
            assertRefusedWith(spoiled(row -> new AdvertisingDecisionRepository.DecisionRow(
                    row.recommendationId(), row.organizationId(), row.storeId(),
                    row.adNativeObjectId(), row.recommendationState(), row.validUntil(),
                    row.version(), row.candidateId(), row.caseId(), row.lane(), row.direction(),
                    row.candidateBasis(), row.currentBidAmount(), row.targetBidAmount(),
                    row.currencyCode(), row.bidUnitCode(), row.controlGranularityState(),
                    row.objectStatus(), row.platformCode(), new BigDecimal("41.0000"),
                    row.bundleId(), row.approvalExpiresAt(), row.leaseSeconds(),
                    row.materialLeaseSeconds())), "BID_MOVED_SINCE_CANDIDATE");
        }

        @Test
        @DisplayName("an object nobody proved independently controllable")
        void controlGranularityUnproven() {
            assertRefusedWith(spoiled(row -> new AdvertisingDecisionRepository.DecisionRow(
                    row.recommendationId(), row.organizationId(), row.storeId(),
                    row.adNativeObjectId(), row.recommendationState(), row.validUntil(),
                    row.version(), row.candidateId(), row.caseId(), row.lane(), row.direction(),
                    row.candidateBasis(), row.currentBidAmount(), row.targetBidAmount(),
                    row.currencyCode(), row.bidUnitCode(), "UNKNOWN", row.objectStatus(),
                    row.platformCode(), row.observedBidAmount(), row.bundleId(),
                    row.approvalExpiresAt(), row.leaseSeconds(), row.materialLeaseSeconds())),
                    "CONTROL_GRANULARITY_UNPROVEN");
        }

        @Test
        @DisplayName("a retired object")
        void retiredObject() {
            assertRefusedWith(spoiled(row -> new AdvertisingDecisionRepository.DecisionRow(
                    row.recommendationId(), row.organizationId(), row.storeId(),
                    row.adNativeObjectId(), row.recommendationState(), row.validUntil(),
                    row.version(), row.candidateId(), row.caseId(), row.lane(), row.direction(),
                    row.candidateBasis(), row.currentBidAmount(), row.targetBidAmount(),
                    row.currencyCode(), row.bidUnitCode(), row.controlGranularityState(),
                    "RETIRED", row.platformCode(), row.observedBidAmount(), row.bundleId(),
                    row.approvalExpiresAt(), row.leaseSeconds(), row.materialLeaseSeconds())),
                    "CONTROL_GRANULARITY_UNPROVEN");
        }

        @Test
        @DisplayName("no complete active policy bundle")
        void bundleUnresolved() {
            assertRefusedWith(spoiled(row -> new AdvertisingDecisionRepository.DecisionRow(
                    row.recommendationId(), row.organizationId(), row.storeId(),
                    row.adNativeObjectId(), row.recommendationState(), row.validUntil(),
                    row.version(), row.candidateId(), row.caseId(), row.lane(), row.direction(),
                    row.candidateBasis(), row.currentBidAmount(), row.targetBidAmount(),
                    row.currencyCode(), row.bidUnitCode(), row.controlGranularityState(),
                    row.objectStatus(), row.platformCode(), row.observedBidAmount(), null,
                    row.approvalExpiresAt(), row.leaseSeconds(), row.materialLeaseSeconds())),
                    "BUNDLE_UNRESOLVED");
        }

        @Test
        @DisplayName("no approval-lease policy for this direction")
        void leasePolicyAbsent() {
            assertRefusedWith(spoiled(row -> new AdvertisingDecisionRepository.DecisionRow(
                    row.recommendationId(), row.organizationId(), row.storeId(),
                    row.adNativeObjectId(), row.recommendationState(), row.validUntil(),
                    row.version(), row.candidateId(), row.caseId(), row.lane(), row.direction(),
                    row.candidateBasis(), row.currentBidAmount(), row.targetBidAmount(),
                    row.currencyCode(), row.bidUnitCode(), row.controlGranularityState(),
                    row.objectStatus(), row.platformCode(), row.observedBidAmount(),
                    row.bundleId(), row.approvalExpiresAt(), null, null)),
                    "APPROVAL_LEASE_POLICY_ABSENT");
        }

        @Test
        @DisplayName("a recommendation that is not an advertising bid change at all")
        void notAnAdvertisingDecision() {
            when(decisions.resolve(ID)).thenReturn(Optional.empty());

            assertThat(service.unresolvedReasons(ID))
                    .containsExactly("NOT_AN_ADVERTISING_BID_CHANGE");
            assertThat(service.decisionScope(ID)).isEmpty();
            assertThat(service.bidProjection(ID)).isEmpty();
        }

        @Test
        @DisplayName("two bundles claiming one scope is the same as none")
        void bundleAmbiguous() {
            when(decisions.resolve(ID)).thenReturn(Optional.of(complete()));
            when(decisions.bundleIsAmbiguous(ID)).thenReturn(true);

            // Picking one of two would invent the authority the uniqueness rule
            // exists to guarantee.
            assertThat(service.unresolvedReasons(ID)).containsExactly("BUNDLE_AMBIGUOUS");
            assertThat(service.decisionScope(ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("TC-AD-DECIDE-003 the reservation is taken only at the action stage")
    class Reserving {

        @Test
        @DisplayName("an unresolved decision reserves nothing")
        void unresolvedDecisionReservesNothing() {
            when(decisions.resolve(ID)).thenReturn(Optional.empty());

            assertThat(service.reserveForExecution(ID, "correlation")).isEmpty();
            verify(reservations, never()).take(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an affected set nobody could enumerate reserves nothing")
        void unresolvedAffectedSetReservesNothing() {
            resolving(complete());
            when(candidates.resolvedAffectedSet(ID, ID)).thenReturn(Optional.empty());

            // Reserving here would claim to hold variants that were never listed.
            assertThat(service.reserveForExecution(ID, "correlation")).isEmpty();
            verify(reservations, never()).take(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a complete decision takes the reservation once")
        void completeDecisionReserves() {
            resolving(complete());
            when(candidates.resolvedAffectedSet(ID, ID)).thenReturn(Optional.of(
                    new AdvertisingCandidateRepository.AffectedSetRow(ID, "a".repeat(64),
                            List.of(ID))));
            when(ids.newId()).thenReturn(ID);
            when(reservations.take(any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any())).thenReturn(ID);

            assertThat(service.reserveForExecution(ID, "correlation")).contains(ID);
        }

        @Test
        @DisplayName("an overlapping set is a refusal, not a failure")
        void overlappingSetIsARefusal() {
            resolving(complete());
            when(candidates.resolvedAffectedSet(ID, ID)).thenReturn(Optional.of(
                    new AdvertisingCandidateRepository.AffectedSetRow(ID, "a".repeat(64),
                            List.of(ID))));
            when(ids.newId()).thenReturn(ID);
            when(reservations.take(any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "an active PROTECTION reservation already holds one of these"));

            // The containment working, and the decision stays approvable once
            // the other intervention finishes.
            assertThat(service.reserveForExecution(ID, "correlation")).isEmpty();
        }
    }
}
