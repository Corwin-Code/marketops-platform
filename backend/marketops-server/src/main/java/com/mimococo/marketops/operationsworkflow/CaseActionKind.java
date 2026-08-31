package com.mimococo.marketops.operationsworkflow;

/**
 * The closed set of actions that can satisfy an availability case's first stage.
 *
 * <p>Closed on purpose. Every member names something with an artefact behind it
 * — a bound consignment, a restoration reference, a repaired mapping, a
 * published policy version, a recorded disposition. There is deliberately no
 * member meaning "looked at it", because a free-text acknowledgement is exactly
 * what the Contract refuses to accept as action.
 */
public enum CaseActionKind {

    /** Attested inbound supply was bound to the shortfall. */
    INBOUND_EVIDENCE_BOUND,

    /** A channel restoration was performed and its reference recorded. */
    CHANNEL_RESTORATION_REFERENCE,

    /** A stock, mapping or ownership defect was repaired. */
    DATA_OR_MAPPING_REPAIR,

    /** A policy version was published to resolve a blocked resolution. */
    POLICY_VERSION_PUBLISHED,

    /** A return or quality disposition was recorded. */
    QUALITY_DISPOSITION_RECORDED,

    /** An ownership declaration was published for a store and mode. */
    OWNERSHIP_DECLARATION_PUBLISHED
}
