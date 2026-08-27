package com.mimococo.marketops.operatingfacts.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.operatingfacts.internal.domain.IntakeDataset;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.InternalReferenceRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Both preview and application use this schema; a preview acceptance must fit storage. */
class ImportRowValidatorTest {
    private final InternalReferenceRepository references = mock(InternalReferenceRepository.class);
    private final ImportRowValidator validator = new ImportRowValidator(references);
    private final UUID organization = UUID.randomUUID();

    @ParameterizedTest
    @CsvSource({"unitCost,-1", "unitCost,100000000000000", "unitCost,1.00001", "unitCost,1e100000",
            "currencyCode,RU", "currencyCode,123", "costKind,RETAIL", "effectiveFrom,not-a-date"})
    void invalidCostIsRejectedBeforeApproval(String field, String value) {
        var row = cost(); row.put(field, value);
        assertThat(validate(IntakeDataset.PURCHASE_COST, row).accepted()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"quantityOnHand,2147483648", "quantityOnHand,-1", "quantityReserved,-1",
            "quantityReserved,2147483648", "quantityReserved,1.5"})
    void stockMatchesDatabaseIntegerBounds(String field, String value) {
        var row = new LinkedHashMap<>(Map.of("skuCode", "fixture-sku", "warehouseCode", "fixture-warehouse",
                "quantityOnHand", "1", "observedAt", "2026-08-01"));
        row.put(field, value);
        assertThat(validate(IntakeDataset.INTERNAL_STOCK, row).accepted()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"scopeKind,MYSTERY", "inputCode,MYSTERY", "valueKind,MYSTERY", "rateValue,1.1",
            "rateValue,0.0000001", "currencyCode,RUB", "scopeCode,unexpected"})
    void financeShapeIsClosed(String field, String value) {
        var row = new LinkedHashMap<>(Map.of("inputCode", "VARIABLE_TAX_RATE", "scopeKind", "ORGANIZATION",
                "valueKind", "RATE", "rateValue", "0.1", "effectiveFrom", "2026-08-01"));
        row.put(field, value);
        assertThat(validate(IntakeDataset.FINANCE_INPUT, row).accepted()).isFalse();
    }

    @Test
    void valuesHaveTheSameCanonicalTypesForPreviewAndApplication() {
        var row = cost(); row.put("currencyCode", "rub"); row.put("costKind", "landed");
        var accepted = validate(IntakeDataset.PURCHASE_COST, row);
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.values()).containsEntry("currencyCode", "RUB").containsEntry("costKind", "LANDED");
        assertThat(accepted.values().get("unitCost")).isInstanceOf(java.math.BigDecimal.class);
        assertThat(accepted.values().get("effectiveFrom")).isInstanceOf(java.time.Instant.class);
    }

    private ImportRowValidator.Outcome validate(IntakeDataset dataset, Map<String, String> row) {
        UUID target = UUID.randomUUID();
        when(references.productVariantIdBySku(any(), any())).thenReturn(Optional.of(target));
        when(references.warehouseIdByCode(any(), any())).thenReturn(Optional.of(target));
        Map<String, String> mapping = new LinkedHashMap<>();
        row.keySet().forEach(field -> mapping.put(field, field));
        return validator.validate(organization, dataset, mapping, row);
    }

    private static LinkedHashMap<String, String> cost() {
        return new LinkedHashMap<>(Map.of("skuCode", "fixture-sku", "unitCost", "42.1234",
                "currencyCode", "RUB", "effectiveFrom", "2026-08-01"));
    }
}
