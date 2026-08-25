package com.mimococo.marketops.marketplaceintegration.port;

/** Deliberately invokes the package-private request factory outside the executor. */
public final class AcquisitionRequestRebinderFixture {

    /** Rebind through the otherwise hidden factory. */
    public AcquisitionRequest rebind(CallAuthorityGrant grant) {
        return AcquisitionRequest.from(grant);
    }
}
