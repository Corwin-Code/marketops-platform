package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An affected set is complete only when every observed variant maps without an open conflict. */
class AffectedSetResolutionTest {

    private static final UUID V1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID V2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @Test
    @DisplayName("TC-LC-AS01 every variant mapped is COMPLETE")
    void allMappedIsComplete() {
        var resolution = AffectedSetResolution.resolve(List.of(
                new AffectedSetResolution.Member(V1, P1, false), new AffectedSetResolution.Member(V2, P2, false)));

        assertThat(resolution.state()).isEqualTo("COMPLETE");
        assertThat(resolution.productVariantIds()).containsExactly(P1, P2);
        assertThat(resolution.reasonCodes()).isEmpty();
    }

    @Test
    @DisplayName("TC-LC-AS02 an open mapping conflict is CONFLICTED before anything else")
    void conflictWins() {
        var resolution = AffectedSetResolution.resolve(List.of(
                new AffectedSetResolution.Member(V1, P1, false), new AffectedSetResolution.Member(V2, null, true)));

        assertThat(resolution.state()).isEqualTo("CONFLICTED");
        assertThat(resolution.reasonCodes()).containsExactly("MAPPING_CONFLICT_OPEN");
    }

    @Test
    @DisplayName("TC-LC-AS03 an unmapped variant or no variant at all is INCOMPLETE")
    void unmappedOrEmptyIsIncomplete() {
        assertThat(AffectedSetResolution.resolve(List.of(new AffectedSetResolution.Member(V1, null, false)))
                .reasonCodes()).containsExactly("VARIANT_UNMAPPED");
        assertThat(AffectedSetResolution.resolve(List.of()).state()).isEqualTo("INCOMPLETE");
        assertThat(AffectedSetResolution.resolve(List.of()).reasonCodes()).containsExactly("NO_OBSERVED_VARIANTS");
    }
}
