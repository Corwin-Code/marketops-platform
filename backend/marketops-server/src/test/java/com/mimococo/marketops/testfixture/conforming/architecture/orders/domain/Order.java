package com.mimococo.marketops.testfixture.conforming.architecture.orders.domain;

import com.mimococo.marketops.testfixture.conforming.architecture.shared.PlatformIdentifier;

/** Platform-owned domain model with no adapter, infrastructure or SDK dependency. */
public record Order(PlatformIdentifier identifier) {
}
