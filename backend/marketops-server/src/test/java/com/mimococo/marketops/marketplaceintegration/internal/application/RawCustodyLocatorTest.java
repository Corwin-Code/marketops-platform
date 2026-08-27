package com.mimococo.marketops.marketplaceintegration.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shape of a custody locator, asserted against the schema's own rule.
 *
 * <p>This exists because the rule was stated in three places — the check
 * constraint in V0010 and both storage adapters — and nothing checked that a
 * locator the product actually builds satisfied it. It did not: a locator
 * segment is capped at 63 characters and a SHA-256 in hexadecimal is 64, so
 * every filesystem custody write was refused with a validation error that named
 * nothing useful.
 *
 * <p>The pattern below is copied from the constraint deliberately. If the two
 * drift again, this test is what notices.
 */
class RawCustodyLocatorTest {

    /** The exact shape `raw.raw_content.object_ref` accepts, from V0010. */
    private static final Pattern SCHEMA_SHAPE = Pattern.compile(
            "^object-ref://[a-z0-9][a-z0-9-]{0,62}(/[a-z0-9][a-z0-9._-]{0,62}){1,6}$");

    @Test
    @DisplayName("TC-CUSTODY-001 a real locator satisfies the shape the database enforces")
    void locatorMatchesTheSchema() {
        String digest = Digest.ofBytes("some marketplace answer".getBytes(StandardCharsets.UTF_8));

        String locator = RawCustodyService.locatorFor("marketplace-raw", digest);

        assertThat(digest).hasSize(64);
        assertThat(locator).matches(SCHEMA_SHAPE);
    }

    @Test
    @DisplayName("TC-CUSTODY-002 the whole digest survives being split across segments")
    void theDigestIsRecoverable() {
        String digest = Digest.ofBytes("another answer".getBytes(StandardCharsets.UTF_8));

        String locator = RawCustodyService.locatorFor("internal-intake", digest);
        String[] segments = locator.substring("object-ref://".length()).split("/");

        assertThat(segments[segments.length - 2] + segments[segments.length - 1])
                .isEqualTo(digest);
    }

    @Test
    @DisplayName("TC-CUSTODY-003 every namespace this product uses produces a valid locator")
    void everyNamespaceWorks() {
        String digest = Digest.ofBytes("payload".getBytes(StandardCharsets.UTF_8));

        for (String namespace : new String[] {"marketplace-raw", "internal-intake",
                "price-command"}) {
            assertThat(RawCustodyService.locatorFor(namespace, digest)).matches(SCHEMA_SHAPE);
        }
    }

    @Test
    @DisplayName("TC-CUSTODY-004 a locator the database would refuse never reaches an adapter")
    void aMalformedLocatorIsRefusedHere() {
        String digest = Digest.ofBytes("payload".getBytes(StandardCharsets.UTF_8));

        // An upper-case namespace cannot appear in a locator. Refusing here
        // names the problem; letting it through would surface as a storage
        // failure that says nothing about why.
        assertThatThrownBy(() -> RawCustodyService.locatorFor("Marketplace-Raw", digest))
                .isInstanceOf(OperationRejectedException.class);
    }
}
