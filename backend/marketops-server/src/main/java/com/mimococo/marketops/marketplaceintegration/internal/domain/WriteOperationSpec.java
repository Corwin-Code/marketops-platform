package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.util.UUID;

/**
 * The recorded shape of one write operation against one platform.
 *
 * <p>Every field here is a marketplace fact somebody checked against official
 * documentation and a real account. None of it is defaulted: a specification
 * that does not exist means the operation cannot be performed, which is the
 * fail-closed behaviour the write path relies on.
 *
 * @param capabilityId the capability this belongs to
 * @param platformCode marketplace it applies to
 * @param operation what the call is for
 * @param writeResultModel whether the platform answers synchronously
 * @param requestTemplate how the operation's values are placed in the request
 * @param taskKeyPointer where an asynchronous handle lives, or {@code null}
 * @param taskStatusPointer where a task's status lives, or {@code null}
 * @param taskSuccessValue the platform's own word for finished, or {@code null}
 * @param taskFailureValue the platform's own word for rejected, or {@code null}
 * @param observedPricePointer where an observed price lives, or {@code null}
 * @param observedCurrencyPointer where an observed currency lives, or {@code null}
 * @param endpoint the endpoint the call is made through
 */
public record WriteOperationSpec(
        UUID capabilityId,
        String platformCode,
        String operation,
        String writeResultModel,
        String requestTemplate,
        String taskKeyPointer,
        String taskStatusPointer,
        String taskSuccessValue,
        String taskFailureValue,
        String observedPricePointer,
        String observedCurrencyPointer,
        EndpointCallSpec endpoint,
        String conditionalWriteHeader,
        String acceptedPointer,
        tools.jackson.databind.JsonNode acceptedValue,
        java.util.Set<String> taskPendingValues) {

    public WriteOperationSpec {
        taskPendingValues = taskPendingValues == null ? java.util.Set.of() : java.util.Set.copyOf(taskPendingValues);
        acceptedValue = acceptedValue == null ? null : acceptedValue.deepCopy();
    }

    public WriteOperationSpec(UUID capabilityId, String platformCode, String operation,
            String writeResultModel, String requestTemplate, String taskKeyPointer,
            String taskStatusPointer, String taskSuccessValue, String taskFailureValue,
            String observedPricePointer, String observedCurrencyPointer, EndpointCallSpec endpoint,
            String conditionalWriteHeader) {
        this(capabilityId, platformCode, operation, writeResultModel, requestTemplate,
                taskKeyPointer, taskStatusPointer, taskSuccessValue, taskFailureValue,
                observedPricePointer, observedCurrencyPointer, endpoint, conditionalWriteHeader,
                null, null, java.util.Set.of());
    }

    public WriteOperationSpec(UUID capabilityId, String platformCode, String operation,
            String writeResultModel, String requestTemplate, String taskKeyPointer,
            String taskStatusPointer, String taskSuccessValue, String taskFailureValue,
            String observedPricePointer, String observedCurrencyPointer, EndpointCallSpec endpoint) {
        this(capabilityId, platformCode, operation, writeResultModel, requestTemplate,
                taskKeyPointer, taskStatusPointer, taskSuccessValue, taskFailureValue,
                observedPricePointer, observedCurrencyPointer, endpoint, null);
    }

    /** Whether the platform reports write outcomes through a separate enquiry. */
    public boolean asynchronous() {
        return "ASYNCHRONOUS_TASK".equals(writeResultModel);
    }
}
