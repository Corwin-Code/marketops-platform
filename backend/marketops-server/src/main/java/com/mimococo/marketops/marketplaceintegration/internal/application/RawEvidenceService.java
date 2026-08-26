package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.RawContentRef;
import com.mimococo.marketops.marketplaceintegration.RawCustody;
import com.mimococo.marketops.marketplaceintegration.RawEvidenceQuery;
import com.mimococo.marketops.marketplaceintegration.RawObservationView;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.RawEvidenceRepository;
import com.mimococo.marketops.shared.CorrelationId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Published reading of stored acquisition evidence.
 *
 * <p>Bytes are verified on every read rather than trusted from the custody
 * record. A record whose content has gone missing or changed is a
 * reconciliation finding, and returning it silently would let a replay produce
 * facts from something other than what was acquired.
 */
@Service
public class RawEvidenceService implements RawEvidenceQuery {

    private static final Logger log = LoggerFactory.getLogger(RawEvidenceService.class);

    private final RawEvidenceRepository evidence;
    private final RawCustody custody;

    RawEvidenceService(RawEvidenceRepository evidence, RawCustody custody) {
        this.evidence = evidence;
        this.custody = custody;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RawObservationView> observationsAfter(UUID jobId,
                                                      Instant afterIngestionTime,
                                                      UUID afterObservationId,
                                                      int limit) {
        return evidence
                .observationsAfter(jobId, afterIngestionTime, afterObservationId,
                        Math.clamp(limit, 1, 500))
                .stream()
                .map(stored -> view(stored, jobId))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RawObservationView> observation(UUID observationId) {
        return evidence.findObservation(observationId)
                .map(stored -> view(stored, null));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<byte[]> verifiedBody(UUID observationId) {
        Optional<RawEvidenceRepository.StoredObservation> stored =
                evidence.findObservation(observationId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        RawContentRef reference = new RawContentRef(stored.get().contentId(),
                stored.get().sha256(), stored.get().byteLength(), stored.get().objectRef());
        Optional<byte[]> body = custody.read(reference);
        if (body.isEmpty() || !custody.verify(reference)) {
            log.atError()
                    .addKeyValue("event", "raw_evidence_object_unverifiable")
                    .addKeyValue("observationId", observationId.toString())
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("Stored evidence no longer matches its custody record");
            return Optional.empty();
        }
        return body;
    }

    private static RawObservationView view(RawEvidenceRepository.StoredObservation stored,
                                           UUID jobId) {
        return new RawObservationView(
                stored.id(), jobId, stored.runId(), stored.unitKind(), stored.sourceUnitKey(),
                stored.sourceTime(), stored.nativeStatus(), stored.outcomeClass(),
                stored.ingestionTime(), stored.sha256(), stored.byteLength());
    }
}
