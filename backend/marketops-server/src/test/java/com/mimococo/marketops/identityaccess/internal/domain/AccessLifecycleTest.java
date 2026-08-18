package com.mimococo.marketops.identityaccess.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The access-metadata state machines: a recoverable disable, a terminal
 * revocation, and forward-only source withdrawal.
 */
class AccessLifecycleTest {

    @Test
    @DisplayName("TC-IA-101 service accounts disable recoverably and revoke terminally")
    void serviceAccountMachineIsExact() {
        assertThat(ServiceAccountStatus.ACTIVE.allowedTransitions())
                .containsExactlyInAnyOrder(
                        ServiceAccountStatus.DISABLED, ServiceAccountStatus.REVOKED);
        assertThat(ServiceAccountStatus.DISABLED.allowedTransitions())
                .containsExactlyInAnyOrder(
                        ServiceAccountStatus.ACTIVE, ServiceAccountStatus.REVOKED);
        assertThat(ServiceAccountStatus.REVOKED.allowedTransitions()).isEmpty();
    }

    @Test
    @DisplayName("TC-IA-102 a grant revokes once; a new authorization is a new grant")
    void grantMachineIsExact() {
        assertThat(ScopeGrantStatus.ACTIVE.allowedTransitions())
                .containsExactly(ScopeGrantStatus.REVOKED);
        assertThat(ScopeGrantStatus.REVOKED.allowedTransitions()).isEmpty();
    }

    @Test
    @DisplayName("TC-IA-103 an allowed source withdraws once; re-adding is a new row")
    void allowedSourceMachineIsExact() {
        assertThat(AllowedSourceStatus.ACTIVE.allowedTransitions())
                .containsExactly(AllowedSourceStatus.WITHDRAWN);
        assertThat(AllowedSourceStatus.WITHDRAWN.allowedTransitions()).isEmpty();
    }
}
