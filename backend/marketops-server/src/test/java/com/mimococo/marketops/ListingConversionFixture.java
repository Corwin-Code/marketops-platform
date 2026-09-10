package com.mimococo.marketops;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A complete fictional listing graph: two listings on the fictional platform,
 * an Owner-published calibration and allowance, a described and verified
 * description capability, and two actions approved up to the point where the
 * launch function is the only way forward. No production platform, credential
 * or listing is named; every write beyond seeding goes through the functions
 * the application role may call.
 */
public final class ListingConversionFixture {

    private static final Map<String, String> TEMPLATE = Map.ofEntries(
            Map.entry("organization", "8689c119-8fa0-50b7-8ba2-f9bf3039d336"),
            Map.entry("legalEntity", "8f17abcd-c8f2-5dbb-af7d-e0dd234dc59a"),
            Map.entry("account", "2be0ab6f-af56-56cf-b332-700dd591a96e"),
            Map.entry("store", "f5eced9a-7d0a-5d65-8942-8d1efeabf41a"),
            Map.entry("product", "40c77853-4a6b-5909-b79c-fdeaadfddad6"),
            Map.entry("productVariant", "1484c926-777f-5205-8893-941965dbb38a"),
            Map.entry("listing", "aa14dd95-b455-5db2-924c-8a3972e6f9d2"),
            Map.entry("listingVariant", "7d693f80-2ad3-570d-8f47-e589af7b5598"),
            Map.entry("executorUser", "0998716b-6f78-56da-bbea-554b20cfd093"),
            Map.entry("verifierUser", "8ec704dd-3aa5-529c-93db-def4bbf39260"),
            Map.entry("ownerUser", "9264ceb0-c29a-5837-9339-c84bfe73a444"),
            Map.entry("provider", "bdd07a92-b359-552a-81c9-46e654657965"),
            Map.entry("provenance", "0e994c7c-409d-506f-a310-f256f77d0920"),
            Map.entry("calculationRun", "4d57d2d4-daa5-519a-8c7b-1a00cfa924ba"),
            Map.entry("calibrationPackage", "5c000000-0000-5000-8000-000000000001"),
            Map.entry("allowanceConcurrent", "5c000000-0000-5000-8000-000000000002"),
            Map.entry("allowanceVariants", "5c000000-0000-5000-8000-000000000003"),
            Map.entry("gateAuthority", "5c000000-0000-5000-8000-000000000004"),
            Map.entry("observationOne", "5c000000-0000-5000-8000-000000000005"),
            Map.entry("affectedSetOne", "5c000000-0000-5000-8000-000000000006"),
            Map.entry("healthOne", "5c000000-0000-5000-8000-000000000007"),
            Map.entry("candidateOne", "5c000000-0000-5000-8000-000000000008"),
            Map.entry("recommendationOne", "5c000000-0000-5000-8000-000000000009"),
            Map.entry("actionOne", "5c000000-0000-5000-8000-00000000000a"),
            Map.entry("guardrailOne", "5c000000-0000-5000-8000-00000000000c"),
            Map.entry("approvalOne", "5c000000-0000-5000-8000-00000000000d"),
            Map.entry("bindingOne", "5c000000-0000-5000-8000-00000000000e"),
            Map.entry("planOne", "5c000000-0000-5000-8000-00000000000f"),
            Map.entry("capability", "5c000000-0000-5000-8000-000000000010"),
            Map.entry("endpointApply", "5c000000-0000-5000-8000-000000000011"),
            Map.entry("endpointReadback", "5c000000-0000-5000-8000-000000000012"),
            Map.entry("endpointRestore", "5c000000-0000-5000-8000-000000000013"),
            Map.entry("credential", "5c000000-0000-5000-8000-000000000014"),
            Map.entry("executionGuardrailOne", "5c000000-0000-5000-8000-00000000001c"),
            Map.entry("productTwo", "5c000000-0000-5000-8000-000000000020"),
            Map.entry("productVariantTwo", "5c000000-0000-5000-8000-000000000021"),
            Map.entry("listingTwo", "5c000000-0000-5000-8000-000000000022"),
            Map.entry("listingVariantTwo", "5c000000-0000-5000-8000-000000000023"),
            Map.entry("observationTwo", "5c000000-0000-5000-8000-000000000025"),
            Map.entry("affectedSetTwo", "5c000000-0000-5000-8000-000000000026"),
            Map.entry("healthTwo", "5c000000-0000-5000-8000-000000000027"),
            Map.entry("candidateTwo", "5c000000-0000-5000-8000-000000000028"),
            Map.entry("recommendationTwo", "5c000000-0000-5000-8000-000000000029"),
            Map.entry("actionTwo", "5c000000-0000-5000-8000-00000000002a"),
            Map.entry("guardrailTwo", "5c000000-0000-5000-8000-00000000002c"),
            Map.entry("approvalTwo", "5c000000-0000-5000-8000-00000000002d"),
            Map.entry("bindingTwo", "5c000000-0000-5000-8000-00000000002e"),
            Map.entry("planTwo", "5c000000-0000-5000-8000-00000000002f"));

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String PRIOR_TEXT_ONE = "Прежнее описание товара для покупателя";
    static final String TARGET_TEXT_ONE = "Новое описание товара для покупателя с точными характеристиками";

    final DataSource migration;
    final DataSource application;
    final DataSource admin;
    final JdbcClient seed;
    final JdbcClient app;
    final AdvertisingR1Fixture.Graph graph;

    public ListingConversionFixture(DataSource migration, DataSource application, DataSource admin) throws Exception {
        this.migration = migration;
        this.application = application;
        this.admin = admin;
        this.seed = JdbcClient.create(migration);
        this.app = JdbcClient.create(application);
        AdvertisingR1Fixture.Graph base = AdvertisingR1Fixture.seedManual(migration);
        Map<String, UUID> named = new HashMap<>(base.ids());
        Map<String, String> replacement = new HashMap<>();
        TEMPLATE.forEach((name, template) -> {
            UUID actual = named.computeIfAbsent(name, ignored -> UUID.randomUUID());
            replacement.put(template, actual.toString());
        });
        String source = new ClassPathResource("listing/lc-fictional-positive.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        var uuid = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(source);
        String sql = uuid.replaceAll(match -> replacement.computeIfAbsent(match.group(),
                        ignored -> UUID.randomUUID().toString()))
                .replace("SYNTHETIC_AD", base.platform())
                .replace("secret-ref://fictional/never-resolve-content",
                        "secret-ref://fictional/content/" + named.get("credential"));
        try (Connection connection = migration.getConnection()) {
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            connection.commit();
        }
        this.graph = new AdvertisingR1Fixture.Graph(Map.copyOf(named), base.platform());
    }

    public UUID id(String name) {
        UUID found = graph.id(name);
        if (found == null) {
            throw new IllegalArgumentException("no fixture identifier named " + name);
        }
        return found;
    }

    Connection transaction() throws SQLException {
        Connection connection = application.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    /** One-use proof for exactly this transaction, this person, this target and this version. */
    String proof(Connection transaction, UUID actor, String purpose, UUID target, UUID version) throws Exception {
        return AdvertisingR1Fixture.proof(admin, transaction, graph, actor, purpose, target, version);
    }

    /** Launch one action as one person inside one committed transaction; the database's answer comes back. */
    JsonNode launch(UUID launchId, String actionName, UUID actor) throws Exception {
        String suffix = actionName.endsWith("Two") ? "Two" : "One";
        UUID action = id(actionName);
        UUID recommendation = id("recommendation" + suffix);
        UUID approval = id("approval" + suffix);
        try (Connection connection = transaction()) {
            String proof = proof(connection, actor, "LISTING_ACTION_LAUNCH", recommendation, approval);
            try (var query = connection.prepareStatement(
                    "SELECT ops.acquire_lc_launch_allowance(?, ?, ?, ?, '{}'::jsonb)::text")) {
                query.setObject(1, launchId);
                query.setObject(2, action);
                query.setObject(3, actor);
                query.setString(4, proof);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    JsonNode answer = JSON.readTree(rows.getString(1));
                    connection.commit();
                    return answer;
                }
            } catch (SQLException refused) {
                connection.rollback();
                throw refused;
            }
        }
    }

    UUID createCommand(UUID action, UUID actor) {
        long version = app.sql("SELECT version FROM ops.lc_action WHERE id = :id").param("id", action)
                .query(Long.class).single();
        return app.sql("SELECT ops.create_lc_description_command(:action, :actor, :version, 'listing-fixture')")
                .param("action", action).param("actor", actor).param("version", version)
                .query(UUID.class).single();
    }

    List<String> gateReasons(UUID command) {
        return app.sql("SELECT unnest(ops.evaluate_lc_description_write_gate(:id))").param("id", command)
                .query(String.class).list();
    }

    String actionState(UUID action) {
        return app.sql("SELECT state FROM ops.lc_action WHERE id = :id").param("id", action)
                .query(String.class).single();
    }

    UUID contain(UUID containmentId, UUID actor, UUID listing) throws Exception {
        try (Connection connection = transaction()) {
            String proof = proof(connection, actor, "LISTING_CONTAINMENT_STOP", containmentId, containmentId);
            try (var query = connection.prepareStatement("""
                    SELECT ops.record_lc_containment(?, ?, ?, ?, 'LISTING', ?, NULL, NULL, NULL,
                        'SAFETY_FAILURE', 'OWNER', 'fixture stop', 'fixture://stop')
                    """)) {
                query.setObject(1, containmentId);
                query.setObject(2, actor);
                query.setObject(3, id("organization"));
                query.setString(4, proof);
                query.setObject(5, listing);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    UUID recorded = rows.getObject(1, UUID.class);
                    connection.commit();
                    return recorded;
                }
            } catch (SQLException refused) {
                connection.rollback();
                throw refused;
            }
        }
    }

    UUID attest(UUID attestationId, UUID containmentId, UUID actor, String kind) throws Exception {
        try (Connection connection = transaction()) {
            String purpose = "REPAIR_ATTESTATION".equals(kind) ? "LISTING_CONTAINMENT_ATTEST" : "LISTING_CONTAINMENT_CONSENT";
            String proof = proof(connection, actor, purpose, containmentId, containmentId);
            try (var query = connection.prepareStatement(
                    "SELECT ops.attest_lc_containment(?, ?, ?, ?, ?, 'fixture://attestation')")) {
                query.setObject(1, attestationId);
                query.setObject(2, containmentId);
                query.setObject(3, actor);
                query.setString(4, proof);
                query.setString(5, kind);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    UUID recorded = rows.getObject(1, UUID.class);
                    connection.commit();
                    return recorded;
                }
            } catch (SQLException refused) {
                connection.rollback();
                throw refused;
            }
        }
    }

    /** A display observation of the target text, so a STOP_EVIDENCE release has something to rest on. */
    UUID displayObservation(String listingName, String text, UUID observer) {
        UUID observation = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.lc_display_observation(id,organization_id,provenance_id,platform_listing_id,source_fact_key,
                    observed_at,acquired_at,evidence_grade,observer_user_id,display_state,displayed_text,displayed_text_digest,
                    evidence_reference)
                VALUES (:id,:org,:provenance,:listing,:key,now(),now(),'INDEPENDENT_HUMAN',:observer,'DISPLAYED',:text,
                    encode(sha256(convert_to(:text,'UTF8')),'hex'),'fixture://display')
                """).param("id", observation).param("org", id("organization")).param("provenance", id("provenance"))
                .param("listing", id(listingName)).param("key", "fictional-display-" + observation)
                .param("observer", observer).param("text", text).update();
        return observation;
    }

    void release(UUID occupation, UUID actor, UUID evidence) throws Exception {
        try (Connection connection = transaction()) {
            String proof = proof(connection, actor, "LISTING_OCCUPATION_RELEASE", occupation, occupation);
            try (var query = connection.prepareStatement(
                    "SELECT ops.release_lc_occupation(?, ?, ?, 'STOP_EVIDENCE', ?, 'fixture://display')")) {
                query.setObject(1, occupation);
                query.setObject(2, actor);
                query.setString(3, proof);
                query.setObject(4, evidence);
                query.execute();
                connection.commit();
            } catch (SQLException refused) {
                connection.rollback();
                throw refused;
            }
        }
    }

    /** The SQLSTATE at the root of a failure, or null when there is none. */
    static String sqlState(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
