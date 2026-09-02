package io.github.thisccl.j4a.validation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

final class SourceSnapshot<T> {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] bytes;
    private final String fingerprint;
    private final T parsed;

    private SourceSnapshot(byte[] bytes, String fingerprint, T parsed) {
        this.bytes = bytes;
        this.fingerprint = fingerprint;
        this.parsed = parsed;
    }

    static <T> SourceSnapshot<T> read(Path source, Parser<T> parser) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(parser, "parser");
        byte[] bytes = Files.readAllBytes(source);
        String fingerprint = sha256(bytes);
        T parsed;
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            parsed = parser.parse(input);
        }
        return new SourceSnapshot<>(bytes, fingerprint, parsed);
    }

    byte[] bytes() {
        return bytes.clone();
    }

    String fingerprint() {
        return fingerprint;
    }

    T parsed() {
        return parsed;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] encoded = new char[digest.length * 2];
            for (int index = 0; index < digest.length; index++) {
                int value = digest[index] & 0xff;
                encoded[index * 2] = HEX[value >>> 4];
                encoded[index * 2 + 1] = HEX[value & 0x0f];
            }
            return new String(encoded);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    @FunctionalInterface
    interface Parser<T> {
        T parse(InputStream source) throws IOException;
    }
}
