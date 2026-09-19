package com.mimococo.marketops.operatingfacts;

import java.time.Instant;

/** One retained purchase-cost version and its effective end; null means open until superseded. */
public record CostPeriodSnapshot(CostSnapshot cost, Instant effectiveTo) {
}
