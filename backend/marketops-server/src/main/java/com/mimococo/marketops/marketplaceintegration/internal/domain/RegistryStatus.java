package com.mimococo.marketops.marketplaceintegration.internal.domain;

/** Registry-row lifecycle; retirement hides a row from evaluation without deleting it. */
public enum RegistryStatus {
    ACTIVE,
    RETIRED
}
