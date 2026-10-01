package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operationsworkflow.internal.application.FeedWatermarkKeeper;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.FeedWatermarkRepository.Attestation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * How fresh each feed of the price guardrail is for a store, and the Owner's statements that the
 * store has no data in a feed it does not collect (Owner decisions 2026-10-01).
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/stores")
class FeedFreshnessConsoleController {

    private final FeedWatermarkKeeper watermarks;

    FeedFreshnessConsoleController(FeedWatermarkKeeper watermarks) {
        this.watermarks = watermarks;
    }

    /** Each feed's newest watermark and age, with the store's attestations. */
    @GetMapping(value = "/{storeId}/feed-freshness", produces = MediaType.APPLICATION_JSON_VALUE)
    FreshnessView freshness(AuthenticatedActor actor, @PathVariable UUID storeId) {
        FeedWatermarkKeeper.Freshness freshness = watermarks.status(actor, storeId);
        return new FreshnessView(freshness.storeId(), freshness.generatedAt(),
                freshness.feeds().stream().map(feed -> new FeedView(feed.feedCode(), feed.attestable(),
                        feed.effectiveAt(), feed.recordedAt(), feed.evidence(), feed.ageSeconds())).toList(),
                freshness.attestations().stream()
                        .map(attestation -> AttestationView.of(attestation, freshness.generatedAt())).toList());
    }

    /** Attest that the store has no data in the feeds named; needs COMMERCIAL_POLICY_MANAGE. */
    @PostMapping(value = "/{storeId}/feed-attestations", produces = MediaType.APPLICATION_JSON_VALUE)
    Attested attest(AuthenticatedActor actor, @PathVariable UUID storeId, @Valid @RequestBody AttestRequest request) {
        return new Attested(watermarks.attest(actor, storeId, request.feedCodes(), request.statement(),
                request.validDays()));
    }

    /** Revoke a standing attestation; needs COMMERCIAL_POLICY_MANAGE. */
    @PostMapping(value = "/{storeId}/feed-attestations/{attestationId}/revocation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(AuthenticatedActor actor, @PathVariable UUID storeId, @PathVariable UUID attestationId,
                @Valid @RequestBody RevokeRequest request) {
        watermarks.revoke(actor, storeId, attestationId, request.reason());
    }

    /**
     * An attestation request.
     *
     * @param feedCodes RETURNS, FINANCE_FEES and/or ADVERTISING
     * @param validDays how long it stands; 30 when absent, at most 90
     */
    record AttestRequest(@NotEmpty List<String> feedCodes, @NotBlank @Size(max = 500) String statement,
                         Integer validDays) {
    }

    /** Why an attestation is revoked. */
    record RevokeRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** The attestations recorded, one per feed. */
    record Attested(List<UUID> attestationIds) {
    }

    /** The store's feed freshness. */
    record FreshnessView(UUID storeId, Instant generatedAt, List<FeedView> feeds, List<AttestationView> attestations) {
    }

    /**
     * One feed's newest watermark.
     *
     * @param effectiveAt the instant the guardrail measures its age from, or {@code null} without one
     */
    record FeedView(String feedCode, boolean attestable, Instant effectiveAt, Instant recordedAt, String evidence,
                    Long ageSeconds) {
    }

    /**
     * One attestation; {@code state} is STANDING, LAPSED, REVOKED or EXPIRED.
     */
    record AttestationView(UUID attestationId, String feedCode, String statement, Instant attestedAt,
                           Instant expiresAt, String state, Instant endedAt, String endReason) {

        static AttestationView of(Attestation attestation, Instant now) {
            String state = attestation.lapsedAt() != null ? "LAPSED" : attestation.revokedAt() != null ? "REVOKED"
                    : attestation.expiresAt().isAfter(now) ? "STANDING" : "EXPIRED";
            Instant endedAt = attestation.lapsedAt() != null ? attestation.lapsedAt()
                    : attestation.revokedAt() != null ? attestation.revokedAt()
                    : "EXPIRED".equals(state) ? attestation.expiresAt() : null;
            String endReason = attestation.lapsedAt() != null ? attestation.lapseReason()
                    : attestation.revocationReason();
            return new AttestationView(attestation.id(), attestation.feedCode(), attestation.statement(),
                    attestation.attestedAt(), attestation.expiresAt(), state, endedAt, endReason);
        }
    }
}
