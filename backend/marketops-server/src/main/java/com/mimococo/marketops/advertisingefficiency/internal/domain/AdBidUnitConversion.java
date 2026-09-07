package com.mimococo.marketops.advertisingefficiency.internal.domain;

import java.math.BigDecimal;

/** Exact denomination conversion before candidate generation; never adapter-time rounding. */
public final class AdBidUnitConversion {
    private AdBidUnitConversion() { }
    public static BigDecimal toNative(BigDecimal majorAmount,String nativeUnit) {
        if(majorAmount==null) return null;
        return majorAmount.multiply(factor(nativeUnit));
    }
    public static BigDecimal toMajor(BigDecimal nativeAmount,String nativeUnit) {
        if(nativeAmount==null) return null;
        return nativeAmount.divide(factor(nativeUnit));
    }
    private static BigDecimal factor(String nativeUnit) {
        return switch(nativeUnit) {
            case "CURRENCY_MAJOR" -> BigDecimal.ONE;
            case "CURRENCY_MINOR" -> BigDecimal.valueOf(100);
            default -> throw new IllegalArgumentException("unknown native bid denomination");
        };
    }
}
