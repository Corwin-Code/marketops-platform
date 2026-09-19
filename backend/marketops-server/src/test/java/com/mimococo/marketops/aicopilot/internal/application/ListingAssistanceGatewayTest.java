package com.mimococo.marketops.aicopilot.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.aicopilot.*;
import com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc.AiRepository;
import com.mimococo.marketops.aicopilot.port.*;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.productlisting.*;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class ListingAssistanceGatewayTest {
    final ListingIdentityDirectory identities=mock(ListingIdentityDirectory.class);
    final ProjectionBuilder builder=mock(ProjectionBuilder.class);
    final AiRepository repository=mock(AiRepository.class);
    final ModelGatewayPort gateway=mock(ModelGatewayPort.class);
    final UUID actor=UUID.randomUUID(),org=UUID.randomUUID(),listing=UUID.randomUUID(),store=UUID.randomUUID(),
            member=UUID.randomUUID(),product=UUID.randomUUID(),metric=UUID.randomUUID();
    final Instant at=Instant.parse("2026-09-01T00:00:00Z");
    UUID invocation;
    String state,failure;
    AiDiagnosisService service;

    @BeforeEach void isolatedInvocationLedgerAndFakeGateway() {
        var transactions=mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service=new AiDiagnosisService(identities,builder,new OutputValidator(JsonMapper.builder().build()),gateway,
                repository,mock(com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder.class),UUID::randomUUID,
                Clock.fixed(at,ZoneOffset.UTC),transactions);
        when(identities.variantContext(member,at)).thenReturn(Optional.of(new ListingVariantContext(member,listing,store,
                UUID.randomUUID(),"OZON","not-exported-native-key","not-exported-variant",UUID.randomUUID(),product,false)));
        var projection=new SubjectProjection(List.of(new SubjectProjection.Field("metrics.valueRef",metric.toString()),
                new SubjectProjection.Field("metrics.numericValue","10")),Set.of(metric),Set.of());
        when(builder.build(eq(store),eq("OZON"),anyString(),eq(member),eq(MetricWindow.D14))).thenReturn(projection);
        when(repository.allowedProjectionFields("LISTING_ASSISTANCE",1)).thenReturn(Set.of("listing.subjectRef","listing.memberRef",
                "listing.assistancePurpose","metrics.valueRef","metrics.numericValue"));
        when(repository.eligibleModel()).thenReturn(Optional.of(new AiRepository.EligibleModel(UUID.randomUUID(),"fake","fake",
                "secret-ref://fake/model/test",32000)));
        doAnswer(call->{ invocation=call.getArgument(0);state=call.getArgument(11);return null; }).when(repository)
                .openInvocation(any(),any(),anyString(),anyInt(),anyString(),anyInt(),nullable(UUID.class),anyString(),any(),
                        anyString(),anyString(),anyString(),any(),any(),nullable(String.class));
        doAnswer(call->{ state=call.getArgument(1);failure=call.getArgument(2);return null; }).when(repository)
                .closeInvocation(any(),anyString(),nullable(String.class),anyBoolean(),nullable(Integer.class),any());
        when(repository.findInvocation(any())).thenAnswer(call->Optional.of(new AiRepository.InvocationRow(invocation,listing,
                state,failure,!"SUCCEEDED".equals(state),"fake","fake",at,"DISPATCHED".equals(state)?null:at,2)));
        when(gateway.invoke(any())).thenReturn(ModelResponse.answered("""
                {"recommendations":[{"statement":"Проверьте формулировку перед использованием.",
                  "actionCapability":"LISTING_CONTENT_REVIEW","expectedEffect":"Clearer wording",
                  "risk":"Product facts need confirmation","validationWindowDays":14,
                  "proposedParameters":{"reviewFocus":"Подтвердите характеристики товара перед публикацией."}}]}
                """,1));
    }

    @ParameterizedTest @EnumSource(ListingAssistancePurpose.class)
    void finitePurposesUseTheExistingGatewayAndBindOriginalDisclosureScope(ListingAssistancePurpose purpose) {
        assertThat(service.assistListing(actor,org,listing,store,List.of(member),List.of(product),MetricWindow.D14,purpose).state()).isEqualTo("SUCCEEDED");
        var request=ArgumentCaptor.forClass(ModelRequest.class);
        verify(gateway).invoke(request.capture());
        assertThat(request.getValue().userPrompt()).contains(purpose.name(),member.toString(),metric.toString())
                .doesNotContain("not-exported-native-key","not-exported-variant");
        verify(repository).bindListingScope(invocation,store,List.of(member),List.of(product));
    }

    @Test void changedMappingOrUndeclaredFieldIsRefusedBeforeAnyModelCall() {
        assertThatThrownBy(()->service.assistListing(actor,org,listing,UUID.randomUUID(),List.of(member),List.of(product),MetricWindow.D14,
                ListingAssistancePurpose.REVIEW_SUMMARY)).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(()->service.assistListing(actor,org,listing,store,List.of(member),List.of(UUID.randomUUID()),MetricWindow.D14,
                ListingAssistancePurpose.REVIEW_SUMMARY)).isInstanceOf(OperationRejectedException.class);
        when(repository.allowedProjectionFields("LISTING_ASSISTANCE",1)).thenReturn(Set.of());
        assertThatThrownBy(()->service.assistListing(actor,org,listing,store,List.of(member),List.of(product),MetricWindow.D14,
                ListingAssistancePurpose.REVIEW_SUMMARY)).isInstanceOf(OperationRejectedException.class);
        verifyNoInteractions(gateway);
    }

    @Test void unavailableModelRecordsDegradationAndOriginalScopeWithoutCallingTheGateway() {
        when(repository.eligibleModel()).thenReturn(Optional.empty());
        var result=service.assistListing(actor,org,listing,store,List.of(member),List.of(product),MetricWindow.D14,ListingAssistancePurpose.RUSSIAN_DESCRIPTION);
        assertThat(result.state()).isEqualTo("REFUSED");
        assertThat(result.failureCode()).isEqualTo("NO_ELIGIBLE_PROVIDER");
        verify(repository).bindListingScope(invocation,store,List.of(member),List.of(product));
        verifyNoInteractions(gateway);
    }
}
