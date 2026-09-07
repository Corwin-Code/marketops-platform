package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthHeaderSpec;
import com.mimococo.marketops.marketplaceintegration.internal.domain.AuthValueSource;
import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The recorded facts an outbound platform call is built from.
 *
 * <p>Every query filters on verification and status inside the database rather
 * than in the caller. An endpoint that has not been verified, a platform profile
 * that has been retired and an authentication header nobody has checked are all
 * invisible here, so an adapter that finds nothing cannot proceed by accident —
 * it has nothing to proceed with.
 */
@Repository
public class PlatformCallSpecRepository {

    private final JdbcClient jdbc;

    PlatformCallSpecRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The call specification for one endpoint, when everything it needs is
     * verified.
     *
     * <p>An empty result is the fail-closed answer and covers every reason at
     * once: the endpoint does not exist, is retired, is unverified, has no
     * recorded method or path, or its platform has no active profile.
     */
    public Optional<EndpointCallSpec> findVerifiedSpec(UUID endpointId) {
        return jdbc.sql("""
                        SELECT endpoint.id, endpoint.platform_code, endpoint.endpoint_code,
                               profile.base_url, endpoint.http_method, endpoint.path_template,
                               endpoint.query_template, endpoint.body_template,
                               endpoint.response_content_type, endpoint.continuation_pointer,
                               endpoint.pagination_model,
                               endpoint.rate_limit_per_minute, profile.request_timeout_ms,
                               profile.max_response_bytes
                          FROM platform.platform_endpoint AS endpoint
                          JOIN platform.platform_api_profile AS profile
                            ON profile.platform_code = endpoint.platform_code
                         WHERE endpoint.id = :endpointId
                           AND endpoint.status = 'ACTIVE'
                           AND endpoint.deprecated_at IS NULL
                           AND endpoint.verification_state = 'VERIFIED'
                           AND endpoint.http_method IS NOT NULL
                           AND endpoint.path_template IS NOT NULL
                           AND profile.status = 'ACTIVE'
                           AND profile.verification_state = 'VERIFIED'
                        """)
                .param("endpointId", endpointId)
                .query(PlatformCallSpecRepository::mapSpec)
                .optional();
    }

    /** The verified authentication headers of one platform, in recorded order. */
    public List<AuthHeaderSpec> verifiedAuthHeaders(String platformCode, String credentialPurpose) {
        return jdbc.sql("""
                        SELECT header_name, value_source, value_template,
                               credential_purpose, ordinal
                          FROM platform.platform_auth_header
                         WHERE platform_code = :platformCode
                           AND credential_purpose = :credentialPurpose
                           AND status = 'ACTIVE'
                           AND verification_state = 'VERIFIED'
                         ORDER BY ordinal, header_name
                        """)
                .param("platformCode", platformCode)
                .param("credentialPurpose", credentialPurpose)
                .query((rows, rowNumber) -> new AuthHeaderSpec(
                        rows.getString("header_name"),
                        AuthValueSource.valueOf(rows.getString("value_source")),
                        rows.getString("value_template"),
                        rows.getString("credential_purpose"),
                        rows.getInt("ordinal")))
                .list();
    }

    /**
     * The opaque secret reference of one credential, when the credential is
     * currently valid for the purpose the header requires.
     *
     * <p>Validity is evaluated here against the database clock. A credential
     * whose window has passed is invisible, so an expired credential cannot be
     * used by a caller that read it a moment before it lapsed.
     */
    public Optional<String> activeSecretReference(UUID credentialId, String credentialPurpose) {
        return jdbc.sql("""
                        SELECT secret_reference FROM platform.credential_metadata
                         WHERE id = :credentialId
                           AND purpose_code = :credentialPurpose
                           AND status = 'ACTIVE'
                           AND effective_from <= clock_timestamp()
                           AND expires_at > clock_timestamp()
                        """)
                .param("credentialId", credentialId)
                .param("credentialPurpose", credentialPurpose)
                .query(String.class)
                .optional();
    }

    /** The marketplace account's own identifier for one credential's account. */
    public Optional<String> accountNativeKey(UUID credentialId) {
        return jdbc.sql("""
                        SELECT account.native_account_key
                          FROM platform.credential_metadata AS credential
                          JOIN core.marketplace_account AS account
                            ON account.id = credential.marketplace_account_id
                         WHERE credential.id = :credentialId
                           AND account.native_account_key IS NOT NULL
                        """)
                .param("credentialId", credentialId)
                .query(String.class)
                .optional();
    }

    /** The acquisition cursor of one job, when it holds a position. */
    public Optional<String> checkpointPosition(UUID jobId) {
        return jdbc.sql("""
                        SELECT position_value FROM ops.ingestion_checkpoint
                         WHERE job_id = :jobId AND position_value IS NOT NULL
                        """)
                .param("jobId", jobId)
                .query(String.class)
                .optional();
    }

    /** Atomically consumes the deployment-wide endpoint quota; an absent limit denies. */
    public boolean reserveCallBudget(UUID endpointId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT platform.reserve_endpoint_quota(:id)")
                .param("id", endpointId).query(Boolean.class).single());
    }

    public Optional<String> acquisitionEvidenceDigest(UUID endpointId,UUID credentialId) {
        return jdbc.sql("""
                SELECT encode(sha256(convert_to(platform.registry_configuration_snapshot(endpoint.capability_id)::text,'UTF8')),'hex')
                    FROM platform.platform_endpoint endpoint
                    JOIN platform.credential_metadata credential ON credential.id=:credential
                    JOIN core.marketplace_account account ON account.id=credential.marketplace_account_id
                    WHERE endpoint.id=:endpoint AND endpoint.platform_code=account.platform_code
                      AND endpoint.operation_function='READ_DATA' AND credential.purpose_code='READ'
                      AND credential.status='ACTIVE' AND credential.effective_from<=clock_timestamp() AND credential.expires_at>clock_timestamp()
                      AND account.status='ACTIVE'
                      AND platform.capability_evidence_current(account.id,endpoint.capability_id,endpoint.id)
                """).param("endpoint",endpointId).param("credential",credentialId).query(String.class).optional();
    }

    /**
     * A durable intent must match both the recorded configuration and the approved command.
     * The caller supplies the attempt digest, so digest equality alone cannot authorize
     * its target, native identifiers, idempotency key or asynchronous task handle.
     */
    public boolean priceAttemptCurrent(com.mimococo.marketops.marketplaceintegration.port.PriceWriteRequest request) {
        if (request.attemptId() == null || request.credentialId() == null) return false;
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM ops.price_command_attempt a
                    JOIN ops.price_command c ON c.id=a.command_id
                    JOIN core.store store ON store.id=c.store_id
                    JOIN core.platform_listing_variant variant ON variant.id=c.platform_listing_variant_id
                    JOIN core.platform_listing listing ON listing.id=variant.platform_listing_id
                    JOIN platform.credential_metadata credential ON credential.marketplace_account_id=store.marketplace_account_id
                    WHERE a.id=:attempt AND a.request_digest=:digest AND a.outcome_class='IN_FLIGHT'
                      AND a.purpose=:purpose AND c.capability_id=:capability
                      AND listing.native_listing_key=:listing AND variant.native_variant_key=:variant
                      AND :idempotency=CASE WHEN a.purpose='RESTORE'
                          THEN encode(sha256(convert_to(c.idempotency_key||chr(31)||'RESTORE'||chr(31),'UTF8')),'hex')
                          ELSE c.idempotency_key END
                      AND c.currency_code=:currency
                      AND :price=CASE WHEN a.purpose='RESTORE' THEN c.prior_price ELSE c.target_price END
                      AND CASE WHEN a.purpose='STATUS_ENQUIRY'
                          THEN CAST(:task AS text)=(SELECT prior.native_task_key FROM ops.price_command_attempt prior
                              WHERE prior.command_id=c.id AND prior.native_task_key IS NOT NULL
                              ORDER BY prior.started_at DESC,prior.attempt_no DESC LIMIT 1)
                          ELSE CAST(:task AS text) IS NULL END
                      AND c.fence_token=a.fence_token AND c.lease_owner=a.lease_owner
                      AND c.lease_expires_at > clock_timestamp()
                      AND a.expected_version_token IS NOT DISTINCT FROM CAST(:precondition AS text)
                      AND (a.purpose<>'RESTORE' OR (c.state='COMPENSATION_PENDING' AND EXISTS (
                          SELECT 1 FROM ops.price_command_readback r
                          JOIN ops.price_command_attempt read_attempt ON read_attempt.id=r.attempt_id
                          JOIN raw.price_response_observation evidence ON evidence.id=r.raw_observation_id
                          WHERE r.command_id=c.id AND r.match_state='MATCHES_TARGET'
                            AND read_attempt.purpose='READBACK' AND read_attempt.fence_token=c.fence_token
                            AND evidence.version_token=a.expected_version_token
                            AND r.observed_at>=c.updated_at
                            AND r.observed_at>clock_timestamp()-interval '30 seconds'
                            AND r.id=(SELECT latest.id FROM ops.price_command_readback latest
                                WHERE latest.command_id=c.id ORDER BY latest.observed_at DESC,latest.id DESC LIMIT 1)
                            AND EXISTS (SELECT 1 FROM ops.price_command_attempt applied
                                WHERE applied.command_id=c.id AND applied.purpose='APPLY'
                                  AND applied.outcome_class IN ('ACCEPTED','UNKNOWN_STATE')
                                  AND applied.completed_at<=r.observed_at))))
                      AND a.operation_snapshot=platform.price_operation_snapshot(c.capability_id,a.purpose)
                      AND platform.capability_evidence_current(store.marketplace_account_id,c.capability_id,
                          (a.operation_snapshot #>> '{operation,endpoint_id}')::uuid)
                      AND credential.id=:credential AND credential.organization_id=c.organization_id
                      AND credential.purpose_code='PRICE_WRITE' AND credential.status='ACTIVE'
                      AND credential.effective_from<=clock_timestamp() AND credential.expires_at>clock_timestamp()
                      AND (credential.scope_mode='ACCOUNT' OR EXISTS (SELECT 1 FROM platform.credential_store_scope scope
                          WHERE scope.credential_id=credential.id AND scope.store_id=store.id AND scope.status='ACTIVE'))
                      AND (a.purpose NOT IN ('APPLY','RESTORE') OR cardinality(ops.evaluate_price_write_gate(c.id))=0))
                """).param("attempt",request.attemptId()).param("digest",request.digest())
                .param("purpose",request.operation().name()).param("capability",request.capabilityId())
                .param("listing",request.nativeListingKey()).param("variant",request.nativeVariantKey())
                .param("idempotency",request.idempotencyKey()).param("currency",request.targetPrice().currencyCode())
                .param("price",request.targetPrice().amount()).param("task",request.nativeTaskKey())
                .param("credential",request.credentialId()).param("precondition",request.expectedVersionToken())
                .query(Boolean.class).single());
    }

    /**
     * The advertising twin of {@link #priceAttemptCurrent}, and it exists for the
     * same reason.
     *
     * <p>{@code ops.open_ad_bid_command_attempt} already evaluated the gate when
     * the attempt row was written. This is the second evaluation, made after the
     * destination is built and immediately before anything leaves, so a kill,
     * quarantine, expired approval or revoked credential that arrived in the
     * intervening milliseconds stops the call rather than following it.
     *
     * <p>It also re-derives every value the caller supplied from the command
     * itself. The adapter is handed a request record; without this, a caller
     * inside the module could hand it a well-formed request naming a different
     * bid, a different object or a different idempotency key, and the digest
     * would agree with itself the whole way down.
     */
    public boolean adBidAttemptCurrent(
            com.mimococo.marketops.marketplaceintegration.port.AdBidWriteRequest request) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM ops.ad_bid_command_attempt a
                    JOIN ops.ad_bid_command c ON c.id=a.command_id
                    JOIN core.store store ON store.id=c.store_id
                    JOIN core.ad_native_object object ON object.id=c.ad_native_object_id
                        AND object.organization_id=c.organization_id
                    JOIN platform.credential_metadata credential
                        ON credential.marketplace_account_id=store.marketplace_account_id
                    WHERE a.id=:attempt AND a.request_digest=:digest AND a.outcome_class='IN_FLIGHT'
                      AND a.purpose=:purpose AND c.capability_id=:capability
                      AND object.native_campaign_key=:campaign AND object.native_object_key=:object
                      AND object.status='ACTIVE'
                      AND object.control_granularity_state='PROVEN_INDEPENDENT'
                      AND :idempotency=CASE WHEN a.purpose='RESTORE'
                          THEN encode(sha256(convert_to(c.idempotency_key||chr(31)||'RESTORE'||chr(31),'UTF8')),'hex')
                          ELSE c.idempotency_key END
                      AND (a.purpose='STATUS_ENQUIRY' OR (c.currency_code=:currency
                          AND c.bid_unit_code=:unit
                          AND :bid=CASE WHEN a.purpose='RESTORE' THEN c.prior_bid_amount
                                        ELSE c.target_bid_amount END))
                      AND CASE WHEN a.purpose='STATUS_ENQUIRY'
                          THEN CAST(:task AS text)=(SELECT prior.native_task_key
                              FROM ops.ad_bid_command_attempt prior
                              WHERE prior.command_id=c.id AND prior.native_task_key IS NOT NULL
                              ORDER BY prior.started_at DESC,prior.attempt_no DESC LIMIT 1)
                          ELSE CAST(:task AS text) IS NULL END
                      AND c.fence_token=a.fence_token AND c.lease_owner=a.lease_owner
                      AND c.lease_expires_at > clock_timestamp()
                      AND (a.purpose<>'APPLY' OR c.approval_expires_at > clock_timestamp())
                      AND a.expected_version_token IS NOT DISTINCT FROM CAST(:precondition AS text)
                      AND a.operation_snapshot-'adSemanticProfile'=platform.ad_bid_operation_snapshot(c.capability_id,a.purpose)
                      AND a.operation_snapshot->'adSemanticProfile'=(SELECT to_jsonb(profile)
                          FROM platform.ad_semantic_profile profile WHERE profile.id=c.semantic_profile_id)
                      AND platform.capability_evidence_current(store.marketplace_account_id,c.capability_id,
                          (a.operation_snapshot #>> '{operation,endpoint_id}')::uuid)
                      AND credential.id=:credential AND credential.organization_id=c.organization_id
                      AND credential.purpose_code='ADS_WRITE' AND credential.status='ACTIVE'
                      AND credential.effective_from<=clock_timestamp()
                      AND credential.expires_at>clock_timestamp()
                      AND (credential.scope_mode='ACCOUNT' OR EXISTS (
                          SELECT 1 FROM platform.credential_store_scope scope
                          WHERE scope.credential_id=credential.id AND scope.store_id=store.id
                            AND scope.status='ACTIVE'))
                      AND (a.purpose NOT IN ('APPLY','RESTORE')
                          OR cardinality(CASE WHEN a.purpose='RESTORE' THEN ops.evaluate_ad_bid_compensation_gate(c.id)
                              ELSE ops.evaluate_ad_bid_write_gate(c.id) END)=0))
                """).param("attempt",request.attemptId()).param("digest",request.digest())
                .param("purpose",request.operation().name()).param("capability",request.capabilityId())
                .param("campaign",request.nativeCampaignKey()).param("object",request.nativeObjectKey())
                .param("idempotency",request.idempotencyKey())
                .param("currency",request.targetBid()==null?null:request.targetBid().currencyCode())
                .param("unit",request.bidUnitCode())
                .param("bid",request.targetBid()==null?null:request.targetBid().amount())
                .param("task",request.nativeTaskKey())
                .param("credential",request.credentialId()).param("precondition",request.expectedVersionToken())
                .query(Boolean.class).single());
    }

    private static EndpointCallSpec mapSpec(ResultSet rows, int rowNumber) throws SQLException {
        // wasNull reports on the column read immediately before it, so the
        // absence of a rate limit is captured here rather than inside the
        // constructor call, where later reads would have replaced the flag.
        int rateLimitValue = rows.getInt("rate_limit_per_minute");
        Integer rateLimit = rows.wasNull() ? null : rateLimitValue;
        return new EndpointCallSpec(
                rows.getObject("id", UUID.class),
                rows.getString("platform_code"),
                rows.getString("endpoint_code"),
                rows.getString("base_url"),
                rows.getString("http_method"),
                rows.getString("path_template"),
                rows.getString("query_template"),
                rows.getString("body_template"),
                rows.getString("response_content_type"),
                rows.getString("continuation_pointer"),
                rows.getString("pagination_model"),
                rateLimit,
                rows.getInt("request_timeout_ms"),
                rows.getLong("max_response_bytes"));
    }
}
