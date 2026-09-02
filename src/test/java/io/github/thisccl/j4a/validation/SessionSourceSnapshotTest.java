package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionSourceSnapshotTest {
    private static final String ABC_SHA_256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @TempDir
    Path tempDir;

    @Test
    void hashesAndParsesTheExactCapturedBytes() throws Exception {
        Path source = Files.write(tempDir.resolve("source.jmx"), "abc".getBytes(StandardCharsets.UTF_8));

        SourceSnapshot<String> snapshot = SourceSnapshot.read(source, SessionSourceSnapshotTest::utf8);

        assertThat(snapshot.parsed()).isEqualTo("abc");
        assertThat(snapshot.fingerprint()).isEqualTo(ABC_SHA_256);
        assertThat(snapshot.bytes()).containsExactly("abc".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sourceMutationDuringParsingCannotChangeTheCapturedSnapshot() throws Exception {
        Path source = Files.write(tempDir.resolve("mutable.jmx"), "abc".getBytes(StandardCharsets.UTF_8));

        SourceSnapshot<String> snapshot = SourceSnapshot.read(source, input -> {
            Files.write(source, "changed-after-capture".getBytes(StandardCharsets.UTF_8));
            return utf8(input);
        });

        assertThat(snapshot.parsed()).isEqualTo("abc");
        assertThat(snapshot.fingerprint()).isEqualTo(ABC_SHA_256);
        assertThat(snapshot.bytes()).containsExactly("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(Files.readAllBytes(source), StandardCharsets.UTF_8))
                .isEqualTo("changed-after-capture");
    }

    @Test
    void returnedBytesCannotMutateTheSnapshot() throws Exception {
        Path source = Files.write(tempDir.resolve("immutable.jmx"), "abc".getBytes(StandardCharsets.UTF_8));
        SourceSnapshot<String> snapshot = SourceSnapshot.read(source, SessionSourceSnapshotTest::utf8);

        byte[] returned = snapshot.bytes();
        returned[0] = 'z';

        assertThat(snapshot.bytes()).containsExactly("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(snapshot.parsed()).isEqualTo("abc");
        assertThat(snapshot.fingerprint()).isEqualTo(ABC_SHA_256);
    }

    @Test
    void missingSourceFailsBeforeTheParserRuns() {
        final boolean[] parserCalled = {false};

        assertThatThrownBy(() -> SourceSnapshot.read(tempDir.resolve("missing.jmx"), input -> {
            parserCalled[0] = true;
            return utf8(input);
        })).isInstanceOf(IOException.class);
        assertThat(parserCalled[0]).isFalse();
    }

    private static String utf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
