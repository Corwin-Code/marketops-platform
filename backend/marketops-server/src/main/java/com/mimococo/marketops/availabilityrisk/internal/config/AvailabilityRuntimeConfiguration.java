package com.mimococo.marketops.availabilityrisk.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the availability worker's bounds. */
@Configuration
@EnableConfigurationProperties(AvailabilityWorkerProperties.class)
public class AvailabilityRuntimeConfiguration {
}
