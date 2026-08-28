package com.mimococo.marketops.aicopilot.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc.AiProviderRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and eligibility of the model providers this deployment may call.
 *
 * <p>A provider is registered unverified and calls nothing. Activating it needs
 * two separate things recorded together: that somebody checked the contract and
 * data-processing terms for this business, and the exact wire shape of the call.
 * Either alone leaves a provider that cannot be used — a verified contract with
 * no endpoint is a contract nobody can act on, and an endpoint with no verified
 * contract is a call nobody has authorised.
 *
 * <p>No credential is stored here. A model names its secret by opaque reference,
 * and the value is resolved inside the gateway at the moment of use.
 */
@Service
public class AiProviderService {

    static final String PROVIDER_ENTITY_TYPE = "ai-provider";
    static final String MODEL_ENTITY_TYPE = "ai-model";

    /** The opaque secret reference shape the registry issues. */
    private static final Pattern SECRET_REFERENCE = Pattern.compile(
            "^secret-ref://[a-z0-9][a-z0-9-]{0,62}(/[a-z0-9][a-z0-9._-]{0,62}){1,4}$");

    /** A response pointer: a non-empty JSON Pointer. */
    private static final Pattern RESPONSE_POINTER = Pattern.compile("^(/[^/~]*(~[01][^/~]*)*)+$");

    private final AiProviderRepository providers;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    AiProviderService(AiProviderRepository providers,
                      MetadataAuditRecorder auditRecorder,
                      IdGenerator idGenerator,
                      Clock clock) {
        this.providers = providers;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Register a provider. It starts unverified and calls nothing. */
    @Transactional
    public UUID registerProvider(String operator, String providerCode, String displayName,
                                 String serviceRegionLabel, String ownerLabel) {
        String validCode = MetadataFieldPolicy.requireRegistryCode(providerCode);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validOwner = MetadataFieldPolicy.requireText("ownerLabel", ownerLabel);
        String validRegion = serviceRegionLabel == null
                ? null : MetadataFieldPolicy.requireText("serviceRegionLabel",
                        serviceRegionLabel);

        Instant now = clock.instant();
        UUID id = idGenerator.newId();
        providers.insertProvider(id, validCode, validName, validRegion, validOwner, now);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.AI_COPILOT, operator, AuditAction.CREATE,
                PROVIDER_ENTITY_TYPE, id, validCode,
                Map.of(
                        "providerCode", new FieldChange(null, validCode),
                        "eligibilityState", new FieldChange(null, "UNVERIFIED"),
                        "status", new FieldChange(null, "RETIRED")),
                null, null));
        return id;
    }

    /**
     * Record the checked contract and the recorded call shape, and activate.
     *
     * <p>The two arrive together because the relational contract requires both
     * before a provider can be active, and because activating on one without the
     * other would produce a provider that fails at the first call rather than at
     * the moment somebody could fix it.
     */
    @Transactional
    public void verifyAndActivate(String operator, UUID providerId, String invocationUrl,
                                  String requestTemplate, String responsePointer,
                                  String authHeaderName, String authValueTemplate,
                                  int requestTimeoutMillis, String evidenceRef,
                                  String verifiedSourceTitle, long expectedVersion) {
        String validUrl = MetadataFieldPolicy.requireText("invocationUrl", invocationUrl);
        if (!validUrl.startsWith("https://")) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String validTemplate = MetadataFieldPolicy.requireText("requestTemplate",
                requestTemplate);
        String validPointer = MetadataFieldPolicy.requireText("responsePointer",
                responsePointer);
        if (!RESPONSE_POINTER.matcher(validPointer).matches()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String validHeader = MetadataFieldPolicy.requireText("authHeaderName", authHeaderName);
        String validAuthTemplate = MetadataFieldPolicy.requireText("authValueTemplate",
                authValueTemplate);
        if (!validAuthTemplate.contains("{value}")) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String validEvidence = MetadataFieldPolicy.requireText("evidenceRef", evidenceRef);
        String validTitle = MetadataFieldPolicy.requireText("verifiedSourceTitle",
                verifiedSourceTitle);

        if (!providers.verifyAndActivate(providerId, validUrl, validTemplate, validPointer,
                validHeader, validAuthTemplate, requestTimeoutMillis, clock.instant(),
                validEvidence, validTitle, expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.AI_COPILOT, operator, AuditAction.VERIFICATION_CHANGE,
                PROVIDER_ENTITY_TYPE, providerId, null,
                Map.of(
                        "eligibilityState", new FieldChange("UNVERIFIED", "VERIFIED"),
                        "status", new FieldChange("RETIRED", "ACTIVE"),
                        "invocationUrl", new FieldChange(null, validUrl)),
                null, validEvidence));
    }

    /** Stop calling a provider. The direction to closed is never gated. */
    @Transactional
    public void retireProvider(String operator, UUID providerId, String reason,
                               long expectedVersion) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!providers.retireProvider(providerId, clock.instant(), expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.AI_COPILOT, operator, AuditAction.STATUS_CHANGE,
                PROVIDER_ENTITY_TYPE, providerId, null,
                Map.of("status", new FieldChange("ACTIVE", "RETIRED")),
                validReason, null));
    }

    /** Register a model a provider offers, naming its credential by reference. */
    @Transactional
    public UUID registerModel(String operator, UUID providerId, String modelCode,
                              String displayName, String secretReference,
                              Integer maximumContextTokens) {
        String validCode = MetadataFieldPolicy.requireRegistryCode(modelCode);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        if (secretReference == null || !SECRET_REFERENCE.matcher(secretReference).matches()) {
            throw OperationRejectedException.of(ErrorCode.SECRET_REFERENCE_INVALID);
        }

        Instant now = clock.instant();
        UUID id = idGenerator.newId();
        providers.insertModel(id, providerId, validCode, validName, secretReference,
                maximumContextTokens, now);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.AI_COPILOT, operator, AuditAction.CREATE,
                MODEL_ENTITY_TYPE, id, validCode,
                Map.of(
                        "providerId", new FieldChange(null, providerId.toString()),
                        "modelCode", new FieldChange(null, validCode)),
                null, null));
        return id;
    }

    /** Every registered provider, with its eligibility state. */
    @Transactional(readOnly = true)
    public List<AiProviderRepository.ProviderRow> listProviders() {
        return providers.listProviders();
    }
}
