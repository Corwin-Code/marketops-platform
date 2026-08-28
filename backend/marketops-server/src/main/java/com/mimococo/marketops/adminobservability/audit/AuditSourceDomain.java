package com.mimococo.marketops.adminobservability.audit;

/**
 * The module a metadata audit event originates from.
 *
 * <p>The database value is the module's package name, so an audit row can be
 * traced to its owning module without a mapping table.
 */
public enum AuditSourceDomain {

    ORGANIZATION_ACCOUNT("organizationaccount"),
    IDENTITY_ACCESS("identityaccess"),
    MARKETPLACE_INTEGRATION("marketplaceintegration"),
    ADMIN_OBSERVABILITY("adminobservability"),
    PRODUCT_LISTING("productlisting"),
    OPERATING_FACTS("operatingfacts"),
    ANALYTICS_DECISION("analyticsdecision"),
    AI_COPILOT("aicopilot"),
    OPERATIONS_WORKFLOW("operationsworkflow");

    private final String dbValue;

    AuditSourceDomain(String dbValue) {
        this.dbValue = dbValue;
    }

    /** Stable value stored in the journal. */
    public String dbValue() {
        return dbValue;
    }

    /** Resolve the enum for a stored value. */
    public static AuditSourceDomain fromDbValue(String value) {
        for (AuditSourceDomain candidate : values()) {
            if (candidate.dbValue.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown audit source domain");
    }
}
