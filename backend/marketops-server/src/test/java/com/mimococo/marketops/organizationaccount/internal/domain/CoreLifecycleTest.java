package com.mimococo.marketops.organizationaccount.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The operating-entity and association state machines, asserted transition by
 * transition so a widened or narrowed machine fails by name.
 */
class CoreLifecycleTest {

    @Test
    @DisplayName("TC-OA-101 entities alternate active and suspended; retirement is terminal")
    void entityMachineIsExact() {
        assertThat(EntityStatus.ACTIVE.allowedTransitions())
                .containsExactlyInAnyOrder(EntityStatus.SUSPENDED, EntityStatus.RETIRED);
        assertThat(EntityStatus.SUSPENDED.allowedTransitions())
                .containsExactlyInAnyOrder(EntityStatus.ACTIVE, EntityStatus.RETIRED);
        assertThat(EntityStatus.RETIRED.allowedTransitions()).isEmpty();
        assertThat(EntityStatus.RETIRED.canTransitionTo(EntityStatus.ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("TC-OA-102 associations end or cancel once and stay ended")
    void associationMachineIsExact() {
        assertThat(AssociationStatus.ACTIVE.allowedTransitions())
                .containsExactlyInAnyOrder(AssociationStatus.ENDED, AssociationStatus.CANCELLED);
        assertThat(AssociationStatus.ENDED.allowedTransitions()).isEmpty();
        assertThat(AssociationStatus.CANCELLED.allowedTransitions()).isEmpty();
    }

    @Test
    @DisplayName("no state machine offers a self-transition")
    void noSelfTransitions() {
        for (EntityStatus status : EntityStatus.values()) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }
        for (AssociationStatus status : AssociationStatus.values()) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }
        assertThat(Set.of(EntityStatus.values())).hasSize(3);
        assertThat(Set.of(AssociationStatus.values())).hasSize(3);
    }
}
