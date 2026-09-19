package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Isolation follows proven dependencies transitively and nothing else. */
class IsolationScopeTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    @Test
    @DisplayName("TC-LC-I01 the scope is the transitive closure of proven links, cycles included")
    void transitiveClosure() {
        assertThat(IsolationScope.widen(A, Map.of(A, List.of(B), B, List.of(C), C, List.of(A), D, List.of(A))))
                .containsExactly(A, B, C);
    }

    @Test
    @DisplayName("TC-LC-I02 a listing with no proven links isolates alone")
    void unlinkedListingIsolatesAlone() {
        assertThat(IsolationScope.widen(A, Map.of())).containsExactly(A);
    }

    @Test
    @DisplayName("TC-LC-I03 an unmet target is not a failure that isolates; a protection failure is")
    void onlyProtectionFailureIsolates() {
        assertThat(IsolationScope.isolates("NOT_MET", "PASS")).isFalse();
        assertThat(IsolationScope.isolates("MET", "FAIL")).isTrue();
        assertThat(IsolationScope.isolates("UNDETERMINED", "UNDETERMINED")).isFalse();
    }
}
