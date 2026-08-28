package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Database-owned verification transitions and their scoped read models. */
@Repository
public class RegistryVerificationRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    RegistryVerificationRepository(JdbcClient jdbc,ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    public Optional<Configuration> configuration(UUID account,UUID capability) {
        return jdbc.sql("""
                SELECT snapshot::text AS snapshot,encode(sha256(convert_to(snapshot::text,'UTF8')),'hex') AS digest
                FROM (SELECT platform.registry_configuration_snapshot(c.id) AS snapshot
                    FROM platform.platform_capability c JOIN core.marketplace_account account ON account.platform_code=c.platform_code
                    WHERE c.id=:capability AND account.id=:account) config
                """).param("account",account).param("capability",capability)
                .query((row,index) -> new Configuration(row.getString("digest"),
                    com.mimococo.marketops.shared.JsonValues.object(com.mimococo.marketops.shared.JsonValues.read(mapper,row.getString("snapshot"))))).optional();
    }

    public UUID configure(UUID account,UUID capability,UUID actor,String kind,UUID id,long version,Map<String,Object> definition,String correlation) {
        return jdbc.sql("SELECT platform.configure_registry_draft(:account,:capability,:actor,:kind,:id,:version,CAST(:definition AS jsonb),:correlation)")
                .param("account",account).param("capability",capability).param("actor",actor).param("kind",kind).param("id",id)
                .param("version",version).param("definition",mapper.writeValueAsString(definition)).param("correlation",correlation)
                .query(UUID.class).single();
    }

    public UUID submit(UUID account,UUID capability,UUID actor,List<UUID> endpoints,List<UUID> headers,
                       Map<String,Object> evidence,String digest,String correlation) {
        return jdbc.sql("""
                SELECT platform.submit_registry_verification(:account,:capability,:actor,
                    CAST(:endpoints AS uuid[]),CAST(:headers AS uuid[]),CAST(:evidence AS jsonb),:digest,:correlation)
                """).param("account",account).param("capability",capability).param("actor",actor)
                .param("endpoints",array(endpoints)).param("headers",array(headers)).param("evidence",mapper.writeValueAsString(evidence))
                .param("digest",digest).param("correlation",correlation).query(UUID.class).single();
    }

    public Optional<CaseView> find(UUID id) {
        return jdbc.sql("""
                SELECT id,organization_id,marketplace_account_id,capability_id,state,version,valid_until,
                    submitted_by_user_id,reviewed_by_user_id,evidence_class,
                    (state='APPROVED' AND evidence_class='REAL_ACCOUNT' AND tested_at<=clock_timestamp()
                     AND valid_until>clock_timestamp() AND configuration_snapshot=platform.registry_configuration_snapshot(capability_id)
                     AND platform.capability_evidence_current(marketplace_account_id,capability_id,NULL)) AS current_evidence
                FROM platform.registry_verification_case WHERE id=:id
                """).param("id",id).query((row,index) -> new CaseView(row.getObject("id",UUID.class),
                    row.getObject("organization_id",UUID.class),row.getObject("marketplace_account_id",UUID.class),
                    row.getObject("capability_id",UUID.class),row.getString("state"),row.getLong("version"),
                    row.getTimestamp("valid_until").toInstant(),row.getObject("submitted_by_user_id",UUID.class),
                    row.getObject("reviewed_by_user_id",UUID.class),row.getString("evidence_class"),row.getBoolean("current_evidence"))).optional();
    }

    public void review(UUID id,UUID actor,long version,boolean approve,String correlation) {
        jdbc.sql("SELECT platform.review_registry_verification(:id,:actor,:version,:approve,:correlation)")
                .param("id",id).param("actor",actor).param("version",version).param("approve",approve).param("correlation",correlation)
                .query(String.class).optional();
    }

    public void revoke(UUID id,UUID actor,long version,String correlation) {
        jdbc.sql("SELECT platform.revoke_registry_verification(:id,:actor,:version,:correlation)")
                .param("id",id).param("actor",actor).param("version",version).param("correlation",correlation).query(String.class).optional();
    }

    public void beginRevision(UUID account,UUID capability,UUID actor,String digest,String correlation) {
        jdbc.sql("SELECT platform.begin_registry_revision(:account,:capability,:actor,:digest,:correlation)")
                .param("account",account).param("capability",capability).param("actor",actor)
                .param("digest",digest).param("correlation",correlation).query(String.class).optional();
    }

    private static String array(List<UUID> ids) {
        return "{"+ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","))+"}";
    }

    public record Configuration(String digest,Map<String,Object> snapshot) { }
    public record CaseView(UUID id,UUID organizationId,UUID marketplaceAccountId,UUID capabilityId,String state,
        long version,java.time.Instant validUntil,UUID submittedBy,UUID reviewedBy,String evidenceClass,boolean currentEvidence) { }
}
