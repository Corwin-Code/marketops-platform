package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.marketplaceintegration.IngestionJobDirectory;
import com.mimococo.marketops.marketplaceintegration.IngestionJobView;
import com.mimococo.marketops.marketplaceintegration.RawEvidenceQuery;
import com.mimococo.marketops.marketplaceintegration.RawObservationView;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.NormalizationDeclarationRepository;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
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

        Reading main = reading(declaration.get());
        List<Reading> companions = declarations.companionMappings(job.platformCode(), job.datasetKind())
                .stream().map(this::reading).toList();
        // What the companions read is theirs: it is not drift of the main declaration.
        java.util.Set<String> covered = companions.stream()
                .map(companion -> coveredBy(main.declaration(), companion.declaration()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        int factsRecorded = 0;
        int recordsRejected = 0;
        Map<UUID, Optional<IngestionJobDirectory.RunWindow>> windows = new java.util.HashMap<>();
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
            // A declared observation-time field takes the time the source gave
            // the answer, or the time it was stored when the source gave none.
            Instant observedAt = observation.sourceTime() != null
                    ? observation.sourceTime() : observation.ingestionTime();
            // A declared window field takes the window the run asked the source for.
            Optional<IngestionJobDirectory.RunWindow> window = windows.computeIfAbsent(
                    observation.runId(), jobs::runWindow);
            Instant windowFrom = window.map(IngestionJobDirectory.RunWindow::from).orElse(null);
            Instant windowTo = window.map(IngestionJobDirectory.RunWindow::to).orElse(null);

            PayloadReader.ReadResult read;
            try {
                read = payloadReader.read(body.get(), main.declaration().recordPointer(),
                        main.declaration().childPointer(), main.fields(), main.valueKinds(), observedAt,
                        windowFrom, windowTo, observation.requestKey(), covered, true);
            } catch (PayloadReader.PayloadUnreadableException unreadable) {
                log.atWarn().addKeyValue("event","normalization_payload_unreadable")
                        .addKeyValue("observationId",observation.observationId())
                        .log("Normalization stopped without advancing its cursor");
                return new PassOutcome(jobId,observations.size(),factsRecorded,recordsRejected+1,"PAYLOAD_UNREADABLE");
            }

            int[] counts;
            try {
                counts = transactions.execute(status -> recordAll(job, observation, main, read));
            } catch (ArithmeticException outOfRange) {
                log.atWarn().addKeyValue("event","normalization_record_out_of_range")
                        .addKeyValue("observationId",observation.observationId())
                        .log("Normalization refused an unrepresentable source value");
                return new PassOutcome(jobId,observations.size(),factsRecorded,recordsRejected+read.records().size(),"RECORD_OUT_OF_RANGE");
            } catch (FactRecorder.RecordWithoutMeasureException noMeasure) {
                log.atWarn().addKeyValue("event","normalization_record_without_measure")
                        .addKeyValue("observationId",observation.observationId())
                        .log("Normalization stopped at a record whose declared measures are all absent");
                return new PassOutcome(jobId,observations.size(),factsRecorded,recordsRejected+1,"RECORD_WITHOUT_MEASURE");
            }
            factsRecorded += counts[0];
            recordsRejected += counts[1];
            if (counts[1]>0) return new PassOutcome(jobId,observations.size(),factsRecorded,recordsRejected,"REQUIRED_FIELD_MISSING");

            // Companions add facts of their own from the same evidence. They never hold the main
            // facts back: one that cannot read or record this observation is skipped for it, and
            // the pass reports the rejected records and moves on.
            for (Reading companion : companions) {
                int[] added = companion(job, observation, companion, body.get(), observedAt, windowFrom, windowTo);
                factsRecorded += added[0];
                recordsRejected += added[1];
            }
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

    /**
     * The pointer inside a main record that a companion reads: its child records when both read
     * the same records, or its own records when those sit inside the main record — a main
     * declaration that reads the whole answer, with a companion reading a list in it.
     */
    static String coveredBy(NormalizationDeclarationRepository.MappingDeclaration main,
                            NormalizationDeclarationRepository.MappingDeclaration companion) {
        String records = main.recordPointer() == null ? "" : main.recordPointer();
        String companionRecords = companion.recordPointer() == null ? "" : companion.recordPointer();
        return companionRecords.startsWith(records + "/")
                ? companionRecords.substring(records.length()) : companion.childPointer();
    }

    /** One declaration with everything a pass needs to read and check its records. */
    private record Reading(NormalizationDeclarationRepository.MappingDeclaration declaration,
                           Map<String, NormalizationDeclarationRepository.FieldSource> fields,
                           Map<String, String> valueKinds,
                           List<String> requiredFields) {

        String datasetKind() {
            return declaration.datasetKind();
        }
    }

    private Reading reading(NormalizationDeclarationRepository.MappingDeclaration declaration) {
        return new Reading(declaration, declarations.fieldSources(declaration.id()),
                declarations.valueKinds(declaration.datasetKind()),
                declarations.requiredFields(declaration.datasetKind()));
    }

    /**
     * Record drift and every record of one declaration's reading, or nothing when a record lacks
     * a required field: {accepted facts, rejected records}.
     */
    private int[] recordAll(IngestionJobView job, RawObservationView observation, Reading reading,
                            PayloadReader.ReadResult read) {
        for (String pointer : read.unmappedPointers()) {
            declarations.recordDrift(idGenerator.newId(), job.jobId(), reading.declaration().id(),
                    pointer, observation.observationId(), clock.instant());
        }
        // Outside the catalog itself, an item identifier can stand in for the listing keys.
        boolean itemKeyStandsIn = !"LISTING".equals(reading.datasetKind());
        int rejected = 0;
        for (CanonicalRecord record : read.records()) {
            if (!carriesRequiredFields(record, reading.requiredFields(), itemKeyStandsIn)) {
                rejected++;
            }
        }
        if (rejected > 0) {
            return new int[]{0, rejected};
        }
        int accepted = 0;
        for (CanonicalRecord record : read.records()) {
            accepted += factRecorder.record(job, reading.datasetKind(), observation, record);
        }
        return new int[]{accepted, 0};
    }

    /** One companion's facts from one observation, in a transaction of its own. */
    private int[] companion(IngestionJobView job, RawObservationView observation, Reading companion,
                            byte[] body, Instant observedAt, Instant windowFrom, Instant windowTo) {
        String refusal;
        int rejected;
        try {
            PayloadReader.ReadResult read = payloadReader.read(body, companion.declaration().recordPointer(),
                    companion.declaration().childPointer(), companion.fields(), companion.valueKinds(), observedAt,
                    windowFrom, windowTo, observation.requestKey(), java.util.Set.of(), false);
            int[] counts = transactions.execute(status -> recordAll(job, observation, companion, read));
            if (counts[1] == 0) {
                return counts;
            }
            refusal = "REQUIRED_FIELD_MISSING";
            rejected = counts[1];
        } catch (PayloadReader.PayloadUnreadableException unreadable) {
            refusal = "PAYLOAD_UNREADABLE";
            rejected = 1;
        } catch (ArithmeticException | FactRecorder.RecordWithoutMeasureException outOfRange) {
            refusal = "RECORD_OUT_OF_RANGE";
            rejected = 1;
        } catch (RuntimeException unexpected) {
            // Whatever else went wrong stays with the companion: its transaction rolled back, and
            // the main facts of this observation are already recorded.
            refusal = unexpected.getClass().getSimpleName();
            rejected = 1;
        }
        log.atWarn().addKeyValue("event", "normalization_companion_skipped")
                .addKeyValue("observationId", observation.observationId())
                .addKeyValue("datasetKind", companion.datasetKind())
                .addKeyValue("reason", refusal)
                .log("A companion declaration recorded nothing from one observation");
        return new int[]{0, rejected};
    }

    /**
     * Whether a record carries every field its dataset requires.
     *
     * <p>Outside the catalog itself, an item identifier can stand in for the
     * listing and variant keys: the fact recorder resolves it through what the
     * catalog recorded, and a record it cannot resolve produces nothing.
     */
    private static boolean carriesRequiredFields(CanonicalRecord record,
                                                 List<String> requiredFields,
                                                 boolean itemKeyStandsIn) {
        boolean byItem = itemKeyStandsIn && record.values().containsKey(FactRecorder.ITEM_KEY);
        return requiredFields.stream().allMatch(field -> record.values().containsKey(field)
                || (byItem && FactRecorder.VARIANT_KEYS.contains(field)));
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
