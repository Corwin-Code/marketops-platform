package com.mimococo.marketops.marketplaceintegration.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthHeaderSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthValueSource;
import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.WriteOperationSpec;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteResult;
import com.mimococo.marketops.shared.port.OutboundHttp;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * How the adapter turns the transport's wait signals into evidence. The transport here is a stub
 * that returns a fixed response, so only classification is under test; the production filter is
 * exercised by the transport and integration tests.
 */
class DescriptionWaitSignalClassificationTest {

    @Test
    void theLongestReadableNativeOrStandardWaitIsTheHint() {
        var ozon = classify("OZON", Map.of("Item-Retry-After", List.of("2"), "Retry-After", List.of("1")), Map.of());
        assertThat(ozon.retryAfterSeconds()).isEqualTo(120);
        assertThat(ozon.response().headers()).containsEntry("item-retry-after", "2").containsEntry("retry-after", "1");
        assertThat(ozon.response().unresolvedTiming()).isEmpty();
        var wb = classify("WILDBERRIES", Map.of("X-Ratelimit-Retry", List.of("120"), "Retry-After", List.of("1")), Map.of());
        assertThat(wb.retryAfterSeconds()).isEqualTo(120);
    }

    @Test
    void aWithheldWaitLineStaysUnresolvedBesideItsReadableSibling() {
        var mixed = classify("OZON", Map.of("retry-after", List.of("120")), Map.of("retry-after", 1));
        assertThat(mixed.retryAfterSeconds()).isNull();
        assertThat(mixed.response().headers()).doesNotContainKey("retry-after");
        assertThat(mixed.response().unresolvedTiming()).containsEntry("retry-after", Arrays.asList("120", null));
        var nativeOnly = classify("OZON", Map.of("retry-after", List.of("1")), Map.of("item-retry-after", 1));
        assertThat(nativeOnly.retryAfterSeconds()).isNull();
        assertThat(nativeOnly.response().headers()).containsEntry("retry-after", "1");
        assertThat(nativeOnly.response().unresolvedTiming())
                .containsEntry("item-retry-after", Arrays.asList((String) null));
    }

    @Test
    void severalFieldLinesAreNeverJoinedIntoOneValue() {
        var split = classify("OZON", Map.of("Retry-After", List.of("Thu", "10 Sep 2026 02:00:00 GMT")), Map.of());
        assertThat(split.retryAfterSeconds()).isNull();
        assertThat(split.response().headers()).doesNotContainKey("retry-after");
        assertThat(split.response().unresolvedTiming())
                .containsEntry("retry-after", List.of("Thu", "10 Sep 2026 02:00:00 GMT"));
    }

    @Test
    void anotherPlatformsNativeHeaderGivesNoHintAndIsKeptForTheDurableParser() {
        var foreign = classify("OZON", Map.of("X-Ratelimit-Retry", List.of("120"), "Retry-After", List.of("1")), Map.of());
        assertThat(foreign.retryAfterSeconds()).isNull();
        assertThat(foreign.response().headers()).containsEntry("x-ratelimit-retry", "120");
    }

    @Test
    void noWaitSignalIsAbsentAndOtherHeadersKeepTheirJoinedValues() {
        var absent = classify("OZON", Map.of("ETag", List.of("\"a\"", "\"b\""), "Set-Cookie", List.of("s=1")), Map.of());
        assertThat(absent.retryAfterSeconds()).isNull();
        assertThat(absent.response().unresolvedTiming()).isEmpty();
        assertThat(absent.response().headers()).containsEntry("etag", "\"a\", \"b\"").doesNotContainKey("set-cookie");
    }

    private static DescriptionWriteResult classify(String platform, Map<String, List<String>> headers,
                                                   Map<String, Integer> withheld) {
        var operations = mock(WriteOperationRepository.class);
        var specs = mock(PlatformCallSpecRepository.class);
        var request = new DescriptionWriteRequest(DescriptionWriteRequest.Operation.READBACK, UUID.randomUUID(),
                UUID.randomUUID(), "synthetic-listing", null, null, null, false, "synthetic-wait-request", null, null,
                UUID.randomUUID());
        var endpoint = new EndpointCallSpec(UUID.randomUUID(), platform, "synthetic.readback",
                "https://fixture.invalid", "GET", "/description", null, null, "application/json", null, "NONE", null,
                2000, 4096);
        when(operations.verifiedOperation(any(), anyString())).thenReturn(Optional.of(new WriteOperationSpec(
                request.capabilityId(), platform, "READBACK", "SYNCHRONOUS", "", null, null, null, null, null, null,
                endpoint)));
        when(specs.descriptionAttemptContext(any())).thenReturn(Optional.of(
                new PlatformCallSpecRepository.DescriptionAttemptContext(0, 6000)));
        when(specs.verifiedAuthHeaders(anyString(), anyString())).thenReturn(List.of(
                new AuthHeaderSpec("X-Fixture-Auth", AuthValueSource.LITERAL, "synthetic", "CONTENT_WRITE", 1)));
        OutboundHttp transport = new OutboundHttp() {
            @Override public Plan prepare(Destination destination) {
                return new Plan() { };
            }

            @Override public Response exchange(Plan plan, Map<String, String> requestHeaders) {
                return new Response(429, "{}".getBytes(StandardCharsets.UTF_8), headers, true, null, withheld);
            }
        };
        var answer = new PlatformHttpDescriptionWriteAdapter(operations, specs, mock(SecretResolverPort.class),
                transport, Clock.systemUTC()).perform(request);
        assertThat(answer.response()).isNotNull();
        return answer;
    }
}
