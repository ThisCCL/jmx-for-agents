package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.property.RuntimeFingerprint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class ComponentCategoryCursor {
    static final String PROJECTION = "authoring:scalar-array-v1";
    private static final String VERSION = "v4";
    private static final String KEY_DOMAIN = "j4a-components-category-cursor-key-v4";
    private static final String AUTH_DOMAIN = "j4a-components-category-cursor-auth-v4";
    private ComponentCategoryCursor() {
    }

    static String encode(
            RuntimeFingerprint runtime, String category, String projection, int limit, String lastComponent) {
        return encode(runtime, category, projection, limit, 16384, lastComponent);
    }

    static String encode(
            RuntimeFingerprint runtime, String category, String projection, int limit,
            int maxBytes, String lastComponent) {
        String payload = runtimeDigest(runtime) + "\n" + category + "\n" + PROJECTION + "\n"
                + limit + "\n" + maxBytes + "\n" + componentDigest(lastComponent);
        String encoded = base64(payload.getBytes(StandardCharsets.UTF_8));
        String body = VERSION + "." + encoded;
        String token = body + "." + base64(authenticate(runtime, body));
        if (token.length() > 768) {
            throw new IllegalStateException("Components cursor exceeds the 768 byte limit");
        }
        return token;
    }

    static String requireLastComponent(
            String cursor, RuntimeFingerprint runtime, String category, String projection, int limit) {
        return requireLastComponent(cursor, runtime, category, projection, limit, 16384);
    }

    static String requireLastComponent(
            String cursor, RuntimeFingerprint runtime, String category, String projection,
            int limit, int maxBytes) {
        if (cursor == null) {
            return null;
        }
        try {
            String[] token = cursor.split("\\.", -1);
            if (token.length == 3 && "v3".equals(token[0])) {
                throw new ComponentsCursorException(
                        "Components cursor version 3 is no longer accepted; start category details again without cursor");
            }
            if (token.length != 3 || !VERSION.equals(token[0])
                    || token[1].isEmpty() || token[2].isEmpty()) {
                throw invalid();
            }
            String body = token[0] + "." + token[1];
            byte[] expected = authenticate(runtime, body);
            byte[] supplied = Base64.getUrlDecoder().decode(token[2]);
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw invalid();
            }
            String[] fields = new String(Base64.getUrlDecoder().decode(token[1]), StandardCharsets.UTF_8)
                    .split("\\n", -1);
            if (fields.length != 6) {
                throw invalid();
            }
            int boundLimit = Integer.parseInt(fields[3]);
            int boundMaxBytes = Integer.parseInt(fields[4]);
            if (!runtimeDigest(runtime).equals(fields[0]) || !category.equals(fields[1])
                    || !PROJECTION.equals(fields[2]) || !PROJECTION.equals(projection)
                    || !projection.equals(fields[2]) || limit != boundLimit || maxBytes != boundMaxBytes) {
                throw new ComponentsCursorException(
                        "Components cursor does not match the selected runtime, category, projection, limit, or max-bytes");
            }
            if (!fields[5].matches("[0-9a-f]{64}")) {
                throw invalid();
            }
            return fields[5];
        } catch (ComponentsCursorException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static byte[] authenticate(RuntimeFingerprint fingerprint, String body) {
        byte[] key = hmac(
                ComponentCursorSigningKey.loadOrCreate(),
                (KEY_DOMAIN + "\n" + canonicalRuntime(fingerprint)).getBytes(StandardCharsets.UTF_8));
        return hmac(key, (AUTH_DOMAIN + "\n" + body).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    static String runtimeDigest(RuntimeFingerprint fingerprint) {
        return hex(digest(canonicalRuntime(fingerprint).getBytes(StandardCharsets.UTF_8)));
    }

    static String canonicalRuntime(RuntimeFingerprint fingerprint) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(fingerprint.jmeterHome()).append('\n')
                .append(fingerprint.jmeterVersion()).append('\n');
        for (Map.Entry<String, String> library : fingerprint.librarySha256().entrySet()) {
            canonical.append(library.getKey()).append('=').append(library.getValue()).append('\n');
        }
        return canonical.toString();
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String componentDigest(String component) {
        return hex(digest(component.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(item & 0x0f, 16));
        }
        return result.toString();
    }

    private static ComponentsCursorException invalid() {
        return new ComponentsCursorException("Invalid components cursor");
    }
}
