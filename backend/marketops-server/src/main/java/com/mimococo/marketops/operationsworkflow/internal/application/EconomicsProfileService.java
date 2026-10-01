package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.EconomicsProfileRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.EconomicsProfileRepository.DraftRow;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.EconomicsProfileRepository.Profile;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.EconomicsProfileRepository.Stated;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.EconomicsProfileRepository.Tariffs;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Economics projection profiles generated from the marketplace's own tariffs and verified by two
 * Owners (Owner decisions 2026-10-01).
 *
 * <p>A draft takes, for every fee, the highest value the newest price observation of any listing of
 * the store states within the last seven days: the sales commission, the logistics tariffs of the
 * declared fulfilment mode, acquiring (a share of the buyer price) and the VAT contained in the price.
 * Every listing must state every fee, or the highest value would not bound them all. Storage, promotion, return
 * loss and advertising are recorded as not applicable, each with its reason: the store fulfils its own
 * orders, its promotions are inside the buyer price, it has no orders to return, and it does not
 * advertise. Only seller fulfilment is generated for now; the marketplace's storage fees of its own
 * fulfilment are not read.
 *
 * <p>The Owner who submits a draft cannot approve it. Approval publishes the profile through the
 * database (ops.publish_economics_profile), which retires the scope's previous profile; its
 * verification expires after thirty days unless stated, because the marketplace changes its tariffs.
 */
@Service
public class EconomicsProfileService {

    /** The only fulfilment mode a draft is generated for so far. */
    static final String SELLER_FULFILLED = "SELLER_FULFILLED";

    /** How far back the newest price observation of a listing may be to count. */
    private static final Duration TARIFF_LOOKBACK = Duration.ofDays(7);

    /** How long a published profile stays verified unless stated, and at most. */
    public static final int DEFAULT_VERIFICATION_DAYS = 30;
    public static final int MAXIMUM_VERIFICATION_DAYS = 90;

    private static final String ENTITY_TYPE = "economics-profile-draft";

    private final EconomicsProfileRepository profiles;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    EconomicsProfileService(EconomicsProfileRepository profiles, BusinessAuthorization authorization,
                            MetadataAuditRecorder audit, IdGenerator idGenerator, ObjectMapper objectMapper,
                            Clock clock) {
        this.profiles = profiles;
        this.authorization = authorization;
        this.audit = audit;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Generate a draft from the store's newest tariffs and submit it for a second Owner's review,
     * setting aside a draft of the same scope that was still waiting.
     */
    @Transactional
    public DraftRow generate(AuthenticatedActor actor, UUID storeId) {
        authorization.require(actor, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                ResourceScope.organization(actor.organizationId()));
        UUID organizationId = actor.organizationId();
        Instant now = clock.instant();
        EconomicsProfileRepository.Scope scope = profiles.scope(organizationId, storeId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!profiles.declaredModes(organizationId, storeId, now).equals(List.of(SELLER_FULFILLED))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Tariffs tariffs = profiles.tariffs(organizationId, storeId, now.minus(TARIFF_LOOKBACK))
                .filter(found -> found.currencies() == 1 && found.lowestBuyerPrice() != null
                        && found.highestBuyerPrice() != null)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.VALIDATION_FAILED));
        Map<String, Object> payload = payload(tariffs);
        String evidence = "highest fees of the newest price observations of " + tariffs.listings()
                + " listings, " + tariffs.oldest() + " to " + tariffs.newest();
        profiles.supersedeOpen(organizationId, storeId, SELLER_FULFILLED);
        UUID id = idGenerator.newId();
        profiles.insertDraft(new EconomicsProfileRepository.Draft(id, organizationId, storeId, scope.platformCode(),
                scope.marketplaceAccountId(), SELLER_FULFILLED, tariffs.currencyCode(),
                objectMapper.writeValueAsString(payload), evidence, actor.userId(), now));
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW, actor.userId().toString(),
                AuditAction.CREATE, ENTITY_TYPE, id, null,
                Map.of("storeId", new FieldChange(null, storeId.toString()),
                        "fulfillmentModeCode", new FieldChange(null, SELLER_FULFILLED)),
                evidence, null));
        return profiles.draft(organizationId, id).orElseThrow();
    }

    /** The families and components the publishing function reads, with the supported price range. */
    private static Map<String, Object> payload(Tariffs tariffs) {
        String basis = "of " + tariffs.listings() + " listings' newest price observations";
        List<Map<String, Object>> families = new ArrayList<>();
        List<Map<String, Object>> components = new ArrayList<>();

        Stated commission = required(tariffs.commissionFbsPercent(), tariffs);
        components.add(component("SALES_COMMISSION_FBS", "COMMISSION", "PERCENTAGE", null,
                commission.highest().divide(BigDecimal.valueOf(100), 8, RoundingMode.UP),
                "highest FBS sales commission " + plain(commission.highest()) + "% " + basis));
        families.add(family("COMMISSION", "REQUIRED", "sales commission of seller fulfilment (FBS)"));

        for (Map.Entry<String, Stated> fee : List.of(Map.entry("FBS_DIRECT_FLOW", tariffs.fbsDirectFlow()),
                Map.entry("FBS_FIRST_MILE", tariffs.fbsFirstMile()), Map.entry("FBS_LAST_MILE", tariffs.fbsLastMile()))) {
            components.add(component(fee.getKey(), "FULFILLMENT_DELIVERY", "FIXED",
                    required(fee.getValue(), tariffs).highest().setScale(4, RoundingMode.UP), null,
                    "highest " + fee.getKey().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                            + " tariff (highest tier) " + basis));
        }
        families.add(family("FULFILLMENT_DELIVERY", "REQUIRED", "logistics tariffs of seller fulfilment, highest tier"));

        // The marketplace charges acquiring as a share of the buyer price (the stated fee divided by the
        // buyer price is the same share for every listing), so it scales with the proposed price.
        Stated acquiring = required(tariffs.acquiringRate(), tariffs);
        components.add(component("ACQUIRING", "OTHER_VARIABLE", "PERCENTAGE", null,
                acquiring.highest().setScale(8, RoundingMode.UP),
                "highest acquiring fee as a share of the buyer price " + basis));
        families.add(family("OTHER_VARIABLE", "REQUIRED", "acquiring, a share of the buyer price"));

        Stated vat = required(tariffs.vatRate(), tariffs);
        // The price contains the VAT: its share of the price is v / (1 + v); rounded up, so it never errs low.
        components.add(component("VAT_IN_PRICE", "VARIABLE_TAX", "PERCENTAGE", null,
                vat.highest().divide(BigDecimal.ONE.add(vat.highest()), 8, RoundingMode.UP),
                "VAT contained in the price at the highest listing rate " + plain(vat.highest()) + " " + basis));
        families.add(family("VARIABLE_TAX", "REQUIRED", "VAT contained in the buyer price; no other turnover tax"));

        families.add(family("STORAGE", "VERIFIED_NOT_APPLICABLE",
                "seller fulfilment: the goods stay in the seller's warehouse, the marketplace charges no storage"));
        families.add(family("PROMOTION", "VERIFIED_NOT_APPLICABLE",
                "the seller's promotion discounts are inside the buyer price the projection starts from"));
        families.add(family("RETURN_LOSS", "VERIFIED_NOT_APPLICABLE",
                "no orders yet, so no return history (Owner decision 2026-10-01); revisit once orders exist"));
        families.add(family("ADVERTISING", "VERIFIED_NOT_APPLICABLE",
                "the store does not advertise (Owner attestation 2026-10-01)"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("families", families);
        payload.put("components", components);
        // A band around today's buyer prices that holds every suggested step and any plausible change.
        payload.put("minimumSupportedPrice", tariffs.lowestBuyerPrice().divide(BigDecimal.valueOf(2), 0, RoundingMode.FLOOR)
                .max(BigDecimal.ONE).setScale(4, RoundingMode.UNNECESSARY));
        payload.put("maximumSupportedPrice", tariffs.highestBuyerPrice().multiply(BigDecimal.valueOf(2))
                .setScale(0, RoundingMode.CEILING).setScale(4, RoundingMode.UNNECESSARY));
        payload.put("listings", tariffs.listings());
        payload.put("observedFrom", String.valueOf(tariffs.oldest()));
        payload.put("observedTo", String.valueOf(tariffs.newest()));
        return payload;
    }

    /** A fee every listing states; otherwise its highest value would not bound the listings that omit it. */
    private static Stated required(Stated stated, Tariffs tariffs) {
        if (stated.highest() == null || stated.highest().signum() < 0 || stated.listings() != tariffs.listings()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return stated;
    }

    private static Map<String, Object> family(String code, String applicability, String evidence) {
        Map<String, Object> family = new LinkedHashMap<>();
        family.put("familyCode", code);
        family.put("applicability", applicability);
        family.put("evidence", evidence);
        return family;
    }

    private static Map<String, Object> component(String code, String family, String kind, BigDecimal fixedAmount,
                                                 BigDecimal rateValue, String evidence) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("componentCode", code);
        component.put("familyCode", family);
        component.put("kind", kind);
        component.put("fixedAmount", fixedAmount);
        component.put("rateValue", rateValue);
        component.put("evidence", evidence.length() <= 512 ? evidence : evidence.substring(0, 512));
        return component;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * Approve a draft another Owner submitted: the profile is published and the scope's previous one
     * retired.
     *
     * @param verificationDays how long the profile stays verified; 30 when absent, at most 90
     * @return the published profile
     */
    @Transactional
    public UUID approve(AuthenticatedActor actor, UUID draftId, long expectedVersion, String note,
                        Integer verificationDays) {
        authorization.require(actor, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                ResourceScope.organization(actor.organizationId()));
        DraftRow draft = profiles.draft(actor.organizationId(), draftId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (draft.submittedByUserId().equals(actor.userId())) {
            throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
        }
        int days = verificationDays == null ? DEFAULT_VERIFICATION_DAYS : verificationDays;
        if (days < 1 || days > MAXIMUM_VERIFICATION_DAYS) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String text = note == null || note.isBlank() ? null : note.strip();
        UUID profileId = profiles.publish(draftId, actor.userId(), expectedVersion, text, days);
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW, actor.userId().toString(),
                AuditAction.STATUS_CHANGE, ENTITY_TYPE, draftId, null,
                Map.of("state", new FieldChange("SUBMITTED", "APPROVED"),
                        "profileId", new FieldChange(null, profileId.toString())),
                text, null));
        return profileId;
    }

    /** Withdraw one's own waiting draft, or reject another Owner's. */
    @Transactional
    public void close(AuthenticatedActor actor, UUID draftId, long expectedVersion, String reason) {
        authorization.require(actor, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                ResourceScope.organization(actor.organizationId()));
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty() || text.length() > 500) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        DraftRow draft = profiles.draft(actor.organizationId(), draftId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        boolean own = draft.submittedByUserId().equals(actor.userId());
        if (!profiles.close(draftId, expectedVersion, own ? null : actor.userId(), clock.instant(), text)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW, actor.userId().toString(),
                AuditAction.STATUS_CHANGE, ENTITY_TYPE, draftId, null,
                Map.of("state", new FieldChange("SUBMITTED", own ? "SUPERSEDED" : "REJECTED")), text, null));
    }

    /** The store's declared fulfilment modes, the profile in force and the drafts, newest first. */
    @Transactional(readOnly = true)
    public View view(AuthenticatedActor actor, UUID storeId) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        Instant now = clock.instant();
        List<String> modes = profiles.declaredModes(actor.organizationId(), storeId, now);
        Optional<Profile> profile = modes.size() == 1
                ? profiles.profileInForce(actor.organizationId(), storeId, modes.getFirst(), now) : Optional.empty();
        return new View(storeId, now, modes, profile.orElse(null),
                profiles.drafts(actor.organizationId(), storeId, 10), actor.userId());
    }

    /**
     * What the store's profile page shows.
     *
     * @param profile the profile in force, or {@code null} without one
     * @param viewerId the person asking, so a page can tell a draft they submitted from another's
     */
    public record View(UUID storeId, Instant generatedAt, List<String> declaredModes, Profile profile,
                       List<DraftRow> drafts, UUID viewerId) {
    }
}
