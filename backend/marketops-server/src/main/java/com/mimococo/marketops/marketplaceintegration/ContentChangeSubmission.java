package com.mimococo.marketops.marketplaceintegration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An approved change to a listing's title and description, as the approver confirmed it.
 *
 * @param organizationId the organization
 * @param platformListingVariantId the listing variant the change is for
 * @param priorTitle the title the approver saw, from the newest catalog facts, or {@code null}
 * @param priorDescription the description the approver saw, or {@code null}
 * @param priorObservedAt when those facts were true, or {@code null}
 * @param targetTitle the title to write
 * @param targetDescription the description to write
 * @param titleChanged whether the title differs from the prior one
 * @param descriptionChanged whether the description differs from the prior one
 * @param sourceInvocationId the model draft the text started from, or {@code null}
 * @param reason why the change is made
 * @param approvedByUserId the Owner who confirmed it
 * @param approvedAt when
 * @param approvalExpiresAt after which the command may no longer write
 */
public record ContentChangeSubmission(
        UUID organizationId,
        UUID platformListingVariantId,
        String priorTitle,
        String priorDescription,
        Instant priorObservedAt,
        String targetTitle,
        String targetDescription,
        boolean titleChanged,
        boolean descriptionChanged,
        UUID sourceInvocationId,
        String reason,
        UUID approvedByUserId,
        Instant approvedAt,
        Instant approvalExpiresAt) {

    public ContentChangeSubmission {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(platformListingVariantId, "platformListingVariantId");
        Objects.requireNonNull(targetTitle, "targetTitle");
        Objects.requireNonNull(targetDescription, "targetDescription");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(approvedByUserId, "approvedByUserId");
        Objects.requireNonNull(approvedAt, "approvedAt");
        Objects.requireNonNull(approvalExpiresAt, "approvalExpiresAt");
        if (!titleChanged && !descriptionChanged) {
            throw new IllegalArgumentException("a content change changes the title, the description or both");
        }
    }
}
