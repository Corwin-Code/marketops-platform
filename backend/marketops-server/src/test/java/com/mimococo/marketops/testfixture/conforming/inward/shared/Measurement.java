package com.mimococo.marketops.testfixture.conforming.inward.shared;

import com.mimococo.marketops.testfixture.conforming.inward.shared.internal.MeasurementFormatter;

/**
 * A value every module may use.
 *
 * @param label what was measured
 * @param value the measured quantity
 */
public record Measurement(String label, long value) {

    /** Render the measurement using this module's own implementation. */
    public String rendered() {
        return MeasurementFormatter.format(label, value);
    }
}
