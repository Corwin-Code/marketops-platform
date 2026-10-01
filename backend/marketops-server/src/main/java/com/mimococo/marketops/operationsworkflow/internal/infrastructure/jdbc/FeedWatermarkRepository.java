package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The freshness watermarks the price guardrail reads, what they rest on, and the Owner's statements
 * that a store has no data in a feed it does not collect.
 */
@Repository
public class FeedWatermarkRepository {

    private final JdbcClient jdbc;

    FeedWatermarkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The marketplace account and platform a store belongs to, which every watermark names. */
    public Optional<StoreScope> scope(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        SELECT store.marketplace_account_id, account.platform_code
                          FROM core.store AS store
                          JOIN core.marketplace_account AS account ON account.id = store.marketplace_account_id
                         WHERE store.id = :storeId AND store.organization_id = :organizationId
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query((rows, rowNumber) -> new StoreScope(organizationId, storeId,
                        rows.getObject("marketplace_account_id", UUID.class), rows.getString("platform_code")))
                .optional();
    }

    /**
     * The store's newest scheduled collection of a dataset that succeeded and was normalized without
     * stopping, with the window its run read; optionally only one after which the master-data
     * automation ran.
     */
    public Optional<Collected> latestCollected(UUID storeId, String datasetKind, boolean automationRan) {
        return jdbc.sql("""
                        SELECT event.ingestion_run_id, event.occurred_at, run.window_to
                          FROM ops.scheduled_collection_event AS event
                          JOIN ops.ingestion_run AS run ON run.id = event.ingestion_run_id
                         WHERE event.store_id = :storeId AND event.event_kind = 'COLLECTED'
                           AND event.dataset_kind = :datasetKind
                           AND event.detail ->> 'state' = 'SUCCEEDED'
                           AND coalesce(event.detail ->> 'normalization', '') <> 'FAILED'
                           AND (NOT :automationRan OR event.detail ->> 'masterDataAutomation' = 'RAN')
                           AND NOT EXISTS (
                               SELECT 1 FROM ops.scheduled_collection_event AS stopped
                                WHERE stopped.ingestion_run_id = event.ingestion_run_id
                                  AND stopped.event_kind = 'NORMALIZATION_STOPPED')
                         ORDER BY event.occurred_at DESC, event.id DESC
                         LIMIT 1
                        """)
                .param("storeId", storeId)
                .param("datasetKind", datasetKind)
                .param("automationRan", automationRan)
                .query((rows, rowNumber) -> new Collected(rows.getObject("ingestion_run_id", UUID.class),
                        instant(rows, "occurred_at"), instant(rows, "window_to")))
                .optional();
    }

    /** The store's newest VERIFIED watermark of a feed, or empty without one. */
    public Optional<Watermark> latest(StoreScope scope, String feedCode) {
        return jdbc.sql("""
                        SELECT watermark.id, watermark.feed_code, watermark.source_updated_at, watermark.ingested_at,
                               watermark.reconciled_at, watermark.evidence_reference, watermark.recorded_at
                          FROM core.source_feed_watermark AS watermark
                         WHERE watermark.organization_id = :organizationId AND watermark.platform_code = :platformCode
                           AND watermark.marketplace_account_id = :accountId AND watermark.store_id = :storeId
                           AND watermark.feed_code = :feedCode AND watermark.verification_state = 'VERIFIED'
                         ORDER BY watermark.recorded_at DESC, watermark.id DESC
                         LIMIT 1
                        """)
                .param("organizationId", scope.organizationId())
                .param("platformCode", scope.platformCode())
                .param("accountId", scope.marketplaceAccountId())
                .param("storeId", scope.storeId())
                .param("feedCode", feedCode)
                .query((rows, rowNumber) -> new Watermark(rows.getObject("id", UUID.class),
                        rows.getString("feed_code"), instant(rows, "source_updated_at"), instant(rows, "ingested_at"),
                        instant(rows, "reconciled_at"), rows.getString("evidence_reference"),
                        instant(rows, "recorded_at")))
                .optional();
    }

    /** Record one VERIFIED watermark. */
    public void insert(UUID id, StoreScope scope, String feedCode, Instant sourceUpdatedAt, Instant ingestedAt,
                       Instant reconciledAt, String evidenceReference, Instant recordedAt) {
        jdbc.sql("""
                        INSERT INTO core.source_feed_watermark (
                            id, organization_id, platform_code, marketplace_account_id, store_id, feed_code,
                            source_updated_at, ingested_at, reconciled_at, evidence_reference, verification_state,
                            recorded_at)
                        VALUES (:id, :organizationId, :platformCode, :accountId, :storeId, :feedCode,
                            :sourceUpdatedAt, :ingestedAt, :reconciledAt, :evidence, 'VERIFIED', :recordedAt)
                        """)
                .param("id", id)
                .param("organizationId", scope.organizationId())
                .param("platformCode", scope.platformCode())
                .param("accountId", scope.marketplaceAccountId())
                .param("storeId", scope.storeId())
                .param("feedCode", feedCode)
                .param("sourceUpdatedAt", timestamp(sourceUpdatedAt))
                .param("ingestedAt", Timestamp.from(ingestedAt))
                .param("reconciledAt", timestamp(reconciledAt))
                .param("evidence", evidenceReference)
                .param("recordedAt", Timestamp.from(recordedAt))
                .update();
    }

    /**
     * The finance inputs in force for the store, organization-wide or for the store itself, of the
     * codes asked for: the most specific of each code, as the metric engine resolves them.
     */
    public List<FinanceInput> financeInputsInForce(UUID organizationId, UUID storeId, List<String> inputCodes,
                                                   Instant at) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (input.input_code) input.id, input.input_code, input.effective_from
                          FROM core.finance_input_version AS input
                         WHERE input.organization_id = :organizationId
                           AND input.input_code IN (:inputCodes)
                           AND input.status = 'ACTIVE'
                           AND input.effective_from < :at
                           AND (input.effective_to IS NULL OR input.effective_to > :at)
                           AND (input.scope_kind = 'ORGANIZATION'
                                OR (input.scope_kind = 'STORE' AND input.store_ref_id = :storeId))
                         ORDER BY input.input_code, CASE input.scope_kind WHEN 'STORE' THEN 0 ELSE 1 END,
                                  input.effective_from DESC
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("inputCodes", inputCodes)
                .param("at", Timestamp.from(at))
                .query((rows, rowNumber) -> new FinanceInput(rows.getObject("id", UUID.class),
                        rows.getString("input_code"), instant(rows, "effective_from")))
                .list();
    }

    /** Whether any listing of the store had an ordered unit in a period ending after an instant. */
    public boolean orderedSince(UUID organizationId, UUID storeId, Instant since) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                              FROM core.listing_traffic_observation AS traffic
                              JOIN core.platform_listing_variant AS variant
                                ON variant.id = traffic.platform_listing_variant_id
                              JOIN core.platform_listing AS listing ON listing.id = variant.platform_listing_id
                             WHERE listing.organization_id = :organizationId AND listing.store_id = :storeId
                               AND traffic.period_end > :since AND traffic.ordered_units > 0)
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("since", Timestamp.from(since))
                .query(Boolean.class)
                .single());
    }

    /** Record one attestation. */
    public void insertAttestation(Attestation attestation) {
        jdbc.sql("""
                        INSERT INTO ops.feed_absence_attestation (
                            id, organization_id, store_id, feed_code, statement, attested_by_user_id, attested_at,
                            expires_at, lapses_on_orders)
                        VALUES (:id, :organizationId, :storeId, :feedCode, :statement, :attestedBy, :attestedAt,
                            :expiresAt, :lapsesOnOrders)
                        """)
                .param("id", attestation.id())
                .param("organizationId", attestation.organizationId())
                .param("storeId", attestation.storeId())
                .param("feedCode", attestation.feedCode())
                .param("statement", attestation.statement())
                .param("attestedBy", attestation.attestedByUserId())
                .param("attestedAt", Timestamp.from(attestation.attestedAt()))
                .param("expiresAt", Timestamp.from(attestation.expiresAt()))
                .param("lapsesOnOrders", attestation.lapsesOnOrders())
                .update();
    }

    /** The store's attestations, newest first. */
    public List<Attestation> attestations(UUID organizationId, UUID storeId, int limit) {
        return jdbc.sql("""
                        SELECT attestation.*
                          FROM ops.feed_absence_attestation AS attestation
                         WHERE attestation.organization_id = :organizationId AND attestation.store_id = :storeId
                         ORDER BY attestation.attested_at DESC, attestation.id
                         LIMIT :limit
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("limit", limit)
                .query((rows, rowNumber) -> attestation(rows))
                .list();
    }

    /** One of the store's attestations, or empty. */
    public Optional<Attestation> attestation(UUID organizationId, UUID storeId, UUID id) {
        return jdbc.sql("""
                        SELECT attestation.*
                          FROM ops.feed_absence_attestation AS attestation
                         WHERE attestation.organization_id = :organizationId AND attestation.store_id = :storeId
                           AND attestation.id = :id
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("id", id)
                .query((rows, rowNumber) -> attestation(rows))
                .optional();
    }

    /** End an attestation because ordered units appeared; false when it already ended. */
    public boolean lapse(UUID id, Instant at, String reason) {
        return jdbc.sql("""
                        UPDATE ops.feed_absence_attestation
                           SET lapsed_at = :at, lapse_reason = :reason
                         WHERE id = :id AND lapsed_at IS NULL AND revoked_at IS NULL AND lapses_on_orders
                        """)
                .param("id", id)
                .param("at", Timestamp.from(at))
                .param("reason", reason)
                .update() == 1;
    }

    /** Revoke an attestation; false when it already ended. */
    public boolean revoke(UUID id, UUID revokedBy, Instant at, String reason) {
        return jdbc.sql("""
                        UPDATE ops.feed_absence_attestation
                           SET revoked_at = :at, revoked_by_user_id = :revokedBy, revocation_reason = :reason
                         WHERE id = :id AND revoked_at IS NULL AND lapsed_at IS NULL AND expires_at > :at
                        """)
                .param("id", id)
                .param("at", Timestamp.from(at))
                .param("revokedBy", revokedBy)
                .param("reason", reason)
                .update() == 1;
    }

    private static Attestation attestation(ResultSet rows) throws SQLException {
        return new Attestation(rows.getObject("id", UUID.class), rows.getObject("organization_id", UUID.class),
                rows.getObject("store_id", UUID.class), rows.getString("feed_code"), rows.getString("statement"),
                rows.getObject("attested_by_user_id", UUID.class), instant(rows, "attested_at"),
                instant(rows, "expires_at"), rows.getBoolean("lapses_on_orders"), instant(rows, "lapsed_at"),
                rows.getString("lapse_reason"), instant(rows, "revoked_at"), rows.getString("revocation_reason"));
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** The scope every watermark of a store names. */
    public record StoreScope(UUID organizationId, UUID storeId, UUID marketplaceAccountId, String platformCode) {
    }

    /**
     * One collection a watermark may rest on.
     *
     * @param windowTo the end of the window the run read, or {@code null} for a snapshot
     */
    public record Collected(UUID runId, Instant collectedAt, Instant windowTo) {
    }

    /** One recorded watermark. */
    public record Watermark(UUID id, String feedCode, Instant sourceUpdatedAt, Instant ingestedAt,
                            Instant reconciledAt, String evidenceReference, Instant recordedAt) {

        /** The instant the guardrail measures the feed's age from. */
        public Instant effectiveAt() {
            return reconciledAt != null ? reconciledAt : sourceUpdatedAt != null ? sourceUpdatedAt : ingestedAt;
        }
    }

    /** One finance input in force. */
    public record FinanceInput(UUID id, String inputCode, Instant effectiveFrom) {
    }

    /** The Owner's statement that a store has no data in one feed. */
    public record Attestation(UUID id, UUID organizationId, UUID storeId, String feedCode, String statement,
                              UUID attestedByUserId, Instant attestedAt, Instant expiresAt, boolean lapsesOnOrders,
                              Instant lapsedAt, String lapseReason, Instant revokedAt, String revocationReason) {

        /** Whether the statement still stands at an instant. */
        public boolean standsAt(Instant at) {
            return lapsedAt == null && revokedAt == null && expiresAt.isAfter(at);
        }
    }
}
