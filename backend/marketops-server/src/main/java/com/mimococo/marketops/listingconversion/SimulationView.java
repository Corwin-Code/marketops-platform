package com.mimococo.marketops.listingconversion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Forward scenarios and the inverse minimum quantity, each with its own state. */
public record SimulationView(UUID id, UUID candidateId, List<Scenario> scenarios, BigDecimal inverseMinimumQuantity,
                             String inverseState, String inputsDigest, Instant computedAt, String modelVersion,
                             JsonNode inputSnapshot, Boolean conditionalScenariosPassed, String qualificationState) {

    public record Scenario(String scenarioCode, String state, BigDecimal quantity, BigDecimal netRevenue,
                           BigDecimal contributionProfit, List<String> missingInputs) {
        public Scenario {
            missingInputs = List.copyOf(missingInputs == null ? List.of() : missingInputs);
        }
    }

    public SimulationView {
        scenarios = List.copyOf(scenarios == null ? List.of() : scenarios);
    }
}
