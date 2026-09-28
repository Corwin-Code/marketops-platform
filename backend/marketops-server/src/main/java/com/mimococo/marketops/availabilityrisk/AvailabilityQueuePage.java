package com.mimococo.marketops.availabilityrisk;

import java.util.List;

/**
 * One page of the availability queue and how many cards match in total.
 *
 * <p>The total is counted with exactly the filter the page was read with, so a
 * console can offer the right number of pages instead of silently stopping at
 * the first one.
 *
 * @param items the cards on this page, most urgent first
 * @param total every card matching the same scope, lane and search
 * @param offset how many matching cards precede this page
 * @param limit the page size the read was clamped to
 */
public record AvailabilityQueuePage(List<AvailabilityCardView> items, long total, int offset,
                                    int limit) {

    public AvailabilityQueuePage {
        items = List.copyOf(items);
    }
}
