package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.operationsworkflow.internal.application.EconomicsProfileService;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.EconomicsProfileRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.ObjectMapper;

/**
 * A store's economics projection profile: generated from the marketplace's tariffs, submitted by one
 * Owner and approved by another before the price guardrail uses it (Owner decisions 2026-10-01).
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console")
class EconomicsProfileConsoleController {

    private final EconomicsProfileService profiles;
    private final ObjectMapper objectMapper;

    EconomicsProfileConsoleController(EconomicsProfileService profiles, ObjectMapper objectMapper) {
        this.profiles = profiles;
        this.objectMapper = objectMapper;
    }

    /** The store's declared fulfilment modes, the profile in force and the drafts. */
    @GetMapping(value = "/stores/{storeId}/economics-profiles", produces = MediaType.APPLICATION_JSON_VALUE)
    ProfilesView view(AuthenticatedActor actor, @PathVariable UUID storeId) {
        EconomicsProfileService.View view = profiles.view(actor, storeId);
        return new ProfilesView(view.storeId(), view.generatedAt(), view.declaredModes(),
                view.profile() == null ? null : ProfileView.of(view.profile()),
                view.drafts().stream().map(draft -> draft(draft, view.viewerId())).toList());
    }

    /** Generate a draft from the store's newest tariffs and submit it; needs COMMERCIAL_POLICY_MANAGE. */
    @PostMapping(value = "/stores/{storeId}/economics-profile-drafts", produces = MediaType.APPLICATION_JSON_VALUE)
    DraftView generate(AuthenticatedActor actor, @PathVariable UUID storeId) {
        return draft(profiles.generate(actor, storeId), actor.userId());
    }

    /** Approve another Owner's draft; needs COMMERCIAL_POLICY_MANAGE. */
    @PostMapping(value = "/economics-profile-drafts/{draftId}/approval", produces = MediaType.APPLICATION_JSON_VALUE)
    Approved approve(AuthenticatedActor actor, @PathVariable UUID draftId, @Valid @RequestBody ApprovalRequest request) {
        return new Approved(profiles.approve(actor, draftId, request.expectedVersion(), request.note(),
                request.verificationDays()));
    }

    /** Withdraw one's own waiting draft or reject another Owner's; needs COMMERCIAL_POLICY_MANAGE. */
    @PostMapping(value = "/economics-profile-drafts/{draftId}/closure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close(AuthenticatedActor actor, @PathVariable UUID draftId, @Valid @RequestBody ClosureRequest request) {
        profiles.close(actor, draftId, request.expectedVersion(), request.reason());
    }

    @SuppressWarnings("unchecked")
    private DraftView draft(EconomicsProfileRepository.DraftRow draft, UUID viewerId) {
        Map<String, Object> payload = objectMapper.readValue(draft.payload(), Map.class);
        return new DraftView(draft.id(), draft.fulfillmentModeCode(), draft.currencyCode(), payload,
                draft.evidenceReference(), draft.submittedAt(), draft.submittedByUserId().equals(viewerId),
                draft.state(), draft.reviewedAt(), viewerId.equals(draft.reviewedByUserId()), draft.reviewNote(),
                draft.profileId(), draft.version());
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /**
     * An approval.
     *
     * @param verificationDays how long the profile stays verified; 30 when absent, at most 90
     */
    record ApprovalRequest(long expectedVersion, @Size(max = 500) String note, Integer verificationDays) {
    }

    /** Why a draft is withdrawn or rejected. */
    record ClosureRequest(long expectedVersion, @NotBlank @Size(max = 500) String reason) {
    }

    /** The published profile. */
    record Approved(UUID profileId) {
    }

    /** The store's profile page. */
    record ProfilesView(UUID storeId, Instant generatedAt, List<String> declaredModes, ProfileView profile,
                        List<DraftView> drafts) {
    }

    /**
     * One draft; {@code payload} holds its families, components (amounts and rates as numbers) and the
     * supported price range.
     *
     * @param submittedByViewer whether the person asking submitted it, and so cannot approve it
     */
    record DraftView(UUID draftId, String fulfillmentModeCode, String currencyCode, Map<String, Object> payload,
                     String evidence, Instant submittedAt, boolean submittedByViewer, String state, Instant reviewedAt,
                     boolean reviewedByViewer, String reviewNote, UUID profileId, long version) {
    }

    /** The profile in force; amounts and rates as decimal text. */
    record ProfileView(UUID profileId, int version, String fulfillmentModeCode, String currencyCode,
                       Instant effectiveFrom, String verificationState, Instant verifiedAt,
                       Instant verificationExpiresAt, String evidence, String minimumSupportedPrice,
                       String maximumSupportedPrice, List<FamilyView> families, List<ComponentView> components) {

        static ProfileView of(EconomicsProfileRepository.Profile profile) {
            return new ProfileView(profile.id(), profile.version(), profile.fulfillmentModeCode(),
                    profile.currencyCode(), profile.effectiveFrom(), profile.verificationState(),
                    profile.verifiedAt(), profile.verificationExpiresAt(), profile.evidenceReference(),
                    text(profile.minimumSupportedPrice()), text(profile.maximumSupportedPrice()),
                    profile.families().stream().map(family -> new FamilyView(family.familyCode(),
                            family.applicability(), family.evidence())).toList(),
                    profile.components().stream().map(component -> new ComponentView(component.componentCode(),
                            component.familyCode(), component.kind(), text(component.fixedAmount()),
                            text(component.rateValue()), component.evidence())).toList());
        }
    }

    /** One family of the profile in force. */
    record FamilyView(String familyCode, String applicability, String evidence) {
    }

    /** One component of the profile in force. */
    record ComponentView(String componentCode, String familyCode, String kind, String fixedAmount, String rateValue,
                         String evidence) {
    }
}
