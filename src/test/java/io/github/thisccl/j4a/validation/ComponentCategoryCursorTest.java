package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.jmx.property.RuntimeFingerprint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComponentCategoryCursorTest {
    private static final String PROJECTION = "authoring:scalar-array-v1";
    private static final String LEGACY_DOMAIN = "j4a-components-category-cursor-v1";
    private static final String KEY_DOMAIN = "j4a-components-category-cursor-key-v3";
    private static final String AUTH_DOMAIN = "j4a-components-category-cursor-auth-v3";
    private static final byte[] EXTRACTED_OLD_APPLICATION_KEY = new byte[] {
        (byte) 0x8c, 0x21, (byte) 0xe4, 0x73, 0x19, (byte) 0xb6, 0x5a, (byte) 0xdf,
        0x44, (byte) 0xa9, 0x0e, 0x37, (byte) 0xf2, 0x68, (byte) 0x91, 0x5d,
        0x2b, (byte) 0xc7, 0x54, (byte) 0x83, 0x16, (byte) 0xed, 0x70, 0x3a,
        (byte) 0x9f, 0x42, (byte) 0xd8, 0x65, 0x0b, (byte) 0xb3, 0x7c, 0x28
    };

    @TempDir
    java.nio.file.Path tempDir;

    private String previousUserHome;

    @BeforeEach
    void isolateUserHome() throws Exception {
        previousUserHome = System.getProperty("user.home");
        java.nio.file.Path home = tempDir.resolve("home");
        java.nio.file.Files.createDirectories(home);
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (previousUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousUserHome);
        }
    }

    @Test
    void applicationKeyExtractedFromOldJarCannotForgeLastComponent() throws Exception {
        RuntimeFingerprint runtime = runtime();
        String valid = ComponentCategoryCursor.encode(
                runtime, "sampler", PROJECTION, 20, "a.Component");
        String[] token = valid.split("\\.", -1);
        String payload = new String(Base64.getUrlDecoder().decode(token[1]), StandardCharsets.UTF_8);
        String forgedPayload = payload.substring(0, payload.lastIndexOf('\n') + 1) + "z.ForgedComponent";
        String encoded = base64(forgedPayload.getBytes(StandardCharsets.UTF_8));
        String body = token[0] + "." + encoded;
        byte[] extractedRuntimeKey = hmac(EXTRACTED_OLD_APPLICATION_KEY,
                (KEY_DOMAIN + "\n" + canonicalRuntime(runtime)).getBytes(StandardCharsets.UTF_8));
        String forged = body + "." + base64(hmac(extractedRuntimeKey,
                (AUTH_DOMAIN + "\n" + body).getBytes(StandardCharsets.US_ASCII)));

        assertThatThrownBy(() -> ComponentCategoryCursor.requireLastComponent(
                forged, runtime, "sampler", PROJECTION, 20))
                .isInstanceOf(ComponentsCursorException.class)
                .hasMessage("Invalid components cursor");
    }

    @Test
    void legacyPublicDigestCannotForgeLastComponent() throws Exception {
        RuntimeFingerprint runtime = runtime();
        String forged = legacyCursor(runtime, "sampler", PROJECTION, 20, "z.ForgedComponent");

        assertThatThrownBy(() -> ComponentCategoryCursor.requireLastComponent(
                forged, runtime, "sampler", PROJECTION, 20))
                .isInstanceOf(ComponentsCursorException.class)
                .hasMessage("Invalid components cursor");
    }

    @Test
    void cursorSignedWithWrongKeyIsRejected() throws Exception {
        RuntimeFingerprint runtime = runtime();
        String valid = ComponentCategoryCursor.encode(
                runtime, "sampler", PROJECTION, 20, "a.Component");
        String[] token = valid.split("\\.", -1);
        byte[] wrongRuntimeKey = hmac(new byte[32],
                (KEY_DOMAIN + "\n" + canonicalRuntime(runtime)).getBytes(StandardCharsets.UTF_8));
        String forged = token[0] + "." + token[1] + "."
                + base64(hmac(wrongRuntimeKey,
                        (AUTH_DOMAIN + "\n" + token[0] + "." + token[1])
                                .getBytes(StandardCharsets.US_ASCII)));

        assertThatThrownBy(() -> ComponentCategoryCursor.requireLastComponent(
                forged, runtime, "sampler", PROJECTION, 20))
                .isInstanceOf(ComponentsCursorException.class)
                .hasMessage("Invalid components cursor");
    }

    @Test
    void authenticatedCursorIsDeterministicAndRejectsByteTampering() {
        RuntimeFingerprint runtime = runtime();
        String first = ComponentCategoryCursor.encode(
                runtime, "sampler", PROJECTION, 20, "a.Component");
        String repeated = ComponentCategoryCursor.encode(
                runtime, "sampler", PROJECTION, 20, "a.Component");
        int payloadStart = first.indexOf('.') + 1;
        String tampered = replace(first, payloadStart,
                first.charAt(payloadStart) == 'A' ? 'B' : 'A');

        assertThat(repeated).isEqualTo(first);
        assertThat(ComponentCategoryCursor.requireLastComponent(
                first, runtime, "sampler", PROJECTION, 20))
                .isEqualTo(ComponentCategoryCursor.componentDigest("a.Component"));
        assertThatThrownBy(() -> ComponentCategoryCursor.requireLastComponent(
                tampered, runtime, "sampler", PROJECTION, 20))
                .isInstanceOf(ComponentsCursorException.class)
                .hasMessage("Invalid components cursor");
    }

    @Test
    void scalarArrayCursorUsesV4WithoutEmbeddingBoundaryAndRejectsLegacyV3Token() throws Exception {
        RuntimeFingerprint runtime = runtime();
        String cursor = ComponentCategoryCursor.encode(
                runtime, "sampler", PROJECTION, 20, "a.Component");
        assertThat(cursor).startsWith("v4.").hasSizeLessThanOrEqualTo(768)
                .doesNotContain("a.Component");
        assertThat(ComponentCategoryCursor.requireLastComponent(
                cursor, runtime, "sampler", PROJECTION, 20))
                .isEqualTo(ComponentCategoryCursor.componentDigest("a.Component"));

        String legacy = legacyV3Cursor(runtime, "sampler", PROJECTION, 20, "a.Component");
        assertThatThrownBy(() -> ComponentCategoryCursor.requireLastComponent(
                legacy, runtime, "sampler", PROJECTION, 20))
                .isInstanceOf(ComponentsCursorException.class)
                .hasMessageContaining("start category details again without cursor");
    }

    @Test
    void cursorRemainsBoundToCategoryProjectionAndLimit() {
        RuntimeFingerprint runtime = runtime();
        String cursor = ComponentCategoryCursor.encode(
                runtime, "sampler", PROJECTION, 20, "a.Component");

        assertThatThrownBy(() -> ComponentCategoryCursor.requireLastComponent(
                cursor, runtime, "timer", PROJECTION, 20))
                .isInstanceOf(ComponentsCursorException.class)
                .hasMessage("Components cursor does not match the selected runtime, category, projection, limit, or max-bytes");
        assertThatThrownBy(() -> ComponentCategoryCursor.requireLastComponent(
                cursor, runtime, "sampler", "authoring:canonical", 20))
                .isInstanceOf(ComponentsCursorException.class)
                .hasMessage("Components cursor does not match the selected runtime, category, projection, limit, or max-bytes");
        assertThatThrownBy(() -> ComponentCategoryCursor.requireLastComponent(
                cursor, runtime, "sampler", PROJECTION, 10))
                .isInstanceOf(ComponentsCursorException.class)
                .hasMessage("Components cursor does not match the selected runtime, category, projection, limit, or max-bytes");
    }

    private static String replace(String value, int index, char replacement) {
        return value.substring(0, index) + replacement + value.substring(index + 1);
    }

    private static RuntimeFingerprint runtime() {
        return new RuntimeFingerprint(
                "/runtime", "5.6.3", Collections.singletonMap("lib/example.jar", "abc123"));
    }

    private static String legacyCursor(
            RuntimeFingerprint runtime, String category, String projection, int limit, String lastComponent)
            throws Exception {
        String runtimeDigest = hex(MessageDigest.getInstance("SHA-256")
                .digest(canonicalRuntime(runtime).getBytes(StandardCharsets.UTF_8)));
        String payload = runtimeDigest + "\n" + category + "\n" + projection + "\n"
                + limit + "\n" + lastComponent;
        String encoded = base64(payload.getBytes(StandardCharsets.UTF_8));
        String checksum = hex(MessageDigest.getInstance("SHA-256")
                .digest((LEGACY_DOMAIN + "\n" + encoded).getBytes(StandardCharsets.UTF_8)));
        return encoded + "." + checksum;
    }

    private static String legacyV3Cursor(
            RuntimeFingerprint runtime, String category, String projection, int limit, String lastComponent)
            throws Exception {
        String runtimeDigest = hex(MessageDigest.getInstance("SHA-256")
                .digest(canonicalRuntime(runtime).getBytes(StandardCharsets.UTF_8)));
        String payload = runtimeDigest + "\n" + category + "\n" + projection + "\n"
                + limit + "\n" + lastComponent;
        String encoded = base64(payload.getBytes(StandardCharsets.UTF_8));
        String body = "v3." + encoded;
        byte[] key = hmac(ComponentCursorSigningKey.loadOrCreate(),
                (KEY_DOMAIN + "\n" + canonicalRuntime(runtime))
                        .getBytes(StandardCharsets.UTF_8));
        return body + "." + base64(hmac(key,
                (AUTH_DOMAIN + "\n" + body).getBytes(StandardCharsets.US_ASCII)));
    }

    private static String canonicalRuntime(RuntimeFingerprint runtime) {
        return runtime.jmeterHome() + "\n" + runtime.jmeterVersion() + "\n"
                + "lib/example.jar=abc123\n";
    }

    private static byte[] hmac(byte[] key, byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value);
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(item & 0x0f, 16));
        }
        return result.toString();
    }
}
