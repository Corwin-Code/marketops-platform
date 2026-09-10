package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.OperationRejectedException;
import org.junit.jupiter.api.Test;

class DescriptionTextTest {
    @Test
    void longRussianTextPreservesWhitespaceNewlinesAndExactDigest() {
        String text = "  Описание товара\r\n" + "Мягкая ткань, бережная стирка.\n".repeat(100) + "  ";
        assertThat(DescriptionText.requireTarget(text)).isEqualTo(text);
        assertThat(Digest.ofText(DescriptionText.requireTarget(text))).isEqualTo(Digest.ofText(text));
        assertThat(Digest.ofText(text)).isNotEqualTo(Digest.ofText(text.strip()));
    }

    @Test
    void storageLimitCountsUnicodeCodePointsRatherThanUtf16Units() {
        assertThat(DescriptionText.requireTarget("🧵".repeat(65536))).hasSize(131072);
        assertThatThrownBy(() -> DescriptionText.requireTarget("🧵".repeat(65537)))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void absentEmptyAndWhitespaceObservationsAreNotSilentlyChanged() {
        assertThat(DescriptionText.observation(null)).isNull();
        assertThat(DescriptionText.observation("")).isEmpty();
        assertThat(DescriptionText.observation("  \n")).isEqualTo("  \n");
        assertThatThrownBy(() -> DescriptionText.requireTarget(null)).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> DescriptionText.requireTarget(" \n")).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void invalidUnicodeAndDatabaseNulAreRefusedWithoutReplacement() {
        for (String text : new String[] {"x\0y", "\uD800", "\uDC00", "\uD800x"}) {
            assertThatThrownBy(() -> DescriptionText.observation(text)).isInstanceOf(OperationRejectedException.class);
        }
    }

    @Test
    void secretGuardStillAppliesToBothTargetsAndObservations() {
        String synthetic = "Описание\napi_key: synthetic-not-a-real-credential";
        assertThatThrownBy(() -> DescriptionText.requireTarget(synthetic)).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> DescriptionText.observation(synthetic)).isInstanceOf(OperationRejectedException.class);
    }
}
