package com.mimococo.marketops.productlisting.internal.application;

import com.mimococo.marketops.productlisting.ListingScopeEvidence;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** The native enumeration evidence writer; existing listing and mapping records are never reconstructed here. */
@Service
public class ListingScopeEvidenceService implements ListingScopeEvidence {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final IdGenerator ids;

    ListingScopeEvidenceService(JdbcClient jdbc, ObjectMapper json, IdGenerator ids) {
        this.jdbc = jdbc;
        this.json = json;
        this.ids = ids;
    }

    @Override
    @Transactional
    public UUID record(UUID organizationId, UUID listingId, UUID provenanceId, Instant recordedAt, Capture capture) {
        if (capture.observedAt().isAfter(recordedAt) || !recordedAt.isBefore(capture.verificationExpiresAt())) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        UUID id = ids.newId();
        jdbc.sql("""
                INSERT INTO core.platform_listing_scope_observation(id,organization_id,platform_listing_id,provenance_id,
                  scope_kind,native_scope_key,native_variant_keys,coverage_state,expected_member_count,continuation_reference,
                  source_reference,scope_basis_reference,observed_at,recorded_at,verification_expires_at)
                VALUES(:id,:org,:listing,:provenance,:kind,:key,:members,:state,:count,:continuation,:source,:basis,:observed,:recorded,:expires)
                """).param("id",id).param("org",organizationId).param("listing",listingId).param("provenance",provenanceId)
                .param("kind",capture.scopeKind()).param("key",capture.nativeScopeKey())
                .param("members",capture.nativeVariantKeys().toArray(String[]::new)).param("state",capture.coverageState())
                .param("count",capture.expectedMemberCount()).param("continuation",capture.continuationReference())
                .param("source",capture.sourceReference()).param("basis",capture.scopeBasisReference())
                .param("observed",Timestamp.from(capture.observedAt())).param("recorded",Timestamp.from(recordedAt))
                .param("expires",Timestamp.from(capture.verificationExpiresAt())).update();
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID listingId, Instant at) {
        return jdbc.sql("""
                WITH evidence AS (SELECT core.lc_listing_identity_snapshot(:listing,:at) AS value)
                SELECT value::text AS snapshot,encode(sha256(convert_to(value::text,'UTF8')),'hex') AS digest FROM evidence
                """).param("listing",listingId).param("at",Timestamp.from(at))
                .query((rs,n) -> {
                    String snapshot=rs.getString("snapshot");
                    if(snapshot==null) throw OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND);
                    return new Snapshot(rs.getString("digest"),json.readTree(snapshot));
                }).single();
    }
}
