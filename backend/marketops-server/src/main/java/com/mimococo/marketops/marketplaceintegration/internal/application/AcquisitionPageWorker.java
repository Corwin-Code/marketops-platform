package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.RawContentRef;
import com.mimococo.marketops.marketplaceintegration.RawCustody;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionRunRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.JdbcAuthorizedAcquisitionGateway;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.RawEvidenceRepository;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import com.mimococo.marketops.productlisting.ListingKeyDirectory;
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
    private final ListingKeyDirectory listingKeys;

    AcquisitionPageWorker(IngestionRunRepository runs,
                          RawEvidenceRepository evidence,
                          PlatformCallSpecRepository callSpecs,
                          RawCustody custody,
                          JdbcAuthorizedAcquisitionGateway gateway,
                          ObjectMapper objectMapper,
                          IdGenerator idGenerator,
                          PlatformTransactionManager transactionManager,
                          ListingKeyDirectory listingKeys) {
        this.listingKeys = listingKeys;
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
        // Whether this call carries a cursor from an earlier page: only then can a
        // "not found" answer mean the listing has ended rather than a wrong request.
        String position = callSpecs.checkpointPosition(context.jobId()).orElse("");
        boolean continuationCall = !position.isEmpty();
        EndpointCallSpec spec = specification.get();
        long keysRecorded = keysRecorded(context.jobId(), spec);
        if (spec.asksOneKeyAtATime() && "KEYS_EXHAUSTED".equals(spec.continuationEndRule())) {
            // Keys asked one at a time are the store's current promotions: having none is normal
            // and there is nothing to ask, so the run ends without a call.
            if (keysRecorded == 0) {
                return new PageOutcome(Kind.END, null);
            }
            position = EndpointCallSpec.keyPosition(position, keysRecorded);
        }
        AcquisitionResult result = gateway.acquire(runId, fence, workerName,
                context.scopeGrantId(), CALL_AUTHORITY, CorrelationId.current());
        RawContentRef content = custody.store(custodyNamespace(context), result.body());
        Continuation continuation = continuationToken(result, spec, continuationCall, position, keysRecorded);
        return transactions.execute(status -> {
            UUID observationId = storeEvidence(runId, context, result, content,continuation.kind());
            if (continuation.kind() == Kind.END || continuation.kind() == Kind.NEXT) {
                runs.acknowledgeCheckpoint(runId, fence, workerName, observationId,
                        runs.checkpointVersion(context.jobId()), continuation.token());
            }
            return new PageOutcome(continuation.kind(), observationId);
        });
    }

    /** How many keys a key-batch endpoint has to ask about; zero for any other endpoint. */
    private long keysRecorded(UUID jobId, EndpointCallSpec spec) {
        if (!"KEYS_EXHAUSTED".equals(spec.continuationEndRule())) {
            return 0;
        }
        ListingKeyDirectory.KeyKind kind = spec.keyKind().orElse(ListingKeyDirectory.KeyKind.LISTING);
        return callSpecs.jobStoreId(jobId).map(store -> (long) listingKeys.keyCount(store, kind)).orElse(0L);
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
                result.failureCode(), result.authorityDecisionId(), result.responseHeaders(),paginationOutcome.name(),
                result.requestKey());
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
        return "NONE".equals(spec.paginationModel()) || computedContinuation(spec) || spec.pagesAfterLastRecord() ||
                (List.of("CURSOR", "OFFSET", "PAGE", "DATE_WINDOW").contains(spec.paginationModel())
                        && spec.continuationPointer() != null
                        && spec.continuationPointer().startsWith("/"));
    }

    /**
     * Whether the next position is computed here rather than returned by the
     * source: an offset or page endpoint whose answer carries no continuation,
     * and whose recorded rule says a short page is the last one.
     */
    static boolean computedContinuation(EndpointCallSpec spec) {
        return spec.continuationPointer() == null
                && ((List.of("OFFSET", "PAGE").contains(spec.paginationModel())
                        && List.of("SHORT_PAGE", "SHORT_PAGE_OR_NOT_FOUND").contains(spec.continuationEndRule()))
                    || ("OFFSET".equals(spec.paginationModel())
                        && "KEYS_EXHAUSTED".equals(spec.continuationEndRule())));
    }

    Continuation continuationToken(AcquisitionResult result, EndpointCallSpec spec,
                                   boolean continuationCall, String position, long keysRecorded) {
        if (!validPagination(spec)) return new Continuation(Kind.CONFIG_INVALID, null);
        String endRule = spec.continuationEndRule() == null ? "JSON_NULL" : spec.continuationEndRule();
        // A source that answers "not found" to a cursor past its last item ends
        // the listing that way. Only a call that carried a cursor, and only an
        // endpoint that recorded this rule, reads that answer as the end; the
        // answer itself is kept, and carries no payload to normalize.
        if ("SHORT_PAGE_OR_NOT_FOUND".equals(endRule) && continuationCall
                && result.responseComplete() && "HTTP 404".equals(result.nativeStatus())) {
            return new Continuation(Kind.END, null);
        }
        if ("UNEXPECTED_CONTENT_TYPE".equals(result.failureCode()) && !result.retryable()) {
            return new Continuation(Kind.SCHEMA_DRIFT,null);
        }
        // A request about one promotion that the source answers "not found" (the promotion ended
        // or was withdrawn after the list was read) says nothing about the other promotions: the
        // answer is kept and the next key is asked. Any other refusal, such as a malformed
        // request or an authentication failure, stops the run as before, so a systematic error
        // is never skipped through key by key.
        if (spec.asksOneKeyAtATime() && result.responseComplete()
                && result.outcome() == AcquisitionResult.AcquisitionOutcome.BUSINESS_FAILURE_BYTES
                && "HTTP 404".equals(result.nativeStatus())) {
            return nextKey(spec, position, keysRecorded);
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
            if (spec.pagesAfterLastRecord()) return afterLastRecord(document, spec, position);
            if (spec.asksOneKeyAtATime()) {
                // Paging inside one promotion is not built: a full page may hide more records, and
                // stopping for a person is better than a list that silently stops at the page size.
                JsonNode records = spec.recordsPointer() == null ? null : document.at(spec.recordsPointer());
                if (records == null || !records.isArray() || records.size() >= EndpointCallSpec.REQUESTED_PAGE_SIZE) {
                    return new Continuation(Kind.SCHEMA_DRIFT, null);
                }
            }
            // A page shorter than the size asked for is the last one, whatever
            // token came with it, when the endpoint recorded that rule.
            if (List.of("SHORT_PAGE", "SHORT_PAGE_OR_NOT_FOUND").contains(endRule)) {
                JsonNode records = spec.recordsPointer() == null ? null : document.at(spec.recordsPointer());
                if (records == null || !records.isArray()) return new Continuation(Kind.SCHEMA_DRIFT, null);
                if (records.size() < EndpointCallSpec.REQUESTED_PAGE_SIZE) {
                    return new Continuation(Kind.END, null);
                }
            }
            if (computedContinuation(spec)) {
                // A full page: the next offset is this one plus the page, the next
                // page number this one plus one.
                boolean paged = "PAGE".equals(spec.paginationModel());
                long current;
                try {
                    current = position.isEmpty() ? (paged ? 1 : 0) : Long.parseLong(position);
                } catch (NumberFormatException notAPosition) {
                    return new Continuation(Kind.CONFIG_INVALID, null);
                }
                long next = paged ? current + 1 : current + spec.keyBatchSize();
                if ("KEYS_EXHAUSTED".equals(endRule) && next >= keysRecorded) {
                    // Every recorded key has been asked, whatever the answer held.
                    return new Continuation(Kind.END, null);
                }
                return new Continuation(Kind.NEXT, Long.toString(next));
            }
            JsonNode token = document.at(spec.continuationPointer());
            if (token.isMissingNode()) return new Continuation(Kind.SCHEMA_DRIFT, null);
            // A cursor terminates on JSON null. The endpoint may also record that
            // its source ends a listing with an empty token, an empty page of
            // records, or either; a recorded fact, never a guess. Absence and a
            // value of another type still never imply END.
            if (token.isNull()) return new Continuation(Kind.END, null);
            if (List.of("EMPTY_RECORDS", "EMPTY_TOKEN_OR_RECORDS").contains(endRule)) {
                JsonNode records = spec.recordsPointer() == null ? null : document.at(spec.recordsPointer());
                if (records == null || !records.isArray()) return new Continuation(Kind.SCHEMA_DRIFT, null);
                if (records.isEmpty()) return new Continuation(Kind.END, null);
            }
            if (List.of("EMPTY_TOKEN", "EMPTY_TOKEN_OR_RECORDS").contains(endRule)
                    && token.isString() && token.asString().isEmpty()) {
                return new Continuation(Kind.END, null);
            }
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

    /**
     * The next position of a source that pages after its last record's key. An empty page ends
     * the listing; otherwise the next request asks after the key of this page's last record. The
     * key goes back into a request as a bare JSON number, so only a positive whole number is
     * taken, and a key equal to the position just asked would ask for the same page again.
     */
    static Continuation afterLastRecord(JsonNode document, EndpointCallSpec spec, String position) {
        JsonNode records = document.at(spec.recordsPointer());
        if (!records.isArray()) {
            return new Continuation(Kind.SCHEMA_DRIFT, null);
        }
        if (records.isEmpty()) {
            return new Continuation(Kind.END, null);
        }
        JsonNode key = records.get(records.size() - 1).at(spec.continuationPointer());
        String next = key.isIntegralNumber() ? key.bigIntegerValue().toString()
                : key.isString() ? key.asString() : "";
        if (!EndpointCallSpec.RECORD_KEY.matcher(next).matches() || next.equals(position)) {
            return new Continuation(Kind.SCHEMA_DRIFT, null);
        }
        return new Continuation(Kind.NEXT, next);
    }

    /** The position after one promotion key, or the end when it was the last recorded one. */
    private static Continuation nextKey(EndpointCallSpec spec, String position, long keysRecorded) {
        long current;
        try {
            current = position.isEmpty() ? 0 : Long.parseLong(position);
        } catch (NumberFormatException notAPosition) {
            return new Continuation(Kind.CONFIG_INVALID, null);
        }
        long next = current + spec.keyBatchSize();
        return next >= keysRecorded ? new Continuation(Kind.END, null) : new Continuation(Kind.NEXT, Long.toString(next));
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
