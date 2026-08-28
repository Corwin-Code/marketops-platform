package com.mimococo.marketops.operatingfacts.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.operatingfacts.internal.application.ImportIntakeService;
import com.mimococo.marketops.operatingfacts.internal.application.NormalizationDeclarationService;
import com.mimococo.marketops.operatingfacts.internal.domain.IntakeDataset;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.NormalizationRegistrationRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maintenance of the two declarations that make facts possible: how a
 * marketplace payload is shaped, and what a company's own file columns mean.
 *
 * <p>Both are recorded evidence rather than code, and both are registered on the
 * loopback maintenance surface for the same reason identity is: they have to
 * exist before anybody can use the console, and they carry a verification state
 * that an operator, not a request, is responsible for.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata")
class OperatingFactsAdminController {

    private final NormalizationDeclarationService declarations;
    private final ImportIntakeService imports;

    OperatingFactsAdminController(NormalizationDeclarationService declarations,
                                  ImportIntakeService imports) {
        this.declarations = declarations;
        this.imports = imports;
    }

    /** Register a marketplace payload shape. It starts unverified. */
    @PostMapping(value = "/normalization-mappings",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    MappingCreated registerMapping(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @Valid @RequestBody RegisterMappingRequest request) {
        return new MappingCreated(declarations.register(operator, request.platformCode(),
                request.datasetKind(), request.mappingVersion(), request.recordPointer(),
                request.fieldPointers(), request.ownerLabel()));
    }

    /** Record verified evidence and start normalizing the dataset. */
    @PostMapping(value = "/normalization-mappings/{id}/verification",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyMapping(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                       @PathVariable UUID id,
                       @Valid @RequestBody VerifyMappingRequest request) {
        declarations.verifyAndActivate(operator, id, request.evidenceRef(),
                request.verifiedSourceTitle(), request.expectedVersion());
    }

    /** Stop normalizing a dataset with this declaration. */
    @PostMapping(value = "/normalization-mappings/{id}/retirement",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retireMapping(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                       @PathVariable UUID id,
                       @Valid @RequestBody RetireRequest request) {
        declarations.retire(operator, id, request.reason(), request.expectedVersion());
    }

    /** Every registered payload shape. */
    @GetMapping(value = "/normalization-mappings", produces = MediaType.APPLICATION_JSON_VALUE)
    List<NormalizationRegistrationRepository.MappingRow> listMappings() {
        return declarations.list();
    }

    /** Register what a company's own file columns mean. */
    @PostMapping(value = "/import-schema-profiles", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void registerProfile(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @Valid @RequestBody RegisterProfileRequest request) {
        imports.registerProfile(operator, request.organizationId(), request.dataset(),
                request.profileCode(), request.profileVersion(), request.displayName(),
                request.columnContract(), request.ownerLabel());
    }

    /** What a registration created. */
    record MappingCreated(UUID id) {
    }

    record RegisterMappingRequest(
            @NotBlank String platformCode,
            @NotBlank String datasetKind,
            int mappingVersion,
            @NotNull String recordPointer,
            @NotEmpty Map<String, String> fieldPointers,
            @NotBlank String ownerLabel) {
    }

    record VerifyMappingRequest(
            @NotBlank String evidenceRef,
            @NotBlank String verifiedSourceTitle,
            @NotNull Long expectedVersion) {
    }

    record RetireRequest(@NotBlank String reason, @NotNull Long expectedVersion) {
    }

    record RegisterProfileRequest(
            @NotNull UUID organizationId,
            @NotNull IntakeDataset dataset,
            @NotBlank String profileCode,
            int profileVersion,
            @NotBlank String displayName,
            @NotEmpty List<Map<String, Object>> columnContract,
            @NotBlank String ownerLabel) {
    }
}
