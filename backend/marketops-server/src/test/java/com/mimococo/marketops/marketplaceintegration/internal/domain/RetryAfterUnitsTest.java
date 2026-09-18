package com.mimococo.marketops.marketplaceintegration.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A platform's wait is in the platform's unit, converted here and nowhere else. */
class RetryAfterUnitsTest {

    @Test
    @DisplayName("TC-LC-RA01 Ozon counts minutes and Wildberries counts seconds")
    void unitsPerPlatform() {
        assertThat(RetryAfterUnits.seconds("OZON", "2")).contains(120);
        assertThat(RetryAfterUnits.seconds("ozon", " 3 ")).contains(180);
        assertThat(RetryAfterUnits.seconds("WILDBERRIES", "30")).contains(30);
    }

    @Test
    @DisplayName("TC-LC-RA02 an unknown platform or an unparseable value gives no wait at all")
    void unknownGivesNothing() {
        assertThat(RetryAfterUnits.seconds("SOMEWHERE", "30")).isEmpty();
        assertThat(RetryAfterUnits.seconds("OZON", "soon")).isEmpty();
        assertThat(RetryAfterUnits.seconds("OZON", "0")).contains(0);
        assertThat(RetryAfterUnits.seconds(null, "1")).isEmpty();
        assertThat(RetryAfterUnits.seconds("OZON", null)).isEmpty();
        assertThat(RetryAfterUnits.seconds("OZON", "9999999999")).isEmpty();
    }

    @Test
    @DisplayName("TC-LC-RA03 a provider wait is never shortened to one hour")
    void waitIsNotCapped() {
        assertThat(RetryAfterUnits.seconds("OZON", "100")).contains(6000);
        assertThat(RetryAfterUnits.seconds("WILDBERRIES", "99999")).contains(99999);
        assertThat(RetryAfterUnits.seconds("OZON", "1234567")).contains(74074020);
    }

    @Test
    void standardHeaderIsSecondsAndNativeHeadersArePlatformSpecific() {
        assertThat(RetryAfterUnits.secondsFromHeaders("OZON", java.util.Map.of("Retry-After","2"))).contains(2);
        assertThat(RetryAfterUnits.secondsFromHeaders("OZON", java.util.Map.of("Item-Retry-After","2"))).contains(120);
        assertThat(RetryAfterUnits.secondsFromHeaders("WILDBERRIES", java.util.Map.of("X-Ratelimit-Retry","120"))).contains(120);
        assertThat(RetryAfterUnits.secondsFromHeaders("OZON", java.util.Map.of("Retry-After","180",
                "Item-Retry-After","2"))).contains(180);
        assertThat(RetryAfterUnits.secondsFromHeaders("OZON", java.util.Map.of("Item-Retry-After","2, 3"))).isEmpty();
    }

    @Test
    void anotherPlatformsNativeHeaderIsAnUnknownUnitAndGivesNoHint() {
        assertThat(RetryAfterUnits.secondsFromHeaders("OZON", java.util.Map.of("X-Ratelimit-Retry","120",
                "Retry-After","1"))).isEmpty();
        assertThat(RetryAfterUnits.secondsFromHeaders("WILDBERRIES", java.util.Map.of("Item-Retry-After","2"))).isEmpty();
        assertThat(RetryAfterUnits.secondsFromHeaders("SOMEWHERE", java.util.Map.of("Item-Retry-After","2"))).isEmpty();
        assertThat(RetryAfterUnits.secondsFromHeaders("SOMEWHERE", java.util.Map.of("Retry-After","2"))).contains(2);
    }

    @Test
    void responseEvidenceRetainsNativeHeadersAndRejectsUnrelatedHeaders() {
        var response = new com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteResult.Response(
                429,java.util.Map.of("item-retry-after","100","x-ratelimit-retry","123"),
                "a".repeat(64),"PROTOCOL_FIXTURE",true);
        assertThat(response.headers()).containsEntry("item-retry-after","100");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteResult.Response(
                        429,java.util.Map.of("authorization","not-retainable"),"a".repeat(64),"PROTOCOL_FIXTURE",true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
