package com.mimococo.marketops.analyticsdecision.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the thresholds the deterministic layer decides against.
 *
 * <p>The values live in configuration rather than in the engine because they
 * are commercial judgements about what counts as a problem, and a deployment
 * that sells a different kind of product needs different ones. What does not
 * change is that the engine reads them from one bound object: two sources for
 * the same threshold would let a metric and a rule disagree about the same
 * number.
 */
@Configuration
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsConfiguration {
}
