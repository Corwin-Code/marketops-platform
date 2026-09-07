package com.mimococo.marketops.marketplaceintegration.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The shapes that cross the advertising write boundary. */
class AdBidWritePortContractTest {

    private static final UUID CAPABILITY = UUID.fromString("aaaa0000-0000-0000-0000-000000000001");
    private static final UUID CREDENTIAL = UUID.fromString("aaaa0000-0000-0000-0000-000000000002");
    private static final UUID ATTEMPT = UUID.fromString("aaaa0000-0000-0000-0000-000000000003");
    private static final String DIGEST = "a".repeat(64);

    private static AdBidWriteRequest apply(Money bid, String unit) {
        return new AdBidWriteRequest(AdBidWriteRequest.Operation.APPLY, CAPABILITY, CREDENTIAL,
                "campaign-1", "object-1", bid, unit, "abc-key-0123456789", null, null, ATTEMPT);
    }

    @Nested
    @DisplayName("a request names exactly one call")
    class Requests {

        @Test
        @DisplayName("TC-AD-PORT-001 the same call produces the same digest")
        void sameCallSameDigest() {
            AdBidWriteRequest one = apply(Money.of(new BigDecimal("25.00"), "RUB"),
                    "CURRENCY_MAJOR");
            AdBidWriteRequest again = apply(Money.of(new BigDecimal("25.00"), "RUB"),
                    "CURRENCY_MAJOR");

            assertThat(one.digest()).isEqualTo(again.digest()).matches("^[0-9a-f]{64}$");
        }

        @Test
        @DisplayName("TC-AD-PORT-002 a different bid unit is a different call")
        void unitIsPartOfTheIdentity() {
            // Twenty-five roubles and twenty-five kopecks differ by a factor of a
            // hundred. If the unit were not in the digest, the two would look
            // like the same request and one could answer for the other.
            AdBidWriteRequest major = apply(Money.of(new BigDecimal("25.00"), "RUB"),
                    "CURRENCY_MAJOR");
            AdBidWriteRequest minor = apply(Money.of(new BigDecimal("25.00"), "RUB"),
                    "CURRENCY_MINOR");

            assertThat(major.digest()).isNotEqualTo(minor.digest());
        }

        @Test
        @DisplayName("TC-AD-PORT-003 a restore cannot reuse the apply's provider identity")
        void restoreUsesADifferentIdempotencyKey() {
            String commandKey = "abc-key-0123456789";

            assertThat(AdBidWriteRequest.operationIdempotencyKey(
                    AdBidWriteRequest.Operation.RESTORE, commandKey))
                    .isNotEqualTo(commandKey);
            assertThat(AdBidWriteRequest.operationIdempotencyKey(
                    AdBidWriteRequest.Operation.APPLY, commandKey))
                    .isEqualTo(commandKey);
        }

        @Test
        @DisplayName("TC-AD-PORT-004 a mutating call without a target or a unit is unrepresentable")
        void mutatingCallNeedsATargetAndAUnit() {
            assertThatThrownBy(() -> apply(null, "CURRENCY_MAJOR"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> apply(Money.of(BigDecimal.TEN, "RUB"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("TC-AD-PORT-005 a status enquiry needs neither, because it changes nothing")
        void statusEnquiryNeedsNoTarget() {
            AdBidWriteRequest enquiry = new AdBidWriteRequest(
                    AdBidWriteRequest.Operation.STATUS_ENQUIRY, CAPABILITY, CREDENTIAL,
                    "campaign-1", "object-1", null, null, "abc-key-0123456789",
                    "task-1", null, ATTEMPT);

            assertThat(enquiry.digest()).matches("^[0-9a-f]{64}$");
        }
    }

    @Nested
    @DisplayName("a response is evidence or it is not recorded")
    class Responses {

        private static AdBidWriteResult.Response response(
                int status, Map<String, String> headers, String digest, String evidenceClass) {
            return new AdBidWriteResult.Response(status, headers, digest, evidenceClass, true);
        }

        @Test
        @DisplayName("TC-AD-PORT-006 a well-formed response is accepted")
        void wellFormedResponseIsAccepted() {
            var accepted = response(200, Map.of("content-type", "application/json"),
                    DIGEST, "PROVIDER_RESPONSE");

            assertThat(accepted.httpStatus()).isEqualTo(200);
            assertThat(accepted.complete()).isTrue();
        }

        @Test
        @DisplayName("TC-AD-PORT-007 a response that names no request is not evidence about one")
        void responseWithoutADigestIsRefused() {
            assertThatThrownBy(() -> response(200, Map.of(), "not-a-digest", "PROVIDER_RESPONSE"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exact request");
        }

        @Test
        @DisplayName("TC-AD-PORT-008 a status outside the HTTP range is refused")
        void impossibleStatusIsRefused() {
            assertThatThrownBy(() -> response(99, Map.of(), DIGEST, "PROVIDER_RESPONSE"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> response(600, Map.of(), DIGEST, "PROVIDER_RESPONSE"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("TC-AD-PORT-009 a fixture and a provider response must say which they are")
        void evidenceClassMustBeStated() {
            assertThatThrownBy(() -> response(200, Map.of(), DIGEST, "SOMETHING_ELSE"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("evidence class");
            assertThat(response(200, Map.of(), DIGEST, "PROTOCOL_FIXTURE").evidenceClass())
                    .isEqualTo("PROTOCOL_FIXTURE");
        }

        @Test
        @DisplayName("TC-AD-PORT-010 a header outside the recorded allowlist is not retained")
        void unlistedHeaderIsRefused() {
            assertThatThrownBy(() -> response(200, Map.of("set-cookie", "session=1"),
                    DIGEST, "PROVIDER_RESPONSE"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("allowlist");
        }

        @Test
        @DisplayName("TC-AD-PORT-011 a refusal before dispatch carries no response at all")
        void refusalBeforeDispatchCarriesNoResponse() {
            AdBidWriteResult refused = AdBidWriteResult.refusedBeforeDispatch(
                    "write_operation_not_verified", Instant.parse("2026-09-04T00:00:00Z"));

            assertThat(refused.outcome()).isEqualTo(AdBidWriteResult.Outcome.REJECTED);
            assertThat(refused.response()).isNull();
            assertThat(refused.body()).isNull();
            assertThat(refused.errorCode()).isEqualTo("write_operation_not_verified");
        }

        @Test
        @DisplayName("TC-AD-PORT-012 the body is copied in and out, so a caller cannot mutate evidence")
        void bodyIsDefensivelyCopied() {
            byte[] original = {1, 2, 3};
            AdBidWriteResult result = new AdBidWriteResult(
                    AdBidWriteResult.Outcome.ACCEPTED, "200", null, null, null, null,
                    original, Instant.EPOCH, null,
                    response(200, Map.of(), DIGEST, "PROVIDER_RESPONSE"));

            original[0] = 9;
            assertThat(result.body()[0]).isEqualTo((byte) 1);
            result.body()[0] = 8;
            assertThat(result.body()[0]).isEqualTo((byte) 1);
        }
    }
}
