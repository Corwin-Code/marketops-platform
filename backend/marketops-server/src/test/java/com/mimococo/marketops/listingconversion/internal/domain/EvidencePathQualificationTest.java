package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.listingconversion.EvidencePath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which evidence path may carry a conversion measurement, and why not. */
class EvidencePathQualificationTest {

    @Test
    @DisplayName("TC-LC-E01 the detail path needs complete visit and purchase-link windows")
    void detailPathNeedsCompleteWindows() {
        assertThat(EvidencePathQualification.disqualifications(EvidencePath.DETAIL, true, true, true,
                EvidencePathQualification.SummaryProfile.absent())).isEmpty();
        assertThat(EvidencePathQualification.disqualifications(EvidencePath.DETAIL, false, false, false,
                EvidencePathQualification.SummaryProfile.absent()))
                .containsExactly("VISIT_WINDOW_INCOMPLETE", "PURCHASE_LINK_WINDOW_INCOMPLETE");
    }

    @Test
    @DisplayName("TC-LC-E02 the official summary path is unusable without a published equivalence profile")
    void summaryPathNeedsAProfile() {
        assertThat(EvidencePathQualification.disqualifications(EvidencePath.OFFICIAL_SUMMARY, true, true, true,
                EvidencePathQualification.SummaryProfile.absent()))
                .containsExactly("EQUIVALENCE_PROFILE_ABSENT");
    }

    @Test
    @DisplayName("TC-LC-E03 an unproven or partial profile names every uncovered dimension")
    void partialProfileNamesEveryGap() {
        var profile = new EvidencePathQualification.SummaryProfile(true, false, true, false, true, false, true,
                null, false, false);

        assertThat(EvidencePathQualification.disqualifications(EvidencePath.OFFICIAL_SUMMARY, false, false, false,
                profile))
                .containsExactly("EQUIVALENCE_NOT_PROVEN", "EQUIVALENCE_DENOMINATOR_UNCOVERED",
                        "EQUIVALENCE_MATURITY_UNCOVERED");
    }

    @Test
    @DisplayName("TC-LC-E04 a proven complete profile qualifies the summary path without visit facts")
    void provenProfileQualifies() {
        var profile = new EvidencePathQualification.SummaryProfile(true, true, true, true, true, true, true,
                null, false, false);

        assertThat(EvidencePathQualification.disqualifications(EvidencePath.OFFICIAL_SUMMARY, false, false, false,
                profile)).isEmpty();
    }
    @Test
    void absentSourceStratificationDoesNotInvalidateAnIndependentQualifiedActualTotal() {
        assertThat(EvidencePathQualification.disqualifications(EvidencePath.DETAIL, true, true, false,
                EvidencePathQualification.SummaryProfile.absent())).isEmpty();
    }
}
