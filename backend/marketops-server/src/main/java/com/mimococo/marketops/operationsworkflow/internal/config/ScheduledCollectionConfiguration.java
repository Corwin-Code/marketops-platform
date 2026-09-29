package com.mimococo.marketops.operationsworkflow.internal.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(ScheduledCollectionProperties.class)
public class ScheduledCollectionConfiguration {

    /** Scheduled execution exists only where the collection timer is switched on. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "marketops.data-collection", name = "enabled", havingValue = "true")
    static class SchedulingConfiguration {
    }
}
