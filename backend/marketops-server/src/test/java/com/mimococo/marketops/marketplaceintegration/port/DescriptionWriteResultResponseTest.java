package com.mimococo.marketops.marketplaceintegration.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An unresolved wait header is recorded exactly, once, and never beside a plain value of the same name. */
class DescriptionWriteResultResponseTest {
    private static final String DIGEST = "a".repeat(64);

    private static DescriptionWriteResult.Response response(Map<String, String> headers,
                                                            Map<String, List<String>> unresolved) {
        return new DescriptionWriteResult.Response(429, headers, DIGEST, "PROVIDER_RESPONSE", true, unresolved);
    }

    @Test
    void severalLinesOrAWithheldLineAreRecordedExactly() {
        var withheld = Arrays.asList("120", null);
        var recorded = response(Map.of("content-type", "application/json"),
                Map.of("Retry-After", withheld, "item-retry-after", List.of("2", "3")));
        assertThat(recorded.unresolvedTiming()).containsEntry("retry-after", withheld)
                .containsEntry("item-retry-after", List.of("2", "3"));
        assertThat(response(Map.of(), Map.of("x-ratelimit-retry", Arrays.asList((String) null)))
                .unresolvedTiming().get("x-ratelimit-retry")).containsExactly((String) null);
    }

    @Test
    void theFiveArgumentFormRecordsNoUnresolvedTiming() {
        var plain = new DescriptionWriteResult.Response(200, Map.of("retry-after", "5"), DIGEST, "PROVIDER_RESPONSE", true);
        assertThat(plain.unresolvedTiming()).isEmpty();
    }

    @Test
    void aSingleReadableLineIsAPlainHeaderNotUnresolvedTiming() {
        assertThatThrownBy(() -> response(Map.of(), Map.of("retry-after", List.of("120"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNameIsNeverBothPlainAndUnresolvedWhateverItsCase() {
        assertThatThrownBy(() -> response(Map.of("Retry-After", "1"), Map.of("retry-after", List.of("2", "3"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> response(Map.of(),
                Map.of("Retry-After", List.of("1", "2"), "retry-after", List.of("3", "4"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyWaitHeadersCanBeUnresolvedAndTheirValuesStayBounded() {
        assertThatThrownBy(() -> response(Map.of(), Map.of("etag", List.of("a", "b"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> response(Map.of(), Map.of("retry-after", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> response(Map.of(), Map.of("retry-after", List.of("1", "2\u0001"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> response(Map.of(), Map.of("retry-after", List.of("1", "9".repeat(8193)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
