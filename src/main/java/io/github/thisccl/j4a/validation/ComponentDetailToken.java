package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.components.ComponentCatalog;
import io.github.thisccl.j4a.jmx.property.RuntimeFingerprint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class ComponentDetailToken {
    private static final String VERSION = "ct1";
    private static final String KEY_DOMAIN = "j4a-component-detail-token-key-v1";
    private static final String AUTH_DOMAIN = "j4a-component-detail-token-auth-v1";

    private ComponentDetailToken() {
    }

    static String encode(
            RuntimeFingerprint runtime, String category, String projection, String component) {
        String payload = ComponentCategoryCursor.runtimeDigest(runtime) + "\n" + category + "\n"
                + projection + "\n" + ComponentCategoryCursor.componentDigest(component);
        String body = VERSION + "." + base64(payload.getBytes(StandardCharsets.UTF_8));
        String token = body + "." + base64(authenticate(runtime, body));
        if (token.length() > 512) {
            throw new IllegalStateException("Component detail token exceeds the 512 byte limit");
        }
        return token;
    }

    static String resolve(
            String token,
            RuntimeFingerprint runtime,
            List<ComponentCatalog.ComponentDefinition> retained) {
        String digest = requireDigest(token, runtime);
        String match = null;
        String category = null;
        for (ComponentCatalog.ComponentDefinition definition : retained) {
            if (MessageDigest.isEqual(
                    digest.getBytes(StandardCharsets.US_ASCII),
                    ComponentCategoryCursor.componentDigest(definition.component())
                            .getBytes(StandardCharsets.US_ASCII))) {
                if (match != null) {
                    throw invalid("Component token matches more than one retained runtime component");
                }
                match = definition.component();
                category = definition.category();
            }
        }
        if (match == null) {
            throw invalid("Component token no longer matches a retained runtime component");
        }
        String[] fields = payloadFields(token);
        if (!category.equals(fields[1]) || !ComponentCategoryCursor.PROJECTION.equals(fields[2])) {
            throw invalid("Component token does not match the selected runtime category or projection");
        }
        return match;
    }

    private static String requireDigest(String token, RuntimeFingerprint runtime) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])
                    || parts[1].isEmpty() || parts[2].isEmpty()) {
                throw invalid("Invalid component token");
            }
            String body = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(authenticate(runtime, body), Base64.getUrlDecoder().decode(parts[2]))) {
                throw invalid("Invalid component token");
            }
            String[] fields = payloadFields(token);
            if (fields.length != 4
                    || !ComponentCategoryCursor.runtimeDigest(runtime).equals(fields[0])
                    || !fields[3].matches("[0-9a-f]{64}")) {
                throw invalid("Component token does not match the selected runtime");
            }
            return fields[3];
        } catch (ComponentsCursorException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid component token");
        }
    }

    private static String[] payloadFields(String token) {
        String[] parts = token.split("\\.", -1);
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .split("\\n", -1);
    }

    private static byte[] authenticate(RuntimeFingerprint runtime, String body) {
        byte[] key = hmac(ComponentCursorSigningKey.loadOrCreate(),
                (KEY_DOMAIN + "\n" + ComponentCategoryCursor.canonicalRuntime(runtime))
                        .getBytes(StandardCharsets.UTF_8));
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

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static ComponentsCursorException invalid(String message) {
        return ComponentsCursorException.componentTokenInvalid(message
                + "; restart from category details and use its component token unchanged");
    }
}
