package com.mimococo.marketops.shared;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation rules shared by every metadata writing surface.
 *
 * <p>Formats that the database also checks are checked here first so the
 * operator receives a stable error code instead of a constraint violation.
 * Timezone and currency go further than format: they are validated against the
 * runtime's IANA zone registry and ISO&nbsp;4217 registry, which the database
 * cannot maintain; the relational layer keeps only the format check.
 *
 * <p>Every free-text value accepted here also passes the
 * {@link SecretMaterialGuard}, so no writable text ingress can carry secret
 * material into persistence, logs or audit records.
 */
public final class MetadataFieldPolicy {

    /** Business code shape: lower-case, digit and hyphen, no edge hyphen. */
    public static final Pattern CODE = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    /** Operator attribution shape accepted by the maintenance boundary. */
    public static final Pattern OPERATOR = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    /** Registry code shape: dotted lower-case capability and endpoint names. */
    public static final Pattern REGISTRY_CODE =
            Pattern.compile("^[a-z0-9]([a-z0-9._-]{0,61}[a-z0-9])?$");

    /** Opaque secret reference shape; a name, never a value. */
    public static final Pattern SECRET_REFERENCE = Pattern.compile(
            "^secret-ref://[a-z0-9][a-z0-9-]{0,62}(/[a-z0-9][a-z0-9._-]{0,62}){1,4}$");

    private static final Pattern CURRENCY_SHAPE = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern COUNTRY_SHAPE = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern CIDR_SHAPE = Pattern.compile(
            "^(?:(?:\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}|[0-9A-Fa-f:]{2,45}/\\d{1,3})$");
    private static final Set<String> COUNTRY_CODES = Locale.getISOCountries(
            Locale.IsoCountryCode.PART1_ALPHA2);
    private static final int TEXT_LIMIT = 512;

    private MetadataFieldPolicy() {
    }

    /** Return a validated business code. */
    public static String requireCode(String value) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    /** Return a validated registry code. */
    public static String requireRegistryCode(String value) {
        if (value == null || !REGISTRY_CODE.matcher(value).matches()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    /** Return required free text, bounded and free of secret material. */
    public static String requireText(String fieldName, String value) {
        if (value == null || value.isBlank() || value.length() > TEXT_LIMIT) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        SecretMaterialGuard.requireNonSecret(fieldName, value);
        return value.strip();
    }

    /** Return optional free text, bounded and free of secret material. */
    public static String optionalText(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(fieldName, value);
    }

    /** Return a validated IANA timezone identifier, or {@code null}. */
    public static String optionalTimezone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(value.strip()).getId();
        } catch (RuntimeException rejected) {
            throw OperationRejectedException.of(ErrorCode.INVALID_TIMEZONE);
        }
    }

    /** Return a validated ISO 4217 currency code, or {@code null}. */
    public static String optionalCurrency(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.strip();
        if (!CURRENCY_SHAPE.matcher(candidate).matches()) {
            throw OperationRejectedException.of(ErrorCode.INVALID_CURRENCY);
        }
        try {
            Currency.getInstance(candidate);
        } catch (RuntimeException rejected) {
            throw OperationRejectedException.of(ErrorCode.INVALID_CURRENCY);
        }
        return candidate;
    }

    /** Return a validated ISO 3166-1 alpha-2 country code, or {@code null}. */
    public static String optionalCountry(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.strip();
        if (!COUNTRY_SHAPE.matcher(candidate).matches()
                || !COUNTRY_CODES.contains(candidate)) {
            throw OperationRejectedException.of(ErrorCode.INVALID_COUNTRY);
        }
        return candidate;
    }

    /** Return a validated CIDR source declaration. */
    public static String requireCidr(String value) {
        if (value == null || !CIDR_SHAPE.matcher(value.strip()).matches()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return value.strip();
    }

    /**
     * Return a validated opaque secret reference.
     *
     * <p>The reference format is itself the secret-material defence for this
     * field: a value is either a well-formed {@code secret-ref://} name or it is
     * refused, so raw key material — which cannot carry the scheme — can never
     * be accepted here regardless of its shape.
     */
    public static String requireSecretReference(String value) {
        if (value == null || !SECRET_REFERENCE.matcher(value).matches()) {
            throw OperationRejectedException.of(ErrorCode.SECRET_REFERENCE_INVALID);
        }
        return value;
    }
}
