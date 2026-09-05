package com.mimococo.marketops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** A complete graph of a fictional protocol. No production platform or credential is used. */
public final class AdvertisingR1Fixture {
    private static final Map<String, UUID> TEMPLATE_IDS = Map.ofEntries(
            Map.entry("organization", UUID.fromString("8689c119-8fa0-50b7-8ba2-f9bf3039d336")),
            Map.entry("legalEntity", UUID.fromString("8f17abcd-c8f2-5dbb-af7d-e0dd234dc59a")),
            Map.entry("account", UUID.fromString("2be0ab6f-af56-56cf-b332-700dd591a96e")),
            Map.entry("store", UUID.fromString("f5eced9a-7d0a-5d65-8942-8d1efeabf41a")),
            Map.entry("product", UUID.fromString("40c77853-4a6b-5909-b79c-fdeaadfddad6")),
            Map.entry("productVariant", UUID.fromString("1484c926-777f-5205-8893-941965dbb38a")),
            Map.entry("object", UUID.fromString("fe495002-ca14-5882-a3d2-ca189e300351")),
            Map.entry("affectedSet", UUID.fromString("244ac458-16ba-53fd-89a1-f4003d1a4b5b")),
            Map.entry("caseId", UUID.fromString("6c4036a0-266a-5a8f-9695-41a160fc74d7")),
            Map.entry("configuration", UUID.fromString("efed61ad-59b1-5889-88f2-cc8fc2330df5")),
            Map.entry("profile", UUID.fromString("71491f3e-1853-5678-983a-10f023a23a10")),
            Map.entry("provider", UUID.fromString("bdd07a92-b359-552a-81c9-46e654657965")),
            Map.entry("executorUser", UUID.fromString("0998716b-6f78-56da-bbea-554b20cfd093")),
            Map.entry("verifierUser", UUID.fromString("8ec704dd-3aa5-529c-93db-def4bbf39260")),
            Map.entry("ownerUser", UUID.fromString("9264ceb0-c29a-5837-9339-c84bfe73a444")),
            Map.entry("provenance", UUID.fromString("0e994c7c-409d-506f-a310-f256f77d0920")),
            Map.entry("calculationId", UUID.fromString("6db7e1f7-7421-5074-804d-70d60ca71541")),
            Map.entry("conversion", UUID.fromString("4bdfd9f0-53a8-57fa-887c-04765cc0b9e1")),
            Map.entry("allowableCpa", UUID.fromString("50e96eb3-f3d7-557a-95bb-5093c1659c6b")),
            Map.entry("qualification", UUID.fromString("dcb2ea0f-0349-5e4b-b7e2-b83319ad145c")),
            Map.entry("priority", UUID.fromString("7d163dfa-3ce0-5e08-9b26-84e122136e2d")),
            Map.entry("humanSlo", UUID.fromString("0e3ced6c-93f1-51e2-affd-7d26ee0c8802")),
            Map.entry("approvalLease", UUID.fromString("cf13eff6-6b9c-50f4-a3d3-d51dddcac510")),
            Map.entry("exposure", UUID.fromString("935415e5-316e-58de-baaf-1542f0a80b66")),
            Map.entry("materiality", UUID.fromString("f5b0a314-35c2-501b-a542-7506f943a465")),
            Map.entry("outcome", UUID.fromString("4f30ccee-8886-5c20-9e40-0dbce9c14962")),
            Map.entry("targetPolicy", UUID.fromString("355c4854-db15-5ecf-8e2e-358bc6629a6c")),
            Map.entry("candidate", UUID.fromString("26d942fe-00b2-5242-a3e3-a667e3f6339b")),
            Map.entry("recommendation", UUID.fromString("60cb55d4-9471-5910-b63b-78afb479a8aa")),
            Map.entry("calculationRun", UUID.fromString("4d57d2d4-daa5-519a-8c7b-1a00cfa924ba")),
            Map.entry("bundle", UUID.fromString("cacdad4e-1a61-5901-b7f9-68062f95d854")),
            Map.entry("approval", UUID.fromString("aed0ff40-448e-51ae-b3f4-71fb408e0589")),
            Map.entry("reservation", UUID.fromString("2aa26f0c-0813-58cd-a672-463f70bdb5f3")),
            Map.entry("gate", UUID.fromString("342cf264-3eb4-5105-b854-3e25ee3aa2ea")),
            Map.entry("selection", UUID.fromString("4c64af52-0a4e-5647-8141-afe1b422dc9a")),
            Map.entry("endorsement", UUID.fromString("e5902359-3bc5-52cb-9783-382efc47c9eb")),
            Map.entry("credential", UUID.fromString("509edd0a-8491-5a12-b751-b31adbca0ef6")),
            Map.entry("listing", UUID.fromString("aa14dd95-b455-5db2-924c-8a3972e6f9d2")),
            Map.entry("listingVariant", UUID.fromString("7d693f80-2ad3-570d-8f47-e589af7b5598")),
            Map.entry("baseline", UUID.fromString("d0fa7daf-0724-5272-a691-bc0400c23766")));
    public record Graph(Map<String, UUID> ids, String platform) {
        public UUID id(String name) { return ids.get(name); }
    }
    private AdvertisingR1Fixture() { }

    public static Graph seed(DataSource migration) throws Exception { return seed(migration,true,true); }
    public static Graph seedUnapproved(DataSource migration) throws Exception { return seed(migration,true,false); }
    public static Graph seedUnapproved(DataSource migration, java.util.function.UnaryOperator<String> customize) throws Exception {
        return seed(migration,true,false,null,customize);
    }
    public static Graph seedManual(DataSource migration) throws Exception { return seed(migration,false,false); }
    public static Graph seedManual(DataSource migration, java.util.function.UnaryOperator<String> customize) throws Exception {
        return seed(migration,false,false,null,customize);
    }
    public static Graph seedOutcome(DataSource migration, java.util.function.UnaryOperator<String> customize) throws Exception {
        return seed(migration,true,true,null,customize);
    }
    public static Graph seedBrowser(DataSource migration, UUID storeId) throws Exception { return seed(migration,true,false,storeId,java.util.function.UnaryOperator.identity()); }
    public static Graph seed(DataSource migration, boolean apiVerified, boolean preapproved) throws Exception {
        return seed(migration,apiVerified,preapproved,null,java.util.function.UnaryOperator.identity());
    }
    public static Graph seedManualBrowser(DataSource migration,String platform,UUID storeId) throws Exception {
        if(!java.util.Set.of("OZON","WILDBERRIES").contains(platform)) throw new IllegalArgumentException("Known platform fixture required");
        return seed(migration,false,false,storeId,sql->{
            String manual=sql.replace("'VERIFIED_NATIVE_KEY'","'NO_VERIFIED_IDEMPOTENCY'");
            // Two deliberately different provisional native contracts. Neither is Provider evidence.
            if(platform.equals("WILDBERRIES")) manual=manual.replace("'KEYWORD'","'PLACEMENT'")
                    .replace("'CURRENCY_MAJOR'","'CURRENCY_MINOR'")
                    .replace("2, 0.5, 1.0, 500.0", "0, 5.0, 5.0, 50000.0")
                    .replace("'EXACT_FIELD'","'DERIVED_FIELD'");
            return manual;
        },platform);
    }
    private static Graph seed(DataSource migration, boolean apiVerified, boolean preapproved, UUID storeId,
            java.util.function.UnaryOperator<String> customize) throws Exception {
        return seed(migration,apiVerified,preapproved,storeId,customize,null);
    }
    private static Graph seed(DataSource migration, boolean apiVerified, boolean preapproved, UUID storeId,
            java.util.function.UnaryOperator<String> customize,String fixedPlatform) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String platform = fixedPlatform==null?"FICTIONAL_" + suffix.toUpperCase(java.util.Locale.ROOT):fixedPlatform;
        Map<String, UUID> replacement = new HashMap<>();
        if (storeId != null) replacement.put(TEMPLATE_IDS.get("store").toString(),storeId);
        String template = new ClassPathResource("advertising/r1-fictional-positive.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        template=customize.apply(template);
        var uuid = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(template);
        String sql = uuid.replaceAll(match -> replacement.computeIfAbsent(match.group(), ignored -> UUID.randomUUID()).toString())
                .replace("SYNTHETIC_AD", platform).replace("secret-ref://fictional/never-resolve", "secret-ref://fictional/"+suffix).replace("adfx-idp", "r1-idp-" + suffix)
                .replace("https://fixture.invalid/issuer", "https://" + suffix + ".invalid/issuer")
                .replace("'fictional-organization'", "'fictional-" + suffix + "'");
        StringBuilder filtered=new StringBuilder();
        // Remove complete line comments before splitting this generated SQL fixture.
        // A semicolon in documentation is not a statement terminator.
        for(String statement:sql.replaceAll("(?m)^\\s*--.*$", "").split(";")) {
            String plain=statement.replaceAll("(?m)^\\s*--.*$", "").strip();
            if(plain.isBlank()) continue;
            if(fixedPlatform!=null && plain.startsWith("WITH inserted AS (INSERT INTO core.marketplace_platform")) {
                filtered.append("INSERT INTO platform.control_epoch_membership_guard(guard_kind,platform_code,generation) VALUES('PLATFORM_JOB_SET','")
                        .append(fixedPlatform).append("',1) ON CONFLICT DO NOTHING;");
                continue;
            }
            if(!preapproved && (plain.contains("INSERT INTO ops.ad_candidate_selection")
                    || plain.contains("INSERT INTO ops.ad_candidate_endorsement")
                    || plain.contains("INSERT INTO ops.guardrail_evaluation")
                    || plain.contains("INSERT INTO ops.approval_decision")
                    || plain.contains("UPDATE ops.recommendation SET state='APPROVED'")
                    || plain.contains("SELECT ops.take_ad_action_reservation"))) continue;
            if(!apiVerified && (plain.contains("UPDATE platform.ad_semantic_profile SET source_maturity")
                    || plain.contains("UPDATE platform.platform_capability SET verification_state")
                    || plain.contains("INSERT INTO ops.ad_decision_policy_bundle")
                    || plain.contains("UPDATE ops.ad_decision_policy_bundle")
                    || plain.contains("INSERT INTO ops.ad_gate_authority")
                    || plain.contains("INSERT INTO platform.credential_metadata")
                    || plain.contains("INSERT INTO platform.ad_write_credential_attestation")
                    || plain.contains("INSERT INTO ops.ad_outcome_baseline")
                    || plain.contains("INSERT INTO ops.ad_outcome_stage_baseline"))) continue;
            filtered.append(statement).append(';');
        }
        sql=filtered.toString();
        if(!preapproved) sql=sql.replace("'READY_FOR_REVIEW'","'DRAFT'");
        Map<String, UUID> named = new HashMap<>();
        TEMPLATE_IDS.forEach((name, original) -> named.put(name, replacement.computeIfAbsent(original.toString(), ignored -> UUID.randomUUID())));
        try (Connection connection = migration.getConnection()) {
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            connection.commit();
        }
        return new Graph(Map.copyOf(named), platform);
    }

    /** Separate synthetic identity boundary; the application role never receives issuer membership. */
    public static String proof(DataSource isolatedAdmin, Connection application, Graph graph, UUID actor,
            String purpose, UUID target, UUID version) throws Exception {
        if (application.getAutoCommit()) throw new IllegalArgumentException("a real application transaction is required");
        int pid;
        long transaction;
        try (var query = application.createStatement(); var row = query.executeQuery("SELECT pg_backend_pid(),txid_current()")) {
            row.next(); pid = row.getInt(1); transaction = row.getLong(2);
        }
        String proof = UUID.randomUUID().toString() + UUID.randomUUID();
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(proof.getBytes(StandardCharsets.UTF_8)));
        try (Connection identity = isolatedAdmin.getConnection()) {
            try (var role = identity.createStatement()) { role.execute("SET ROLE marketops_identity_issuer"); }
            Instant authenticated;
            try (var clockQuery=identity.createStatement(); var clockRow=clockQuery.executeQuery("SELECT clock_timestamp()")) {
                clockRow.next(); authenticated=clockRow.getTimestamp(1).toInstant();
            }
            String sql = purpose == null ? "SELECT iam.issue_ad_invocation_grant(?,?,?,?,?,?,?,?,?,?,?,?)"
                    : "SELECT iam.issue_ad_control_invocation_grant(?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (var issue = identity.prepareStatement(sql)) {
                int i=1;
                if (purpose != null) issue.setString(i++, purpose);
                issue.setString(i++, digest); issue.setObject(i++, actor);
                issue.setObject(i++, graph.id("organization")); issue.setObject(i++, graph.id("provider"));
                issue.setString(i++, "a".repeat(64)); issue.setString(i++, "b".repeat(64));
                issue.setTimestamp(i++, Timestamp.from(authenticated));
                issue.setTimestamp(i++, Timestamp.from(authenticated.plusSeconds(3600)));
                issue.setObject(i++, target); issue.setObject(i++, version);
                issue.setInt(i++, pid); issue.setLong(i, transaction); issue.execute();
            }
        }
        return proof;
    }

    public static UUID seal(Connection application, Graph graph, String proof) throws SQLException {
        try (var query = application.prepareStatement("SELECT ops.seal_ad_action_authorization(?,?,?,?)")) {
            query.setObject(1,graph.id("recommendation")); query.setObject(2,graph.id("approval"));
            query.setObject(3,graph.id("baseline")); query.setString(4,proof);
            try (var row=query.executeQuery()) { row.next(); return row.getObject(1,UUID.class); }
        }
    }
    public static UUID reserve(Connection application, Graph graph) throws SQLException {
        try(var query=application.prepareStatement("""
                SELECT ops.take_ad_action_reservation(?,candidate.organization_id,candidate.ad_native_object_id,kase.store_id,
                  affected.id,affected.affected_set_digest,affected.product_variant_ids,'CONTROLLED_AD_BID_CHANGE',
                  candidate.id,candidate.direction,kase.lane,'fictional-r1-test')
                FROM ops.ad_bid_candidate candidate JOIN mart.ad_case kase ON kase.id=candidate.case_id
                JOIN core.ad_affected_set affected ON affected.id=kase.affected_set_id WHERE candidate.id=?
                """)) {
            query.setObject(1,graph.id("reservation"));query.setObject(2,graph.id("candidate"));
            try(var row=query.executeQuery()) { row.next();return row.getObject(1,UUID.class); }
        }
    }
    public static UUID createCommand(Connection application, Graph graph) throws SQLException {
        reserve(application,graph);
        try (var query=application.prepareStatement("SELECT ops.create_ad_bid_command(?,(SELECT version FROM ops.recommendation WHERE id=?),?,'fictional-r1-test')")) {
            query.setObject(1,graph.id("recommendation")); query.setObject(2,graph.id("recommendation"));
            query.setObject(3,graph.id("reservation"));
            try (var row=query.executeQuery()) { row.next(); return row.getObject(1,UUID.class); }
        }
    }
}
