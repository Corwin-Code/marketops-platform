package com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc;

import com.mimococo.marketops.availabilityrisk.internal.domain.InboundConsignment;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and appends attested inbound supply.
 *
 * <p>Only the newest version of each claim is ever read. An amendment does not
 * edit the previous version, so the claim that was believed last week stays
 * readable and the projection that trusted it stays explainable.
 */
@Repository
public class InboundAttestationRepository {

    private final JdbcClient jdbc;

    public InboundAttestationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The current state of every inbound claim for one variant.
     *
     * <p>Every claim is returned, including cancelled and overdue ones. The
     * calculator needs to see a refused consignment to route the case to the
     * inbound owner rather than reporting an unexplained shortfall.
     */
    public List<InboundConsignment> currentFor(UUID organizationId, UUID productVariantId) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (version.attestation_id)
                               version.id, version.quantity, version.expected_arrival_from,
                               version.expected_arrival_to, version.business_status,
                               version.last_verified_at, version.evidence_reference
                          FROM core.inbound_supply_attestation_version AS version
                          JOIN core.inbound_supply_attestation AS claim
                            ON claim.id = version.attestation_id
                           AND claim.organization_id = version.organization_id
                         WHERE claim.organization_id = :organizationId
                           AND claim.product_variant_id = :productVariantId
                         ORDER BY version.attestation_id, version.version_no DESC
                        """)
                .param("organizationId", organizationId)
                .param("productVariantId", productVariantId)
                .query((rows, rowNumber) -> new InboundConsignment(
                        rows.getObject("id", UUID.class),
                        rows.getInt("quantity"),
                        rows.getTimestamp("expected_arrival_from").toInstant(),
                        rows.getTimestamp("expected_arrival_to").toInstant(),
                        InboundConsignment.Status.valueOf(rows.getString("business_status")),
                        rows.getTimestamp("last_verified_at").toInstant(),
                        rows.getString("evidence_reference")))
                .list();
    }

    /**
     * How many inbound claims have stopped being supply.
     *
     * <p>Counted by the sweep so that "the inbound this risk depended on
     * lapsed" is a number an operator can see rather than something only
     * visible one variant at a time. A claim counts as lapsed when its latest
     * version says it will not arrive, or when the window it promised has
     * passed without one saying otherwise.
     */
    public int countLapsed(UUID organizationId, Instant asOf) {
        Long count = jdbc.sql("""
                        SELECT count(*) FROM (
                            SELECT DISTINCT ON (version.attestation_id)
                                   version.business_status, version.expected_arrival_to
                              FROM core.inbound_supply_attestation_version AS version
                              JOIN core.inbound_supply_attestation AS claim
                                ON claim.id = version.attestation_id
                               AND claim.organization_id = version.organization_id
                             WHERE claim.organization_id = :organizationId
                             ORDER BY version.attestation_id, version.version_no DESC
                        ) AS latest
                         WHERE latest.business_status IN
                                   ('CANCELLED', 'OVERDUE', 'CONFLICTED', 'UNKNOWN')
                            OR latest.expected_arrival_to < :asOf
                        """)
                .param("organizationId", organizationId)
                .param("asOf", Timestamp.from(asOf))
                .query(Long.class).single();
        return count == null ? 0 : count.intValue();
    }

    /** Create a claim's identity. Its state arrives as the first version. */
    public void insertClaim(UUID id, UUID organizationId, UUID productVariantId,
                            String externalReference, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO core.inbound_supply_attestation
                            (id, organization_id, product_variant_id, external_reference, created_at)
                        VALUES (:id, :organizationId, :productVariantId, :externalReference,
                                :createdAt)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("productVariantId", productVariantId)
                .param("externalReference", externalReference)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
    }

    /** Append one attested state. Never updates the version it supersedes. */
    public void appendVersion(InboundVersion version) {
        jdbc.sql("""
                        INSERT INTO core.inbound_supply_attestation_version
                            (id, attestation_id, organization_id, version_no, quantity,
                             expected_arrival_from, expected_arrival_to, business_status,
                             change_kind, evidence_reference, source_time, last_verified_at,
                             attested_by_user_id, reason, supersedes_version_id, recorded_at)
                        VALUES (:id, :attestationId, :organizationId, :versionNo, :quantity,
                                :arrivalFrom, :arrivalTo, :businessStatus, :changeKind,
                                :evidenceReference, :sourceTime, :lastVerifiedAt, :attestedBy,
                                :reason, :supersedes, :recordedAt)
                        """)
                .param("id", version.id())
                .param("attestationId", version.attestationId())
                .param("organizationId", version.organizationId())
                .param("versionNo", version.versionNo())
                .param("quantity", version.quantity())
                .param("arrivalFrom", Timestamp.from(version.expectedArrivalFrom()))
                .param("arrivalTo", Timestamp.from(version.expectedArrivalTo()))
                .param("businessStatus", version.businessStatus())
                .param("changeKind", version.changeKind())
                .param("evidenceReference", version.evidenceReference())
                .param("sourceTime", version.sourceTime() == null
                        ? null : Timestamp.from(version.sourceTime()))
                .param("lastVerifiedAt", Timestamp.from(version.lastVerifiedAt()))
                .param("attestedBy", version.attestedByUserId())
                .param("reason", version.reason())
                .param("supersedes", version.supersedesVersionId())
                .param("recordedAt", Timestamp.from(version.recordedAt()))
                .update();
    }

    /** The highest version number recorded for a claim. */
    public int latestVersionNo(UUID attestationId) {
        return jdbc.sql("""
                        SELECT coalesce(max(version_no), 0)
                          FROM core.inbound_supply_attestation_version
                         WHERE attestation_id = :attestationId
                        """)
                .param("attestationId", attestationId)
                .query(Integer.class)
                .single();
    }

    /** Header and current version, resolved together for ownership and optimistic mutation. */
    public Optional<CurrentAttestation> findCurrent(UUID attestationId) {
        return jdbc.sql("""
                        SELECT claim.organization_id, claim.product_variant_id,
                               claim.external_reference, version.id AS version_id,
                               version.version_no, version.quantity,
                               version.expected_arrival_from, version.expected_arrival_to,
                               version.business_status, version.evidence_reference,
                               version.last_verified_at
                          FROM core.inbound_supply_attestation claim
                          JOIN LATERAL (
                               SELECT one.*
                                 FROM core.inbound_supply_attestation_version one
                                WHERE one.attestation_id = claim.id
                                ORDER BY one.version_no DESC LIMIT 1
                          ) version ON true
                         WHERE claim.id = :attestationId
                        """)
                .param("attestationId", attestationId)
                .query((rows, number) -> new CurrentAttestation(attestationId,
                        rows.getObject("organization_id", UUID.class),
                        rows.getObject("product_variant_id", UUID.class),
                        rows.getString("external_reference"),
                        rows.getObject("version_id", UUID.class), rows.getInt("version_no"),
                        rows.getInt("quantity"),
                        rows.getTimestamp("expected_arrival_from").toInstant(),
                        rows.getTimestamp("expected_arrival_to").toInstant(),
                        rows.getString("business_status"),
                        rows.getString("evidence_reference"),
                        rows.getTimestamp("last_verified_at").toInstant()))
                .optional();
    }

    /**
     * The current version of every claim on the permitted variants, newest
     * change first.
     *
     * <p>Each claim appears once, at its latest version: the list answers
     * "what do we currently believe is coming", and the superseded versions
     * stay in the table for anybody reviewing how that belief changed.
     *
     * @param productVariantId one variant, or {@code null} for all permitted
     * @param status one business status of the current version, or {@code null}
     */
    public List<CurrentListRow> listCurrent(UUID organizationId, UUID[] permittedProductVariantIds,
                                            UUID productVariantId, String status, int limit,
                                            int offset) {
        return jdbc.sql("""
                        SELECT claim.id, claim.product_variant_id, claim.external_reference,
                               variant.sku_code, variant.display_name,
                               version.id AS version_id, version.version_no, version.quantity,
                               version.expected_arrival_from, version.expected_arrival_to,
                               version.business_status, version.change_kind,
                               version.evidence_reference, version.source_time,
                               version.last_verified_at, version.recorded_at, version.reason,
                               version.attested_by_user_id
                        """ + CURRENT_LIST_FROM + """
                         ORDER BY version.recorded_at DESC, claim.id
                         LIMIT :limit OFFSET :offset
                        """)
                .param("organizationId", organizationId)
                .param("permittedProductVariantIds", permittedProductVariantIds)
                .param("productVariantId", productVariantId)
                .param("status", status)
                .param("limit", limit)
                .param("offset", offset)
                .query((rows, number) -> new CurrentListRow(
                        rows.getObject("id", UUID.class),
                        rows.getObject("product_variant_id", UUID.class),
                        rows.getString("sku_code"),
                        rows.getString("display_name"),
                        rows.getString("external_reference"),
                        rows.getObject("version_id", UUID.class),
                        rows.getInt("version_no"),
                        rows.getInt("quantity"),
                        rows.getTimestamp("expected_arrival_from").toInstant(),
                        rows.getTimestamp("expected_arrival_to").toInstant(),
                        rows.getString("business_status"),
                        rows.getString("change_kind"),
                        rows.getString("evidence_reference"),
                        rows.getTimestamp("source_time") == null
                                ? null : rows.getTimestamp("source_time").toInstant(),
                        rows.getTimestamp("last_verified_at").toInstant(),
                        rows.getTimestamp("recorded_at").toInstant(),
                        rows.getString("reason"),
                        rows.getObject("attested_by_user_id", UUID.class)))
                .list();
    }

    /** How many claims the same filter matches in total. */
    public long countCurrent(UUID organizationId, UUID[] permittedProductVariantIds,
                             UUID productVariantId, String status) {
        Long count = jdbc.sql("SELECT count(*)" + CURRENT_LIST_FROM)
                .param("organizationId", organizationId)
                .param("permittedProductVariantIds", permittedProductVariantIds)
                .param("productVariantId", productVariantId)
                .param("status", status)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    /** The claims, their current versions and the filter every list read shares. */
    private static final String CURRENT_LIST_FROM = """
              FROM core.inbound_supply_attestation AS claim
              JOIN core.product_variant AS variant
                ON variant.id = claim.product_variant_id
               AND variant.organization_id = claim.organization_id
              JOIN LATERAL (
                   SELECT one.*
                     FROM core.inbound_supply_attestation_version AS one
                    WHERE one.attestation_id = claim.id
                      AND one.organization_id = claim.organization_id
                    ORDER BY one.version_no DESC
                    LIMIT 1
              ) AS version ON true
             WHERE claim.organization_id = :organizationId
               AND claim.product_variant_id = ANY (:permittedProductVariantIds)
               AND (CAST(:productVariantId AS uuid) IS NULL
                    OR claim.product_variant_id = CAST(:productVariantId AS uuid))
               AND (CAST(:status AS text) IS NULL OR version.business_status = :status)
            """;

    /** One attested state to append. */
    public record InboundVersion(
            UUID id, UUID attestationId, UUID organizationId, int versionNo, int quantity,
            Instant expectedArrivalFrom, Instant expectedArrivalTo, String businessStatus,
            String changeKind, String evidenceReference, Instant sourceTime,
            Instant lastVerifiedAt, UUID attestedByUserId, String reason,
            UUID supersedesVersionId, Instant recordedAt) {
    }

    public record CurrentAttestation(UUID id, UUID organizationId, UUID productVariantId,
                                     String externalReference, UUID versionId, int versionNo,
                                     int quantity, Instant expectedArrivalFrom,
                                     Instant expectedArrivalTo, String businessStatus,
                                     String evidenceReference, Instant lastVerifiedAt) {
    }

    /** One claim at its current version, with the variant it is for. */
    public record CurrentListRow(UUID id, UUID productVariantId, String skuCode,
                                 String displayName, String externalReference, UUID versionId,
                                 int versionNo, int quantity, Instant expectedArrivalFrom,
                                 Instant expectedArrivalTo, String businessStatus,
                                 String changeKind, String evidenceReference, Instant sourceTime,
                                 Instant lastVerifiedAt, Instant recordedAt, String reason,
                                 UUID attestedByUserId) {
    }
}
