package com.mimococo.marketops.listingconversion.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.analyticsdecision.CalculationRunLedger;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.*;
import com.mimococo.marketops.operationsworkflow.ListingActionIntake;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class EvaluationPlanFreezeTest {
    final ObjectMapper json=new ObjectMapper();
    final ListingActionRepository actions=mock(ListingActionRepository.class);
    final ListingFactRepository facts=mock(ListingFactRepository.class);
    final CalibrationService calibration=mock(CalibrationService.class);
    final ListingActionRepository.ActionRow action=mock(ListingActionRepository.ActionRow.class);
    final UUID organization=UUID.randomUUID(), listing=UUID.randomUUID(), store=UUID.randomUUID();
    final UUID actionId=UUID.randomUUID(), packageId=UUID.randomUUID(), planId=UUID.randomUUID();
    final Instant at=Instant.parse("2026-09-01T00:00:00Z");
    final Map<String,CalibrationRepository.Value> values=new HashMap<>();
    final EvaluationService service=new EvaluationService(mock(EvaluationRepository.class),actions,
            mock(ListingHealthRepository.class),facts,calibration,mock(CalculationRunLedger.class),
            mock(ListingActionIntake.class),()->planId,Clock.fixed(at,ZoneOffset.UTC),
            mock(ListingScopeAuthorization.class),mock(ListingDisclosureService.class),json);

    @BeforeEach void exactActionAndAcceptedPackage() {
        when(action.id()).thenReturn(actionId);
        when(action.state()).thenReturn("DRAFT");
        when(action.organizationId()).thenReturn(organization);
        when(action.listingId()).thenReturn(listing);
        when(action.calibrationPackageId()).thenReturn(packageId);
        when(action.calibrationVersion()).thenReturn(1);
        when(action.currentTextDigest()).thenReturn("a".repeat(64));
        when(action.targetTextDigest()).thenReturn("b".repeat(64));
        when(action.affectedSetDigest()).thenReturn("c".repeat(64));
        when(actions.planId(actionId)).thenReturn(Optional.empty());
        when(facts.listing(listing)).thenReturn(Optional.of(new ListingFactRepository.ListingContext(
                listing,organization,store,UUID.randomUUID(),"OZON","synthetic-listing","ACTIVE")));
        put("FORMAL_NODES",null,"""
                [{"nodeCode":"D14","maturityDays":14,"method":"QUALIFIED_COMPARISON","threshold":"0.05",
                  "schedule":{"lookOffsetsDays":[14],"qualificationRef":"fixture://method"}}]
                """);
        put("STOP_RULE",null,"{}");
        put("CROSS_PERIOD_WINDOW",BigDecimal.ZERO,null);
        put("CRITICAL_GROUP_RULE",null,"{\"groups\":[{\"code\":\"RETURNS\",\"bound\":0.02}]}");
        when(calibration.resolve(organization,"OZON",store,at)).thenReturn(new CalibrationService.Outcome(
                new CalibrationService.Resolved(packageId,1,values),"RESOLVED"));
    }

    @Test void explicitNoStopAndZeroTailFreezeWithTheWholeMethodAndGroupRules() {
        assertThat(service.freezePlan(action)).isEqualTo(planId);
        verify(actions).insertPlan(eq(planId),eq(organization),eq(actionId),eq(packageId),eq(1),anyMap(),
                eq(at.plusSeconds(14L*86400)),argThat(nodes -> json.<JsonNode>valueToTree(nodes)
                        .get(0).path("schedule").path("qualificationRef").asText().equals("fixture://method")),
                eq(Map.of()),argThat(groups -> groups.get(0).path("bound").decimalValue().compareTo(new BigDecimal("0.02"))==0),
                eq("PRIOR_VERSION_WINDOW"),eq(0),matches("[0-9a-f]{64}"),eq(at));
    }

    @Test void missingStopOrDifferentPackageVersionCannotFreeze() {
        values.remove("STOP_RULE");
        rejectsUnresolved();
        put("STOP_RULE",null,"{}");
        when(action.calibrationVersion()).thenReturn(2);
        rejectsUnresolved();
        verify(actions,never()).insertPlan(any(),any(),any(),any(),anyInt(),anyMap(),any(),anyList(),anyMap(),anyList(),anyString(),anyInt(),anyString(),any());
    }

    @Test void malformedMaturityHasNoFallbackToThirtyDays() {
        put("FORMAL_NODES",null,"[{\"nodeCode\":\"D14\",\"maturityDays\":14.5}]");
        rejectsUnresolved();
        put("FORMAL_NODES",null,"[{\"nodeCode\":\"D14\"}]");
        rejectsUnresolved();
    }

    @Test void firstFreezeCannotBeAddedAfterReviewOrApproval() {
        for (String state:java.util.List.of("REVIEWED","APPROVED","LAUNCHED")) {
            when(action.state()).thenReturn(state);
            assertThatThrownBy(()->service.freezePlan(action)).isInstanceOfSatisfying(OperationRejectedException.class,
                    failure->assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        }
        verify(actions,never()).insertPlan(any(),any(),any(),any(),anyInt(),anyMap(),any(),anyList(),anyMap(),anyList(),anyString(),anyInt(),anyString(),any());
    }

    @Test void admittedIntegerTextRetainsItsExactRepresentationAndMaturity() {
        put("FORMAL_NODES",null,"[{\"nodeCode\":\"D14\",\"maturityDays\":\"14\",\"method\":\"QUALIFIED_COMPARISON\",\"threshold\":\"0.05\"}]");
        assertThat(service.freezePlan(action)).isEqualTo(planId);
        verify(actions).insertPlan(any(),any(),any(),any(),anyInt(),anyMap(),eq(at.plusSeconds(14L*86400)),
                argThat(nodes->json.<JsonNode>valueToTree(nodes).get(0).path("maturityDays").isTextual()),
                anyMap(),anyList(),anyString(),eq(0),anyString(),eq(at));
    }

    void rejectsUnresolved() {
        assertThatThrownBy(()->service.freezePlan(action)).isInstanceOfSatisfying(OperationRejectedException.class,
                failure->assertThat(failure.errorCode()).isEqualTo(ErrorCode.CALIBRATION_UNRESOLVED));
    }

    @Test void executableMethodFreezesItsWholeObservationAndDecisionHorizon() {
        put("CRITICAL_GROUP_RULE",null,"{\"groups\":[]}");
        put("FORMAL_NODES",null,"""
                [{"nodeCode":"D14","maturityDays":14,"method":"EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1","threshold":"0.05",
                  "methodParameters":{"familyAlpha":"0.05","nodeAlpha":"0.05",
                   "samplingModel":"INDEPENDENT_BERNOULLI_VISITS","qualificationRef":"fixture://verified-method"},
                  "schedule":{"windowStartOffsetDays":1,"windowEndOffsetDays":15,"notBeforeOffsetDays":29,"lastOffsetDays":43}}]
                """);
        assertThat(service.freezePlan(action)).isEqualTo(planId);
        verify(actions).insertPlan(any(),any(),any(),any(),anyInt(),anyMap(),eq(at.plusSeconds(43L*86400)),
                anyList(),anyMap(),anyList(),anyString(),eq(0),anyString(),eq(at));
        put("CRITICAL_GROUP_RULE",null,"{\"groups\":[\"unstructured\"]}");
        rejectsUnresolved();
    }
    void put(String category,BigDecimal number,String body) {
        values.put(category,new CalibrationRepository.Value(category,number,null,body==null?null:json.readTree(body),
                "RULE",null,"fixture://accepted-calibration"));
    }
}
