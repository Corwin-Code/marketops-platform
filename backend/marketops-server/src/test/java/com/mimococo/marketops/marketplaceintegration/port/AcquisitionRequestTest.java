package com.mimococo.marketops.marketplaceintegration.port;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The request refuses to exist in any shape that could bypass an identity.
 *
 * <p>Every field that later gates a call -- the endpoint most of all -- is
 * required at construction, so an "accidentally null" request is not a value
 * that flows onward and fails somewhere quieter; it never comes into being.
 */
class AcquisitionRequestTest {

    private static final UUID SOME = UUID.randomUUID();

    @Test
    @DisplayName("TC-PORT-001 a fully identified request constructs")
    void fullyIdentifiedRequestConstructs() {
        assertThatCode(() -> new AcquisitionRequest(
                SOME, SOME, 1L, SOME, SOME, 1, Instant.now().plusSeconds(30)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TC-PORT-002 a request without an endpoint identity is refused")
    void endpointlessRequestIsRefused() {
        assertThatThrownBy(() -> new AcquisitionRequest(
                SOME, SOME, 1L, null, SOME, 1, Instant.now().plusSeconds(30)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("endpointId");
    }

    @Test
    @DisplayName("TC-PORT-003 a nonpositive fence or call sequence is refused")
    void nonpositiveFenceOrSequenceIsRefused() {
        assertThatThrownBy(() -> new AcquisitionRequest(
                SOME, SOME, 0L, SOME, SOME, 1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AcquisitionRequest(
                SOME, SOME, 1L, SOME, SOME, 0, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
