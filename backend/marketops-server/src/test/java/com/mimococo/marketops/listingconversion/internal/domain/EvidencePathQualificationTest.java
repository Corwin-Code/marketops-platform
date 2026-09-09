package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.listingconversion.EvidencePath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which evidence path may carry a conversion measurement, and why not. */
class EvidencePathQualificationTest {

    @Test
    @DisplayName("TC-LC-E01 the detail path needs visits, purchase links and a known source per visit")
    void detailPathNeedsAllThree() {
        assertThat(EvidencePathQualification.disqualifications(EvidencePath.DETAIL, true, true, true,
                EvidencePathQualification.SummaryProfile.absent())).isEmpty();
        assertThat(EvidencePathQualification.disqualifications(EvidencePath.DETAIL, false, false, false,
                EvidencePathQualification.SummaryProfile.absent()))
                .containsExactly("VISIT_FACTS_ABSENT", "PURCHASE_LINKS_ABSENT", "SOURCE_STRATIFICATION_MISSING");
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
        var profile = new EvidencePathQualification.SummaryProfile(true, false, true, false, true, false, true);

        assertThat(EvidencePathQualification.disqualifications(EvidencePath.OFFICIAL_SUMMARY, false, false, false,
                profile))
                .containsExactly("EQUIVALENCE_NOT_PROVEN", "EQUIVALENCE_DENOMINATOR_UNCOVERED",
                        "EQUIVALENCE_MATURITY_UNCOVERED", "SOURCE_STRATIFICATION_MISSING");
    }

    @Test
    @DisplayName("TC-LC-E04 a proven complete profile qualifies the summary path without visit facts")
    void provenProfileQualifies() {
        var profile = new EvidencePathQualification.SummaryProfile(true, true, true, true, true, true, true);

        assertThat(EvidencePathQualification.disqualifications(EvidencePath.OFFICIAL_SUMMARY, false, false, true,
                profile)).isEmpty();
    }
}
