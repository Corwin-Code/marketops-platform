package com.mimococo.marketops.listingconversion;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SimulationReviewMaterialTest {
    @Test
    void largeMoneyAndSmallLossRemainExactAcrossReviewProjection() {
        var json=new ObjectMapper();
        var snapshot=com.mimococo.marketops.shared.JsonValues.read(json,"{\"inputs\":{\"currencyCode\":\"RUB\"},\"referenceProfitLine\":9007199254740993.0001}");
        var simulation=new SimulationView(UUID.randomUUID(),UUID.randomUUID(),List.of(
                new SimulationView.Scenario("DOWNSIDE","COMPUTED",new BigDecimal("99999999999999"),
                        new BigDecimal("9007199254740993.0001"),new BigDecimal("-0.0001"),List.of())),
                null,"NO_SOLUTION","digest",Instant.parse("2026-09-01T00:00:00Z"),"model",snapshot,false,"UNQUALIFIED");
        var material=MeaningReviewBasis.SimulationMaterial.from(simulation);
        assertThat(material.scenarios().getFirst().netRevenue()).isEqualTo("9007199254740993.0001");
        assertThat(material.scenarios().getFirst().contributionProfit()).isEqualTo("-0.0001");
        assertThat(json.valueToTree(material).path("scenarios").get(0).path("netRevenue").isTextual()).isTrue();
        assertThat(material.referenceProfitLine()).isEqualTo("9007199254740993.0001");
        assertThat(material.qualificationState()).isEqualTo("UNQUALIFIED");
    }

    @Test
    void applicableExperienceReviewProjectionCarriesReferencesWithoutCopyingOutcomeOrApproval() {
        var applicable=new MeaningReviewBasis.ApplicableExperience(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),"PRIMARY","OPERATIONAL",0,"a".repeat(64),"CONTENT_DESCRIPTION",
                "evidence://same-purpose-and-scope");
        var basis=new MeaningReviewBasis(UUID.randomUUID(),"b".repeat(64),"QUALIFIED",null,"Новый текст",
                null,List.of(),null,null,List.of(applicable));

        var material=new ObjectMapper().valueToTree(basis).path("applicableExperience").get(0);
        assertThat(material.path("sourceStage").asText()).isEqualTo("OPERATIONAL");
        assertThat(material.path("applicabilityEvidenceReference").asText())
                .isEqualTo("evidence://same-purpose-and-scope");
        assertThat(material.has("sourceVerdict")).isFalse();
        assertThat(material.has("sourceProtectionVerdict")).isFalse();
        assertThat(material.has("expectedEffect")).isFalse();
        assertThat(material.has("approval")).isFalse();
    }
}
