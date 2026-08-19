package com.mimococo.marketops.adminobservability.audit;

/**
 * Names of the operator-attribution contract on the maintenance surface.
 *
 * <p>Attribution records who was at the keyboard for a loopback maintenance
 * mutation; it is explicitly not authentication and grants nothing. The
 * boundary validates the header against the shared operator shape, stores the
 * accepted value under the request attribute, and refuses the mutation — with
 * a truthful system-attributed audit event — when the header is missing or
 * invalid.
 */
public final class OperatorAttribution {

    /** Header a maintenance mutation must carry. */
    public static final String HEADER_NAME = "X-Operator";

    /** Request attribute holding the validated operator value. */
    public static final String REQUEST_ATTRIBUTE = "marketops.operator";

    /** Fixed system identity for events observed by the maintenance boundary. */
    public static final String BOUNDARY_RECORDER = "metadata-maintenance-boundary";

    private OperatorAttribution() {
    }
}
