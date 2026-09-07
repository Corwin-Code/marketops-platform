package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Java parameter contract and the database one describe the same rule.
 *
 * <p>There are deliberately two. The database refuses a row nobody's Java
 * touched, and the Java refuses before a transaction is opened so an operator
 * gets a reason rather than a constraint violation. Two copies of a rule drift,
 * and this is the test that notices when they do — every shared case is put to
 * both, and disagreement on any one of them fails.
 */
@SpringBootTest
@ActiveProfiles("ci")
class AdBidParameterContractParityIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        var container = TestDatabase.isolatedContainer();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @Test
    @DisplayName("TC-AD-PARAM-005 both implementations accept and refuse exactly the same shapes")
    void bothImplementationsAgree() {
        SoftAssertions softly = new SoftAssertions();
        for (AdBidParameterCases.Case testCase : AdBidParameterCases.all()) {
            softly.assertThat(acceptedByDatabase(testCase.parameters()))
                    .describedAs("database, %s", testCase.description())
                    .isEqualTo(testCase.valid());
            softly.assertThat(acceptedByJava(testCase.parameters()))
                    .describedAs("java, %s", testCase.description())
                    .isEqualTo(testCase.valid());
        }
        softly.assertAll();
    }

    private boolean acceptedByDatabase(Map<String, String> parameters) {
        return Boolean.TRUE.equals(jdbc
                .sql("SELECT ops.ad_bid_parameter_contract_is_valid(CAST(:parameters AS jsonb))")
                .param("parameters", MAPPER.writeValueAsString(parameters))
                .query(Boolean.class)
                .single());
    }

    private static boolean acceptedByJava(Map<String, String> parameters) {
        try {
            AdBidChangeParameterContract.requireValid(parameters);
            return true;
        } catch (OperationRejectedException refused) {
            return false;
        }
    }
}
