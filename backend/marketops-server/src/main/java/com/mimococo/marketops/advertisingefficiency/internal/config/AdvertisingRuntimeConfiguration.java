package com.mimococo.marketops.advertisingefficiency.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the advertising loop's schedule. It declares no beans of its own. */
@Configuration
@EnableConfigurationProperties(AdvertisingWorkerProperties.class)
public class AdvertisingRuntimeConfiguration {
}
