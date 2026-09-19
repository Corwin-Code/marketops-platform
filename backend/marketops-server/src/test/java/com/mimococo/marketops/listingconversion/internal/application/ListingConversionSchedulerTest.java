package com.mimococo.marketops.listingconversion.internal.application;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.listingconversion.internal.config.ListingConversionProperties;
import com.mimococo.marketops.operationsworkflow.ListingExecutionJournal;
import org.junit.jupiter.api.Test;

class ListingConversionSchedulerTest {
    @Test void emptyRecalculationQueueStillDrainsPendingExecutionJournal() {
        var queue=mock(RecalculationService.class);
        var journal=mock(ListingExecutionJournal.class);
        var properties=new ListingConversionProperties();
        when(queue.runOnce(properties.getListingsPerPass())).thenReturn(0);
        new ListingConversionScheduler(queue,properties,journal).drainQueue();
        var order=inOrder(journal,queue);
        order.verify(journal).deliverPending(properties.getListingsPerPass());
        order.verify(queue).runOnce(properties.getListingsPerPass());
    }
    @Test void deliveryFailureDoesNotStarveTheIndependentRecalculationQueue() {
        var queue=mock(RecalculationService.class);
        var journal=mock(ListingExecutionJournal.class);
        var properties=new ListingConversionProperties();
        when(journal.deliverPending(properties.getListingsPerPass())).thenThrow(new IllegalStateException("fixture failure"));
        new ListingConversionScheduler(queue,properties,journal).drainQueue();
        verify(queue).runOnce(properties.getListingsPerPass());
    }
}
