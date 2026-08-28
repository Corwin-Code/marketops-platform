package com.mimococo.marketops.aicopilot;

/**
 * What kind of statement a model made.
 *
 * <p>The four kinds are kept apart because they carry different weight. A fact
 * only restates something the deterministic layer already computed; an
 * inference is the model's own reasoning; a recommendation is a proposal that
 * still has to pass every gate; and an unknown is the model saying what it could
 * not establish, which is often the most useful of the four.
 */
public enum AiClaimKind {

    /** A restatement of a canonical value or a deterministic finding. */
    FACT,

    /** The model's own hypothesis, with its supporting and counter evidence. */
    INFERENCE,

    /** A proposed action, which authorizes nothing by itself. */
    RECOMMENDATION,

    /** Something the model could not establish, and why it matters. */
    UNKNOWN
}
