package com.mimococo.marketops.listingconversion.internal.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(ListingAllowanceReleaseProperties.class)
public class ListingAllowanceReleaseConfiguration {

    /** Scheduled execution exists only where the release timer is switched on. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "marketops.listing-conversion.allowance-release", name = "enabled",
            havingValue = "true")
    static class SchedulingConfiguration {
    }
}
