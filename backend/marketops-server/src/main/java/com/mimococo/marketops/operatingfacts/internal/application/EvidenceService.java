package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.marketplaceintegration.RawEvidenceQuery;
import com.mimococo.marketops.marketplaceintegration.RawObservationView;
import com.mimococo.marketops.operatingfacts.EvidenceQuery;
import com.mimococo.marketops.operatingfacts.EvidenceTrail;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.ImportRepository;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.ProvenanceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a canonical fact back to the thing that produced it.
 *
 * <p>The trail is assembled from whichever source the provenance names. A
 * marketplace fact resolves through the acquisition evidence and carries the
 * transport's own status words and the digest of the stored bytes; an imported
 * fact resolves to the submitted file; a hand-entered fact resolves to the
 * person who entered it and what they said.
 *
 * <p>Reading the bytes goes through the acquisition module's verified path, so a
 * caller receives content that still matches the digest it was stored under or
 * receives nothing at all.
 */
@Service
public class EvidenceService implements EvidenceQuery {

    private final ProvenanceRepository provenance;
    private final ImportRepository imports;
    private final RawEvidenceQuery rawEvidence;

    EvidenceService(ProvenanceRepository provenance,
                    ImportRepository imports,
                    RawEvidenceQuery rawEvidence) {
        this.provenance = provenance;
        this.imports = imports;
        this.rawEvidence = rawEvidence;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EvidenceTrail> trail(UUID provenanceId) {
        return provenance.find(provenanceId).map(this::assemble);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvidenceTrail> trails(List<UUID> provenanceIds) {
        return provenanceIds.stream()
                .map(this::trail)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<byte[]> verifiedBytes(UUID provenanceId) {
        return provenance.find(provenanceId)
                .map(ProvenanceRepository.ProvenanceRow::rawObservationId)
                .flatMap(observationId -> observationId == null
                        ? Optional.empty() : rawEvidence.verifiedBody(observationId));
    }

    private EvidenceTrail assemble(ProvenanceRepository.ProvenanceRow row) {
        Optional<RawObservationView> observation = row.rawObservationId() == null
                ? Optional.empty() : rawEvidence.observation(row.rawObservationId());
        Optional<ImportRepository.ImportBatch> batch = row.importBatchId() == null
                ? Optional.empty() : imports.findBatch(row.importBatchId());
        return new EvidenceTrail(
                row.id(),
                row.sourceKind(),
                row.sourceTime(),
                row.ingestionTime(),
                row.rawObservationId(),
                observation.map(RawObservationView::nativeStatus).orElse(null),
                observation.map(RawObservationView::sha256).orElse(null),
                observation.map(RawObservationView::byteLength).orElse(null),
                row.importBatchId(),
                batch.map(ImportRepository.ImportBatch::declaredFileName).orElse(null),
                row.recordedByUserId(),
                row.evidenceNote());
    }
}
