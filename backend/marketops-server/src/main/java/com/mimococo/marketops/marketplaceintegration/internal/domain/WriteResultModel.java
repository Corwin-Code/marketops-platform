package com.mimococo.marketops.marketplaceintegration.internal.domain;

/**
 * How a platform reports the outcome of a write.
 *
 * <p>{@code UNKNOWN} is the state a capability starts in and the state a write
 * can never be attempted from. A synchronous platform answers with the result
 * and the command moves straight to readback; an asynchronous one answers with
 * a handle and the command waits on a status enquiry first.
 */
public enum WriteResultModel {
    SYNCHRONOUS,
    ASYNCHRONOUS_TASK,
    UNKNOWN
}
