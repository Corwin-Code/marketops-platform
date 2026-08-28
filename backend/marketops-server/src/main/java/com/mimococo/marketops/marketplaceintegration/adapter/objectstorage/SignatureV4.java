package com.mimococo.marketops.marketplaceintegration.adapter.objectstorage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Request signing for an S3-compatible object store.
 *
 * <p>The scheme is AWS Signature Version 4 as published: a canonical request, a
 * string to sign derived from it, a signing key derived from the secret by a
 * chain of keyed hashes, and an authorization header carrying the result. It is
 * implemented here rather than pulled in as a vendor SDK because the whole of
 * what this application needs is three object operations, and a signer is a
 * pure function that can be exercised exactly.
 *
 * <p>The secret never leaves this class as a string. It arrives as a character
 * array, is used to derive the signing key, and the caller clears it afterwards;
 * nothing here retains it, logs it, or places it in a message.
 */
final class SignatureV4 {

    /** The signing algorithm identifier that appears in the string to sign. */
    static final String ALGORITHM = "AWS4-HMAC-SHA256";

    /** Service name for an S3-compatible store, used in the credential scope. */
    static final String SERVICE = "s3";

    /** Terminator of the credential scope. */
    private static final String SCOPE_TERMINATOR = "aws4_request";

    private static final DateTimeFormatter AMZ_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private static final HexFormat HEX = HexFormat.of();

    private SignatureV4() {
    }

    /** The request timestamp in the basic form the scheme requires. */
    static String amzDateTime(Instant instant) {
        return AMZ_DATE_TIME.format(instant);
    }

    /** The request date in the basic form the credential scope requires. */
    static String amzDate(Instant instant) {
        return AMZ_DATE.format(instant);
    }

    /** Lower-case hexadecimal SHA-256 of a payload. */
    static String hashPayload(byte[] payload) {
        return HEX.formatHex(sha256(payload));
    }

    /**
     * The canonical request: the exact bytes both sides agree to hash.
     *
     * <p>Header names are lower-cased and sorted, values are trimmed, and the
     * signed-header list is derived from the same map, so a caller cannot sign a
     * different set of headers than it declares.
     */
    static String canonicalRequest(String method,
                                   String canonicalUri,
                                   String canonicalQuery,
                                   SortedMap<String, String> headers,
                                   String payloadHash) {
        String canonicalHeaders = headers.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue().trim() + "\n")
                .collect(Collectors.joining());
        return String.join("\n",
                method,
                canonicalUri,
                canonicalQuery,
                canonicalHeaders,
                signedHeaders(headers),
                payloadHash);
    }

    /** The semicolon-joined list of header names covered by the signature. */
    static String signedHeaders(SortedMap<String, String> headers) {
        return String.join(";", headers.keySet());
    }

    /** The credential scope a signature is valid inside. */
    static String credentialScope(Instant instant, String region) {
        return amzDate(instant) + "/" + region + "/" + SERVICE + "/" + SCOPE_TERMINATOR;
    }

    /** The string a signature is computed over. */
    static String stringToSign(Instant instant, String region, String canonicalRequest) {
        return String.join("\n",
                ALGORITHM,
                amzDateTime(instant),
                credentialScope(instant, region),
                HEX.formatHex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * The complete authorization header value.
     *
     * <p>The secret is consumed here and not retained. Clearing the array is the
     * caller's responsibility, because the caller is what obtained it and knows
     * when the last use has happened.
     */
    static String authorization(String accessKeyId,
                                char[] secretKey,
                                Instant instant,
                                String region,
                                SortedMap<String, String> headers,
                                String stringToSign) {
        byte[] signingKey = signingKey(secretKey, instant, region);
        String signature = HEX.formatHex(
                hmac(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));
        java.util.Arrays.fill(signingKey, (byte) 0);
        return ALGORITHM
                + " Credential=" + accessKeyId + "/" + credentialScope(instant, region)
                + ", SignedHeaders=" + signedHeaders(headers)
                + ", Signature=" + signature;
    }

    /** A case-insensitive, sorted header map in the form the scheme expects. */
    static SortedMap<String, String> canonicalHeaderMap(Map<String, String> headers) {
        SortedMap<String, String> canonical = new TreeMap<>();
        headers.forEach((name, value) ->
                canonical.put(name.toLowerCase(java.util.Locale.ROOT), value.trim()));
        return canonical;
    }

    /**
     * Percent-encode one path segment.
     *
     * <p>The unreserved set is left alone and everything else is encoded, which
     * is what the scheme requires; the standard form encoder is deliberately not
     * used because it encodes a space as a plus sign.
     */
    static String encodeSegment(String segment) {
        StringBuilder encoded = new StringBuilder(segment.length());
        for (byte raw : segment.getBytes(StandardCharsets.UTF_8)) {
            int value = raw & 0xFF;
            char character = (char) value;
            boolean unreserved = (character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '.'
                    || character == '_' || character == '~';
            if (unreserved) {
                encoded.append(character);
            } else {
                encoded.append('%')
                        .append(Character.toUpperCase(HEX.toHighHexDigit(value)))
                        .append(Character.toUpperCase(HEX.toLowHexDigit(value)));
            }
        }
        return encoded.toString();
    }

    private static byte[] signingKey(char[] secretKey, Instant instant, String region) {
        char[] material = new char[secretKey.length + 4];
        "AWS4".getChars(0,4,material,0);
        System.arraycopy(secretKey,0,material,4,secretKey.length);
        java.nio.ByteBuffer encoded = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(material));
        java.util.Arrays.fill(material,'\0');
        byte[] initial = new byte[encoded.remaining()];
        encoded.get(initial);
        java.util.Arrays.fill(encoded.array(),(byte)0);
        byte[] dateKey = hmac(initial, amzDate(instant).getBytes(StandardCharsets.UTF_8));
        java.util.Arrays.fill(initial, (byte) 0);
        byte[] regionKey = hmac(dateKey, region.getBytes(StandardCharsets.UTF_8));
        java.util.Arrays.fill(dateKey, (byte) 0);
        byte[] serviceKey = hmac(regionKey, SERVICE.getBytes(StandardCharsets.UTF_8));
        java.util.Arrays.fill(regionKey, (byte) 0);
        byte[] signing = hmac(serviceKey, SCOPE_TERMINATOR.getBytes(StandardCharsets.UTF_8));
        java.util.Arrays.fill(serviceKey, (byte) 0);
        return signing;
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException unavailable) {
            throw new IllegalStateException("HMAC-SHA256 is required by this platform", unavailable);
        }
    }

    private static byte[] sha256(byte[] content) {
        Objects.requireNonNull(content, "content");
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by this platform", unavailable);
        }
    }
}
