package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.RawContentRef;
import com.mimococo.marketops.marketplaceintegration.RawCustody;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionRunRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.JdbcAuthorizedAcquisitionGateway;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.RawEvidenceRepository;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One acquisition page: call, keep the bytes, record what was observed, move the
 * cursor.
 *
 * <p>All four happen in one transaction, and the order between them is the
 * durability argument. The custody write is verified before the record that
 * names it exists; the record commits with the observation; and the cursor
 * acknowledgement, inside the same transaction, can only succeed against an
 * observation that is committing with it. A crash at any point leaves the cursor
 * behind the evidence, never ahead of it.
 *
 * <p>The class is a separate bean from the run orchestration on purpose. A
 * transaction boundary declared on a method that its own class calls is not a
 * transaction boundary at all, and this one carries the guarantee that a cursor
 * never outruns stored bytes.
 */
@Service
public class AcquisitionPageWorker {

    /** How much of the authority window one call is granted. */
    private static final Duration CALL_AUTHORITY = Duration.ofSeconds(30);

    /** Custody namespace prefix; the platform completes it. */
    private static final String CUSTODY_NAMESPACE_PREFIX = "acquisition";

    private final IngestionRunRepository runs;
    private final RawEvidenceRepository evidence;
    private final PlatformCallSpecRepository callSpecs;
    private final RawCustody custody;
    private final JdbcAuthorizedAcquisitionGateway gateway;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;

    AcquisitionPageWorker(IngestionRunRepository runs,
                          RawEvidenceRepository evidence,
                          PlatformCallSpecRepository callSpecs,
                          RawCustody custody,
                          JdbcAuthorizedAcquisitionGateway gateway,
                          ObjectMapper objectMapper,
                          IdGenerator idGenerator) {
        this.runs = runs;
        this.evidence = evidence;
        this.callSpecs = callSpecs;
        this.custody = custody;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    /** Acquire, store and acknowledge one page. */
    @Transactional
    public PageOutcome acquireOnePage(UUID runId,
                                      long fence,
                                      String workerName,
                                      IngestionRunRepository.JobExecutionContext context) {
        AcquisitionResult result = gateway.acquire(runId, fence, workerName,
                context.scopeGrantId(), CALL_AUTHORITY, CorrelationId.current());

        UUID observationId = storeEvidence(runId, context, result);
        if (result.outcome() == AcquisitionResult.AcquisitionOutcome.UNKNOWN_STATE) {
            // The evidence is kept and the cursor is not moved. An answer that
            // cannot be classified does not say whether the source produced
            // anything, so advancing past it could skip real data forever.
            return new PageOutcome(Kind.UNKNOWN_RESULT, observationId);
        }

        Optional<String> continuation = continuationToken(result, context);
        runs.acknowledgeCheckpoint(runId, fence, workerName, observationId,
                runs.checkpointVersion(context.jobId()), continuation.orElse(null));
        return new PageOutcome(
                continuation.isPresent() ? Kind.PAGE_STORED : Kind.SOURCE_EXHAUSTED,
                observationId);
    }

    /**
     * Put the returned bytes into custody and record what was observed.
     *
     * <p>A business failure is stored exactly like a success. A marketplace that
     * answers "this account may not read that" has told us something worth
     * keeping, and discarding it would leave a gap where the explanation of a
     * missing metric should be.
     */
    private UUID storeEvidence(UUID runId,
                               IngestionRunRepository.JobExecutionContext context,
                               AcquisitionResult result) {
        RawContentRef content = custody.store(custodyNamespace(context), result.body());
        String sourceUnitKey = Digest.ofComponents(List.of(
                context.jobCode(), context.datasetKind(), content.sha256()));
        UUID unitId = evidence.recordLogicalUnit(idGenerator.newId(), context.jobId(),
                context.marketplaceAccountId(), context.datasetKind(), sourceUnitKey,
                result.sourceTime());
        int callSeq = runs.findRun(runId)
                .map(IngestionRunRepository.RunState::lastCallSeq)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.INTERNAL_ERROR));
        UUID observationId = idGenerator.newId();
        evidence.recordObservation(observationId, runId, unitId, content.contentId(),
                callSeq, result.nativeStatus(), result.outcome().name());
        return observationId;
    }

    /**
     * The source's own continuation token, when the endpoint declares where it
     * lives.
     *
     * <p>Reading it requires knowing the payload's shape, which is a recorded
     * fact rather than something this class can assume. An endpoint with no
     * declared continuation pointer yields one page per run, which is the honest
     * behaviour: reading a second page would mean guessing where the first
     * ended.
     */
    private Optional<String> continuationToken(
            AcquisitionResult result,
            IngestionRunRepository.JobExecutionContext context) {
        Optional<String> pointer = callSpecs.findVerifiedSpec(context.endpointId())
                .map(spec -> spec.continuationPointer())
                .filter(declared -> declared != null && !declared.isBlank());
        if (pointer.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode document =
                    objectMapper.readTree(new String(result.body(), StandardCharsets.UTF_8));
            JsonNode token = document.at(pointer.get());
            if (token.isMissingNode() || token.isNull()) {
                return Optional.empty();
            }
            String value = token.asString();
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (JacksonException unreadable) {
            // A payload that does not parse is still evidence and is already
            // stored. It simply cannot say where the next page begins.
            return Optional.empty();
        }
    }

    private static String custodyNamespace(IngestionRunRepository.JobExecutionContext context) {
        return (CUSTODY_NAMESPACE_PREFIX + "-" + context.platformCode())
                .toLowerCase(Locale.ROOT);
    }

    /** What one page attempt produced. */
    public enum Kind {

        /** Bytes were stored and the cursor advanced to a further page. */
        PAGE_STORED,

        /** Bytes were stored and the source declared no further page. */
        SOURCE_EXHAUSTED,

        /** The answer could not be classified; the run stops for a person. */
        UNKNOWN_RESULT
    }

    /**
     * The result of one page attempt.
     *
     * @param kind what happened
     * @param observationId the evidence that was recorded
     */
    public record PageOutcome(Kind kind, UUID observationId) {
    }
}
