package io.github.thisccl.j4a.jmx.property;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.property.JMeterProperty;

final class OpaqueEnvelope {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final RecursiveValue.OpaqueValue value;
    private final OpaqueClassSkeleton skeleton;

    private OpaqueEnvelope(
            RecursiveValue.OpaqueValue value, OpaqueClassSkeleton skeleton) {
        this.value = value;
        this.skeleton = skeleton;
    }

    static OpaqueEnvelope observe(
            JMeterProperty property, String runtimeFingerprint) throws IOException {
        byte[] bytes = save(property);
        String payload = new String(bytes, StandardCharsets.UTF_8);
        RecursiveValue.OpaqueValue value = new RecursiveValue.OpaqueValue(
                RecursiveValue.OpaqueValue.FORMAT,
                sha256(bytes),
                property.getClass().getName(),
                runtimeFingerprint,
                payload);
        return new OpaqueEnvelope(value, OpaqueClassSkeleton.capture(property));
    }

    static byte[] save(Object value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveElement(value, output);
        return output.toByteArray();
    }

    static Object load(byte[] payload) throws IOException {
        return SaveService.loadElement(new ByteArrayInputStream(payload));
    }

    static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            char[] encoded = new char[digest.length * 2];
            for (int index = 0; index < digest.length; index++) {
                int item = digest[index] & 0xff;
                encoded[index * 2] = HEX[item >>> 4];
                encoded[index * 2 + 1] = HEX[item & 0x0f];
            }
            return new String(encoded);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    RecursiveValue.OpaqueValue value() {
        return value;
    }

    OpaqueClassSkeleton skeleton() {
        return skeleton;
    }
}
