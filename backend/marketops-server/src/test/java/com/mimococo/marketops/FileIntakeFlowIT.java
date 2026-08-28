package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
import com.mimococo.marketops.identityaccess.internal.application.IdentityProviderService;
import com.mimococo.marketops.identityaccess.internal.application.UserAdministrationService;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.internal.application.ImportIntakeService;
import com.mimococo.marketops.operatingfacts.internal.domain.IntakeDataset;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.ImportRepository;
import com.mimococo.marketops.productlisting.internal.application.ProductCatalogService;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Internal facts arriving as a file: custody, preview, validation, rejection,
 * approval, application and the refusal of a file already submitted.
 *
 * <p>The whole point of the file path is that a company's own numbers are as
 * traceable as a marketplace's. So this asserts the parts that make that true —
 * the content is hashed and stored before a row is read, every rejected row
 * says why, and applying a batch produces facts that point back at it — rather
 * than only that a happy-path file lands.
 */
@SpringBootTest
@ActiveProfiles("ci")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FileIntakeFlowIT {

    private static final String OPERATOR = "olga.ivanova";
    private static final String ISSUER = "https://id.example.test/realms/intake";

    private static UUID organizationId;
    private static UUID productVariantId;
    private static UUID warehouseId;
    private static AuthenticatedActor actor;
    private static UUID validatedBatchId;
    private static long validatedBatchVersion;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IdentityProviderService identityProviders;

    @Autowired
    private UserAdministrationService users;

    @Autowired
    private ProductCatalogService catalogue;

    @Autowired
    private ImportIntakeService intake;

    @Autowired
    private OperatingFactQuery facts;

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
    @Order(1)
    @DisplayName("TC-INTAKE-001 a file contract must exist before a file can be read")
    void registerTheContract() {
        organizationId = UUID.randomUUID();
        UUID legalEntityId = UUID.randomUUID();
        warehouseId = UUID.randomUUID();

        jdbc.sql("""
                        INSERT INTO core.organization
                            (id, code, display_name, status, created_at, updated_at)
                        VALUES (:id, 'intake-acme', 'Intake Acme', 'ACTIVE', now(), now())
                        """).param("id", organizationId).update();
        jdbc.sql("""
                        INSERT INTO core.legal_entity
                            (id, organization_id, code, display_name, status,
                             created_at, updated_at)
                        VALUES (:id, :org, 'intake-acme-ru', 'Intake Acme RU', 'ACTIVE',
                                now(), now())
                        """).param("id", legalEntityId).param("org", organizationId).update();
        jdbc.sql("""
                        INSERT INTO core.warehouse
                            (id, organization_id, legal_entity_id, code, display_name,
                             timezone, status, created_at, updated_at)
                        VALUES (:id, :org, :entity, 'intake-warehouse', 'Intake Warehouse',
                                'Europe/Moscow', 'ACTIVE', now(), now())
                        """)
                .param("id", warehouseId).param("org", organizationId)
                .param("entity", legalEntityId).update();

        var provider = identityProviders.register(OPERATOR, "intake-oidc", "Intake OIDC",
                ISSUER, 900, "platform-team");
        identityProviders.verifyAndActivate(OPERATOR, provider.id(), "amr", "mfa",
                "evidence://identity/intake", "Intake provider discovery document",
                provider.version());
        var profile = users.provision(OPERATOR, organizationId, provider.id(),
                "intake-operator-1", null, "Intake Operator", null);
        users.assignRole(OPERATOR, profile.id(), BusinessRoleCode.FINANCE, null);
        users.grantScope(OPERATOR, profile.id(), ActionScopeCode.INTERNAL_FACT_INTAKE,
                ResourceScopeType.ORGANIZATION, organizationId, null);

        Instant now = Instant.now();
        actor = new AuthenticatedActor(profile.id(), organizationId, provider.id(), ISSUER,
                "Intake Operator", "intake-subject-digest", "intake-session-digest", now,
                now.plus(Duration.ofMinutes(10)), true, Set.of(BusinessRoleCode.FINANCE));

        var product = catalogue.createProduct(OPERATOR, organizationId, "intake-widget",
                "Intake widget", null, null);
        productVariantId = catalogue.createVariant(OPERATOR, product.id(), "intake-widget-s",
                "Intake widget S", null, "S").id();

        // A file cannot be read before somebody has said what its columns mean.
        assertThatThrownBy(() -> intake.submit(actor, IntakeDataset.PURCHASE_COST,
                "cost.csv", "text/csv", costFile("intake-widget-s")))
                .isInstanceOf(OperationRejectedException.class)
                .extracting(failure -> ((OperationRejectedException) failure).errorCode())
                .isEqualTo(ErrorCode.IMPORT_SCHEMA_PROFILE_MISSING);

        intake.registerProfile(OPERATOR, organizationId, IntakeDataset.PURCHASE_COST,
                "acme-cost", 1, "Acme purchase cost sheet",
                List.of(
                        Map.of("column", "sku", "field", "skuCode"),
                        Map.of("column", "cost", "field", "unitCost"),
                        Map.of("column", "currency", "field", "currencyCode"),
                        Map.of("column", "from", "field", "effectiveFrom")),
                "finance-team");

        intake.registerProfile(OPERATOR, organizationId, IntakeDataset.INTERNAL_STOCK,
                "acme-stock", 1, "Acme stock count",
                List.of(
                        Map.of("column", "sku", "field", "skuCode"),
                        Map.of("column", "warehouse", "field", "warehouseCode"),
                        Map.of("column", "onhand", "field", "quantityOnHand"),
                        Map.of("column", "at", "field", "observedAt")),
                "operations-team");
    }

    @Test
    @Order(2)
    @DisplayName("TC-INTAKE-002 every row is judged and every rejection says why")
    void submitAFileWithGoodAndBadRows() {
        String csv = """
                sku,cost,currency,from
                intake-widget-s,60.0000,RUB,2026-08-01T00:00:00Z
                unknown-sku,70.0000,RUB,2026-08-01T00:00:00Z
                intake-widget-s,not-a-number,RUB,2026-08-01T00:00:00Z
                intake-widget-s,80.0000,RUB,
                """;

        ImportRepository.ImportBatch batch = intake.submit(actor, IntakeDataset.PURCHASE_COST,
                "cost-mixed.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(batch.state()).isEqualTo("VALIDATED");
        assertThat(batch.acceptedRowCount()).isEqualTo(1);
        assertThat(batch.rejectedRowCount()).isEqualTo(3);

        List<ImportRepository.ImportRow> rejected = intake.rows(batch.id(), true, 50);
        assertThat(rejected).hasSize(3);
        rejected.forEach(row -> assertThat(row.rejectionCode()).isNotBlank());

        // The bytes are in custody before any row was read, so the file that
        // produced this report can be produced again.
        assertThat(intake.storedContent(batch.id())).isPresent();

        validatedBatchId = batch.id();
        validatedBatchVersion = batch.version();
    }

    @Test
    @Order(3)
    @DisplayName("TC-INTAKE-003 the same file cannot be submitted twice")
    void refuseADuplicateFile() {
        String csv = """
                sku,cost,currency,from
                intake-widget-s,60.0000,RUB,2026-08-01T00:00:00Z
                unknown-sku,70.0000,RUB,2026-08-01T00:00:00Z
                intake-widget-s,not-a-number,RUB,2026-08-01T00:00:00Z
                intake-widget-s,80.0000,RUB,
                """;

        assertThatThrownBy(() -> intake.submit(actor, IntakeDataset.PURCHASE_COST,
                "cost-mixed-again.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(OperationRejectedException.class)
                .extracting(failure -> ((OperationRejectedException) failure).errorCode())
                .isEqualTo(ErrorCode.DUPLICATE_IDENTITY);
    }

    @Test
    @Order(4)
    @DisplayName("TC-INTAKE-004 a file whose every row fails is a rejected file")
    void refuseAFileNothingPassed() {
        String csv = """
                sku,cost,currency,from
                unknown-one,60.0000,RUB,2026-08-01T00:00:00Z
                unknown-two,70.0000,RUB,2026-08-01T00:00:00Z
                """;

        ImportRepository.ImportBatch batch = intake.submit(actor, IntakeDataset.PURCHASE_COST,
                "cost-all-bad.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(batch.state()).isEqualTo("REJECTED");
        assertThat(batch.rejectionCode()).isEqualTo("NO_ROW_PASSED_VALIDATION");

        // A rejected batch cannot be approved into facts.
        assertThatThrownBy(() -> intake.approveAndApply(actor, batch.id(), null,
                batch.version()))
                .isInstanceOf(OperationRejectedException.class)
                .extracting(failure -> ((OperationRejectedException) failure).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @Order(5)
    @DisplayName("TC-INTAKE-005 approving writes only the rows that passed")
    void approveAndApply() {
        ImportRepository.ImportBatch applied = intake.approveAndApply(actor, validatedBatchId,
                null, validatedBatchVersion);

        assertThat(applied.state()).isEqualTo("APPLIED");
        assertThat(facts.unitCost(productVariantId, Instant.now())).isPresent();
        assertThat(facts.unitCost(productVariantId, Instant.now()).orElseThrow()
                .unitCost().amount()).isEqualByComparingTo("60.0000");

        // An applied batch is finished; approving it again changes nothing.
        assertThatThrownBy(() -> intake.approveAndApply(actor, validatedBatchId, null,
                applied.version()))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    @Order(6)
    @DisplayName("TC-INTAKE-006 a stock count lands as a fact somebody can trace")
    void importStock() {
        String csv = """
                sku,warehouse,onhand,at
                intake-widget-s,intake-warehouse,42,2026-08-20T09:00:00Z
                """;

        ImportRepository.ImportBatch batch = intake.submit(actor, IntakeDataset.INTERNAL_STOCK,
                "stock.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(batch.acceptedRowCount()).isEqualTo(1);

        intake.approveAndApply(actor, batch.id(), null, batch.version());

        assertThat(facts.internalStock(productVariantId, Instant.now()).quantityOnHand())
                .isEqualTo(42);
        assertThat(intake.list(organizationId, IntakeDataset.INTERNAL_STOCK, 10)).hasSize(1);
    }

    @Test
    @Order(7)
    @DisplayName("TC-INTAKE-007 a rejected batch is closed with a reason")
    void rejectABatch() {
        String csv = """
                sku,cost,currency,from
                intake-widget-s,61.0000,RUB,2026-08-05T00:00:00Z
                """;

        ImportRepository.ImportBatch batch = intake.submit(actor, IntakeDataset.PURCHASE_COST,
                "cost-withdrawn.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        ImportRepository.ImportBatch rejected = intake.reject(actor, batch.id(),
                "the finance team withdrew this sheet", batch.version());

        assertThat(rejected.state()).isEqualTo("REJECTED");
        assertThat(rejected.rejectionCode()).isNotBlank();
    }

    @Test
    @Order(8)
    @DisplayName("TC-INTAKE-008 every row beyond the first 5000 is applied and reconciled")
    void applyMoreThanOnePage() {
        StringBuilder csv = new StringBuilder("sku,warehouse,onhand,at\n");
        for (int row = 0; row < 6001; row++) {
            csv.append("intake-widget-s,intake-warehouse,").append(row)
                    .append(",2026-08-20T00:00:00Z\n");
        }
        var batch = intake.submit(actor, IntakeDataset.INTERNAL_STOCK, "stock-many.csv",
                "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(batch.acceptedRowCount()).isEqualTo(6001);
        var applied = intake.approveAndApply(actor, batch.id(), null, batch.version());
        assertThat(applied.state()).isEqualTo("APPLIED");
        assertThat(jdbc.sql("SELECT count(*) FROM core.internal_stock_snapshot f JOIN core.fact_provenance p"
                + " ON p.id=f.provenance_id WHERE p.import_batch_id=:id")
                .param("id", batch.id()).query(Integer.class).single()).isEqualTo(6001);
        assertThat(jdbc.sql("SELECT applied_row_count FROM staging.import_batch WHERE id=:id")
                .param("id", batch.id()).query(Integer.class).single()).isEqualTo(6001);
        assertThatThrownBy(() -> intake.approveAndApply(actor, batch.id(), null, applied.version()))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    @Order(9)
    @DisplayName("TC-INTAKE-009 a mid-apply database failure rolls back the whole batch and permits a safe retry")
    void atomicFailureAndRetry() {
        var batch = intake.submit(actor, IntakeDataset.INTERNAL_STOCK, "stock-atomic.csv", "text/csv",
                ("sku,warehouse,onhand,at\n"
                    + "intake-widget-s,intake-warehouse,10001,2026-08-21T00:00:00Z\n"
                    + "intake-widget-s,intake-warehouse,10002,2026-08-21T00:00:00Z\n")
                .getBytes(StandardCharsets.UTF_8));
        var arranger = JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                TestDatabase.container().getJdbcUrl(), TestDatabase.migrationRole(), TestDatabase.migrationPassword()));
        arranger.sql("CREATE FUNCTION public.synthetic_import_failure() RETURNS trigger LANGUAGE plpgsql AS $$"
                + " BEGIN IF NEW.quantity_on_hand=10002 THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$").update();
        arranger.sql("CREATE TRIGGER synthetic_import_failure BEFORE INSERT ON core.internal_stock_snapshot"
                + " FOR EACH ROW EXECUTE FUNCTION public.synthetic_import_failure()").update();
        try {
            assertThatThrownBy(() -> intake.approveAndApply(actor, batch.id(), null, batch.version()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(intake.require(batch.id()).state()).isEqualTo("VALIDATED");
            assertThat(jdbc.sql("SELECT count(*) FROM core.fact_provenance WHERE import_batch_id=:id")
                    .param("id", batch.id()).query(Integer.class).single()).isZero();
        } finally {
            arranger.sql("DROP TRIGGER synthetic_import_failure ON core.internal_stock_snapshot").update();
            arranger.sql("DROP FUNCTION public.synthetic_import_failure()").update();
        }
        assertThat(intake.approveAndApply(actor, batch.id(), null, batch.version()).state()).isEqualTo("APPLIED");
        assertThat(jdbc.sql("SELECT applied_row_count FROM staging.import_batch WHERE id=:id")
                .param("id", batch.id()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    @Order(10)
    @DisplayName("TC-INTAKE-010 profile creation is operator-attributed and rolls back if audit is refused")
    void profileRegistrationIsAuditedAtomically() {
        assertThat(jdbc.sql("SELECT count(*) FROM ops.metadata_audit_event a"
                + " JOIN staging.import_schema_profile p ON p.id=a.entity_id"
                + " WHERE p.organization_id=:organization AND a.entity_type='import-schema-profile'"
                + " AND a.actor_id=:operator AND a.action='CREATE'")
                .param("organization", organizationId).param("operator", OPERATOR)
                .query(Integer.class).single()).isEqualTo(2);
        assertThatThrownBy(() -> intake.registerProfile("invalid operator", organizationId,
                IntakeDataset.INTERNAL_STOCK, "refused-audit-profile", 1, "Refused audit fixture",
                List.of(Map.of("column", "sku", "field", "skuCode"),
                        Map.of("column", "warehouse", "field", "warehouseCode"),
                        Map.of("column", "onhand", "field", "quantityOnHand"),
                        Map.of("column", "at", "field", "observedAt")), "operations-team"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM staging.import_schema_profile"
                + " WHERE organization_id=:organization AND profile_code='refused-audit-profile'")
                .param("organization", organizationId).query(Integer.class).single()).isZero();
    }

    @Test
    @Order(11)
    @DisplayName("TC-INTAKE-011 nonintegral and overflowing stock cells are rejected before typed application")
    void stockNumericBoundaryIsValidatedBeforeApplication() {
        String header = "sku,warehouse,onhand,at\n";
        for (String value : List.of("NaN", "1.5", "2147483648", "9223372036854775808")) {
            var batch = intake.submit(actor, IntakeDataset.INTERNAL_STOCK, "stock-invalid.csv", "text/csv",
                    (header + "intake-widget-s,intake-warehouse," + value + ",2026-08-22T00:00:00Z\n")
                            .getBytes(StandardCharsets.UTF_8));
            assertThat(batch.state()).isEqualTo("REJECTED");
            assertThatThrownBy(() -> intake.approveAndApply(actor, batch.id(), null, batch.version()))
                    .isInstanceOf(OperationRejectedException.class);
        }
        var maximum = intake.submit(actor, IntakeDataset.INTERNAL_STOCK, "stock-maximum.csv", "text/csv",
                (header + "intake-widget-s,intake-warehouse,2147483647,2026-08-22T00:00:00Z\n")
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(intake.approveAndApply(actor, maximum.id(), null, maximum.version()).state()).isEqualTo("APPLIED");
        assertThat(jdbc.sql("SELECT f.quantity_on_hand FROM core.internal_stock_snapshot f"
                + " JOIN core.fact_provenance p ON p.id=f.provenance_id WHERE p.import_batch_id=:id")
                .param("id", maximum.id()).query(Integer.class).single()).isEqualTo(Integer.MAX_VALUE);
    }

    private static byte[] costFile(String skuCode) {
        return ("sku,cost,currency,from\n" + skuCode + ",60.0000,RUB,2026-08-01T00:00:00Z\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
