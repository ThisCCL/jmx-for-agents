package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComponentCursorSigningKeyTest {
    @TempDir
    Path tempDir;

    private Path home;
    private String previousUserHome;

    @BeforeEach
    void isolateUserHome() throws Exception {
        previousUserHome = System.getProperty("user.home");
        home = Files.createDirectories(tempDir.resolve("home"));
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

    @org.junit.jupiter.api.RepeatedTest(100)
    void createsOneExactKeyRaceSafelyAndRestrictsPosixPermissions() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<byte[]>> tasks = new ArrayList<Callable<byte[]>>();
            for (int index = 0; index < 16; index++) {
                tasks.add(new Callable<byte[]>() {
                    @Override
                    public byte[] call() {
                        return ComponentCursorSigningKey.loadOrCreate();
                    }
                });
            }
            List<Future<byte[]>> futures = executor.invokeAll(tasks);
            byte[] expected = futures.get(0).get();
            assertThat(expected).hasSize(32);
            for (Future<byte[]> future : futures) {
                assertThat(future.get()).containsExactly(expected);
            }
        } finally {
            executor.shutdownNow();
        }

        Path stateRoot = home.resolve(".j4a");
        Path state = stateRoot.resolve("state");
        Path key = state.resolve("components-cursor-signing.key");
        assertThat(Files.readAllBytes(key)).hasSize(32);
        if (Files.getFileStore(key).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> directoryMode = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            Set<PosixFilePermission> keyMode = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            assertThat(Files.getPosixFilePermissions(stateRoot)).isEqualTo(directoryMode);
            assertThat(Files.getPosixFilePermissions(state)).isEqualTo(directoryMode);
            assertThat(Files.getPosixFilePermissions(key)).isEqualTo(keyMode);
        }
    }

    @Test
    void rejectsMalformedKeyWithoutDisclosingStatePath() throws Exception {
        Path state = Files.createDirectories(home.resolve(".j4a").resolve("state"));
        Path key = state.resolve("components-cursor-signing.key");
        Files.write(key, new byte[31]);

        assertUnavailableWithoutPath(key);
    }

    @Test
    void rejectsSymlinkedStateWithoutTraversal() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("outside"));
        Path link = home.resolve(".j4a");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links unavailable");
        }

        assertUnavailableWithoutPath(link);
        try (java.util.stream.Stream<Path> entries = Files.list(target)) {
            assertThat(entries.count()).isZero();
        }
    }

    @Test
    void rejectsStateRootThatCannotContainState() throws Exception {
        Path blocked = home.resolve(".j4a");
        Files.write(blocked, Arrays.asList("not a directory"));

        assertUnavailableWithoutPath(blocked);
    }

    private static void assertUnavailableWithoutPath(Path path) {
        assertThatThrownBy(ComponentCursorSigningKey::loadOrCreate)
                .isInstanceOf(ComponentsCursorException.class)
                .hasMessage("Components cursor signing key is unavailable")
                .hasMessageNotContaining(path.toString())
                .hasMessageNotContaining(".j4a")
                .hasMessageNotContaining("components-cursor-signing.key");
    }
}
