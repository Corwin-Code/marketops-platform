package com.mimococo.marketops.operatingfacts.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.identityaccess.OwnedResource;
import com.mimococo.marketops.operatingfacts.EvidenceQuery;
import com.mimococo.marketops.operatingfacts.EvidenceTrail;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drill-through from a canonical fact to the source that produced it.
 *
 * <p>The trail is available to anyone who may view evidence. The bytes are a
 * separate call because they are the source's own payload: they can be large,
 * and they are the one place a marketplace's raw content reaches a person, so
 * the decision to hand them over is made explicitly rather than as part of
 * rendering a page.
 *
 * <p>Content is returned as an attachment with a generic media type. A payload a
 * marketplace produced is untrusted input, and letting a browser render it
 * inline would let a source choose what appears inside the console.
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/evidence")
class EvidenceConsoleController {

    /**
     * Stops a browser from guessing a payload's type.
     *
     * <p>A marketplace's own bytes are untrusted input. Without this, content
     * sniffing could let a source decide that its payload is markup and have it
     * rendered inside the console's origin.
     */
    private static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";

    private final EvidenceQuery evidence;
    private final BusinessAuthorization authorization;

    EvidenceConsoleController(EvidenceQuery evidence, BusinessAuthorization authorization) {
        this.evidence = evidence;
        this.authorization = authorization;
    }

    /** Where one fact came from. */
    @GetMapping(value = "/{provenanceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    EvidenceTrail trail(AuthenticatedActor actor, @PathVariable UUID provenanceId) {
        requireEvidenceView(actor, provenanceId);
        return evidence.trail(provenanceId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** Where several facts came from, for one metric's evidence panel. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<EvidenceTrail> trails(AuthenticatedActor actor,
                               @RequestParam List<UUID> provenanceId) {
        if (provenanceId.isEmpty() || provenanceId.size() > 200) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        provenanceId.forEach(id -> requireEvidenceView(actor, id));
        return evidence.trails(provenanceId);
    }

    /**
     * The exact stored bytes behind one fact.
     *
     * <p>An empty result is not a missing page: it means custody no longer holds
     * content matching the record, which is a reconciliation finding, so the
     * refusal names that rather than reporting the fact as unknown.
     */
    @GetMapping(value = "/{provenanceId}/content",
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<byte[]> content(AuthenticatedActor actor,
                                   @PathVariable UUID provenanceId) {
        requireEvidenceView(actor, provenanceId);
        byte[] body = evidence.verifiedBytes(provenanceId)
                .orElseThrow(() -> OperationRejectedException.of(
                        ErrorCode.OBJECT_STORAGE_VERIFICATION_FAILED));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("evidence-" + provenanceId + ".bin")
                        .build()
                        .toString())
                .header(CONTENT_TYPE_OPTIONS_HEADER, "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    private void requireEvidenceView(AuthenticatedActor actor, UUID provenanceId) {
        authorization.requireOwned(actor, ActionScopeCode.EVIDENCE_VIEW,
                new OwnedResource(OwnedResource.Kind.PROVENANCE, provenanceId));
    }
}
