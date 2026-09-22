package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Finite accepted conditions and exact review bindings, against isolated PostgreSQL. */
class ListingMeaningReviewIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DriverManagerDataSource migration,application,admin;
    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }

    @Test void conditionDocumentsRejectNumericCharacterRatiosAndMalformedCatalogs() throws Exception {
        var f=new ListingConversionFixture(migration,application,admin);
        var json=new tools.jackson.databind.ObjectMapper();
        assertThat(valid(f,ListingConversionFixture.MEANING_ORDINARY)).isTrue();
        assertThat(valid(f,ListingConversionFixture.MEANING_MATERIAL)).isTrue();
        for(String doc:java.util.List.of("null","0.1","[]","{}",
                "{\"model\":\"LC_MEANING_CONDITIONS_1\",\"description\":null}",
                "{\"model\":\"LC_MEANING_CONDITIONS_1\",\"description\":[{\"code\":\"NO\",\"condition\":\"\"}]}",
                "{\"model\":\"LC_MEANING_CONDITIONS_1\",\"description\":[],\"extra\":true}"))
            assertThat(valid(f,doc)).as("reject invalid finite rule document").isFalse();
        var duplicate=(tools.jackson.databind.node.ObjectNode)json.readTree(ListingConversionFixture.MEANING_ORDINARY);
        var rules=(tools.jackson.databind.node.ArrayNode)duplicate.path("description");
        rules.add(rules.get(0).deepCopy());
        assertThat(valid(f,json.writeValueAsString(duplicate))).isFalse();
        rules.removeAll();
        for(int i=0;i<17;i++) rules.add(json.createObjectNode().put("code","RULE_"+i).put("condition","Synthetic condition"));
        assertThat(valid(f,json.writeValueAsString(duplicate))).isFalse();
    }

    @Test void reviewedClassificationAndMeaningEvidenceCannotBeRewritten() throws Exception {
        var f=new ListingConversionFixture(migration,application,admin);
        assertThat(f.app.sql("SELECT ops.lc_action_has_meaning_review(:id,statement_timestamp())")
                .param("id",f.id("actionOne")).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(()->f.app.sql("UPDATE ops.lc_action_review SET meaning_assessment='{}' WHERE action_id=:id")
                .param("id",f.id("actionOne")).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->f.seed.sql("UPDATE ops.lc_action SET exposure_axis_material=NOT exposure_axis_material WHERE id=:id")
                .param("id",f.id("actionOne")).update())
                .satisfies(e->assertThat(ListingConversionFixture.sqlState(e)).isEqualTo("MO107"));
    }

    private boolean valid(ListingConversionFixture f,String document) {
        return f.app.sql("SELECT core.lc_meaning_rule_document_valid(CAST(:doc AS jsonb))")
                .param("doc",document).query(Boolean.class).single();
    }
}
