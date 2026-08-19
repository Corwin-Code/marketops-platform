package com.mimococo.marketops.shared;

import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rejects writable text that appears to carry secret material.
 *
 * <p>Every externally writable free-text field passes through this guard before
 * validation succeeds, because any of them could receive a pasted credential by
 * accident. The guard refuses; it never redacts and persists, and it never
 * echoes the rejected value anywhere — the refusal record carries only the
 * field name and the name of the rule that fired.
 *
 * <p>The patterns describe material, not vocabulary: an uninterrupted run long
 * enough to be an encoded key, a bearer-token shape, an armored key header, or
 * a key-value assignment whose key names a credential. Ordinary prose about
 * secrets does not match.
 */
public final class SecretMaterialGuard {

    private static final Logger log = LoggerFactory.getLogger(SecretMaterialGuard.class);

    /** An uninterrupted encoded run long enough to be key material. */
    private static final Pattern ENCODED_RUN = Pattern.compile("[A-Za-z0-9+/=_-]{64,}");

    /** An HTTP bearer credential shape. */
    private static final Pattern BEARER_SHAPE =
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}");

    /** An armored private-key or certificate header. */
    private static final Pattern ARMOR_HEADER = Pattern.compile("-----BEGIN\\s");

    /** A credential-named assignment. */
    private static final Pattern CREDENTIAL_ASSIGNMENT =
            Pattern.compile("(?i)\\b(?:password|passwd|secret|token|api[_-]?key)\\s*[:=]");

    /** A refusal rule: the pattern that fires and the name reported for it. */
    private record Refusal(String ruleName, Pattern pattern) {
    }

    private static final List<Refusal> REFUSALS = List.of(
            new Refusal("encoded-run", ENCODED_RUN),
            new Refusal("bearer-shape", BEARER_SHAPE),
            new Refusal("armor-header", ARMOR_HEADER),
            new Refusal("credential-assignment", CREDENTIAL_ASSIGNMENT));

    private SecretMaterialGuard() {
    }

    /**
     * Refuse {@code value} when it looks like secret material.
     *
     * <p>A {@code null} value is acceptable: absence carries nothing. A refusal
     * is observable through a structured event naming only the field and the
     * rule — never the value.
     *
     * @throws OperationRejectedException with {@link ErrorCode#SECRET_MATERIAL_SUSPECTED}
     */
    public static void requireNonSecret(String fieldName, String value) {
        if (value == null) {
            return;
        }
        for (Refusal refusal : REFUSALS) {
            if (refusal.pattern().matcher(value).find()) {
                log.atWarn()
                        .addKeyValue("event", "secret_material_rejected")
                        .addKeyValue("fieldName", fieldName)
                        .addKeyValue("ruleName", refusal.ruleName())
                        .addKeyValue("correlationId", CorrelationId.current())
                        .log("Writable text refused by the secret-material guard");
                throw OperationRejectedException.of(ErrorCode.SECRET_MATERIAL_SUSPECTED);
            }
        }
    }
}
