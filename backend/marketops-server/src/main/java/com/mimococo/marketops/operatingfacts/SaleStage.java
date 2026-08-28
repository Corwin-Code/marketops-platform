package com.mimococo.marketops.operatingfacts;

/**
 * How certain a sale is.
 *
 * <p>The three stages are separate facts rather than a status that moves,
 * because they answer three different questions and arrive at three different
 * times. A completed order may still be returned; an order that survived the
 * return window may still be adjusted at settlement; a settled payout is what
 * the money finally was.
 */
public enum SaleStage {

    /** The marketplace recorded the order. */
    COMPLETED,

    /** The order survived a stated retention window. */
    RETAINED,

    /** The marketplace settled the payout. */
    SETTLED
}
