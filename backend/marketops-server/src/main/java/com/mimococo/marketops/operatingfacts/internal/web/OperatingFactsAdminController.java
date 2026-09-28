package com.mimococo.marketops.operatingfacts.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.operatingfacts.internal.application.ImportIntakeService;
import com.mimococo.marketops.operatingfacts.internal.application.NormalizationDeclarationService;
import com.mimococo.marketops.operatingfacts.internal.application.NormalizationRunner;
import com.mimococo.marketops.operatingfacts.internal.domain.IntakeDataset;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.NormalizationRegistrationRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    /** The most passes one request may run; each pass reads at most one batch of observations. */
    private static final int MAXIMUM_PASSES = 50;

    private final NormalizationDeclarationService declarations;
    private final ImportIntakeService imports;
    private final NormalizationRunner normalization;

    OperatingFactsAdminController(NormalizationDeclarationService declarations,
                                  ImportIntakeService imports,
                                  NormalizationRunner normalization) {
        this.declarations = declarations;
        this.imports = imports;
        this.normalization = normalization;
    }

    /**
     * Normalize what one acquisition job has stored, now.
     *
     * <p>Passes run until the job has nothing left, a pass stops for a reason a
     * person has to look at, or the pass limit is reached. Each pass is the
     * runner's own transaction and cursor, exactly as a scheduled pass would
     * be; the operator only chooses when.
     */
    @PostMapping(value = "/ingestion-jobs/{jobId}/normalization-passes",
            produces = MediaType.APPLICATION_JSON_VALUE)
    NormalizationSummary normalize(@PathVariable UUID jobId,
                                   @RequestParam(defaultValue = "20") int maximumPasses) {
        int limit = Math.max(1, Math.min(MAXIMUM_PASSES, maximumPasses));
        int passes = 0;
        int examined = 0;
        int recorded = 0;
        int rejected = 0;
        String reason = "NOT_STARTED";
        while (passes < limit) {
            NormalizationRunner.PassOutcome outcome = normalization.runOnce(jobId);
            passes++;
            examined += outcome.observationsExamined();
            recorded += outcome.factsRecorded();
            rejected += outcome.recordsRejected();
            reason = outcome.reason();
            if (!"PROCESSED".equals(reason)) {
                break;
            }
        }
        return new NormalizationSummary(jobId, passes, examined, recorded, rejected, reason);
    }

    /** Register a marketplace payload shape. It starts unverified. */
    @PostMapping(value = "/normalization-mappings",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    MappingCreated registerMapping(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @Valid @RequestBody RegisterMappingRequest request) {
        Map<String, NormalizationDeclarationService.SourceDeclaration> sources = new LinkedHashMap<>();
        if (request.fieldSources() != null) {
            request.fieldSources().forEach((field, source) -> sources.put(field, source == null ? null
                    : new NormalizationDeclarationService.SourceDeclaration(source.kind(),
                            source.pointer(), source.value(), source.valueMap())));
        }
        return new MappingCreated(declarations.register(operator, request.platformCode(),
                request.datasetKind(), request.mappingVersion(), request.recordPointer(),
                request.childPointer(), request.fieldPointers(), sources, request.ownerLabel()));
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

    /**
     * What a normalization request did.
     *
     * @param lastReason why the last pass stopped: {@code NOTHING_TO_PROCESS} when
     *        everything stored is normalized, {@code PROCESSED} when the pass limit
     *        was reached first, anything else when a person has to look
     */
    record NormalizationSummary(UUID jobId, int passes, int observationsExamined,
                                int factsRecorded, int recordsRejected, String lastReason) {
    }

    record RegisterMappingRequest(
            @NotBlank String platformCode,
            @NotBlank String datasetKind,
            int mappingVersion,
            @NotNull String recordPointer,
            String childPointer,
            @NotEmpty Map<String, String> fieldPointers,
            Map<String, FieldSourceRequest> fieldSources,
            @NotBlank String ownerLabel) {
    }

    /**
     * One non-pointer field source: {@code PARENT_POINTER} with a pointer,
     * {@code OBSERVATION_TIME}, {@code WINDOW_START}, {@code WINDOW_END}, {@code CONSTANT}
     * with a value, or {@code POINTER}
     * with a value map.
     */
    record FieldSourceRequest(String kind, String pointer, String value, Map<String, String> valueMap) {
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
