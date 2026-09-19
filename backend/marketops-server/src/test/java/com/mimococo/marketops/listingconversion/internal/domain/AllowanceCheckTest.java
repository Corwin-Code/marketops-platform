package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headroom is limit minus reserve minus occupied, per axis, and every axis must answer. */
class AllowanceCheckTest {

    private static AllowanceCheck.Axis axis(String code, String limit, String reserve, String occupied, String requested) {
        return new AllowanceCheck.Axis(code, new BigDecimal(limit), new BigDecimal(reserve), new BigDecimal(occupied),
                requested == null ? null : new BigDecimal(requested));
    }

    @Test
    @DisplayName("TC-LC-A01 a request that fits the headroom on every axis is sufficient")
    void fittingRequestIsSufficient() {
        assertThat(AllowanceCheck.insufficientAxes(List.of(
                axis("CONCURRENT_LISTINGS", "5", "1", "3", "1"),
                axis("AFFECTED_VARIANTS", "100", "10", "40", "50")))).isEmpty();
    }

    @Test
    @DisplayName("TC-LC-A02 one unit over the headroom names exactly that axis")
    void overHeadroomNamesTheAxis() {
        assertThat(AllowanceCheck.insufficientAxes(List.of(
                axis("CONCURRENT_LISTINGS", "5", "1", "3", "2"),
                axis("AFFECTED_VARIANTS", "100", "10", "40", "50"))))
                .containsExactly("CONCURRENT_LISTINGS");
        assertThat(axis("CONCURRENT_LISTINGS", "5", "1", "3", "2").headroom()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("TC-LC-A03 no published allowance means unresolved, not unlimited")
    void noAllowanceIsUnresolved() {
        assertThat(AllowanceCheck.insufficientAxes(List.of())).containsExactly("ALLOWANCE_UNRESOLVED");
    }

    @Test
    @DisplayName("TC-LC-A04 an axis the request did not state is insufficient by name")
    void unstatedRequestIsInsufficient() {
        assertThat(AllowanceCheck.insufficientAxes(List.of(axis("REVENUE_EXPOSURE", "1000", "0", "0", null))))
                .containsExactly("REVENUE_EXPOSURE:REQUEST_UNSTATED");
        assertThat(AllowanceCheck.insufficientAxes(List.of(axis("REVENUE_EXPOSURE", "1000", "0", "0", "-1"))))
                .containsExactly("REVENUE_EXPOSURE");
    }
}
