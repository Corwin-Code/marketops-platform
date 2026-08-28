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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One acquisition page: call, keep the bytes, record what was observed, move the
 * cursor.
 *
 * <p>The call authority commits before I/O. The provider call and custody write
 * hold no business transaction. A short result transaction records the
 * observation together with any eligible cursor acknowledgement, so a crash
 * leaves the cursor behind the evidence, never ahead of it.
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
    private final TransactionTemplate transactions;

    AcquisitionPageWorker(IngestionRunRepository runs,
                          RawEvidenceRepository evidence,
                          PlatformCallSpecRepository callSpecs,
                          RawCustody custody,
                          JdbcAuthorizedAcquisitionGateway gateway,
                          ObjectMapper objectMapper,
                          IdGenerator idGenerator,
                          PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.evidence = evidence;
        this.callSpecs = callSpecs;
        this.custody = custody;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Acquire, store and acknowledge one page. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public PageOutcome acquireOnePage(UUID runId,
                                      long fence,
                                      String workerName,
                                      IngestionRunRepository.JobExecutionContext context) {
        Optional<EndpointCallSpec> specification = callSpecs.findVerifiedSpec(context.endpointId());
        if (specification.isEmpty() || !validPagination(specification.get())) {
            return new PageOutcome(Kind.CONFIG_INVALID, null);
        }
        AcquisitionResult result = gateway.acquire(runId, fence, workerName,
                context.scopeGrantId(), CALL_AUTHORITY, CorrelationId.current());
        RawContentRef content = custody.store(custodyNamespace(context), result.body());
        Continuation continuation = continuationToken(result, specification.get());
        return transactions.execute(status -> {
            UUID observationId = storeEvidence(runId, context, result, content,continuation.kind());
            if (continuation.kind() == Kind.END || continuation.kind() == Kind.NEXT) {
                runs.acknowledgeCheckpoint(runId, fence, workerName, observationId,
                        runs.checkpointVersion(context.jobId()), continuation.token());
            }
            return new PageOutcome(continuation.kind(), observationId);
        });
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
                               AcquisitionResult result, RawContentRef content, Kind paginationOutcome) {
        String sourceUnitKey = Digest.ofComponents(List.of(
                context.jobCode(), context.datasetKind(), content.sha256()));
        UUID unitId = evidence.recordLogicalUnit(idGenerator.newId(), context.jobId(),
                context.marketplaceAccountId(), context.datasetKind(), sourceUnitKey,
                result.sourceTime());
        if (result.callSeq() == null || result.authorityDecisionId() == null) {
            throw OperationRejectedException.of(ErrorCode.INTERNAL_ERROR);
        }
        int callSeq = result.callSeq();
        UUID observationId = idGenerator.newId();
        evidence.recordObservation(observationId, runId, unitId, content.contentId(),
                callSeq, result.nativeStatus(), result.outcome().name(), result.responseComplete(),
                result.failureCode(), result.authorityDecisionId(), result.responseHeaders(),paginationOutcome.name());
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
    static boolean validPagination(EndpointCallSpec spec) {
        return "NONE".equals(spec.paginationModel()) ||
                (List.of("CURSOR", "OFFSET", "PAGE", "DATE_WINDOW").contains(spec.paginationModel())
                        && spec.continuationPointer() != null
                        && spec.continuationPointer().startsWith("/"));
    }

    Continuation continuationToken(AcquisitionResult result, EndpointCallSpec spec) {
        if (!validPagination(spec)) return new Continuation(Kind.CONFIG_INVALID, null);
        if ("UNEXPECTED_CONTENT_TYPE".equals(result.failureCode()) && !result.retryable()) {
            return new Continuation(Kind.SCHEMA_DRIFT,null);
        }
        if (!result.responseComplete() || result.outcome() != AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES) {
            return new Continuation(result.retryable() ? Kind.RETRY_LATER : Kind.UNKNOWN_RESULT, null);
        }
        try {
            JsonNode document = com.mimococo.marketops.shared.JsonValues.read(objectMapper,result.body());
            if (document == null || (!document.isObject() && !document.isArray())) {
                return new Continuation(Kind.UNREADABLE, null);
            }
            if ("NONE".equals(spec.paginationModel())) return new Continuation(Kind.END, null);
            JsonNode token = document.at(spec.continuationPointer());
            if (token.isMissingNode()) return new Continuation(Kind.SCHEMA_DRIFT, null);
            // The declared cursor contract terminates on JSON null. Absence,
            // an empty string, and a value of another type never imply END.
            if (token.isNull()) return new Continuation(Kind.END, null);
            if (List.of("OFFSET","PAGE").contains(spec.paginationModel())) {
                if (!token.isIntegralNumber() || !token.canConvertToLong()
                        || token.longValue() < ("PAGE".equals(spec.paginationModel()) ? 1 : 0)) {
                    return new Continuation(Kind.SCHEMA_DRIFT,null);
                }
                return new Continuation(Kind.NEXT,Long.toString(token.longValue()));
            }
            if (!token.isString() || token.asString().isBlank()
                    || token.asString().length() > 2048
                    || token.asString().chars().anyMatch(Character::isISOControl)) {
                return new Continuation(Kind.SCHEMA_DRIFT, null);
            }
            return new Continuation(Kind.NEXT, token.asString());
        } catch (JacksonException | IllegalArgumentException unreadable) {
            return new Continuation(Kind.UNREADABLE, null);
        }
    }

    record Continuation(Kind kind, String token) { }

    private static String custodyNamespace(IngestionRunRepository.JobExecutionContext context) {
        return (CUSTODY_NAMESPACE_PREFIX + "-" + context.platformCode())
                .toLowerCase(Locale.ROOT);
    }

    /** What one page attempt produced. */
    public enum Kind {

        /** Bytes were stored and the cursor advanced to a further page. */
        NEXT,

        /** Bytes were stored and the source declared no further page. */
        END,

        /** The answer could not be classified; the run stops for a person. */
        UNKNOWN_RESULT, SCHEMA_DRIFT, UNREADABLE, CONFIG_INVALID, RETRY_LATER
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
