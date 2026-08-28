package com.mimococo.marketops.analyticsdecision.internal.web;

import com.mimococo.marketops.analyticsdecision.DiagnosticExportView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.internal.application.DiagnosticExportService;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.DiagnosticExportRepository.Manifest;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.shared.ConsoleApi;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Async-only large output API; no endpoint streams an unbounded result set. */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/diagnosis")
public class DiagnosticExportController {
    private final DiagnosticExportService exports;

    public DiagnosticExportController(DiagnosticExportService exports) {
        this.exports = exports;
    }

    /** An idempotent request returns a small 202 job handle, never diagnostic bytes. */
    @PostMapping("/stores/{storeId}/exports")
    ResponseEntity<DiagnosticExportView> submit(AuthenticatedActor actor, @PathVariable UUID storeId,
            @RequestParam(defaultValue = "D30") MetricWindow window,
            @RequestHeader("Idempotency-Key") String key) {
        var job = exports.submit(actor, storeId, window, key);
        return ResponseEntity.accepted().location(URI.create("/api/v1/console/diagnosis/exports/" + job.id()))
                .headers(safeHeaders()).header("Retry-After", "2").body(job);
    }

    /** Current job state, reauthorized against its actual requester and store. */
    @GetMapping("/exports/{id}")
    ResponseEntity<DiagnosticExportView> status(AuthenticatedActor actor, @PathVariable UUID id) {
        return ResponseEntity.ok().headers(safeHeaders()).body(exports.status(actor, id));
    }

    /** The exact manifest text and hash form a bounded, independently verifiable envelope. */
    @GetMapping("/exports/{id}/manifest")
    ResponseEntity<Manifest> manifest(AuthenticatedActor actor, @PathVariable UUID id) {
        return ResponseEntity.ok().headers(safeHeaders()).body(exports.manifest(actor, id));
    }

    /** A verified attachment with a server-generated name, never a browser-rendered document. */
    @GetMapping("/exports/{id}/parts/{part}")
    ResponseEntity<byte[]> part(AuthenticatedActor actor, @PathVariable UUID id, @PathVariable int part) {
        byte[] body = exports.part(actor, id, part);
        return ResponseEntity.ok().headers(safeHeaders()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"diagnostic-" + id + "-" + part + ".ndjson\"")
                .contentLength(body.length).body(body);
    }

    private static HttpHeaders safeHeaders() {
        var headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        return headers;
    }
}
