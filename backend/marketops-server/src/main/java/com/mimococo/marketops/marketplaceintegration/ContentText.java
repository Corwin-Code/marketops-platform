package com.mimococo.marketops.marketplaceintegration;

import java.util.regex.Pattern;

/**
 * How a listing's title or description read back from a marketplace is compared with the text
 * that was written (W2).
 *
 * <p>A marketplace may store the same words in a slightly different shape: line breaks as
 * {@code <br>} tags, runs of spaces collapsed, a trailing newline dropped. Those are not a
 * different text, and reading them as one would leave a write that landed looking like a write
 * that did not. Everything else counts: a changed word, a changed letter, a changed punctuation
 * mark is a different text.
 */
public final class ContentText {

    /** A line break as markup: {@code <br>}, {@code <br/>}, {@code <br />}, any case. */
    private static final Pattern MARKUP_BREAK = Pattern.compile("(?i)<br\\s*/?>");

    /** Any run of whitespace, line breaks included. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ContentText() {
    }

    /** How a read-back text relates to the text written and the text it replaced. */
    public enum Match {
        /** The marketplace holds the text that was written. */
        MATCHES_TARGET,
        /** The marketplace still holds the text that was there before. */
        MATCHES_PRIOR,
        /** The marketplace holds something else. */
        DIFFERENT
    }

    /** The comparable form of a text: markup breaks and whitespace runs as single spaces, trimmed. */
    public static String normalized(String text) {
        if (text == null) {
            return "";
        }
        String unbroken = MARKUP_BREAK.matcher(text).replaceAll(" ");
        return WHITESPACE.matcher(unbroken).replaceAll(" ").strip();
    }

    /** Whether two texts are the same text in the sense above. */
    public static boolean same(String left, String right) {
        return normalized(left).equals(normalized(right));
    }

    /**
     * Compare what was read back with the target and the prior text.
     *
     * <p>A field the change left as it was has the same target and prior; reading it back as that
     * text is a match on the target.
     */
    public static Match compare(String observed, String target, String prior) {
        if (same(observed, target)) {
            return Match.MATCHES_TARGET;
        }
        if (prior != null && same(observed, prior)) {
            return Match.MATCHES_PRIOR;
        }
        return Match.DIFFERENT;
    }
}
