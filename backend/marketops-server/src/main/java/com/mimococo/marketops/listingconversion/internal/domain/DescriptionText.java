package com.mimococo.marketops.listingconversion.internal.domain;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import com.mimococo.marketops.shared.SecretMaterialGuard;

/** Exact business text, never the trimmed 512-character metadata representation. */
public final class DescriptionText {
    // Internal storage bound only. The verified platform/category bound is checked separately.
    private static final int STORAGE_CODE_POINTS = 65536;

    private DescriptionText() { }

    public static String requireTarget(String text) {
        if (text == null || text.isBlank()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return observation(text);
    }

    /** Empty and absent observed text stay distinct; neither is an executable target. */
    public static String observation(String text) {
        if (text == null) {
            return null;
        }
        if (text.codePointCount(0, text.length()) > STORAGE_CODE_POINTS || text.indexOf('\0') >= 0) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
                }
            } else if (Character.isLowSurrogate(c)) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
        }
        SecretMaterialGuard.requireNonSecret("descriptionText", text);
        return text;
    }
}
