package com.mimococo.marketops.marketplaceintegration.port;

/** The one way a description write, status enquiry, readback or restore reaches a marketplace. */
public interface DescriptionWritePort {

    DescriptionWriteResult perform(DescriptionWriteRequest request);
}
