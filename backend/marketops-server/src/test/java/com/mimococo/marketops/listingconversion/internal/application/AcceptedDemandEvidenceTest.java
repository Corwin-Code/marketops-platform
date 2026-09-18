package com.mimococo.marketops.listingconversion.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.mimococo.marketops.listingconversion.SimulationAssumptions;
import com.mimococo.marketops.listingconversion.internal.domain.PromotionSimulator;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.CalibrationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AcceptedDemandEvidenceTest {
    final UUID listing = UUID.randomUUID();
    final Instant at = Instant.parse("2026-09-01T00:00:00Z");
    final SimulationAssumptions context = new SimulationAssumptions(at.plusSeconds(3600),
            at.plusSeconds(86400), Map.of(), "Finite prospective period");

    private CalibrationService.Outcome accepted() {
        var basis = Map.of("periodStart", context.periodStart().toString(), "periodEnd", context.periodEnd().toString(),
                "evidenceReference", "fixture://accepted-demand", "minimumContributionProfit",new BigDecimal("100"),
                "currencyCode","RUB","profitEvidenceReference","fixture://accepted-profit-line","necessaryScenarios", List.of(
                    Map.of("code", "DOWNSIDE", "quantity", 4, "conservative", true, "evidenceReference", "fixture://downside")));
        var value = new CalibrationRepository.Value("DEMAND_SCENARIO_SET", null, null,
                new ObjectMapper().valueToTree(Map.of("economicScenarioBases", Map.of(listing.toString(), basis))),
                null, null, "fixture://accepted-package");
        return new CalibrationService.Outcome(new CalibrationService.Resolved(UUID.randomUUID(), 1,
                Map.of("DEMAND_SCENARIO_SET", value), at), "RESOLVED");
    }

    private PromotionSimulator.Scenario scenario(String code, int quantity, boolean necessary) {
        return new PromotionSimulator.Scenario(code, BigDecimal.valueOf(quantity), necessary, true);
    }

    @Test
    void callerCannotOmitRaiseOrDemoteTheAcceptedDownside() {
        for (var submitted : List.of(List.of(scenario("UPSIDE", 100, true)),
                List.of(scenario("DOWNSIDE", 10, true)), List.of(scenario("DOWNSIDE", 4, false)))) {
            var evidence = CalibrationService.demandEvidence(accepted(), listing, context, submitted, at);
            assertThat(evidence.get("state")).isEqualTo("UNQUALIFIED");
            assertThat(evidence.get("gaps")).isEqualTo(List.of("NECESSARY_DEMAND_SCENARIO_MISMATCH:DOWNSIDE"));
        }
        assertThat(CalibrationService.demandEvidence(accepted(), listing, context,
                List.of(scenario("DOWNSIDE", 4, true), scenario("UPSIDE", 100, false)), at).get("state"))
                .isEqualTo("ACCEPTED_NECESSARY_SCENARIOS_MATCH");
    }

    @Test
    void aLaterAcceptanceCannotManufactureExAnteDemandEvenWithIdenticalNumbers() {
        var original = accepted().resolved();
        var later = new CalibrationService.Outcome(new CalibrationService.Resolved(original.packageId(),
                original.version(), original.values(), context.periodStart().plusSeconds(1)), "RESOLVED");
        var result = CalibrationService.demandEvidence(later, listing, context,
                List.of(scenario("DOWNSIDE", 4, true)), context.periodEnd());
        assertThat(result.get("gaps")).isEqualTo(List.of("DEMAND_NOT_ACCEPTED_EX_ANTE"));
    }

    @Test
    void anotherListingCannotBorrowDemandButLateRecalculationPreservesPriorAcceptance() {
        var submitted = List.of(scenario("DOWNSIDE", 4, true));
        assertThat(CalibrationService.demandEvidence(accepted(), UUID.randomUUID(), context, submitted, at).get("state"))
                .isEqualTo("UNQUALIFIED");
        assertThat(CalibrationService.demandEvidence(accepted(), listing, context, submitted,
                context.periodStart().plusSeconds(1)).get("state")).isEqualTo("ACCEPTED_NECESSARY_SCENARIOS_MATCH");
    }

    @Test
    void callerCannotLowerOrChangeTheCurrencyOfTheAcceptedProfitReference() {
        assertThat(CalibrationService.profitReferenceEvidence(accepted(),listing,context,
                new BigDecimal("100"),"RUB",at).get("state")).isEqualTo("ACCEPTED_PROFIT_REFERENCE_BOUND");
        assertThat(CalibrationService.profitReferenceEvidence(accepted(),listing,context,
                new BigDecimal("99.9999"),"RUB",at).get("gaps"))
                .isEqualTo(List.of("PROFIT_REFERENCE_BELOW_ACCEPTED_MINIMUM"));
        assertThat(CalibrationService.profitReferenceEvidence(accepted(),listing,context,
                new BigDecimal("100"),"USD",at).get("gaps"))
                .isEqualTo(List.of("PROFIT_REFERENCE_CURRENCY_MISMATCH"));
    }

    private CalibrationService.Outcome acceptedWithMinimum(Object minimum) {
        var basis = new java.util.HashMap<String, Object>(Map.of("periodStart", context.periodStart().toString(),
                "periodEnd", context.periodEnd().toString(), "evidenceReference", "fixture://accepted-demand",
                "currencyCode", "RUB", "profitEvidenceReference", "fixture://accepted-profit-line"));
        if (minimum != null) basis.put("minimumContributionProfit", minimum);
        var value = new CalibrationRepository.Value("DEMAND_SCENARIO_SET", null, null,
                new ObjectMapper().valueToTree(Map.of("economicScenarioBases", Map.of(listing.toString(), basis))),
                null, null, "fixture://accepted-package");
        return new CalibrationService.Outcome(new CalibrationService.Resolved(UUID.randomUUID(), 1,
                Map.of("DEMAND_SCENARIO_SET", value), at), "RESOLVED");
    }

    @Test
    void absentNonNumericOrNegativeAcceptedMinimumNeverBecomesABoundProfitReference() {
        for (Object minimum : java.util.Arrays.asList(null, "100")) {
            var evidence = CalibrationService.profitReferenceEvidence(acceptedWithMinimum(minimum), listing, context,
                    new BigDecimal("100"), "RUB", at);
            assertThat(evidence.get("state")).isEqualTo("UNQUALIFIED");
            assertThat(evidence.get("gaps")).isEqualTo(List.of("PROFIT_REFERENCE_VALUE_UNQUALIFIED",
                    "PROFIT_REFERENCE_BELOW_ACCEPTED_MINIMUM"));
            assertThat(evidence).doesNotContainKey("minimumContributionProfit");
        }
        var negative = CalibrationService.profitReferenceEvidence(acceptedWithMinimum(new BigDecimal("-1")), listing,
                context, new BigDecimal("100"), "RUB", at);
        assertThat(negative.get("state")).isEqualTo("UNQUALIFIED");
        assertThat(negative.get("gaps")).isEqualTo(List.of("PROFIT_REFERENCE_VALUE_UNQUALIFIED"));
        assertThat(negative).doesNotContainKey("minimumContributionProfit");
    }
}
