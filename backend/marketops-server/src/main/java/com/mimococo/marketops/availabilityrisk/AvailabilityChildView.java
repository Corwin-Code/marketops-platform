package com.mimococo.marketops.availabilityrisk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One independently governed child risk, as the console sees it.
 *
 * <p>The evidence state and the lane are separate fields, and both are sent.
 * Rendering a provisional CRITICAL the same way as a confirmed one is the
 * presentation mistake the Contract forbids, and the API cannot prevent it if
 * it only sends the lane.
 *
 * @param id the child
 * @param childKind {@code CHANNEL} or {@code COMPANY}
 * @param platformCode marketplace, or {@code null} for a company child
 * @param storeId store, or {@code null}
 * @param platformListingVariantId exact listing variant, or {@code null}
 * @param fulfillmentModeCode exact mode, or {@code null}
 * @param lane the calculated urgency
 * @param evidenceState what the answer rests on
 * @param confidenceState how much weight the rank gives it
 * @param causeCode why somebody is needed
 * @param availableUnits units available, or {@code null}
 * @param dailyDemandRate the selected rate, or {@code null}
 * @param daysOfCover proven cover, or {@code null}
 * @param coverageHorizonDays lead time plus safety, or {@code null}
 * @param projectedStockoutAt when cover runs out, or {@code null}
 * @param profitLane which profit authority spoke
 * @param profitAtRiskAmount contribution profit per unit, or {@code null}
 * @param profitAtRiskCurrency its currency, or {@code null}
 * @param demandSelectionReason why this window was selected
 * @param conservativeProofTerms the danger argument, empty when none
 * @param blockerCodes what this child is waiting on
 * @param rankFactors the visible ordering factors
 * @param demandWindows the windows this child was judged on
 * @param calculatedAt when the answer was produced
 */
public record AvailabilityChildView(
        UUID id,
        String childKind,
        String platformCode,
        UUID storeId,
        UUID platformListingVariantId,
        String fulfillmentModeCode,
        String lane,
        String evidenceState,
        String confidenceState,
        String causeCode,
        Integer availableUnits,
        BigDecimal dailyDemandRate,
        BigDecimal daysOfCover,
        Integer coverageHorizonDays,
        Instant projectedStockoutAt,
        String profitLane,
        BigDecimal profitAtRiskAmount,
        String profitAtRiskCurrency,
        String demandSelectionReason,
        List<String> conservativeProofTerms,
        List<String> blockerCodes,
        List<AvailabilityRankFactorView> rankFactors,
        List<DemandWindowView> demandWindows,
        Instant calculatedAt) {

    public AvailabilityChildView {
        conservativeProofTerms = List.copyOf(conservativeProofTerms);
        blockerCodes = List.copyOf(blockerCodes);
        rankFactors = List.copyOf(rankFactors);
        demandWindows = List.copyOf(demandWindows);
    }
}
