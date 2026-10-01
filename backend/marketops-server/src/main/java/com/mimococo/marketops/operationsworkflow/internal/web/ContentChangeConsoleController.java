package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.marketplaceintegration.ContentCommandView;
import com.mimococo.marketops.operationsworkflow.internal.application.ContentChangeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/**
 * Changing a listing's title and description (W2): what the editor starts from, and the Owner's
 * one confirmation that hands the change to the content worker. What became of it is read from
 * the content commands.
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/content-changes")
class ContentChangeConsoleController {

    private final ContentChangeService changes;

    ContentChangeConsoleController(ContentChangeService changes) {
        this.changes = changes;
    }

    /** The card as the newest catalog facts show it, and its newest change. */
    @GetMapping(value = "/listings/{platformListingVariantId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ContentChangeService.Current current(AuthenticatedActor actor, @PathVariable UUID platformListingVariantId) {
        return changes.current(actor, platformListingVariantId);
    }

    /** Confirm the final title and description; a step-up action. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ContentCommandView confirm(AuthenticatedActor actor, @Valid @RequestBody ConfirmRequest request) {
        return changes.confirm(actor, new ContentChangeService.Confirmation(request.platformListingVariantId(),
                request.title(), request.description(), request.sourceInvocationId(), request.reason()));
    }

    record ConfirmRequest(@NotNull UUID platformListingVariantId, @NotBlank String title,
                          @NotBlank String description, UUID sourceInvocationId, String reason) {
    }
}
