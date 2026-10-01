package com.mimococo.marketops.marketplaceintegration.port;

/**
 * The one outbound doorway a listing title and description change leaves through (W2).
 *
 * <p>An implementation performs exactly the recorded, verified operation and classifies the
 * answer; it never concludes anything about the command. Only a readback that shows the intended
 * text makes a change succeeded, and only the command's own rules decide that.
 */
public interface ContentWritePort {

    /** Perform one operation and say what the marketplace answered. */
    ContentWriteResult perform(ContentWriteRequest request);
}
