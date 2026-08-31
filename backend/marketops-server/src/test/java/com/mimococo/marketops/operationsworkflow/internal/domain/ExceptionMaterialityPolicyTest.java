package com.mimococo.marketops.operationsworkflow.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.operationsworkflow.ExceptionAuthorityLevel;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExceptionMaterialityPolicyTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final UUID POLICY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    @Test
    @DisplayName("TC-EXC-001 a bounded immaterial watch acceptance sits with the domain lead")
    void watchStaysWithTheDomainLead() {
        assertThat(policy().requiredAuthority("WATCH", 1, new BigDecimal("100.0000"), "RUB",
                FROM, FROM.plus(Duration.ofDays(3))))
                .isEqualTo(ExceptionAuthorityLevel.DOMAIN_LEAD);
    }

    @Test
    @DisplayName("TC-EXC-002 an ordinary high acceptance needs the operations lead")
    void highNeedsTheOperationsLead() {
        assertThat(policy().requiredAuthority("HIGH", 1, new BigDecimal("100.0000"), "RUB",
                FROM, FROM.plus(Duration.ofDays(3))))
                .isEqualTo(ExceptionAuthorityLevel.OPS_LEAD);
    }

    @Test
    @DisplayName("TC-EXC-003 critical, repeated, material and long-running all reach the authority")
    void everyEscalatingConditionReachesTheRiskAuthority() {
        ExceptionMaterialityPolicy policy = policy();
        Instant shortPeriod = FROM.plus(Duration.ofDays(3));

        assertThat(policy.requiredAuthority("CRITICAL", 1, null, null, FROM, shortPeriod))
                .isEqualTo(ExceptionAuthorityLevel.RISK_AUTHORITY);
        assertThat(policy.requiredAuthority("WATCH", 3, null, null, FROM, shortPeriod))
                .as("the third acceptance inside the lookback is repetition")
                .isEqualTo(ExceptionAuthorityLevel.RISK_AUTHORITY);
        assertThat(policy.requiredAuthority("WATCH", 1, new BigDecimal("50000.0000"), "RUB",
                FROM, shortPeriod))
                .isEqualTo(ExceptionAuthorityLevel.RISK_AUTHORITY);
        assertThat(policy.requiredAuthority("WATCH", 1, null, null,
                FROM, FROM.plus(Duration.ofDays(14))))
                .isEqualTo(ExceptionAuthorityLevel.RISK_AUTHORITY);
    }

    @Test
    @DisplayName("TC-EXC-004 an amount in another currency is not compared and not converted")
    void aForeignAmountIsNotSilentlyConverted() {
        assertThat(policy().requiredAuthority("WATCH", 1, new BigDecimal("50000.0000"), "USD",
                FROM, FROM.plus(Duration.ofDays(3))))
                .as("comparing across currencies without a published rate invents a fact")
                .isEqualTo(ExceptionAuthorityLevel.DOMAIN_LEAD);
    }

    @Test
    @DisplayName("TC-EXC-005 separation is required exactly where the authority is")
    void separationTracksTheAuthorityLevel() {
        ExceptionMaterialityPolicy policy = policy();
        Instant shortPeriod = FROM.plus(Duration.ofDays(3));

        assertThat(policy.separationRequired("CRITICAL", 1, null, null, FROM, shortPeriod))
                .isTrue();
        assertThat(policy.separationRequired("HIGH", 1, null, null, FROM, shortPeriod)).isFalse();
    }

    @Test
    @DisplayName("TC-EXC-006 a period longer than the organization allows is refused outright")
    void anOverlongPeriodIsRefused() {
        ExceptionMaterialityPolicy policy = policy();

        assertThat(policy.exceedsMaximum(FROM, FROM.plus(Duration.ofDays(30)))).isFalse();
        assertThat(policy.exceedsMaximum(FROM, FROM.plus(Duration.ofDays(31)))).isTrue();
        assertThat(policy.exceedsMaximum(FROM, null)).isTrue();
    }

    @Test
    @DisplayName("TC-EXC-007 the person who reports a risk cannot decide the business accepts it")
    void reportingRolesHoldNoAcceptanceAuthority() {
        assertThat(ExceptionAuthorityLevel.levelsFor(BusinessRoleCode.MARKETPLACE_OPERATOR))
                .isEmpty();
        assertThat(ExceptionAuthorityLevel.levelsFor(BusinessRoleCode.READ_ONLY)).isEmpty();
        assertThat(ExceptionAuthorityLevel.levelsFor(BusinessRoleCode.OPS_LEAD))
                .doesNotContain(ExceptionAuthorityLevel.RISK_AUTHORITY);
        assertThat(ExceptionAuthorityLevel.levelsFor(BusinessRoleCode.RISK_AUTHORITY))
                .contains(ExceptionAuthorityLevel.RISK_AUTHORITY);
    }

    /** Material at 50 000 RUB or fourteen days, repeated at three, capped at thirty days. */
    private static ExceptionMaterialityPolicy policy() {
        return new ExceptionMaterialityPolicy(POLICY, 1, "RUB", new BigDecimal("50000.0000"),
                Duration.ofDays(14), 3, Duration.ofDays(90), Duration.ofDays(30));
    }
}
