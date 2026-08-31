package com.mimococo.marketops.operationsworkflow;

/**
 * The closed set of business reasons for accepting a calculated risk.
 *
 * <p>Closed so that acceptances can be counted and reviewed by kind. A
 * free-text reason would make "how often do we accept supplier outages" an
 * unanswerable question, and that question is the point of reviewing them.
 */
public enum ExceptionReasonCode {

    /** The variant is being discontinued and will not be replenished. */
    PLANNED_DISCONTINUATION,

    /** Selling is paused for a season and the gap is intended. */
    SEASONAL_PAUSE,

    /** A supplier failure is known, owned, and being lived with. */
    SUPPLIER_OUTAGE_ACCEPTED,

    /** The exposure is too small to be worth the work. */
    COMMERCIALLY_IMMATERIAL,

    /** Supply is arranged another way and the calculated shortfall is stale. */
    ALTERNATIVE_SUPPLY_ARRANGED,

    /** A known data limitation is accepted while the source is repaired. */
    KNOWN_DATA_LIMITATION_ACCEPTED
}
