package com.mimococo.marketops.listingconversion.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.identityaccess.*;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFeedbackRepository;
import com.mimococo.marketops.marketplaceintegration.*;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class ListingFeedbackServiceTest {
    final ListingScopeAuthorization scopes=mock(ListingScopeAuthorization.class);
    final ListingFeedbackRepository repository=mock(ListingFeedbackRepository.class);
    final RawEvidenceQuery raw=mock(RawEvidenceQuery.class);
    final IngestionJobDirectory jobs=mock(IngestionJobDirectory.class);
    final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    final AuthenticatedActor actor=mock(AuthenticatedActor.class);
    final UUID org=UUID.randomUUID(),store=UUID.randomUUID(),listing=UUID.randomUUID(),jobId=UUID.randomUUID(),rawId=UUID.randomUUID();
    final Instant at=Instant.parse("2026-09-01T00:00:00Z");
    final ListingFeedbackService service=new ListingFeedbackService(scopes,repository,raw,jobs,new ObjectMapper(),
            UUID::randomUUID,Clock.fixed(at,ZoneOffset.UTC),mock(com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder.class),
            transactions);
    final ListingFeedbackService.Source source=new ListingFeedbackService.Source(rawId,"/items/0","/id","/offer_id","/text");
    ListingFeedbackRepository.Item captured;
    final List<ListingFeedbackRepository.Label> labels=new ArrayList<>();

    @BeforeEach void syntheticCustody() {
        when(transactions.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        when(actor.userId()).thenReturn(UUID.randomUUID());
        when(scopes.require(eq(actor),eq(listing),any())).thenReturn(new ListingScopeAuthorization.ListingScope(listing,org,store,"OZON","offer-1"));
        when(raw.observation(rawId)).thenReturn(Optional.of(new RawObservationView(rawId,jobId,UUID.randomUUID(),"PAGE","page-1",
                at.minusSeconds(60),"200","SUCCESS_BYTES",at.minusSeconds(30),"a".repeat(64),100)));
        when(jobs.job(jobId)).thenReturn(Optional.of(new IngestionJobView(jobId,org,"OZON",UUID.randomUUID(),store,"FEEDBACK","fixture","ACTIVE")));
        when(raw.verifiedBody(rawId)).thenReturn(Optional.of("{\"items\":[{\"id\":\"feedback-1\",\"offer_id\":\"offer-1\",\"text\":\"Не пахнет\"}]}".getBytes(StandardCharsets.UTF_8)));
        when(repository.itemForSource(eq(listing),anyString())).thenAnswer(call->Optional.ofNullable(captured));
        doAnswer(call->{ captured=call.getArgument(0); return null; }).when(repository).insertItem(any(),eq(org),any(),eq(at));
        when(repository.item(any())).thenAnswer(call->Optional.ofNullable(captured));
        when(repository.labels(any())).thenAnswer(call->List.copyOf(labels));
        doAnswer(call->{ labels.add(call.getArgument(0)); return null; }).when(repository).insertLabel(any(),eq(org));
    }

    @Test void repeatedSourceAndCorrectedNegationRetainOneOriginalAndAnAppendOnlyLabelHistory() {
        UUID item=service.capture(actor,listing,source);
        assertThat(service.capture(actor,listing,source)).isEqualTo(item);
        verify(repository,times(1)).insertItem(any(),eq(org),any(),eq(at));
        String originalDigest=captured.originalDigest();
        var mistaken=new ListingFeedbackService.Classification("ODOR","UNCERTAIN","HUMAN_V1","Synthetic provisional label");
        UUID first=service.classify(actor,listing,item,mistaken);
        assertThat(service.classify(actor,listing,item,mistaken)).isEqualTo(first);
        var mistakenCurrent=currentSet(labels.getLast());
        service.classify(actor,listing,item,new ListingFeedbackService.Classification("NO_ODOR","CONFIRMED","HUMAN_V1","Negation corrected from original wording"));
        var correctedCurrent=currentSet(labels.getLast());
        assertThat(labels).extracting(ListingFeedbackRepository.Label::revision).containsExactly(0,1);
        assertThat(labels).extracting(ListingFeedbackRepository.Label::themeCode).containsExactly("ODOR","NO_ODOR");
        assertThat(correctedCurrent.classifications())
                .extracting(ListingFeedbackRepository.CurrentClassification::themeCode).containsExactly("NO_ODOR");
        assertThat(correctedCurrent.evidenceReference()).isNotEqualTo(mistakenCurrent.evidenceReference());
        assertThat(captured.originalDigest()).isEqualTo(originalDigest);
        assertThat(service.detail(actor,listing,item).classifications()).hasSize(2);
    }

    @Test void anotherStoresRawIsRefusedBeforeCustodyBytesAreRead() {
        when(jobs.job(jobId)).thenReturn(Optional.of(new IngestionJobView(jobId,org,"OZON",UUID.randomUUID(),UUID.randomUUID(),"FEEDBACK","other","ACTIVE")));
        assertThatThrownBy(()->service.capture(actor,listing,source)).isInstanceOf(OperationRejectedException.class);
        verify(raw,never()).verifiedBody(any());
        verify(repository,never()).insertItem(any(),any(),any(),any());
    }

    @Test void missingCustodyOrChangedOriginalCannotBecomeADuplicateOrSilentlyReplaceText() {
        service.capture(actor,listing,source);
        String original=captured.originalDigest();
        when(raw.verifiedBody(rawId)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.capture(actor,listing,source)).isInstanceOf(OperationRejectedException.class);
        when(raw.verifiedBody(rawId)).thenReturn(Optional.of("{\"items\":[{\"id\":\"feedback-1\",\"offer_id\":\"offer-1\",\"text\":\"Пахнет\"}]}".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(()->service.capture(actor,listing,source)).isInstanceOf(OperationRejectedException.class);
        assertThat(captured.originalDigest()).isEqualTo(original);
        verify(repository,times(1)).insertItem(any(),any(),any(),any());
    }

    private static ListingFeedbackRepository.CurrentClassificationSet currentSet(ListingFeedbackRepository.Label label) {
        return new ListingFeedbackRepository.CurrentClassificationSet(List.of(
                new ListingFeedbackRepository.CurrentClassification(label.id(),label.itemId(),label.revision(),
                        label.themeCode(),label.qualificationState())));
    }
}
