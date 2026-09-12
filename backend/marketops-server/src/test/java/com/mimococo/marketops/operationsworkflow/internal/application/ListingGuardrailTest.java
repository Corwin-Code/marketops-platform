package com.mimococo.marketops.operationsworkflow.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.analyticsdecision.DiagnosisQuery;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.marketplaceintegration.PriceChangeHistory;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.AdvertisingDecisionAuthority;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.GuardrailReason;
import com.mimococo.marketops.operationsworkflow.ListingActionDecisionAuthority;
import com.mimococo.marketops.operationsworkflow.ListingDecisionScope;
import com.mimococo.marketops.operationsworkflow.ListingImpactPreview;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.GuardrailRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What refuses a listing action, from both sides.
 *
 * <p>The listing module's deterministic blockers are carried through in its own
 * vocabulary and the workflow adds its own. A PASS names the calibration
 * package that authorised it; a refusal names nothing.
 */
class ListingGuardrailTest {

    private static final UUID ID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3311");
    private static final UUID PACKAGE = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3312");
    private static final UUID AUTHOR = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3313");
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
    private static final String DIGEST = "c".repeat(64);

    private final GuardrailRepository evaluations = mock(GuardrailRepository.class);
    private final ListingActionDecisionAuthority listingDecisions = mock(ListingActionDecisionAuthority.class);
    private final IdGenerator ids = mock(IdGenerator.class);

    private final GuardrailService service = new GuardrailService(mock(MetricQuery.class),
            mock(DiagnosisQuery.class), evaluations, mock(PriceChangeHistory.class),
            mock(AdvertisingDecisionAuthority.class), ids, mock(AdvertisingImpactEvidenceService.class),
            listingDecisions, Clock.fixed(NOW, ZoneOffset.UTC));

    private static RecommendationView proposal(Instant validUntil) {
        return new RecommendationView(ID, ID, ID, SubjectKind.PLATFORM_LISTING, ID,
                ActionKind.LISTING_DESCRIPTION_CHANGE, "DETERMINISTIC", null, MetricWindow.D30,
                RecommendationState.READY_FOR_REVIEW, new BigDecimal("500"),
                Map.of("actionId", ID.toString(), "executionPath", "API"), Map.of(), "LOW", 14, DIGEST,
                validUntil, null, List.of(), NOW.minusSeconds(60), 0L);
    }

    private static ListingDecisionScope scope(String route, boolean reviewAttested) {
        return new ListingDecisionScope(ID, ID, ID, ID, ID, 1L, ActionKind.LISTING_DESCRIPTION_CHANGE, "API",
                "REVIEWED", route, false, false, PACKAGE, 3, Duration.ofHours(48), DIGEST, DIGEST, DIGEST, 120,
                Boolean.FALSE, AUTHOR, null, reviewAttested, "{\"calibrationPackageId\":\"" + PACKAGE + "\"}", Map.of("state","CURRENT"));
    }

    @BeforeEach
    void scripted() {
        when(ids.newId()).thenReturn(ID);
        when(listingDecisions.unresolvedReasons(ID)).thenReturn(List.of());
    }

    @Test
    @DisplayName("TC-LC-GR01 a reviewed, calibrated, unblocked action passes and names its calibration package")
    void passNamesTheCalibrationPackage() {
        when(listingDecisions.decisionScope(ID)).thenReturn(Optional.of(scope("ORDINARY_IMPACT", true)));

        ListingImpactPreview preview = service.previewListingAction(proposal(NOW.plusSeconds(3600)),
                GuardrailPurpose.APPROVAL);

        assertThat(preview.verdict().passed()).isTrue();
        assertThat(preview.verdict().reasons()).isEmpty();
        verify(evaluations).insert(eq(ID), eq(ID), eq(ID), isNull(), isNull(), isNull(), isNull(), eq(PACKAGE),
                eq(3), eq(GuardrailPurpose.APPROVAL), eq(true), eq(List.of()), anyMap(), anyString(),
                anyString(), eq(NOW), any());
    }

    @Test
    @DisplayName("TC-LC-GR02 a refusal names no calibration package at all")
    void refusalNamesNoAuthority() {
        when(listingDecisions.decisionScope(ID)).thenReturn(Optional.of(scope("ORDINARY_IMPACT", true)));
        when(listingDecisions.unresolvedReasons(ID)).thenReturn(List.of("SCOPE_CONTAINED"));

        ListingImpactPreview preview = service.previewListingAction(proposal(NOW.plusSeconds(3600)),
                GuardrailPurpose.EXECUTION);

        assertThat(preview.verdict().passed()).isFalse();
        assertThat(preview.verdict().reasons()).containsExactly(GuardrailReason.SCOPE_CONTAINED);
        verify(evaluations).insert(eq(ID), eq(ID), eq(ID), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), eq(GuardrailPurpose.EXECUTION), eq(false), anyList(), anyMap(), anyString(),
                anyString(), eq(NOW), any());
    }

    @Test
    @DisplayName("TC-LC-GR03 no decision scope at all blocks the action outright")
    void missingScopeBlocks() {
        when(listingDecisions.decisionScope(ID)).thenReturn(Optional.empty());

        ListingImpactPreview preview = service.previewListingAction(proposal(NOW.plusSeconds(3600)),
                GuardrailPurpose.APPROVAL);

        assertThat(preview.verdict().reasons()).containsExactly(GuardrailReason.LISTING_ACTION_BLOCKED);
        assertThat(preview.scope()).isNull();
    }

    @Test
    @DisplayName("TC-LC-GR04 an elapsed proposal, a missing review and unresolved materiality each refuse")
    void workflowOwnedReasons() {
        when(listingDecisions.decisionScope(ID)).thenReturn(Optional.of(scope("MATERIALITY_UNRESOLVED", false)));

        ListingImpactPreview approval = service.previewListingAction(proposal(NOW), GuardrailPurpose.APPROVAL);
        ListingImpactPreview preview = service.previewListingAction(proposal(NOW.plusSeconds(1)),
                GuardrailPurpose.IMPACT_PREVIEW);

        assertThat(approval.verdict().reasons()).containsExactly(GuardrailReason.RECOMMENDATION_EXPIRED,
                GuardrailReason.MATERIALITY_UNRESOLVED, GuardrailReason.REVIEW_MISSING);
        // A preview before review is allowed to say what would block; the
        // missing review itself is not a preview blocker.
        assertThat(preview.verdict().reasons()).containsExactly(GuardrailReason.MATERIALITY_UNRESOLVED);
    }

    @Test
    @DisplayName("TC-LC-GR05 the listing module's blockers map onto workflow reasons without duplicates")
    void listingBlockersMap() {
        when(listingDecisions.decisionScope(ID)).thenReturn(Optional.of(scope("ORDINARY_IMPACT", true)));
        when(listingDecisions.unresolvedReasons(ID)).thenReturn(List.of("CALIBRATION_CONFLICTED",
                "CALIBRATION_UNRESOLVED", "AFFECTED_SET_DIGEST_CHANGED", "CURRENT_TEXT_MOVED",
                "LISTING_HEALTH_UNKNOWN", "TEXT_LENGTH_OUT_OF_BOUNDS", "KIZ_MARKED_UNDECLARED",
                "ENTITY_VERSION_CHANGED", "SOMETHING_NEW"));

        ListingImpactPreview preview = service.previewListingAction(proposal(NOW.plusSeconds(3600)),
                GuardrailPurpose.APPROVAL);

        assertThat(preview.verdict().reasons()).containsExactly(GuardrailReason.CALIBRATION_UNRESOLVED,
                GuardrailReason.AFFECTED_SET_INCOMPLETE, GuardrailReason.CURRENT_TEXT_MOVED,
                GuardrailReason.LISTING_HEALTH_NECESSARY_FAILED, GuardrailReason.TEXT_LENGTH_OUT_OF_BOUNDS,
                GuardrailReason.KIZ_MARKED_UNDECLARED, GuardrailReason.ENTITY_VERSION_CHANGED,
                GuardrailReason.LISTING_ACTION_BLOCKED);
        assertThat(preview.unresolved()).hasSize(9);
    }

    @Test
    @DisplayName("TC-LC-GR06 the shared evaluate entry point routes a listing action to the listing preview")
    void evaluateRoutesListingActions() {
        when(listingDecisions.decisionScope(ID)).thenReturn(Optional.of(scope("ORDINARY_IMPACT", true)));

        var verdict = service.evaluate(proposal(NOW.plusSeconds(3600)), null, GuardrailPurpose.APPROVAL);

        assertThat(verdict.passed()).isTrue();
        verify(evaluations).insert(any(), any(), any(), isNull(), isNull(), isNull(), isNull(), eq(PACKAGE), eq(3),
                any(), anyBoolean(), anyList(), anyMap(), anyString(), anyString(), any(), any());
    }
}
