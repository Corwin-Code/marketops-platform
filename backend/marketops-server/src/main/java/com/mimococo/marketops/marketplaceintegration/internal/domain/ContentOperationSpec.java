package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * How one verified content operation is performed and read (W2, V0035).
 *
 * <p>Only ever loaded for an operation whose capability, endpoint and platform profile are all
 * verified and active, so an unverified write has no reachable specification.
 *
 * @param capabilityId the content capability
 * @param platformCode the marketplace
 * @param operation APPLY, STATUS_ENQUIRY or READBACK
 * @param requestTemplate the body template
 * @param taskKeyPointer where an apply's answer holds the platform task key, or {@code null}
 * @param taskStatusPointer where an enquiry's answer holds the task status, or {@code null}
 * @param taskSuccessValue the status that means the task finished, or {@code null}
 * @param taskFailureValue the status that means it failed, or {@code null}
 * @param taskPendingValues the statuses that mean it is still being processed
 * @param noChangeValues statuses that mean the task found nothing to change (Ozon: skipped)
 * @param errorsPointer where an enquiry's answer lists the task's errors, or {@code null}
 * @param descriptionPointer where a readback's answer holds the description, or {@code null}
 * @param titlePointer where a readback's answer holds the title, or {@code null}
 * @param endpoint how to reach the endpoint
 */
public record ContentOperationSpec(
        UUID capabilityId,
        String platformCode,
        String operation,
        String requestTemplate,
        String taskKeyPointer,
        String taskStatusPointer,
        String taskSuccessValue,
        String taskFailureValue,
        Set<String> taskPendingValues,
        Set<String> noChangeValues,
        String errorsPointer,
        String descriptionPointer,
        String titlePointer,
        EndpointCallSpec endpoint) {

    public ContentOperationSpec {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(endpoint, "endpoint");
        taskPendingValues = taskPendingValues == null ? Set.of() : Set.copyOf(taskPendingValues);
        noChangeValues = noChangeValues == null ? Set.of() : Set.copyOf(noChangeValues);
    }
}
