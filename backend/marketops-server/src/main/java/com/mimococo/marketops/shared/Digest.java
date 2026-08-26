package com.mimococo.marketops.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * SHA-256 digests in the lower-case hexadecimal form every schema column
 * expects.
 *
 * <p>Digests appear throughout this product for one reason: to make a stored
 * answer reproducible. A metric value names the digest of its inputs, a
 * recommendation names the digest of the entity versions it was computed
 * against, and an approval names the same digest so it cannot be spent against
 * a different state of the world. All of them must be composed identically,
 * which is why the composition rule lives here rather than in each caller.
 *
 * <p>Components are joined with a separator that cannot occur inside a textual
 * component, so two different component lists cannot produce one input string.
 */
public final class Digest {

    /** Unit separator: a control character no business value contains. */
    private static final char COMPONENT_SEPARATOR = '\u001F';

    /** Stands in for an absent component, distinct from an empty one. */
    private static final String ABSENT = "\u0000";

    private static final HexFormat HEX = HexFormat.of();

    private Digest() {
    }

    /** Digest of raw bytes, as sixty-four lower-case hexadecimal characters. */
    public static String ofBytes(byte[] content) {
        Objects.requireNonNull(content, "content");
        return HEX.formatHex(newDigest().digest(content));
    }

    /** Digest of one string in its UTF-8 encoding. */
    public static String ofText(String text) {
        Objects.requireNonNull(text, "text");
        return ofBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Digest of an ordered component list.
     *
     * <p>Order is significant and an absent component is represented distinctly
     * from an empty one, so a missing value and a blank value never produce the
     * same digest.
     */
    public static String ofComponents(List<String> components) {
        Objects.requireNonNull(components, "components");
        StringBuilder joined = new StringBuilder();
        for (String component : components) {
            joined.append(component == null ? ABSENT : component);
            joined.append(COMPONENT_SEPARATOR);
        }
        return ofText(joined.toString());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by this platform", unavailable);
        }
    }
}
