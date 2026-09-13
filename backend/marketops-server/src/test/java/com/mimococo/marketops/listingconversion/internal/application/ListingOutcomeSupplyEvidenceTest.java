package com.mimococo.marketops.listingconversion.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.availabilityrisk.SupplyCoverageQuery;
import com.mimococo.marketops.listingconversion.ProtectionVerdict;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ListingOutcomeSupplyEvidenceTest {
    private final SupplyCoverageQuery supply=mock(SupplyCoverageQuery.class);
    private final ListingActionRepository actions=mock(ListingActionRepository.class);
    private final ListingActionRepository.ActionRow action=mock(ListingActionRepository.ActionRow.class);
    private final Instant at=Instant.parse("2026-09-01T00:00:00Z");

    @Test void everyFrozenProductAndScenarioNeedsAQualifiedAvailabilityProjection() {
        UUID actionId=UUID.randomUUID(),organizationId=UUID.randomUUID(),productId=UUID.randomUUID(),sourceId=UUID.randomUUID();
        when(action.id()).thenReturn(actionId);
        when(action.organizationId()).thenReturn(organizationId);
        when(actions.frozenDirectProductVariants(actionId)).thenReturn(List.of(productId));
        when(actions.frozenCalibrationDependencies(actionId)).thenReturn(Optional.of(new ObjectMapper().readTree("""
                {"supplyScenarios":[{"code":"CONSERVATIVE","productVariantId":"%s",
                  "companyDailyFulfillmentUnits":4.5,"coverageDays":30,
                  "evidenceReference":"fixture://accepted-demand"}]}
                """.formatted(productId))));
        when(supply.project(any())).thenAnswer(invocation->{
            SupplyCoverageQuery.Scenario scenario=invocation.getArgument(0);
            return new SupplyCoverageQuery.Projection(scenario,"PASS",at.plusSeconds(30L*86400),
                    new BigDecimal("4.5"),null,List.of(),
                    List.of(new SupplyCoverageQuery.SupplyEvidence(sourceId,"fixture","AVAILABLE",100,at)),
                    Map.of("supplyPolicy","1"),"a".repeat(64));
        });

        var result=new ListingOutcomeSupplyEvidence(supply,actions).assess(action,at);
        assertThat(result.verdict()).isEqualTo(ProtectionVerdict.PASS);
        assertThat(result.gaps()).isEmpty();
        assertThat(result.references()).containsEntry("state","PASS");
    }

    @Test void missingFrozenScopeAndScenariosRemainUndetermined() {
        when(action.id()).thenReturn(UUID.randomUUID());
        var result=new ListingOutcomeSupplyEvidence(supply,actions).assess(action,at);
        assertThat(result.verdict()).isEqualTo(ProtectionVerdict.UNDETERMINED);
        assertThat(result.gaps()).contains("FROZEN_SUPPLY_SCOPE_UNQUALIFIED","FROZEN_SUPPLY_SCENARIOS_UNQUALIFIED");
    }
}
