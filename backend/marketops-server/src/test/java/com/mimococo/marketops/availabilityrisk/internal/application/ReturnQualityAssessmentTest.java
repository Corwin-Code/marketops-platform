package com.mimococo.marketops.availabilityrisk.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.availabilityrisk.internal.domain.ReturnQualityAssessment;
import com.mimococo.marketops.availabilityrisk.internal.domain.ReturnQualityPolicyVersion;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyRepository;
import com.mimococo.marketops.operatingfacts.FactEvidence;
import com.mimococo.marketops.operatingfacts.FactWindow;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.ReturnTotals;
import com.mimococo.marketops.operatingfacts.SaleStage;
import com.mimococo.marketops.operatingfacts.SalesTotals;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReturnQualityAssessmentTest {

    private static final UUID LISTING =
            UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final FactEvidence EVIDENCE = FactEvidence.of(
            List.of(UUID.fromString("00000000-0000-0000-0000-000000000802")), NOW);
    private static final ReturnQualityPolicyVersion POLICY = new ReturnQualityPolicyVersion(
            UUID.fromString("00000000-0000-0000-0000-000000000803"), 1,
            new BigDecimal("0.25"), new BigDecimal("0.80"), new BigDecimal("0.10"));

    private OperatingFactQuery facts;
    private AvailabilityEvidenceGatherer gatherer;

    @BeforeEach
    void setUp() {
        facts = Mockito.mock(OperatingFactQuery.class);
        gatherer = new AvailabilityEvidenceGatherer(facts,
                Mockito.mock(ListingIdentityDirectory.class),
                Mockito.mock(AvailabilityPolicyRepository.class));
    }

    @Test
    @DisplayName("TC-RETURN-QUALITY-001 absent evidence fails closed")
    void absentEvidenceFailsClosed() {
        when(facts.sales(eq(LISTING), eq(SaleStage.COMPLETED), eq(null), any(FactWindow.class)))
                .thenReturn(SalesTotals.absent());
        when(facts.sales(eq(LISTING), eq(SaleStage.RETAINED), eq(30), any(FactWindow.class)))
                .thenReturn(sales(80));
        when(facts.returns(eq(LISTING), any(FactWindow.class)))
                .thenReturn(returns(10, Map.of()));

        ReturnQualityAssessment answer = gatherer.returnQuality(LISTING, POLICY, NOW);

        assertThat(answer.state()).isEqualTo(ReturnQualityAssessment.State.DATA_BLOCKED);
        assertThat(answer.blockerCode()).isEqualTo("RETURN_QUALITY_EVIDENCE_UNRESOLVED");
    }

    @Test
    @DisplayName("TC-RETURN-QUALITY-002 defect-heavy returns require supplier or product review")
    void highDefectRatioRequiresReview() {
        stubTotals(100, 85, 20, Map.of("QUALITY", 11L, "OTHER", 9L));

        ReturnQualityAssessment answer = gatherer.returnQuality(LISTING, POLICY, NOW);

        assertThat(answer.state()).isEqualTo(ReturnQualityAssessment.State.REVIEW);
        assertThat(answer.blockerCode()).isEqualTo("SUPPLIER_OR_PRODUCT_DEFECT_RATE_HIGH");
    }

    @Test
    @DisplayName("TC-RETURN-QUALITY-003 return and retention breaches cannot remain clear")
    void highReturnOrLowRetentionRequiresReview() {
        stubTotals(100, 70, 30, Map.of("CUSTOMER_CHANGED_MIND", 30L));

        ReturnQualityAssessment answer = gatherer.returnQuality(LISTING, POLICY, NOW);

        assertThat(answer.state()).isEqualTo(ReturnQualityAssessment.State.REVIEW);
        assertThat(answer.blockerCode()).isEqualTo("RETURN_OR_RETENTION_GUARDRAIL_BREACHED");
    }

    @Test
    @DisplayName("TC-RETURN-QUALITY-004 evidence inside every threshold remains clear")
    void evidenceInsideThresholdsIsClear() {
        stubTotals(100, 90, 10, Map.of("QUALITY", 3L, "OTHER", 7L));

        assertThat(gatherer.returnQuality(LISTING, POLICY, NOW).state())
                .isEqualTo(ReturnQualityAssessment.State.CLEAR);
    }

    private void stubTotals(long completed, long retained, long returned,
                            Map<String, Long> reasons) {
        when(facts.sales(eq(LISTING), eq(SaleStage.COMPLETED), eq(null), any(FactWindow.class)))
                .thenReturn(sales(completed));
        when(facts.sales(eq(LISTING), eq(SaleStage.RETAINED), eq(30), any(FactWindow.class)))
                .thenReturn(sales(retained));
        when(facts.returns(eq(LISTING), any(FactWindow.class)))
                .thenReturn(returns(returned, reasons));
    }

    private static SalesTotals sales(long units) {
        return new SalesTotals(units, null, null, EVIDENCE);
    }

    private static ReturnTotals returns(long units, Map<String, Long> reasons) {
        return new ReturnTotals(units, null, null, reasons, EVIDENCE);
    }
}
