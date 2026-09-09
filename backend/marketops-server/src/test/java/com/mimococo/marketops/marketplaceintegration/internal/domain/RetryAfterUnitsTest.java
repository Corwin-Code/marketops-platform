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
        assertThat(RetryAfterUnits.seconds("OZON", "0")).isEmpty();
        assertThat(RetryAfterUnits.seconds(null, "1")).isEmpty();
        assertThat(RetryAfterUnits.seconds("OZON", null)).isEmpty();
        assertThat(RetryAfterUnits.seconds("OZON", "1234567")).isEmpty();
    }

    @Test
    @DisplayName("TC-LC-RA03 no wait exceeds one hour")
    void waitIsCapped() {
        assertThat(RetryAfterUnits.seconds("OZON", "100")).contains(3600);
        assertThat(RetryAfterUnits.seconds("WILDBERRIES", "99999")).contains(3600);
    }
}
