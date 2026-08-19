package com.mimococo.marketops.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The shared field-validation contract of every metadata writing surface.
 *
 * <p>Each rejection is asserted through its stable error code, because the code
 * is what an operator sees and what the denial journal records.
 */
class MetadataFieldPolicyTest {

    @Nested
    @DisplayName("TC-SEC-201 business and registry codes")
    class Codes {

        @Test
        void acceptsCanonicalCodes() {
            assertThat(MetadataFieldPolicy.requireCode("ozon-main-2")).isEqualTo("ozon-main-2");
            assertThat(MetadataFieldPolicy.requireCode("a")).isEqualTo("a");
            assertThat(MetadataFieldPolicy.requireRegistryCode("orders.list.v3"))
                    .isEqualTo("orders.list.v3");
        }

        @Test
        void rejectsEdgeHyphenUppercaseAndNull() {
            for (String candidate : new String[] {null, "", "-edge", "edge-", "UPPER",
                    "with space", "a".repeat(64)}) {
                assertThatThrownBy(() -> MetadataFieldPolicy.requireCode(candidate))
                        .isInstanceOfSatisfying(OperationRejectedException.class, rejection ->
                                assertThat(rejection.errorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
            }
        }
    }

    @Nested
    @DisplayName("TC-SEC-202 bounded free text")
    class FreeText {

        @Test
        void acceptsAndStripsOrdinaryText() {
            assertThat(MetadataFieldPolicy.requireText("displayName", "  Ozon Main  "))
                    .isEqualTo("Ozon Main");
            assertThat(MetadataFieldPolicy.optionalText("note", "  ")).isNull();
            assertThat(MetadataFieldPolicy.optionalText("note", null)).isNull();
        }

        @Test
        void rejectsBlankAndOversizedText() {
            for (String candidate : new String[] {null, "", "   ", "x".repeat(513)}) {
                assertThatThrownBy(() ->
                        MetadataFieldPolicy.requireText("displayName", candidate))
                        .isInstanceOfSatisfying(OperationRejectedException.class, rejection ->
                                assertThat(rejection.errorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
            }
        }

        @Test
        void refusesSecretLikeTextThroughTheGuard() {
            assertThatThrownBy(() -> MetadataFieldPolicy.requireText(
                    "displayName", "api_key = 12345"))
                    .isInstanceOfSatisfying(OperationRejectedException.class, rejection ->
                            assertThat(rejection.errorCode())
                                    .isEqualTo(ErrorCode.SECRET_MATERIAL_SUSPECTED));
        }
    }

    @Nested
    @DisplayName("TC-SEC-203 reference-data formats")
    class ReferenceFormats {

        @Test
        void validatesTimezoneAgainstTheZoneRegistry() {
            assertThat(MetadataFieldPolicy.optionalTimezone("Europe/Moscow"))
                    .isEqualTo("Europe/Moscow");
            assertThat(MetadataFieldPolicy.optionalTimezone(null)).isNull();
            assertThatThrownBy(() -> MetadataFieldPolicy.optionalTimezone("Mars/Olympus"))
                    .isInstanceOfSatisfying(OperationRejectedException.class, rejection ->
                            assertThat(rejection.errorCode())
                                    .isEqualTo(ErrorCode.INVALID_TIMEZONE));
        }

        @Test
        void validatesCurrencyAgainstIso4217() {
            assertThat(MetadataFieldPolicy.optionalCurrency("RUB")).isEqualTo("RUB");
            for (String candidate : new String[] {"rub", "RUBLE", "ZZZ"}) {
                assertThatThrownBy(() -> MetadataFieldPolicy.optionalCurrency(candidate))
                        .isInstanceOfSatisfying(OperationRejectedException.class, rejection ->
                                assertThat(rejection.errorCode())
                                        .isEqualTo(ErrorCode.INVALID_CURRENCY));
            }
        }

        @Test
        void validatesCountryAgainstIso3166() {
            assertThat(MetadataFieldPolicy.optionalCountry("RU")).isEqualTo("RU");
            for (String candidate : new String[] {"ru", "RUS", "XX"}) {
                assertThatThrownBy(() -> MetadataFieldPolicy.optionalCountry(candidate))
                        .isInstanceOfSatisfying(OperationRejectedException.class, rejection ->
                                assertThat(rejection.errorCode())
                                        .isEqualTo(ErrorCode.INVALID_COUNTRY));
            }
        }

        @Test
        void validatesCidrDeclarations() {
            assertThat(MetadataFieldPolicy.requireCidr("10.20.0.0/16")).isEqualTo("10.20.0.0/16");
            assertThat(MetadataFieldPolicy.requireCidr("2001:db8::/32")).isEqualTo("2001:db8::/32");
            for (String candidate : new String[] {null, "10.20.0.0", "office network"}) {
                assertThatThrownBy(() -> MetadataFieldPolicy.requireCidr(candidate))
                        .isInstanceOfSatisfying(OperationRejectedException.class, rejection ->
                                assertThat(rejection.errorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
            }
        }
    }

    @Nested
    @DisplayName("TC-SEC-204 opaque secret references")
    class SecretReferences {

        @Test
        void acceptsWellFormedReferences() {
            assertThat(MetadataFieldPolicy.requireSecretReference(
                    "secret-ref://vault/marketops/ozon-main/read"))
                    .isEqualTo("secret-ref://vault/marketops/ozon-main/read");
        }

        @Test
        void refusesEverythingElseIncludingRawMaterial() {
            for (String candidate : new String[] {
                    null,
                    "vault/marketops/read",
                    "secret-ref://",
                    "secret-ref://UPPER/name",
                    "A".repeat(80)}) {
                assertThatThrownBy(() ->
                        MetadataFieldPolicy.requireSecretReference(candidate))
                        .isInstanceOfSatisfying(OperationRejectedException.class, rejection ->
                                assertThat(rejection.errorCode())
                                        .isEqualTo(ErrorCode.SECRET_REFERENCE_INVALID));
            }
        }

        @Test
        void longWellFormedReferencesAreNotMistakenForKeyMaterial() {
            String reference = "secret-ref://vault/marketops-platform-metadata/"
                    + "ozon-main-account-credential/finance-read/version-2026-08";
            assertThat(reference.length()).isGreaterThan(64);
            assertThat(MetadataFieldPolicy.requireSecretReference(reference))
                    .isEqualTo(reference);
        }
    }
}
