package com.mimococo.marketops.operationsworkflow;

/** Delivers immutable native execution observations without asserting business outcomes. */
public interface ListingExecutionJournal {
    int deliverPending(int limit);
}
