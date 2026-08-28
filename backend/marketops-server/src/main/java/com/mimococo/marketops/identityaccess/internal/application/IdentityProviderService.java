package com.mimococo.marketops.identityaccess.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.internal.domain.IdentityProviderRecord;
import com.mimococo.marketops.identityaccess.internal.domain.IdentityProviderStatus;
import com.mimococo.marketops.identityaccess.internal.domain.ProviderVerificationState;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.IdentityProviderRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and verification of the external issuers this deployment accepts.
 *
 * <p>A provider is registered unverified and cannot be activated until somebody
 * has checked its published behaviour and recorded that evidence: the exact
 * claim it uses to state that a second factor was used, the value inside that
 * claim, and where those facts were read. Until then no token from the issuer
 * is accepted, so an unchecked provider cannot satisfy a mandatory multi-factor
 * requirement by accident.
 *
 * <p>Nothing here holds a secret. The issuer identifier and the key-set location
 * are public values, the signing key never leaves the provider, and this
 * application has no credential of its own to store.
 */
@Service
public class IdentityProviderService {

    static final String ENTITY_TYPE = "identity-provider";

    /** Bounds on how old an authentication may be for a sensitive action. */
    private static final int MIN_AUTH_AGE_SECONDS = 60;
    private static final int MAX_AUTH_AGE_SECONDS = 86_400;

    /** The claim-name shape published by identity providers. */
    private static final Pattern CLAIM_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    /** The claim-value shape recorded as meaning a second factor. */
    private static final Pattern CLAIM_VALUE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:._-]{0,127}$");

    private final IdentityProviderRepository providers;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    IdentityProviderService(IdentityProviderRepository providers,
                            MetadataAuditRecorder auditRecorder,
                            IdGenerator idGenerator,
                            Clock clock) {
        this.providers = providers;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Register an issuer. It starts unverified and accepts no token. */
    @Transactional
    public IdentityProviderRecord register(String operator,
                                           String code,
                                           String displayName,
                                           String issuer,
                                           int maxAuthAgeSeconds,
                                           String ownerLabel) {
        String validCode = MetadataFieldPolicy.requireCode(code);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validOwner = MetadataFieldPolicy.requireText("ownerLabel", ownerLabel);
        String validIssuer = requireIssuer(issuer);
        requireAuthAge(maxAuthAgeSeconds);
        providers.findByIssuer(validIssuer).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                    ENTITY_TYPE, validCode, existing.id());
        });

        Instant now = clock.instant();
        IdentityProviderRecord provider = new IdentityProviderRecord(
                idGenerator.newId(), validCode, validName, validIssuer, null, null,
                maxAuthAgeSeconds, ProviderVerificationState.UNVERIFIED, null, null, null,
                validOwner, IdentityProviderStatus.RETIRED, now, now, 0L);
        providers.insert(provider);

        Map<String, FieldChange> changes = new LinkedHashMap<>();
        changes.put("code", new FieldChange(null, validCode));
        changes.put("issuer", new FieldChange(null, validIssuer));
        changes.put("maxAuthAgeSeconds", new FieldChange(null, Integer.toString(maxAuthAgeSeconds)));
        changes.put("verificationState",
                new FieldChange(null, ProviderVerificationState.UNVERIFIED.name()));
        changes.put("status", new FieldChange(null, IdentityProviderStatus.RETIRED.name()));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.CREATE,
                ENTITY_TYPE, provider.id(), validCode, changes, null, null));
        return provider;
    }

    /**
     * Record verified behaviour and activate the issuer.
     *
     * <p>The multi-factor vocabulary and the evidence arrive together because
     * they are one decision: claiming a provider is verified without saying how
     * it reports a second factor would leave the mandatory requirement
     * unevaluable while looking satisfied.
     */
    @Transactional
    public IdentityProviderRecord verifyAndActivate(String operator,
                                                    UUID id,
                                                    String mfaClaimName,
                                                    String mfaClaimValue,
                                                    String evidenceRef,
                                                    String verifiedSourceTitle,
                                                    long expectedVersion) {
        IdentityProviderRecord current = require(id);
        String validClaimName = requireMatching(CLAIM_NAME, "mfaClaimName", mfaClaimName);
        String validClaimValue = requireMatching(CLAIM_VALUE, "mfaClaimValue", mfaClaimValue);
        String validEvidence = MetadataFieldPolicy.requireText("evidenceRef", evidenceRef);
        String validTitle = MetadataFieldPolicy.requireText("verifiedSourceTitle",
                verifiedSourceTitle);

        Instant now = clock.instant();
        IdentityProviderRecord verified = new IdentityProviderRecord(
                current.id(), current.code(), current.displayName(), current.issuer(),
                validClaimName, validClaimValue, current.maxAuthAgeSeconds(),
                ProviderVerificationState.VERIFIED, now, validEvidence, validTitle,
                current.ownerLabel(), IdentityProviderStatus.ACTIVE, current.createdAt(),
                now, expectedVersion + 1);
        applyVersioned(providers.update(verified, expectedVersion));

        Map<String, FieldChange> changes = new LinkedHashMap<>();
        changes.put("mfaClaimName", new FieldChange(current.mfaClaimName(), validClaimName));
        changes.put("mfaClaimValue", new FieldChange(current.mfaClaimValue(), validClaimValue));
        changes.put("verificationState", new FieldChange(
                current.verificationState().name(), ProviderVerificationState.VERIFIED.name()));
        changes.put("status", new FieldChange(
                current.status().name(), IdentityProviderStatus.ACTIVE.name()));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.VERIFICATION_CHANGE,
                ENTITY_TYPE, current.id(), current.code(), changes, null, validEvidence));
        return verified;
    }

    /** Stop accepting tokens from an issuer. The direction to closed is never gated. */
    @Transactional
    public IdentityProviderRecord retire(String operator, UUID id, String reason,
                                         long expectedVersion) {
        IdentityProviderRecord current = require(id);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        IdentityProviderRecord retired = new IdentityProviderRecord(
                current.id(), current.code(), current.displayName(), current.issuer(),
                current.mfaClaimName(), current.mfaClaimValue(), current.maxAuthAgeSeconds(),
                current.verificationState(), current.lastVerifiedAt(), current.evidenceRef(),
                current.verifiedSourceTitle(), current.ownerLabel(),
                IdentityProviderStatus.RETIRED, current.createdAt(), clock.instant(),
                expectedVersion + 1);
        applyVersioned(providers.update(retired, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.STATUS_CHANGE,
                ENTITY_TYPE, current.id(), current.code(),
                Map.of("status", new FieldChange(current.status().name(),
                        IdentityProviderStatus.RETIRED.name())),
                validReason, null));
        return retired;
    }

    /** Load one provider, refusing when it does not exist. */
    @Transactional(readOnly = true)
    public IdentityProviderRecord require(UUID id) {
        return providers.findById(id)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** Every registered provider, ordered by code. */
    @Transactional(readOnly = true)
    public List<IdentityProviderRecord> list() {
        return providers.list();
    }

    private static String requireIssuer(String issuer) {
        String validIssuer = MetadataFieldPolicy.requireText("issuer", issuer);
        if (!validIssuer.startsWith("https://")) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return validIssuer;
    }

    private static void requireAuthAge(int seconds) {
        if (seconds < MIN_AUTH_AGE_SECONDS || seconds > MAX_AUTH_AGE_SECONDS) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static String requireMatching(Pattern pattern, String fieldName, String value) {
        String text = MetadataFieldPolicy.requireText(fieldName, value);
        if (!pattern.matcher(text).matches()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return text;
    }

    private static void applyVersioned(boolean applied) {
        if (!applied) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
    }
}
