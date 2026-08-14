package com.mimococo.marketops.testfixture.conforming.inward.shared.internal;

/** Private implementation of the shared module. */
public final class MeasurementFormatter {

    private MeasurementFormatter() {
    }

    /** Render a label and a quantity as one string. */
    public static String format(String label, long value) {
        return label + "=" + value;
    }
}
