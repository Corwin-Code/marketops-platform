package com.mimococo.marketops.operatingfacts.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operatingfacts.internal.application.ImportIntakeService;
import com.mimococo.marketops.operatingfacts.internal.application.ManualFactEntryService;
import com.mimococo.marketops.operatingfacts.internal.domain.IntakeDataset;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.ImportRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The internal-fact intake surface: submit a file, read its rejection report,
 * approve it, or enter one fact directly.
 *
 * <p>The declared media type comes from the request rather than from the file
 * name, and the reader validates the content against it. A file whose name says
 * one thing and whose bytes say another is rejected rather than guessed at.
 */
@RestController
@RequestMapping("/api/v1/console/intake")
class IntakeConsoleController {

    private final ImportIntakeService imports;
    private final ManualFactEntryService manualEntry;
    private final BusinessAuthorization authorization;

    IntakeConsoleController(ImportIntakeService imports,
                            ManualFactEntryService manualEntry,
                            BusinessAuthorization authorization) {
        this.imports = imports;
        this.manualEntry = manualEntry;
        this.authorization = authorization;
    }

    /** Submit a file and receive its validation outcome. */
    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ImportRepository.ImportBatch submit(AuthenticatedActor actor,
                                        @RequestParam IntakeDataset dataset,
                                        @RequestParam String mediaType,
                                        @RequestPart("file") MultipartFile file) {
        requireIntake(actor);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException unreadable) {
            throw OperationRejectedException.of(ErrorCode.IMPORT_VALIDATION_FAILED);
        }
        String fileName = file.getOriginalFilename();
        return imports.submit(actor, dataset,
                fileName == null ? "submission.csv" : fileName, mediaType, content);
    }

    /** An organization's submissions, newest first. */
    @GetMapping(value = "/imports", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ImportRepository.ImportBatch> list(AuthenticatedActor actor,
                                            @RequestParam(required = false)
                                            IntakeDataset dataset,
                                            @RequestParam(required = false, defaultValue = "50")
                                            int limit) {
        requireIntake(actor);
        return imports.list(actor.organizationId(), dataset, limit);
    }

    /** One submission. */
    @GetMapping(value = "/imports/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ImportRepository.ImportBatch get(AuthenticatedActor actor, @PathVariable UUID id) {
        requireIntake(actor);
        return imports.require(id);
    }

    /**
     * A submission's rows.
     *
     * <p>This is both the preview and the rejection report. They are one object
     * because they describe the same rows, and two objects would let them
     * disagree about what the file contained.
     */
    @GetMapping(value = "/imports/{id}/rows", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ImportRepository.ImportRow> rows(AuthenticatedActor actor,
                                          @PathVariable UUID id,
                                          @RequestParam(required = false, defaultValue = "false")
                                          boolean rejectedOnly,
                                          @RequestParam(required = false, defaultValue = "100")
                                          int limit) {
        requireIntake(actor);
        return imports.rows(id, rejectedOnly, limit);
    }

    /** Approve a validated submission and write its accepted rows. */
    @PostMapping(value = "/imports/{id}/approval", produces = MediaType.APPLICATION_JSON_VALUE)
    ImportRepository.ImportBatch approve(AuthenticatedActor actor,
                                         @PathVariable UUID id,
                                         @Valid @RequestBody ApprovalRequest request) {
        requireIntake(actor);
        return imports.approveAndApply(actor, id, request.effectiveFrom(),
                request.expectedVersion());
    }

    /** Reject a validated submission without applying anything. */
    @PostMapping(value = "/imports/{id}/rejection", produces = MediaType.APPLICATION_JSON_VALUE)
    ImportRepository.ImportBatch reject(AuthenticatedActor actor,
                                        @PathVariable UUID id,
                                        @Valid @RequestBody RejectionRequest request) {
        requireIntake(actor);
        return imports.reject(actor, id, request.reason(), request.expectedVersion());
    }

    /** Record one purchase cost directly. */
    @PostMapping(value = "/costs", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    EntryCreated enterCost(AuthenticatedActor actor,
                           @Valid @RequestBody CostEntryRequest request) {
        requireIntake(actor);
        return new EntryCreated(manualEntry.enterCost(actor, request.skuCode(),
                request.unitCost(), request.currencyCode(), request.effectiveFrom(),
                request.reason()));
    }

    /** Record one internal stock count directly. */
    @PostMapping(value = "/internal-stock", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    EntryCreated enterStock(AuthenticatedActor actor,
                            @Valid @RequestBody StockEntryRequest request) {
        requireIntake(actor);
        return new EntryCreated(manualEntry.enterInternalStock(actor, request.skuCode(),
                request.warehouseCode(), request.quantityOnHand(), request.quantityReserved(),
                request.observedAt(), request.reason()));
    }

    private void requireIntake(AuthenticatedActor actor) {
        authorization.require(actor, ActionScopeCode.INTERNAL_FACT_INTAKE,
                ResourceScope.organization(actor.organizationId()));
    }

    /** What a direct entry created. */
    record EntryCreated(UUID id) {
    }

    record ApprovalRequest(Instant effectiveFrom, @NotNull Long expectedVersion) {
    }

    record RejectionRequest(@NotBlank String reason, @NotNull Long expectedVersion) {
    }

    record CostEntryRequest(
            @NotBlank String skuCode,
            @NotNull BigDecimal unitCost,
            @NotBlank String currencyCode,
            Instant effectiveFrom,
            @NotBlank String reason) {
    }

    record StockEntryRequest(
            @NotBlank String skuCode,
            @NotBlank String warehouseCode,
            int quantityOnHand,
            Integer quantityReserved,
            Instant observedAt,
            @NotBlank String reason) {
    }
}
