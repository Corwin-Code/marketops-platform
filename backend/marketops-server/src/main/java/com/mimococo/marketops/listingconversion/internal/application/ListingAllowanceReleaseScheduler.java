package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.listingconversion.internal.config.ListingAllowanceReleaseProperties;
import com.mimococo.marketops.shared.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The matured-outcome release timer. The bean does not exist unless it is switched on; a disabled
 * timer looks like a process with no timer. Maturity is counted in days, so a slow cadence is enough,
 * and a fixed delay keeps passes from piling up behind each other.
 */
@Component
@ConditionalOnProperty(prefix = "marketops.listing-conversion.allowance-release", name = "enabled",
        havingValue = "true")
class ListingAllowanceReleaseScheduler {

    private static final Logger log = LoggerFactory.getLogger(ListingAllowanceReleaseScheduler.class);

    private final ListingAllowanceReleaseService releases;
    private final ListingAllowanceReleaseProperties properties;

    ListingAllowanceReleaseScheduler(ListingAllowanceReleaseService releases,
                                     ListingAllowanceReleaseProperties properties) {
        this.releases = releases;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${marketops.listing-conversion.allowance-release.initial-delay:PT2M}",
            fixedDelayString = "${marketops.listing-conversion.allowance-release.interval:PT1H}")
    void releaseMaturedOutcomes() {
        try {
            releases.runScheduled(properties.getActionsPerPass());
        } catch (RuntimeException failedPass) {
            // The pass rolls back as a whole; nothing was released, and the next pass retries.
            log.warn("event=lc_allowance_outcome_release_failed failureType={} correlationId={}",
                    failedPass.getClass().getSimpleName(), CorrelationId.current());
        }
    }
}
