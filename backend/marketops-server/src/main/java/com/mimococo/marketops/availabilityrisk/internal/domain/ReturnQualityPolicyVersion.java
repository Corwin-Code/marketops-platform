package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Effective-dated return/retention/quality guardrail authority. */
public record ReturnQualityPolicyVersion(UUID policyId, int policyVersion,
        BigDecimal maximumReturnRatio, BigDecimal minimumRetentionRatio,
        BigDecimal maximumDefectReturnRatio) {
}
