package com.mimococo.marketops.marketplaceintegration.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.WriteOperationSpec;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteResult;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How a marketplace's answer is classified.
 *
 * <p>This is the most consequential decision the adapter makes and it is a pure
 * one, so it is asserted without a network. The judgement that matters is
 * whether an unanswered call could have changed a real price: for a write it
 * could, and the answer must be unknown; for a read it could not, and the
 * answer is retriable. Getting that backwards means either a price changed
 * twice or a readback nobody retried.
 *
 * <p>Everything platform-specific comes from the recorded specification, so the
 * cases below vary the recorded pointers and vocabulary rather than varying
 * code paths.
 */
class PriceWriteClassificationTest {

    private static final UUID CAPABILITY = UUID.fromString(
            "55555555-5555-4555-8555-555555555555");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final PriceWriteAnswers adapter = new PriceWriteAnswers(
            Clock.fixed(Instant.parse("2026-08-27T09:00:00Z"), ZoneOffset.UTC));

    @Nested
    @DisplayName("TC-CLASSIFY-001 an unanswered write is not the same as an unanswered read")
    class Inconclusive {

        @Test
        void aRateLimitIsRetriableForEitherKindOfCall() {
            assertThat(adapter.inconclusive(request(PriceWriteRequest.Operation.APPLY), 429,
                    "HTTP 429", new byte[0]).outcome())
                    .isEqualTo(PriceWriteResult.Outcome.RETRIABLE_ERROR);
            assertThat(adapter.inconclusive(request(PriceWriteRequest.Operation.READBACK), 429,
                    "HTTP 429", new byte[0]).outcome())
                    .isEqualTo(PriceWriteResult.Outcome.RETRIABLE_ERROR);
        }

        @Test
        void aServerFailureOnAWriteIsUnknown() {
            for (int status : new int[] {408, 500, 502, 503, 504}) {
                PriceWriteResult result = adapter.inconclusive(
                        request(PriceWriteRequest.Operation.APPLY), status, "HTTP " + status,
                        new byte[0]);

                assertThat(result.outcome())
                        .describedAs("status %s on a write", status)
                        .isEqualTo(PriceWriteResult.Outcome.UNKNOWN_STATE);
                assertThat(result.errorCode()).isEqualTo("platform_did_not_answer_a_write");
            }
        }

        @Test
        void aServerFailureOnARestoreIsAlsoUnknown() {
            // A restore is a write. Treating it as retriable would repeat it.
            assertThat(adapter.inconclusive(request(PriceWriteRequest.Operation.RESTORE), 503,
                    "HTTP 503", new byte[0]).outcome())
                    .isEqualTo(PriceWriteResult.Outcome.UNKNOWN_STATE);
        }

        @Test
        void aServerFailureOnAReadIsRetriable() {
            for (var operation : new PriceWriteRequest.Operation[] {
                    PriceWriteRequest.Operation.READBACK,
                    PriceWriteRequest.Operation.STATUS_ENQUIRY}) {
                assertThat(adapter.inconclusive(request(operation), 503, "HTTP 503",
                        new byte[0]).outcome())
                        .describedAs("%s", operation)
                        .isEqualTo(PriceWriteResult.Outcome.RETRIABLE_ERROR);
            }
        }

        @Test
        void aClientRefusalIsARefusalForEveryOperation() {
            for (var operation : PriceWriteRequest.Operation.values()) {
                assertThat(adapter.inconclusive(request(operation), 400, "HTTP 400",
                        new byte[0]).outcome())
                        .describedAs("%s", operation)
                        .isEqualTo(PriceWriteResult.Outcome.REJECTED);
            }
        }
    }

    @Nested
    @DisplayName("TC-CLASSIFY-002 an apply is read through the recorded task pointer")
    class Applied {

        @Test
        void aSynchronousPlatformNeedsNoHandle() {
            PriceWriteResult result = adapter.applied(spec("SYNCHRONOUS", null, null, null,
                    null, null, null), json("{\"result\":\"ok\"}"), "HTTP 200", new byte[0]);

            assertThat(result.outcome()).isEqualTo(PriceWriteResult.Outcome.ACCEPTED);
            assertThat(result.nativeTaskKey()).isNull();
        }

        @Test
        void anAsynchronousPlatformYieldsItsHandle() {
            PriceWriteResult result = adapter.applied(
                    spec("ASYNCHRONOUS_TASK", "/result/task_id", null, null, null, null, null),
                    json("{\"result\":{\"task_id\":\"TASK-9\"}}"), "HTTP 202", new byte[0]);

            assertThat(result.outcome()).isEqualTo(PriceWriteResult.Outcome.ACCEPTED);
            assertThat(result.nativeTaskKey()).isEqualTo("TASK-9");
        }

        @Test
        void anAsynchronousAcceptWithNoHandleIsUnknown() {
            // The platform took something and nothing here can later ask what
            // became of it. Calling that a success would be a guess.
            PriceWriteResult result = adapter.applied(
                    spec("ASYNCHRONOUS_TASK", "/result/task_id", null, null, null, null, null),
                    json("{\"result\":{}}"), "HTTP 202", new byte[0]);

            assertThat(result.outcome()).isEqualTo(PriceWriteResult.Outcome.UNKNOWN_STATE);
            assertThat(result.errorCode()).isEqualTo("task_key_not_at_recorded_pointer");
        }
    }

    @Nested
    @DisplayName("TC-CLASSIFY-003 a task's fate is read in the platform's own words")
    class Enquired {

        private final WriteOperationSpec status = spec("ASYNCHRONOUS_TASK", "/result/task_id",
                "/result/state", "COMPLETED", "FAILED", null, null);

        @Test
        void thePlatformsWordForFinishedIsAccepted() {
            PriceWriteResult result = adapter.enquired(status,
                    json("{\"result\":{\"state\":\"COMPLETED\"}}"), "HTTP 200", new byte[0],
                    request(PriceWriteRequest.Operation.STATUS_ENQUIRY));

            assertThat(result.outcome()).isEqualTo(PriceWriteResult.Outcome.ACCEPTED);
        }

        @Test
        void thePlatformsWordForRejectedIsARefusal() {
            PriceWriteResult result = adapter.enquired(status,
                    json("{\"result\":{\"state\":\"FAILED\"}}"), "HTTP 200", new byte[0],
                    request(PriceWriteRequest.Operation.STATUS_ENQUIRY));

            assertThat(result.outcome()).isEqualTo(PriceWriteResult.Outcome.REJECTED);
            assertThat(result.errorCode()).isEqualTo("platform_task_rejected");
        }

        @Test
        void anythingElseMeansStillRunning() {
            PriceWriteResult result = adapter.enquired(status,
                    json("{\"result\":{\"state\":\"PROCESSING\"}}"), "HTTP 200", new byte[0],
                    request(PriceWriteRequest.Operation.STATUS_ENQUIRY));

            assertThat(result.outcome()).isEqualTo(PriceWriteResult.Outcome.RETRIABLE_ERROR);
            assertThat(result.errorCode()).isEqualTo("platform_task_in_progress");
        }

        @Test
        void anAnswerWithoutAStatusIsUnknown() {
            PriceWriteResult result = adapter.enquired(status, json("{\"result\":{}}"),
                    "HTTP 200", new byte[0],
                    request(PriceWriteRequest.Operation.STATUS_ENQUIRY));

            assertThat(result.outcome()).isEqualTo(PriceWriteResult.Outcome.UNKNOWN_STATE);
            assertThat(result.errorCode()).isEqualTo("task_status_not_at_recorded_pointer");
        }
    }

    @Nested
    @DisplayName("TC-CLASSIFY-004 an observed price is read from where it was recorded")
    class Observed {

        private final WriteOperationSpec readback = spec("SYNCHRONOUS", null, null, null, null,
                "/item/price", "/item/currency");

        @Test
        void aPriceIsReadWithItsCurrency() {
            PriceWriteResult result = adapter.observed(readback,
                    json("{\"item\":{\"price\":\"140.5000\",\"currency\":\"RUB\"}}"),
                    "HTTP 200", new byte[0]);

            assertThat(result.observedPrice()).isEqualByComparingTo("140.5000");
            assertThat(result.observedCurrency()).isEqualTo("RUB");
        }

        @Test
        void aPriceSentAsANumberIsStillAPrice() {
            PriceWriteResult result = adapter.observed(readback,
                    json("{\"item\":{\"price\":140.5,\"currency\":\"RUB\"}}"), "HTTP 200",
                    new byte[0]);

            assertThat(result.observedPrice()).isEqualByComparingTo("140.5");
        }

        @Test
        void anAnswerThePointerDoesNotReachCarriesNoPrice() {
            PriceWriteResult result = adapter.observed(readback,
                    json("{\"item\":{\"cost\":\"140.5000\"}}"), "HTTP 200", new byte[0]);

            assertThat(result.outcome()).isEqualTo(PriceWriteResult.Outcome.UNKNOWN_STATE);
            assertThat(result.errorCode()).isEqualTo("observed_price_not_at_recorded_pointer");
            assertThat(result.observedPrice()).isNull();
        }

        @Test
        void aPriceThatIsNotANumberIsRefusedRatherThanCoerced() {
            PriceWriteResult result = adapter.observed(readback,
                    json("{\"item\":{\"price\":\"on request\"}}"), "HTTP 200", new byte[0]);

            assertThat(result.outcome()).isEqualTo(PriceWriteResult.Outcome.UNKNOWN_STATE);
            assertThat(result.errorCode()).isEqualTo("observed_price_not_a_number");
        }
    }

    @Nested
    @DisplayName("TC-CLASSIFY-005 a pointer reads only what it names")
    class Pointers {

        @Test
        void aMissingPointerReadsNothing() {
            assertThat(PriceWriteAnswers.text(json("{\"a\":\"b\"}"), null)).isNull();
        }

        @Test
        void aPointerThatReachesNothingReadsNothing() {
            assertThat(PriceWriteAnswers.text(json("{\"a\":\"b\"}"), "/c")).isNull();
        }

        @Test
        void anEmptyValueIsAnAbsentValue() {
            assertThat(PriceWriteAnswers.text(json("{\"a\":\"  \"}"), "/a"))
                    .isNull();
        }

        @Test
        void anObjectIsNotAValue() {
            assertThat(PriceWriteAnswers.text(json("{\"a\":{\"b\":1}}"), "/a"))
                    .isNull();
        }

        @Test
        void aNumberAndABooleanAreValues() {
            assertThat(PriceWriteAnswers.text(json("{\"a\":42}"), "/a"))
                    .isEqualTo("42");
            assertThat(PriceWriteAnswers.text(json("{\"a\":true}"), "/a"))
                    .isEqualTo("true");
        }
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private static JsonNode json(String body) {
        return MAPPER.readTree(body);
    }

    private static PriceWriteRequest request(PriceWriteRequest.Operation operation) {
        return new PriceWriteRequest(operation, CAPABILITY, null, "LIST-1", "VAR-1",
                Money.of(new BigDecimal("105.0000"), "RUB"), "classification-test-key", null);
    }

    private static WriteOperationSpec spec(String writeResultModel, String taskKeyPointer,
                                           String taskStatusPointer, String successValue,
                                           String failureValue, String pricePointer,
                                           String currencyPointer) {
        EndpointCallSpec endpoint = new EndpointCallSpec(UUID.randomUUID(), "OZON",
                "adapter.prices", "https://api.example.test", "POST", "/v1/prices", null,
                null, "application/json", null, "NONE", null, 3000, 1_048_576L);
        return new WriteOperationSpec(CAPABILITY, "OZON", "APPLY", writeResultModel,
                "{\"price\":\"{targetPrice}\"}", taskKeyPointer, taskStatusPointer,
                successValue, failureValue, pricePointer, currencyPointer, endpoint);
    }
}
