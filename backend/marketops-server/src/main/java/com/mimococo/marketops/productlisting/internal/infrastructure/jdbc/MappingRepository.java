package com.mimococo.marketops.productlisting.internal.infrastructure.jdbc;

import com.mimococo.marketops.productlisting.ListingVariantContext;
import com.mimococo.marketops.productlisting.internal.domain.CandidateState;
import com.mimococo.marketops.productlisting.internal.domain.ConflictKind;
import com.mimococo.marketops.productlisting.internal.domain.ConflictState;
import com.mimococo.marketops.productlisting.internal.domain.ListingMapping;
import com.mimococo.marketops.productlisting.internal.domain.MappingCandidate;
import com.mimococo.marketops.productlisting.internal.domain.MappingConflict;
import com.mimococo.marketops.productlisting.internal.domain.MappingStatus;
import com.mimococo.marketops.productlisting.internal.domain.MatchMethod;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to mapping proposals, confirmed mappings and conflicts. */
@Repository
public class MappingRepository {

    private final JdbcClient jdbc;

    MappingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record a proposal, ignoring one that is already open for the same pair.
     *
     * <p>Re-running the matcher must not grow the review queue. The conflict
     * clause is on the partial index over open proposals, so a proposal that was
     * rejected earlier does become a new row and the rejection stays readable.
     */
    public void proposeIfAbsent(MappingCandidate candidate) {
        jdbc.sql("""
                        INSERT INTO core.listing_mapping_candidate (
                            id, organization_id, platform_listing_variant_id,
                            product_variant_id, match_method, confidence, evidence_note,
                            state, decided_by_user_id, decided_at, decision_reason,
                            created_at, updated_at, version)
                        VALUES (:id, :organizationId, :listingVariantId,
                            :productVariantId, :matchMethod, :confidence, :evidenceNote,
                            :state, NULL, NULL, NULL, :createdAt, :updatedAt, 0)
                        ON CONFLICT (platform_listing_variant_id, product_variant_id)
                            WHERE state = 'PROPOSED'
                        DO NOTHING
                        """)
                .param("id", candidate.id())
                .param("organizationId", candidate.organizationId())
                .param("listingVariantId", candidate.platformListingVariantId())
                .param("productVariantId", candidate.productVariantId())
                .param("matchMethod", candidate.matchMethod().name())
                .param("confidence", candidate.confidence())
                .param("evidenceNote", candidate.evidenceNote())
                .param("state", CandidateState.PROPOSED.name())
                .param("createdAt", Timestamp.from(candidate.createdAt()))
                .param("updatedAt", Timestamp.from(candidate.updatedAt()))
                .update();
    }

    /** Record a decision on a proposal; false means the version was stale. */
    public boolean decideCandidate(UUID id,
                                   CandidateState state,
                                   UUID decidedByUserId,
                                   Instant decidedAt,
                                   String reason,
                                   long expectedVersion) {
        return jdbc.sql("""
                        UPDATE core.listing_mapping_candidate
                        SET state = :state, decided_by_user_id = :userId,
                            decided_at = :decidedAt, decision_reason = :reason,
                            updated_at = :decidedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion AND state = 'PROPOSED'
                        """)
                .param("state", state.name())
                .param("userId", decidedByUserId)
                .param("decidedAt", Timestamp.from(decidedAt))
                .param("reason", reason)
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Load one proposal. */
    public Optional<MappingCandidate> findCandidate(UUID id) {
        return jdbc.sql("SELECT * FROM core.listing_mapping_candidate WHERE id = :id")
                .param("id", id)
                .query(MappingRepository::mapCandidate)
                .optional();
    }

    /** Open proposals for one listing variant, strongest method first. */
    public List<MappingCandidate> openCandidatesFor(UUID platformListingVariantId) {
        return jdbc.sql("""
                        SELECT * FROM core.listing_mapping_candidate
                        WHERE platform_listing_variant_id = :listingVariantId
                          AND state = 'PROPOSED'
                        ORDER BY confidence DESC, id
                        """)
                .param("listingVariantId", platformListingVariantId)
                .query(MappingRepository::mapCandidate)
                .list();
    }

    /** The organization's open review queue, strongest proposals first. */
    public List<MappingCandidate> openCandidateQueue(UUID organizationId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM core.listing_mapping_candidate
                        WHERE organization_id = :organizationId AND state = 'PROPOSED'
                        ORDER BY confidence DESC, created_at, id
                        LIMIT :pageLimit
                        """)
                .param("organizationId", organizationId)
                .param("pageLimit", limit)
                .query(MappingRepository::mapCandidate)
                .list();
    }

    /** Record a confirmed mapping interval. */
    public void insertMapping(ListingMapping mapping) {
        jdbc.sql("""
                        INSERT INTO core.listing_mapping (
                            id, organization_id, platform_listing_variant_id,
                            product_variant_id, source_candidate_id, effective_from,
                            effective_to, status, confirmed_by_user_id, reason,
                            created_at, updated_at, version)
                        VALUES (:id, :organizationId, :listingVariantId,
                            :productVariantId, :sourceCandidateId, :effectiveFrom,
                            :effectiveTo, :status, :userId, :reason,
                            :createdAt, :updatedAt, :version)
                        """)
                .param("id", mapping.id())
                .param("organizationId", mapping.organizationId())
                .param("listingVariantId", mapping.platformListingVariantId())
                .param("productVariantId", mapping.productVariantId())
                .param("sourceCandidateId", mapping.sourceCandidateId())
                .param("effectiveFrom", Timestamp.from(mapping.effectiveFrom()))
                .param("effectiveTo",
                        mapping.effectiveTo() == null ? null : Timestamp.from(mapping.effectiveTo()))
                .param("status", mapping.status().name())
                .param("userId", mapping.confirmedByUserId())
                .param("reason", mapping.reason())
                .param("createdAt", Timestamp.from(mapping.createdAt()))
                .param("updatedAt", Timestamp.from(mapping.updatedAt()))
                .param("version", mapping.version())
                .update();
    }

    /**
     * Close the open mapping of one listing variant at an instant.
     *
     * <p>Ending the previous interval before opening the next is what keeps the
     * exclusion constraint satisfiable; the constraint is the reason a mapping
     * correction cannot leave two overlapping answers behind.
     */
    public boolean endOpenMapping(UUID platformListingVariantId, Instant at, String reason) {
        return jdbc.sql("""
                        UPDATE core.listing_mapping
                        SET status = 'ENDED', effective_to = :at, reason = :reason,
                            updated_at = :at, version = version + 1
                        WHERE platform_listing_variant_id = :listingVariantId
                          AND status = 'ACTIVE'
                          AND effective_to IS NULL
                        """)
                .param("at", Timestamp.from(at))
                .param("reason", reason)
                .param("listingVariantId", platformListingVariantId)
                .update() > 0;
    }

    /** The internal variant a listing variant resolved to at an instant. */
    public Optional<UUID> resolveAt(UUID platformListingVariantId, Instant at) {
        return jdbc.sql("""
                        SELECT product_variant_id FROM core.listing_mapping
                        WHERE platform_listing_variant_id = :listingVariantId
                          AND status = 'ACTIVE'
                          AND effective_from <= :at
                          AND (effective_to IS NULL OR effective_to > :at)
                        """)
                .param("listingVariantId", platformListingVariantId)
                .param("at", Timestamp.from(at))
                .query(UUID.class)
                .optional();
    }

    /** Resolve many listing variants at one instant, omitting the unresolved. */
    public Map<UUID, UUID> resolveManyAt(Collection<UUID> platformListingVariantIds, Instant at) {
        if (platformListingVariantIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> resolved = new HashMap<>();
        jdbc.sql("""
                        SELECT platform_listing_variant_id, product_variant_id
                          FROM core.listing_mapping
                         WHERE platform_listing_variant_id = ANY (:listingVariantIds)
                           AND status = 'ACTIVE'
                           AND effective_from <= :at
                           AND (effective_to IS NULL OR effective_to > :at)
                        """)
                .param("listingVariantIds", platformListingVariantIds.toArray(UUID[]::new))
                .param("at", Timestamp.from(at))
                .query((rows, rowNumber) -> resolved.put(
                        rows.getObject("platform_listing_variant_id", UUID.class),
                        rows.getObject("product_variant_id", UUID.class)))
                .list();
        return Map.copyOf(resolved);
    }

    /** The mapping history of one listing variant, newest interval first. */
    public List<ListingMapping> mappingHistory(UUID platformListingVariantId) {
        return jdbc.sql("""
                        SELECT * FROM core.listing_mapping
                        WHERE platform_listing_variant_id = :listingVariantId
                        ORDER BY effective_from DESC, id
                        """)
                .param("listingVariantId", platformListingVariantId)
                .query(MappingRepository::mapMapping)
                .list();
    }

    /**
     * Open a conflict, refreshing the existing one of the same kind.
     *
     * <p>Repeated detection must not grow an unbounded queue of one problem, so
     * the detection time and detail are refreshed instead. The row keeps its
     * identity, which is what an operator's link to it depends on.
     */
    public void openConflict(MappingConflict conflict) {
        jdbc.sql("""
                        INSERT INTO core.mapping_conflict (
                            id, organization_id, platform_listing_variant_id, conflict_kind,
                            detail, state, detected_at, resolved_by_user_id, resolved_at,
                            resolution_reason, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :listingVariantId, :conflictKind,
                            :detail, 'OPEN', :detectedAt, NULL, NULL, NULL,
                            :detectedAt, :detectedAt, 0)
                        ON CONFLICT (platform_listing_variant_id, conflict_kind)
                            WHERE state = 'OPEN'
                        DO UPDATE
                        SET detail = EXCLUDED.detail,
                            detected_at = EXCLUDED.detected_at,
                            updated_at = EXCLUDED.updated_at,
                            version = core.mapping_conflict.version + 1
                        """)
                .param("id", conflict.id())
                .param("organizationId", conflict.organizationId())
                .param("listingVariantId", conflict.platformListingVariantId())
                .param("conflictKind", conflict.conflictKind().name())
                .param("detail", conflict.detail())
                .param("detectedAt", Timestamp.from(conflict.detectedAt()))
                .update();
    }

    /** Close a conflict; false means the version was stale or it was already closed. */
    public boolean closeConflict(UUID id,
                                 ConflictState state,
                                 UUID resolvedByUserId,
                                 Instant resolvedAt,
                                 String reason,
                                 long expectedVersion) {
        return jdbc.sql("""
                        UPDATE core.mapping_conflict
                        SET state = :state, resolved_by_user_id = :userId,
                            resolved_at = :resolvedAt, resolution_reason = :reason,
                            updated_at = :resolvedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion AND state = 'OPEN'
                        """)
                .param("state", state.name())
                .param("userId", resolvedByUserId)
                .param("resolvedAt", Timestamp.from(resolvedAt))
                .param("reason", reason)
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Close every open conflict of one listing variant. */
    public void closeConflictsFor(UUID platformListingVariantId,
                                  UUID resolvedByUserId,
                                  Instant resolvedAt,
                                  String reason) {
        jdbc.sql("""
                        UPDATE core.mapping_conflict
                        SET state = 'RESOLVED', resolved_by_user_id = :userId,
                            resolved_at = :resolvedAt, resolution_reason = :reason,
                            updated_at = :resolvedAt, version = version + 1
                        WHERE platform_listing_variant_id = :listingVariantId AND state = 'OPEN'
                        """)
                .param("userId", resolvedByUserId)
                .param("resolvedAt", Timestamp.from(resolvedAt))
                .param("reason", reason)
                .param("listingVariantId", platformListingVariantId)
                .update();
    }

    /** Load one conflict. */
    public Optional<MappingConflict> findConflict(UUID id) {
        return jdbc.sql("SELECT * FROM core.mapping_conflict WHERE id = :id")
                .param("id", id)
                .query(MappingRepository::mapConflict)
                .optional();
    }

    /** Whether an unresolved conflict currently blocks a listing variant. */
    public boolean hasOpenConflict(UUID platformListingVariantId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM core.mapping_conflict
                            WHERE platform_listing_variant_id = :listingVariantId
                              AND state = 'OPEN')
                        """)
                .param("listingVariantId", platformListingVariantId)
                .query(Boolean.class)
                .single());
    }

    /**
     * Where one listing variant sits and what it maps to at an instant.
     *
     * <p>One query rather than three, so the store, the platform and the mapping
     * describe the same instant. Two lookups a moment apart could disagree if a
     * mapping were confirmed between them.
     */
    public Optional<ListingVariantContext> variantContext(UUID platformListingVariantId,
                                                          Instant at) {
        return jdbc.sql("""
                        SELECT variant.id AS listing_variant_id,
                               listing.id AS listing_id, listing.store_id,
                               listing.marketplace_account_id, listing.platform_code,
                               listing.native_listing_key, variant.native_variant_key,
                               mapping.product_variant_id,
                               EXISTS (
                                   SELECT 1 FROM core.mapping_conflict AS conflict
                                    WHERE conflict.platform_listing_variant_id = variant.id
                                      AND conflict.state = 'OPEN') AS conflict_open
                          FROM core.platform_listing_variant AS variant
                          JOIN core.platform_listing AS listing
                            ON listing.id = variant.platform_listing_id
                          LEFT JOIN core.listing_mapping AS mapping
                            ON mapping.platform_listing_variant_id = variant.id
                           AND mapping.status = 'ACTIVE'
                           AND mapping.effective_from <= :at
                           AND (mapping.effective_to IS NULL OR mapping.effective_to > :at)
                         WHERE variant.id = :listingVariantId
                        """)
                .param("listingVariantId", platformListingVariantId)
                .param("at", Timestamp.from(at))
                .query((rows, rowNumber) -> new ListingVariantContext(
                        rows.getObject("listing_variant_id", UUID.class),
                        rows.getObject("listing_id", UUID.class),
                        rows.getObject("store_id", UUID.class),
                        rows.getObject("marketplace_account_id", UUID.class),
                        rows.getString("platform_code"),
                        rows.getString("native_listing_key"),
                        rows.getString("native_variant_key"),
                        rows.getObject("product_variant_id", UUID.class),
                        rows.getBoolean("conflict_open")))
                .optional();
    }

    /** The organization's open conflict queue, newest detection first. */
    public List<MappingConflict> openConflictQueue(UUID organizationId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM core.mapping_conflict
                        WHERE organization_id = :organizationId AND state = 'OPEN'
                        ORDER BY detected_at DESC, id
                        LIMIT :pageLimit
                        """)
                .param("organizationId", organizationId)
                .param("pageLimit", limit)
                .query(MappingRepository::mapConflict)
                .list();
    }

    private static MappingCandidate mapCandidate(ResultSet rows, int rowNumber)
            throws SQLException {
        Timestamp decidedAt = rows.getTimestamp("decided_at");
        return new MappingCandidate(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("platform_listing_variant_id", UUID.class),
                rows.getObject("product_variant_id", UUID.class),
                MatchMethod.valueOf(rows.getString("match_method")),
                rows.getBigDecimal("confidence"),
                rows.getString("evidence_note"),
                CandidateState.valueOf(rows.getString("state")),
                rows.getObject("decided_by_user_id", UUID.class),
                decidedAt == null ? null : decidedAt.toInstant(),
                rows.getString("decision_reason"),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getLong("version"));
    }

    private static ListingMapping mapMapping(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp effectiveTo = rows.getTimestamp("effective_to");
        return new ListingMapping(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("platform_listing_variant_id", UUID.class),
                rows.getObject("product_variant_id", UUID.class),
                rows.getObject("source_candidate_id", UUID.class),
                rows.getTimestamp("effective_from").toInstant(),
                effectiveTo == null ? null : effectiveTo.toInstant(),
                MappingStatus.valueOf(rows.getString("status")),
                rows.getObject("confirmed_by_user_id", UUID.class),
                rows.getString("reason"),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getLong("version"));
    }

    private static MappingConflict mapConflict(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp resolvedAt = rows.getTimestamp("resolved_at");
        return new MappingConflict(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("platform_listing_variant_id", UUID.class),
                ConflictKind.valueOf(rows.getString("conflict_kind")),
                rows.getString("detail"),
                ConflictState.valueOf(rows.getString("state")),
                rows.getTimestamp("detected_at").toInstant(),
                rows.getObject("resolved_by_user_id", UUID.class),
                resolvedAt == null ? null : resolvedAt.toInstant(),
                rows.getString("resolution_reason"),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getLong("version"));
    }
}
