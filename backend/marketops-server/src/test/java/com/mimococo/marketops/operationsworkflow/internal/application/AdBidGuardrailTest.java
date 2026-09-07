package com.mimococo.marketops.operationsworkflow.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.analyticsdecision.DiagnosisQuery;
import com.mimococo.marketops.marketplaceintegration.PriceChangeHistory;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.AdvertisingBidProjection;
import com.mimococo.marketops.operationsworkflow.AdvertisingDecisionAuthority;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.GuardrailReason;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.GuardrailRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What refuses an advertising bid change, from both sides.
 *
 * <p>The advertising module's deterministic refusals are carried through rather
 * than re-derived, and the workflow adds its own. Neither overrides the other
 * and an empty union is the only way to pass — which is asserted here by
 * spoiling one thing at a time and watching exactly one reason appear.
 */
class AdBidGuardrailTest {

    private static final UUID ID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
    private static final String DIGEST = "b".repeat(64);

    private final MetricQuery metrics = mock(MetricQuery.class);
    private final DiagnosisQuery diagnosis = mock(DiagnosisQuery.class);
    private final GuardrailRepository evaluations = mock(GuardrailRepository.class);
    private final PriceChangeHistory changeHistory = mock(PriceChangeHistory.class);
    private final AdvertisingDecisionAuthority advertising =
            mock(AdvertisingDecisionAuthority.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final AdvertisingImpactEvidenceService impact = mock(AdvertisingImpactEvidenceService.class);

    private final GuardrailService service = new GuardrailService(
            metrics, diagnosis, evaluations, changeHistory, advertising, ids,impact);

    private static RecommendationView proposal() {
        return new RecommendationView(ID, ID, ID, SubjectKind.AD_NATIVE_OBJECT, ID,
                ActionKind.AD_BID_CHANGE, "DETERMINISTIC", null, MetricWindow.D30,
                RecommendationState.READY_FOR_REVIEW, new BigDecimal("900"),
                Map.of("candidateId", ID.toString(), "direction", "PROTECTION_DECREASE",
                        "targetBid", "20.0000"),
                Map.of(), "LOW", 14, DIGEST, NOW.plusSeconds(3600), null, List.of(),
                NOW.minusSeconds(60), 0L);
    }

    private static AdvertisingBidProjection projection(
            List<String> blockers, String maxCpcState, BigDecimal maxCpc,
            List<String> exhaustedAxes, UUID bundleId, String entityDigest) {
        return new AdvertisingBidProjection(ID, ID, ID, ID, ID, "PROTECTION", "P2",
                "PROVEN_ADVERTISING_LOSS", "CANONICAL_CONFIRMED", "HIGH", blockers,
                "PROTECTION_DECREASE", "MAX_CPC_BOUNDED", new BigDecimal("30.0000"),
                new BigDecimal("20.0000"), "RUB", "CURRENCY_MAJOR", maxCpc, maxCpcState,
                new BigDecimal("0.100000"), 47, "a".repeat(64), "MATERIAL_IMPACT",
                // A bundle is named with its version or not at all, which the
                // projection refuses to express any other way.
                exhaustedAxes, entityDigest, bundleId, bundleId == null ? null : 1);
    }

    private static AdvertisingBidProjection clean() {
        return projection(List.of(), "AVAILABLE", new BigDecimal("25.0000"), List.of(), ID,
                DIGEST);
    }

    @BeforeEach
    void everythingResolves() {
        when(ids.newId()).thenReturn(ID);
        when(evaluations.captureAdBidAuthority(ID)).thenReturn(
                new GuardrailRepository.AdvertisingAuthority(NOW, "{}"));
        when(advertising.bidProjection(ID)).thenReturn(Optional.of(clean()));
        when(advertising.unresolvedReasons(ID)).thenReturn(List.of());
        var evidence=new tools.jackson.databind.ObjectMapper().createObjectNode();
        evidence.putObject("policyVersions").putObject("target").put("ceiling_headroom_ratio",BigDecimal.ZERO);
        when(impact.capture(ID,NOW,ID)).thenReturn(evidence);
    }

    private List<GuardrailReason> reasonsFor() {
        return service.previewAdBidChange(proposal(), GuardrailPurpose.EXECUTION)
                .verdict().reasons();
    }

    @Test void theConservativePolicyCeilingCannotBeReplacedWithTheRawEconomicCeiling() {
        var evidence=new tools.jackson.databind.ObjectMapper().createObjectNode();
        evidence.putObject("policyVersions").putObject("target").put("ceiling_headroom_ratio",new BigDecimal("0.3"));
        when(impact.capture(ID,NOW,ID)).thenReturn(evidence);
        assertThat(reasonsFor()).contains(GuardrailReason.ABOVE_MAX_CPC);
    }

    @Test void absentHeadroomAuthorityCannotSilentlyUseZero() {
        when(impact.capture(ID,NOW,ID)).thenReturn(new tools.jackson.databind.ObjectMapper().createObjectNode());
        assertThat(reasonsFor()).contains(GuardrailReason.AD_POLICY_BUNDLE_UNRESOLVED);
    }

    @Nested
    @DisplayName("TC-AD-GUARD-001 a clean decision passes and names its authority")
    class Passing {

        @Test
        @DisplayName("nothing refuses it, and the verdict names the bundle that let it pass")
        void cleanDecisionPasses() {
            var preview = service.previewAdBidChange(proposal(), GuardrailPurpose.EXECUTION);

            assertThat(preview.verdict().passed()).isTrue();
            assertThat(preview.verdict().reasons()).isEmpty();
            // A verdict that passes has to name the authority that let it.
            verify(evaluations).insert(eq(ID), eq(ID), eq(ID), eq(null), eq(null), eq(ID),
                    eq(1), eq(GuardrailPurpose.EXECUTION), eq(true), anyList(), anyMap(),
                    anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("the projection travels to the operator with the verdict")
        void projectionTravelsWithTheVerdict() {
            var preview = service.previewAdBidChange(proposal(), GuardrailPurpose.EXECUTION);

            // The number that distinguishes a bid change from a price change.
            assertThat(preview.affectedVariantCount()).isEqualTo(47);
            assertThat(preview.clear()).isTrue();
        }
    }

    @Nested
    @DisplayName("TC-AD-GUARD-002 each refusal appears on its own")
    class Refusals {

        @Test
        @DisplayName("the advertising calculation's own blockers are carried through")
        void advertisingBlockersAreCarriedThrough() {
            when(advertising.bidProjection(ID)).thenReturn(Optional.of(projection(
                    List.of("AD_LINKED_CONVERSION_NOT_WRITE_GRADE"), "AVAILABLE",
                    new BigDecimal("25.0000"), List.of(), ID, DIGEST)));

            // Mapped to one reason rather than copied in one by one: they are
            // the module's vocabulary and it owns their meaning.
            assertThat(reasonsFor()).contains(GuardrailReason.ADVERTISING_CASE_BLOCKED);
        }

        @Test
        @DisplayName("facts that moved since the case was built")
        void entityVersionChanged() {
            when(advertising.bidProjection(ID)).thenReturn(Optional.of(projection(
                    List.of(), "AVAILABLE", new BigDecimal("25.0000"), List.of(), ID,
                    "c".repeat(64))));

            assertThat(reasonsFor()).contains(GuardrailReason.ENTITY_VERSION_CHANGED);
        }

        @Test
        @DisplayName("an absent ceiling refuses rather than permits")
        void absentCeilingRefuses() {
            when(advertising.bidProjection(ID)).thenReturn(Optional.of(projection(
                    List.of(), "NOT_AVAILABLE", null, List.of(), ID, DIGEST)));

            // A ceiling that could not be computed is not a ceiling of infinity.
            assertThat(reasonsFor()).contains(GuardrailReason.MAX_CPC_UNAVAILABLE);
        }

        @Test
        @DisplayName("a target above the ceiling")
        void aboveTheCeiling() {
            when(advertising.bidProjection(ID)).thenReturn(Optional.of(projection(
                    List.of(), "AVAILABLE", new BigDecimal("15.0000"), List.of(), ID, DIGEST)));

            assertThat(reasonsFor()).contains(GuardrailReason.ABOVE_MAX_CPC);
        }

        @Test
        @DisplayName("an exposure axis with no headroom left")
        void exposureExhausted() {
            when(advertising.bidProjection(ID)).thenReturn(Optional.of(projection(
                    List.of(), "AVAILABLE", new BigDecimal("25.0000"),
                    List.of("ACTIVE_INTERVENTIONS"), ID, DIGEST)));

            assertThat(reasonsFor()).contains(GuardrailReason.EXPOSURE_ENVELOPE_EXHAUSTED);
        }

        @Test
        @DisplayName("no bundle means nothing to record a pass against")
        void noBundleMeansNoPass() {
            when(advertising.bidProjection(ID)).thenReturn(Optional.of(projection(
                    List.of(), "AVAILABLE", new BigDecimal("25.0000"), List.of(), null,
                    DIGEST)));

            assertThat(reasonsFor()).contains(GuardrailReason.AD_POLICY_BUNDLE_UNRESOLVED);
        }

        @Test
        @DisplayName("nothing known about the change at all")
        void noProjectionAtAll() {
            when(advertising.bidProjection(ID)).thenReturn(Optional.empty());

            var preview = service.previewAdBidChange(proposal(), GuardrailPurpose.EXECUTION);

            assertThat(preview.verdict().reasons())
                    .containsExactly(GuardrailReason.ADVERTISING_CASE_BLOCKED);
            // And the verdict is recorded naming no bundle, which the schema
            // will refuse for a PASS — so it is a BLOCK, which it is.
            assertThat(preview.verdict().passed()).isFalse();
        }
    }

    @Nested
    @DisplayName("TC-AD-GUARD-003 the module's reasons are translated, not echoed")
    class Translation {

        @Test
        @DisplayName("every decision-scope reason the workflow owns becomes its own verdict reason")
        void scopeReasonsBecomeVerdictReasons() {
            record Case(String scopeReason, GuardrailReason verdictReason) {
            }
            for (var mapping : List.of(
                    new Case("BID_MOVED_SINCE_CANDIDATE",
                            GuardrailReason.BID_MOVED_SINCE_CANDIDATE),
                    new Case("CURRENT_BID_NOT_OBSERVED",
                            GuardrailReason.CURRENT_BID_NOT_OBSERVED),
                    new Case("CONTROL_GRANULARITY_UNPROVEN",
                            GuardrailReason.CONTROL_GRANULARITY_UNPROVEN),
                    new Case("RESERVATION_NOT_HELD", GuardrailReason.RESERVATION_NOT_HELD),
                    new Case("BUNDLE_AMBIGUOUS", GuardrailReason.AD_POLICY_BUNDLE_UNRESOLVED),
                    new Case("APPROVAL_LEASE_POLICY_ABSENT",
                            GuardrailReason.APPROVAL_LEASE_POLICY_ABSENT),
                    new Case("NO_CHANGE_PROPOSED", GuardrailReason.NO_CHANGE_PROPOSED))) {
                when(advertising.unresolvedReasons(ID)).thenReturn(List.of(mapping.scopeReason()));

                assertThat(reasonsFor())
                        .describedAs("%s", mapping.scopeReason())
                        .contains(mapping.verdictReason());
            }
        }

        @Test
        @DisplayName("approval absence permits a preview but blocks execution")
        void approvalMissingBlocksOnlyExecution() {
            when(advertising.unresolvedReasons(ID)).thenReturn(List.of("APPROVAL_MISSING"));

            assertThat(service.previewAdBidChange(proposal(),GuardrailPurpose.IMPACT_PREVIEW).verdict().passed()).isTrue();
            assertThat(service.previewAdBidChange(proposal(),GuardrailPurpose.APPROVAL).verdict().passed()).isTrue();
            assertThat(reasonsFor()).contains(GuardrailReason.ADVERTISING_CASE_BLOCKED);
        }

        @Test
        @DisplayName("an unrecognized authority refusal remains a refusal")
        void unknownScopeReasonFailsClosed() {
            when(advertising.unresolvedReasons(ID))
                    .thenReturn(List.of("A_REASON_FROM_THE_FUTURE"));

            assertThat(reasonsFor()).contains(GuardrailReason.ADVERTISING_CASE_BLOCKED);
        }

        @Test
        @DisplayName("a proposal that has run out is refused by the workflow, not the module")
        void expiredProposalIsRefusedHere() {
            var expired = new RecommendationView(ID, ID, ID, SubjectKind.AD_NATIVE_OBJECT, ID,
                    ActionKind.AD_BID_CHANGE, "DETERMINISTIC", null, MetricWindow.D30,
                    RecommendationState.READY_FOR_REVIEW, new BigDecimal("900"),
                    proposal().proposedParameters(), Map.of(), "LOW", 14, DIGEST,
                    NOW.minusSeconds(1), null, List.of(), NOW.minusSeconds(60), 0L);

            assertThat(service.previewAdBidChange(expired, GuardrailPurpose.EXECUTION)
                    .verdict().reasons())
                    .contains(GuardrailReason.RECOMMENDATION_EXPIRED);
        }
    }

    @Nested
    @DisplayName("TC-AD-GUARD-004 evaluate dispatches on the action")
    class Dispatch {

        @Test
        @DisplayName("an advertising proposal never reaches the price engine")
        void advertisingNeverReachesThePriceEngine() {
            service.evaluate(proposal(), null, GuardrailPurpose.EXECUTION);

            // The price authority is never captured, so the price path's
            // listing, cost and stock reads never happen for an advertising
            // subject that has none of them.
            verify(evaluations).captureAdBidAuthority(ID);
            verify(evaluations, org.mockito.Mockito.never()).captureAuthority(any());
        }
    }
}
