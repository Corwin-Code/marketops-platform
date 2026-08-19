/**
 * Published audit contract of the admin-observability module.
 *
 * <p>Metadata modules record every successful mutation in the same transaction
 * as the mutation and every refusal in an independent transaction, through the
 * {@link com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder}.
 * The journal is append-only at the database privilege level and is queried
 * through {@link com.mimococo.marketops.adminobservability.audit.MetadataAuditQueries}.
 */
@org.springframework.modulith.NamedInterface("audit")
package com.mimococo.marketops.adminobservability.audit;
