package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** App-role recommendation mutation must never substitute an unapproved candidate or native object. */
class AdvertisingCommandSubstitutionIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration,application,admin;
    private static JdbcClient seed,appRead;
    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        seed=JdbcClient.create(migration);appRead=JdbcClient.create(application);
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }
    enum Substitution { CANDIDATE_BEFORE_SEAL, OBJECT_BEFORE_SEAL, CANDIDATE_AFTER_SEAL, OBJECT_AFTER_SEAL }
    private Connection transaction() throws SQLException {
        var connection=application.getConnection();connection.setAutoCommit(false);return connection;
    }
    private UUID seal(Connection app,AdvertisingR1Fixture.Graph graph) throws Exception {
        String proof=AdvertisingR1Fixture.proof(admin,app,graph,graph.id("ownerUser"),null,
                graph.id("recommendation"),graph.id("approval"));
        return AdvertisingR1Fixture.seal(app,graph,proof);
    }
    private UUID create(Connection app,AdvertisingR1Fixture.Graph graph) throws SQLException {
        try(var query=app.prepareStatement("SELECT ops.create_ad_bid_command(?,(SELECT version FROM ops.recommendation WHERE id=?),?,'substitution-fixture')")) {
            query.setObject(1,graph.id("recommendation"));query.setObject(2,graph.id("recommendation"));
            query.setObject(3,graph.id("reservation"));
            try(var row=query.executeQuery()) { row.next();return row.getObject(1,UUID.class); }
        }
    }
    @ParameterizedTest(name="app-role {0} cannot acquire command authority")
    @EnumSource(Substitution.class)
    void writableRecommendationCannotSubstituteCandidateOrObjectAcrossTheSealedBoundary(Substitution substitution) throws Exception {
        var graph=AdvertisingR1Fixture.seed(migration);
        boolean candidate=substitution.name().startsWith("CANDIDATE");
        boolean afterSeal=substitution.name().endsWith("AFTER_SEAL");
        UUID replacement=candidate?anotherUnapprovedCandidate(graph):anotherUnapprovedObject(graph);
        UUID authority=null;
        if(afterSeal) {
            try(var app=transaction()) {
                authority=seal(app,graph);AdvertisingR1Fixture.reserve(app,graph);app.commit();
            }
        }
        // The attack really uses the application's UPDATE permission and a current version.
        // It supplies no actor, Bundle or expiry parameter: those removed inputs have no API.
        try(var app=transaction()) {
            String sql=candidate
                    ?"UPDATE ops.recommendation SET proposed_parameters=jsonb_set(proposed_parameters,'{candidateId}',to_jsonb(?::text)) WHERE id=?"
                    :"UPDATE ops.recommendation SET subject_id=? WHERE id=?";
            try(var update=app.prepareStatement(sql)) {
                if(candidate) update.setString(1,replacement.toString());else update.setObject(1,replacement);
                update.setObject(2,graph.id("recommendation"));assertThat(update.executeUpdate()).isEqualTo(1);
            }
            app.commit();
        }
        assertThat(appRead.sql(candidate
                ?"SELECT (proposed_parameters->>'candidateId')::uuid FROM ops.recommendation WHERE id=:id"
                :"SELECT subject_id FROM ops.recommendation WHERE id=:id")
                .param("id",graph.id("recommendation")).query(UUID.class).single()).isEqualTo(replacement);
        try(var app=transaction()) {
            if(afterSeal) {
                assertThatThrownBy(()->create(app,graph)).isInstanceOf(SQLException.class)
                        .satisfies(error->assertThat(((SQLException)error).getSQLState()).startsWith("MO"));
            } else {
                assertThatThrownBy(()->seal(app,graph)).isInstanceOf(SQLException.class)
                        .satisfies(error->assertThat(((SQLException)error).getSQLState()).startsWith("MO"));
            }
            app.rollback();
        }
        assertThat(appRead.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Integer.class).single()).isZero();
        if(afterSeal) {
            assertThat(appRead.sql("SELECT candidate_id FROM ops.ad_action_authorization WHERE id=:id")
                    .param("id",authority).query(UUID.class).single()).isEqualTo(graph.id("candidate"));
        } else {
            assertThat(appRead.sql("SELECT count(*) FROM ops.ad_action_authorization WHERE recommendation_id=:id")
                    .param("id",graph.id("recommendation")).query(Integer.class).single()).isZero();
        }
    }
    /** A distinct same-object candidate with identical amounts isolates identity substitution. */
    private UUID anotherUnapprovedCandidate(AdvertisingR1Fixture.Graph graph) {
        UUID other=UUID.randomUUID();
        assertThat(seed.sql("""
            INSERT INTO ops.ad_bid_candidate SELECT (jsonb_populate_record(NULL::ops.ad_bid_candidate,
              to_jsonb(original)||jsonb_build_object('id',:other::text,'ordinal',original.ordinal+1))).*
            FROM ops.ad_bid_candidate original WHERE original.id=:original
            """).param("other",other).param("original",graph.id("candidate")).update()).isEqualTo(1);
        return other;
    }
    /** Valid same-organization native/configuration input, but no selected/approved action on it. */
    private UUID anotherUnapprovedObject(AdvertisingR1Fixture.Graph graph) {
        UUID other=UUID.randomUUID();
        assertThat(seed.sql("""
            INSERT INTO core.ad_native_object SELECT (jsonb_populate_record(NULL::core.ad_native_object,
              to_jsonb(original)||jsonb_build_object('id',:other::text,'native_object_key',:key))).*
            FROM core.ad_native_object original WHERE original.id=:original
            """).param("other",other).param("key","fictional-unapproved-"+other)
                .param("original",graph.id("object")).update()).isEqualTo(1);
        assertThat(seed.sql("""
            INSERT INTO core.ad_object_configuration_observation SELECT (jsonb_populate_record(NULL::core.ad_object_configuration_observation,
              to_jsonb(original)||jsonb_build_object('id',:other::text,'ad_native_object_id',:object::text))).*
            FROM core.ad_object_configuration_observation original WHERE original.id=:original
            """).param("other",UUID.randomUUID()).param("object",other)
                .param("original",graph.id("configuration")).update()).isEqualTo(1);
        return other;
    }
}
