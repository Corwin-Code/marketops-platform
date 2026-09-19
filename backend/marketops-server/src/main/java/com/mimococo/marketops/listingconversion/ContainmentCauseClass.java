package com.mimococo.marketops.listingconversion;

/** Why a scope was stopped; each class has its own cause owner. */
public enum ContainmentCauseClass {
    LOCAL_COST,
    SHARED_VERSION,
    PATH_INTEGRITY,
    SAFETY_FAILURE,
    PLATFORM_INCIDENT
}
