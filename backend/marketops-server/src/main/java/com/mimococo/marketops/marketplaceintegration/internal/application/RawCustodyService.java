package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.RawContentRef;
import com.mimococo.marketops.marketplaceintegration.RawCustody;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.RawContentRepository;
import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that reaches the object store.
 *
 * <p>Custody is content-addressed: the locator is derived from the digest, so
 * the same bytes always land in the same place and two callers converge on one
 * record instead of racing. The digest is computed here rather than accepted
 * from a caller, because custody that trusted a supplied digest would store
 * bytes under a name that does not describe them.
 *
 * <p>The order of operations is the durability argument. Bytes are written,
 * read back and compared before the custody record is inserted, so a committed
 * record always refers to content that was verified after the write. A caller
 * that later acknowledges a cursor or accepts an import against that record is
 * therefore acknowledging against evidence rather than against an intention.
 */
@Service
public class RawCustodyService implements RawCustody {

    private static final Logger log = LoggerFactory.getLogger(RawCustodyService.class);

    /** The bucket segment of every locator this deployment writes. */
    private static final String CUSTODY_BUCKET = "marketops-raw";

    /** Namespace shape accepted inside a locator. */
    private static final Pattern NAMESPACE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    private final ObjectStoragePort objectStorage;
    private final RawContentRepository contents;
    private final IdGenerator idGenerator;

    RawCustodyService(ObjectStoragePort objectStorage,
                      RawContentRepository contents,
                      IdGenerator idGenerator) {
        this.objectStorage = objectStorage;
        this.contents = contents;
        this.idGenerator = idGenerator;
    }

    @Override
    @Transactional
    public RawContentRef store(String namespace, byte[] body) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (body == null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }

        String sha256 = Digest.ofBytes(body).toLowerCase(Locale.ROOT);
        Optional<RawContentRef> existing = contents.findByDigest(sha256);
        if (existing.isPresent()) {
            // Identical bytes are one custody record. Re-verifying is what makes
            // a replay of stored evidence prove custody rather than assume it.
            return requireVerified(existing.get());
        }

        String objectRef = locatorFor(namespace, sha256);
        objectStorage.putIfAbsent(objectRef, body);
        if (!objectStorage.verify(objectRef, sha256)) {
            log.atError()
                    .addKeyValue("event", "raw_custody_verification_failed")
                    .addKeyValue("namespace", namespace)
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("Stored content did not read back with its written digest");
            throw OperationRejectedException.of(ErrorCode.OBJECT_STORAGE_VERIFICATION_FAILED);
        }

        contents.recordIfAbsent(idGenerator.newId(), sha256, body.length, objectRef);
        return contents.findByDigest(sha256)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.INTERNAL_ERROR));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<byte[]> read(RawContentRef reference) {
        return objectStorage.read(reference.objectRef());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verify(RawContentRef reference) {
        return objectStorage.verify(reference.objectRef(), reference.sha256());
    }

    private RawContentRef requireVerified(RawContentRef reference) {
        if (!objectStorage.verify(reference.objectRef(), reference.sha256())) {
            log.atError()
                    .addKeyValue("event", "raw_custody_object_missing")
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("A custody record no longer resolves to matching content");
            throw OperationRejectedException.of(ErrorCode.OBJECT_STORAGE_VERIFICATION_FAILED);
        }
        return reference;
    }

    /**
     * Derive the locator from the digest.
     *
     * <p>The two-character fan-out keeps a single directory or key prefix from
     * accumulating every object ever stored, which matters for both a filesystem
     * store and an object store's listing behaviour.
     */
    private static String locatorFor(String namespace, String sha256) {
        return "object-ref://" + CUSTODY_BUCKET + "/" + namespace + "/"
                + sha256.substring(0, 2) + "/" + sha256;
    }
}
