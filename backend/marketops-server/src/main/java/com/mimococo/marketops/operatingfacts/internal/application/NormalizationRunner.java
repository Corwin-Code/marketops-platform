package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.marketplaceintegration.IngestionJobDirectory;
import com.mimococo.marketops.marketplaceintegration.IngestionJobView;
import com.mimococo.marketops.marketplaceintegration.RawEvidenceQuery;
import com.mimococo.marketops.marketplaceintegration.RawObservationView;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.NormalizationDeclarationRepository;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns stored acquisition evidence into canonical operating facts.
 *
 * <p>The runner reads evidence, never a source. Everything it processes is
 * already in custody and already verified, which is what makes reprocessing safe
 * to run at any time: moving the cursor back re-reads bytes rather than
 * re-downloading them, and every fact is unique on the source's own key, so
 * re-reading writes nothing new.
 *
 * <p>It is fail-closed on declarations. A platform and dataset whose payload
 * shape nobody has recorded and verified produces no facts and an explicit
 * reason, rather than a parser guessing at a structure and producing numbers
 * that look real.
 *
 * <p>A field the source sent that no declaration names is recorded as drift. The
 * value is not silently dropped and it is not silently accepted; it becomes an
 * operator queue item pointing at the exact stored bytes that first showed it.
 */
@Service
public class NormalizationRunner {

    private static final Logger log = LoggerFactory.getLogger(NormalizationRunner.class);

    /** How many observations one pass reads. */
    private static final int OBSERVATION_PAGE = 100;

    private final IngestionJobDirectory jobs;
    private final RawEvidenceQuery evidence;
    private final NormalizationDeclarationRepository declarations;
    private final PayloadReader payloadReader;
    private final FactRecorder factRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final TransactionTemplate transactions;

    NormalizationRunner(IngestionJobDirectory jobs,
                        RawEvidenceQuery evidence,
                        NormalizationDeclarationRepository declarations,
                        PayloadReader payloadReader,
                        FactRecorder factRecorder,
                        IdGenerator idGenerator,
                        Clock clock, PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.evidence = evidence;
        this.declarations = declarations;
        this.payloadReader = payloadReader;
        this.factRecorder = factRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Process one page of a job's unprocessed evidence.
     *
     * <p>The cursor advances past every observation the pass examined, including
     * ones that carried a business failure rather than a payload. Leaving those
     * behind would stall the job forever on evidence that will never produce a
     * fact.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public PassOutcome runOnce(UUID jobId) {
        Optional<IngestionJobView> found = jobs.job(jobId);
        if (found.isEmpty()) {
            return PassOutcome.refused(jobId, "JOB_NOT_FOUND");
        }
        IngestionJobView job = found.get();
        if (job.storeId() == null) {
            return PassOutcome.refused(jobId, "JOB_HAS_NO_STORE");
        }

        Optional<NormalizationDeclarationRepository.MappingDeclaration> declaration =
                declarations.liveMapping(job.platformCode(), job.datasetKind());
        if (declaration.isEmpty()) {
            return PassOutcome.refused(jobId, "PAYLOAD_DECLARATION_NOT_VERIFIED");
        }

        NormalizationDeclarationRepository.ProgressCursor cursor = declarations.progress(jobId)
                .orElse(new NormalizationDeclarationRepository.ProgressCursor(
                        null, null, 0L, 0L));
        List<RawObservationView> observations = evidence.observationsAfter(
                jobId, cursor.lastIngestionTime(), cursor.lastObservationId(),
                OBSERVATION_PAGE);
        if (observations.isEmpty()) {
            return new PassOutcome(jobId, 0, 0, 0, "NOTHING_TO_PROCESS");
        }

        Map<String, String> fieldPointers = declarations.fieldPointers(declaration.get().id());
        Map<String, String> valueKinds = declarations.valueKinds(job.datasetKind());
        List<String> requiredFields = declarations.requiredFields(job.datasetKind());

        int factsRecorded = 0;
        int recordsRejected = 0;
        RawObservationView last = observations.getLast();
        for (RawObservationView observation : observations) {
            if (!observation.carriesPayload()) {
                continue;
            }
            Optional<byte[]> body = evidence.verifiedBody(observation.observationId());
            if (body.isEmpty()) {
                // Custody no longer holds content matching the record. The
                // observation is skipped rather than normalized from nothing;
                // reconciliation reports it as a missing object.
                log.atError()
                        .addKeyValue("event", "normalization_evidence_unverifiable")
                        .addKeyValue("observationId", observation.observationId().toString())
                        .addKeyValue("correlationId", CorrelationId.current())
                        .log("Stored evidence could not be verified for normalization");
                return new PassOutcome(jobId, observations.size(), factsRecorded,
                        recordsRejected + 1, "RAW_UNVERIFIABLE");
            }

            PayloadReader.ReadResult read;
            try {
                read = payloadReader.read(body.get(), declaration.get().recordPointer(),
                        fieldPointers, valueKinds);
            } catch (PayloadReader.PayloadUnreadableException unreadable) {
                log.atWarn().addKeyValue("event","normalization_payload_unreadable")
                        .addKeyValue("observationId",observation.observationId())
                        .log("Normalization stopped without advancing its cursor");
                return new PassOutcome(jobId,observations.size(),factsRecorded,recordsRejected+1,"PAYLOAD_UNREADABLE");
            }

            int[] counts;
            try {
                counts = transactions.execute(status -> {
                for (String pointer : read.unmappedPointers()) {
                    declarations.recordDrift(idGenerator.newId(), jobId, declaration.get().id(),
                            pointer, observation.observationId(), clock.instant());
                }
                int accepted = 0;
                int rejected = 0;
                for (CanonicalRecord record : read.records()) {
                    if (!carriesRequiredFields(record, requiredFields)) {
                        rejected++;
                    }
                }
                if (rejected>0) return new int[]{0,rejected};
                for (CanonicalRecord record : read.records()) accepted += factRecorder.record(job, observation, record);
                return new int[]{accepted, rejected};
                });
            } catch (ArithmeticException outOfRange) {
                log.atWarn().addKeyValue("event","normalization_record_out_of_range")
                        .addKeyValue("observationId",observation.observationId())
                        .log("Normalization refused an unrepresentable source value");
                return new PassOutcome(jobId,observations.size(),factsRecorded,recordsRejected+read.records().size(),"RECORD_OUT_OF_RANGE");
            }
            factsRecorded += counts[0];
            recordsRejected += counts[1];
            if (counts[1]>0) return new PassOutcome(jobId,observations.size(),factsRecorded,recordsRejected,"REQUIRED_FIELD_MISSING");
        }

        boolean advanced = declarations.advanceProgress(jobId, last.ingestionTime(),
                last.observationId(), observations.size(), clock.instant(), cursor.version());
        if (!advanced) {
            // Another normalizer moved the cursor while this pass was running.
            // Its facts are already written and idempotent, so nothing is lost;
            // this pass simply reports that it did not own the advance.
            return new PassOutcome(jobId, observations.size(), factsRecorded, recordsRejected,
                    "CURSOR_TAKEN_OVER");
        }
        return new PassOutcome(jobId, observations.size(), factsRecorded, recordsRejected,
                "PROCESSED");
    }

    private static boolean carriesRequiredFields(CanonicalRecord record,
                                                 List<String> requiredFields) {
        return requiredFields.stream().allMatch(field -> record.values().containsKey(field));
    }

    /**
     * What one normalization pass produced.
     *
     * @param jobId the job
     * @param observationsExamined how many observations the pass read
     * @param factsRecorded how many canonical facts it wrote
     * @param recordsRejected how many records it could not use
     * @param reason why the pass stopped where it did
     */
    public record PassOutcome(
            UUID jobId, int observationsExamined, int factsRecorded, int recordsRejected,
            String reason) {

        /** A pass that could not start. */
        static PassOutcome refused(UUID jobId, String reason) {
            return new PassOutcome(jobId, 0, 0, 0, reason);
        }
    }
}
