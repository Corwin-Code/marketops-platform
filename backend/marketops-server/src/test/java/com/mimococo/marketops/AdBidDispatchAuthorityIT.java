package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteRequest;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The advertising dispatch-authority query, executed against the real schema.
 *
 * <p>This query is the last thing that runs before an advertising write leaves
 * the process, and it is sixty lines of SQL that no unit test can type-check. A
 * misspelt column or a function whose signature moved would not fail until a
 * command was actually dispatched — which, in an environment where nothing is
 * verified, might be never. So it is executed here.
 *
 * <p>Nothing is seeded on purpose. The default answer to "may this call leave?"
 * is no, and an empty database is the strongest possible statement of that: no
 * command, no attempt, no verified capability and no credential exists, so the
 * query must return false rather than raise. The adversarial matrix that varies
 * one field at a time against a real approved command lives with the rest of the
 * command-lifecycle evidence.
 */
@SpringBootTest
@ActiveProfiles("ci")
class AdBidDispatchAuthorityIT {

    @Autowired
    private PlatformCallSpecRepository callSpecs;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        var container = TestDatabase.container();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @Test
    @DisplayName("TC-AD-DISPATCH-001 the query runs against the real schema for every operation")
    void everyOperationShapeExecutes() {
        for (AdBidWriteRequest.Operation operation : AdBidWriteRequest.Operation.values()) {
            assertThat(callSpecs.adBidAttemptCurrent(request(operation)))
                    .describedAs("an attempt that does not exist cannot authorize a %s", operation)
                    .isFalse();
        }
    }

    private static AdBidWriteRequest request(AdBidWriteRequest.Operation operation) {
        return new AdBidWriteRequest(operation, UUID.randomUUID(), UUID.randomUUID(),
                "campaign-absent", "object-absent",
                operation == AdBidWriteRequest.Operation.STATUS_ENQUIRY
                        ? null : Money.of(new BigDecimal("12.00"), "RUB"),
                operation == AdBidWriteRequest.Operation.STATUS_ENQUIRY ? null : "CURRENCY_MAJOR",
                "key-absent",
                operation == AdBidWriteRequest.Operation.STATUS_ENQUIRY ? "task-absent" : null,
                null, UUID.randomUUID());
    }
}
