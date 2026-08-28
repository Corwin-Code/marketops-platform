package com.mimococo.marketops.operationsworkflow;

/**
 * Why a guardrail evaluation was run.
 *
 * <p>The same rules run at all three moments and the verdict is recorded each
 * time, because the world moves between them: a preview that passed an hour ago
 * says nothing about whether the cooldown has since started or the stock has
 * since dropped. The write gate specifically requires an {@link #EXECUTION}
 * pass, so an approval cannot stand in for the check made at the moment of the
 * write.
 */
public enum GuardrailPurpose {

    /** An operator is looking at what the change would do. */
    IMPACT_PREVIEW,

    /** A decision is being recorded. */
    APPROVAL,

    /** A command is about to be created and executed. */
    EXECUTION
}
