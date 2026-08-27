package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.DiagnosticExportView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.DiagnosticExportRepository;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.DiagnosticExportRepository.Manifest;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.marketplaceintegration.RawCustody;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Authenticated export requests and bounded verified downloads, without external I/O in a transaction. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class DiagnosticExportService {
    private final DiagnosticExportRepository exports;
    private final BusinessAuthorization authorization;
    private final RawCustody custody;

    public DiagnosticExportService(DiagnosticExportRepository exports,
            BusinessAuthorization authorization, RawCustody custody) {
        this.exports = exports;
        this.authorization = authorization;
        this.custody = custody;
    }

    /** Queue work without loading metrics, allocating export bytes or contacting storage. */
    public DiagnosticExportView submit(AuthenticatedActor actor, UUID store, MetricWindow window, String key) {
        if (window == null || key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._-]{15,127}")) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        requireScope(actor, store);
        UUID id = exports.submit(actor.userId(), store, window, Digest.ofBytes(key.getBytes(StandardCharsets.UTF_8)));
        return status(actor, id);
    }

    /** Only the requester, with current rights on the stored scope, sees a job. */
    public DiagnosticExportView status(AuthenticatedActor actor, UUID id) {
        DiagnosticExportView result = exports.status(id, actor.userId(), actor.organizationId())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED));
        requireScope(actor, result.storeId());
        return result;
    }

    /** A small hash-bound manifest, distinct from its separately fetched data parts. */
    public Manifest manifest(AuthenticatedActor actor, UUID id) {
        status(actor, id);
        exports.authorizeRead(id, actor.userId(), 0, false);
        Manifest manifest = exports.manifest(id);
        if (manifest.document().getBytes(StandardCharsets.UTF_8).length > 65536
                || !Digest.ofBytes(manifest.document().getBytes(StandardCharsets.UTF_8)).equals(manifest.sha256())) {
            throw OperationRejectedException.of(ErrorCode.EXPORT_INTEGRITY_FAILED);
        }
        return manifest;
    }

    /** Verify custody and reauthorize after I/O; never return a partial or corrupt part. */
    public byte[] part(AuthenticatedActor actor, UUID id, int partNumber) {
        if (partNumber < 1 || partNumber > 64) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        status(actor, id);
        exports.authorizeRead(id, actor.userId(), partNumber, false);
        var part = exports.part(id, partNumber);
        byte[] bytes = custody.readById(part.contentId())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.EXPORT_INTEGRITY_FAILED));
        if (bytes.length != part.byteLength() || bytes.length > 4194304
                || !Digest.ofBytes(bytes).equals(part.sha256())) {
            throw OperationRejectedException.of(ErrorCode.EXPORT_INTEGRITY_FAILED);
        }
        exports.authorizeRead(id, actor.userId(), partNumber, true);
        return bytes;
    }

    private void requireScope(AuthenticatedActor actor, UUID store) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(store));
        authorization.require(actor, ActionScopeCode.EVIDENCE_VIEW, ResourceScope.store(store));
    }
}
