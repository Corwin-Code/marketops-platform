package com.mimococo.marketops.advertisingefficiency;

/** The inference an observation supports; this Slice publishes no causal experiment result. */
public enum AdvertisingOutcomeInferenceScope {
    OPERATIONAL_ASSOCIATION_NOT_CAUSAL_INCREMENTALITY,
    UNKNOWN;

    /** Historical absence or an unsupported claim must not acquire a stronger interpretation. */
    public static AdvertisingOutcomeInferenceScope fromStored(String value) {
        return OPERATIONAL_ASSOCIATION_NOT_CAUSAL_INCREMENTALITY.name().equals(value)
                ? OPERATIONAL_ASSOCIATION_NOT_CAUSAL_INCREMENTALITY : UNKNOWN;
    }
}
