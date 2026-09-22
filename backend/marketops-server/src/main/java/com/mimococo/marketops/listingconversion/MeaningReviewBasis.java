package com.mimococo.marketops.listingconversion;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exact before/after Russian text and the accepted condition catalog; no hidden exposure amounts. */
public record MeaningReviewBasis(UUID actionId,String basisDigest,String ruleState,String currentText,String targetText,
                                PromotionTerms promotionTerms,List<Condition> conditions,ListingPurposeBasis purposeBasis,
                                SimulationMaterial selectedSimulation,List<ApplicableExperience> applicableExperience,
                                DescriptionMaterial currentDescription,AffectedSetMaterial affectedSet,
                                ReviewMaterial reviewEvidence,Map<String,String> calibrationEvidence,
                                Map<String,String> materialityEvidence,Map<String,String> businessProtectionEvidence,
                                String authorityDocument) {
    public MeaningReviewBasis(UUID actionId,String basisDigest,String ruleState,String currentText,String targetText,
            PromotionTerms promotionTerms,List<Condition> conditions,ListingPurposeBasis purposeBasis) {
        this(actionId,basisDigest,ruleState,currentText,targetText,promotionTerms,conditions,purposeBasis,null,List.of(),
                null,null,null,Map.of(),Map.of(),Map.of(),null);
    }
    public MeaningReviewBasis(UUID actionId,String basisDigest,String ruleState,String currentText,String targetText,
            PromotionTerms promotionTerms,List<Condition> conditions,ListingPurposeBasis purposeBasis,
            SimulationMaterial selectedSimulation,List<ApplicableExperience> applicableExperience) {
        this(actionId,basisDigest,ruleState,currentText,targetText,promotionTerms,conditions,purposeBasis,
                selectedSimulation,applicableExperience,null,null,null,Map.of(),Map.of(),Map.of(),null);
    }
    public record DescriptionMaterial(UUID observationId,String textDigest,String text,String languageCode,
            Boolean kizMarkedDeclared,java.time.Instant observedAt,java.time.Instant acquiredAt,String sourceKind) { }
    public record AffectedSetMaterial(UUID affectedSetId,String digest,String state,List<UUID> listingVariantIds,
            List<UUID> productVariantIds,UUID nativeScopeObservationId,String identityLineage) {
        public AffectedSetMaterial {
            listingVariantIds=List.copyOf(listingVariantIds);
            productVariantIds=List.copyOf(productVariantIds);
        }
    }
    public record ReviewMaterial(UUID reviewerUserId,String verdict,String reason,java.time.Instant reviewedAt,
            String factsDigest,String evaluationPlanDigest,String purposeBasisDigest,String meaningAssessment,
            String exposureEvidence,Boolean contentAxisMaterial,Boolean exposureAxisMaterial,String materialityRoute) { }
    /** A current applicability reference for review; it deliberately carries no copied Outcome or approval. */
    public record ApplicableExperience(UUID experienceApplicationId,UUID sourceActionId,UUID sourceResultId,
            UUID sourceListingId,String sourceNodeCode,String sourceStage,int sourceRevision,
            String targetAffectedSetDigest,String candidateKind,String applicabilityEvidenceReference) { }
    /** Review projection keeps exact decimal values and the original evidence text. */
    public record SimulationMaterial(UUID id,String inputsDigest,java.time.Instant computedAt,String currency,
            String periodStart,String periodEnd,String referenceProfitLine,String inverseMinimumQuantity,
            String inverseState,String qualificationState,Boolean conditionalScenariosPassed,
            List<ScenarioMaterial> scenarios,String inputEvidence) {
        public SimulationMaterial { scenarios=List.copyOf(scenarios); }
        public static SimulationMaterial from(SimulationView value) {
            if (value==null) return null;
            var snapshot=value.inputSnapshot();
            return new SimulationMaterial(value.id(),value.inputsDigest(),value.computedAt(),
                    snapshot.path("inputs").path("currencyCode").asText(null),
                    snapshot.path("context").path("periodStart").asText(null),
                    snapshot.path("context").path("periodEnd").asText(null),
                    snapshot.path("referenceProfitLine").isNumber()?snapshot.path("referenceProfitLine").decimalValue().toPlainString()
                        :snapshot.path("referenceProfitLine").asText(null),decimal(value.inverseMinimumQuantity()),
                    value.inverseState(),value.qualificationState(),value.conditionalScenariosPassed(),
                    value.scenarios().stream().map(row->new ScenarioMaterial(row.scenarioCode(),row.state(),
                        decimal(row.quantity()),decimal(row.netRevenue()),decimal(row.contributionProfit()),row.missingInputs())).toList(),
                    snapshot.toPrettyString());
        }
    }
    public record ScenarioMaterial(String code,String state,String quantity,String netRevenue,String contributionProfit,
                                   List<String> missingInputs) {
        public ScenarioMaterial { missingInputs=List.copyOf(missingInputs); }
    }
    private static String decimal(java.math.BigDecimal value) { return value==null?null:value.toPlainString(); }
    public record Condition(String code,String condition,String axis) { }
    public MeaningReviewBasis {
        conditions=List.copyOf(conditions);
        applicableExperience=List.copyOf(applicableExperience==null?List.of():applicableExperience);
        calibrationEvidence=Map.copyOf(calibrationEvidence==null?Map.of():calibrationEvidence);
        materialityEvidence=Map.copyOf(materialityEvidence==null?Map.of():materialityEvidence);
        businessProtectionEvidence=Map.copyOf(businessProtectionEvidence==null?Map.of():businessProtectionEvidence);
    }
}
