package com.mimococo.marketops.operatingfacts.internal.application;

import com.mimococo.marketops.operatingfacts.internal.domain.IntakeDataset;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.InternalReferenceRepository;
import com.mimococo.marketops.shared.SecretMaterialGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Decides whether one submitted row can become an internal fact.
 *
 * <p>Every rejection names a stable code and the field that caused it. A
 * submitter fixing a file needs to know which cell was wrong, and a report that
 * said only "invalid" would send them through the whole spreadsheet.
 *
 * <p>The row's own text is never echoed into the rejection detail. A cell can
 * contain anything somebody pasted, including a credential, so the detail names
 * the field and the rule rather than the value.
 *
 * <p>Reference resolution happens here rather than at application time. A row
 * naming a stock-keeping unit that does not exist is a rejected row with an
 * explanation, not a failure halfway through writing a batch that has already
 * changed some costs.
 */
@Component
public class ImportRowValidator {

    /** Rejections this validator can produce. */
    private static final String MISSING_REQUIRED_FIELD = "MISSING_REQUIRED_FIELD";
    private static final String VALUE_NOT_CONVERTIBLE = "VALUE_NOT_CONVERTIBLE";
    private static final String REFERENCE_NOT_FOUND = "REFERENCE_NOT_FOUND";
    private static final String VALUE_OUT_OF_RANGE = "VALUE_OUT_OF_RANGE";
    private static final String INCONSISTENT_VALUE_KIND = "INCONSISTENT_VALUE_KIND";

    private final InternalReferenceRepository references;

    ImportRowValidator(InternalReferenceRepository references) {
        this.references = references;
    }

    /** Validate one row against its dataset's field contract. */
    public Outcome validate(UUID organizationId,
                            IntakeDataset dataset,
                            Map<String, String> columnToField,
                            Map<String, String> row) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> column : columnToField.entrySet()) {
            String raw = row.get(column.getKey());
            if (raw == null || raw.isBlank()) {
                continue;
            }
            // A pasted credential must not reach persistence, a log or an audit
            // record, so the guard runs before the value is converted or stored.
            SecretMaterialGuard.requireNonSecret(column.getValue(), raw);
            Optional<IntakeDataset.Field> field = dataset.field(column.getValue());
            if (field.isEmpty()) {
                continue;
            }
            Object converted = convert(raw, field.get().kind());
            if (converted == null) {
                return Outcome.rejected(values, VALUE_NOT_CONVERTIBLE, column.getValue());
            }
            values.put(column.getValue(), converted);
        }

        for (IntakeDataset.Field required : dataset.requiredFields()) {
            if (!values.containsKey(required.name())) {
                return Outcome.rejected(values, MISSING_REQUIRED_FIELD, required.name());
            }
        }
        return switch (dataset) {
            case PURCHASE_COST -> validatePurchaseCost(organizationId, values);
            case INTERNAL_STOCK -> validateInternalStock(organizationId, values);
            case FINANCE_INPUT -> validateFinanceInput(organizationId, values);
        };
    }

    private Outcome validatePurchaseCost(UUID organizationId, Map<String, Object> values) {
        values.putIfAbsent("costKind", "PURCHASE");
        if (!enumValue(values, "costKind", "PURCHASE", "LANDED")
                || !currency(values, "currencyCode")) {
            return Outcome.rejected(values, VALUE_OUT_OF_RANGE, "costKind/currencyCode");
        }
        Optional<UUID> variantId = references.productVariantIdBySku(
                organizationId, values.get("skuCode").toString());
        if (variantId.isEmpty()) {
            return Outcome.rejected(values, REFERENCE_NOT_FOUND, "skuCode");
        }
        BigDecimal unitCost = (BigDecimal) values.get("unitCost");
        if (!decimalFits(unitCost, 18, 4)) {
            return Outcome.rejected(values, VALUE_OUT_OF_RANGE, "unitCost");
        }
        return Outcome.accepted(values, variantId.get().toString());
    }

    private Outcome validateInternalStock(UUID organizationId, Map<String, Object> values) {
        Optional<UUID> variantId = references.productVariantIdBySku(
                organizationId, values.get("skuCode").toString());
        if (variantId.isEmpty()) {
            return Outcome.rejected(values, REFERENCE_NOT_FOUND, "skuCode");
        }
        Optional<UUID> warehouseId = references.warehouseIdByCode(
                organizationId, values.get("warehouseCode").toString());
        if (warehouseId.isEmpty()) {
            return Outcome.rejected(values, REFERENCE_NOT_FOUND, "warehouseCode");
        }
        long onHand = (Long) values.get("quantityOnHand");
        if (onHand < 0 || onHand > Integer.MAX_VALUE) {
            return Outcome.rejected(values, VALUE_OUT_OF_RANGE, "quantityOnHand");
        }
        if (values.get("quantityReserved") instanceof Long reserved
                && (reserved < 0 || reserved > Integer.MAX_VALUE)) {
            return Outcome.rejected(values, VALUE_OUT_OF_RANGE, "quantityReserved");
        }
        for (String field : List.of("quantityQualityLocked", "quantityDamaged",
                "quantityWrittenOff")) {
            if (values.get(field) instanceof Long value
                    && (value < 0 || value > Integer.MAX_VALUE)) {
                return Outcome.rejected(values, VALUE_OUT_OF_RANGE, field);
            }
        }
        if (values.get("sellable") != null
                && !List.of("YES", "NO", "UNKNOWN").contains(values.get("sellable").toString())) {
            return Outcome.rejected(values, VALUE_OUT_OF_RANGE, "sellable");
        }
        if (values.get("returnReentryId") != null) {
            try {
                UUID.fromString(values.get("returnReentryId").toString());
            } catch (IllegalArgumentException invalid) {
                return Outcome.rejected(values, VALUE_OUT_OF_RANGE, "returnReentryId");
            }
        }
        return Outcome.accepted(values, variantId.get() + "|" + warehouseId.get());
    }

    /**
     * A finance input must carry exactly the value its kind declares.
     *
     * <p>A rate and an amount are not interchangeable. A row declaring a rate
     * while carrying an amount would produce a figure that is wrong by whatever
     * the scale between them happens to be, which is exactly the kind of error
     * nobody notices in a profit report.
     */
    private Outcome validateFinanceInput(UUID organizationId, Map<String, Object> values) {
        if (!enumValue(values, "inputCode", "VARIABLE_TAX_RATE", "PAYMENT_PROCESSING_RATE",
                "RETURN_HANDLING_UNIT_COST", "INBOUND_LOGISTICS_UNIT_COST",
                "REQUIRED_PROFIT_PER_UNIT", "SAFETY_BUFFER_PER_UNIT")
                || !enumValue(values, "scopeKind", "ORGANIZATION", "STORE", "PRODUCT_VARIANT")
                || !enumValue(values, "valueKind", "RATE", "AMOUNT")) {
            return Outcome.rejected(values, VALUE_OUT_OF_RANGE, "inputCode/scopeKind/valueKind");
        }
        String valueKind = values.get("valueKind").toString().toUpperCase(Locale.ROOT);
        if (values.get("inputCode").toString().endsWith("_RATE") != "RATE".equals(valueKind)) {
            return Outcome.rejected(values, INCONSISTENT_VALUE_KIND, "inputCode/valueKind");
        }
        boolean hasRate = values.containsKey("rateValue");
        boolean hasAmount = values.containsKey("amountValue");
        if ("RATE".equals(valueKind)) {
            if (!hasRate || hasAmount || values.containsKey("currencyCode")) {
                return Outcome.rejected(values, INCONSISTENT_VALUE_KIND, "rateValue");
            }
            BigDecimal rate = (BigDecimal) values.get("rateValue");
            if (!decimalFits(rate, 9, 6) || rate.compareTo(BigDecimal.ONE) > 0) {
                return Outcome.rejected(values, VALUE_OUT_OF_RANGE, "rateValue");
            }
        } else if ("AMOUNT".equals(valueKind)) {
            if (!hasAmount || hasRate || !values.containsKey("currencyCode")) {
                return Outcome.rejected(values, INCONSISTENT_VALUE_KIND, "amountValue");
            }
            if (!decimalFits((BigDecimal) values.get("amountValue"), 18, 4)
                    || !currency(values, "currencyCode")) {
                return Outcome.rejected(values, VALUE_OUT_OF_RANGE, "amountValue/currencyCode");
            }
        } else {
            return Outcome.rejected(values, INCONSISTENT_VALUE_KIND, "valueKind");
        }

        String scopeKind = values.get("scopeKind").toString().toUpperCase(Locale.ROOT);
        if ("ORGANIZATION".equals(scopeKind) && values.containsKey("scopeCode")) {
            return Outcome.rejected(values, INCONSISTENT_VALUE_KIND, "scopeCode");
        }
        if (!"ORGANIZATION".equals(scopeKind)) {
            Object scopeCode = values.get("scopeCode");
            if (scopeCode == null) {
                return Outcome.rejected(values, MISSING_REQUIRED_FIELD, "scopeCode");
            }
            Optional<UUID> scopeId = "STORE".equals(scopeKind)
                    ? references.storeIdByCode(organizationId, scopeCode.toString())
                    : references.productVariantIdBySku(organizationId, scopeCode.toString());
            if (scopeId.isEmpty()) {
                return Outcome.rejected(values, REFERENCE_NOT_FOUND, "scopeCode");
            }
            return Outcome.accepted(values, scopeId.get().toString());
        }
        return Outcome.accepted(values, organizationId.toString());
    }

    /**
     * Convert one cell's text, or report that it cannot be converted.
     *
     * <p>A date is accepted as a full instant or as a plain calendar date, which
     * is what a spreadsheet exports. A spreadsheet serial number is deliberately
     * not accepted: interpreting it would mean assuming an epoch, and the wrong
     * assumption places a cost version decades away from where it belongs.
     */
    private static Object convert(String raw, IntakeDataset.FieldKind kind) {
        String text = raw.trim();
        if (text.length() > 512) return null;
        return switch (kind) {
            case TEXT -> text;
            case INTEGER -> {
                try {
                    yield Long.valueOf(text);
                } catch (NumberFormatException notANumber) {
                    yield null;
                }
            }
            case DECIMAL -> {
                try {
                    if (!text.matches("[+-]?[0-9]{1,32}([.,][0-9]{1,12})?")) yield null;
                    yield new BigDecimal(text.replace(",", "."));
                } catch (NumberFormatException notANumber) {
                    yield null;
                }
            }
            case INSTANT -> parseInstant(text);
        };
    }

    private static Instant parseInstant(String text) {
        try {
            Instant parsed = Instant.parse(text);
            return validInstant(parsed) ? parsed : null;
        } catch (DateTimeParseException notAnInstant) {
            try {
                Instant parsed = LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
                return validInstant(parsed) ? parsed : null;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }

    private static boolean validInstant(Instant instant) {
        return !instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                && instant.isBefore(Instant.parse("+10000-01-01T00:00:00Z"));
    }

    private static boolean decimalFits(BigDecimal value, int precision, int scale) {
        BigDecimal canonical = value.stripTrailingZeros();
        return canonical.signum() >= 0 && canonical.scale() <= scale
                && canonical.precision() - canonical.scale() <= precision - scale;
    }

    private static boolean enumValue(Map<String, Object> values, String field, String... allowed) {
        String value = String.valueOf(values.get(field)).toUpperCase(Locale.ROOT);
        if (!java.util.List.of(allowed).contains(value)) return false;
        values.put(field, value);
        return true;
    }

    private static boolean currency(Map<String, Object> values, String field) {
        String value = String.valueOf(values.get(field)).toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z]{3}")) return false;
        values.put(field, value);
        return true;
    }

    /**
     * What validation decided about one row.
     *
     * @param accepted whether the row may become a fact
     * @param values the mapped internal fields that were resolved
     * @param rejectionCode why it was rejected, or {@code null}
     * @param rejectionDetail which field caused it, or {@code null}
     * @param targetKey the internal entity the row addresses, or {@code null}
     */
    public record Outcome(
            boolean accepted, Map<String, Object> values, String rejectionCode,
            String rejectionDetail, String targetKey) {

        static Outcome accepted(Map<String, Object> values, String targetKey) {
            return new Outcome(true, Map.copyOf(values), null, null, targetKey);
        }

        static Outcome rejected(Map<String, Object> values, String code, String field) {
            return new Outcome(false, Map.copyOf(values), code, field, null);
        }
    }
}
