package io.github.thisccl.j4a.validation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

final class ComponentCursorSigningKey {
    private static final int KEY_BYTES = 32;
    private static final String STATE_DIRECTORY = ".j4a";
    private static final String CURSOR_STATE_DIRECTORY = "state";
    private static final String KEY_FILE = "components-cursor-signing.key";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            Collections.unmodifiableSet(EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            Collections.unmodifiableSet(EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));

    private ComponentCursorSigningKey() {
    }

    static byte[] loadOrCreate() {
        try {
            Path home = userHome();
            requireDirectory(home);
            Path stateRoot = secureDirectory(home.resolve(STATE_DIRECTORY));
            Path cursorState = secureDirectory(stateRoot.resolve(CURSOR_STATE_DIRECTORY));
            Path keyFile = cursorState.resolve(KEY_FILE);
            if (Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
                return readWinner(keyFile);
            }
            byte[] generated = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(generated);
            try {
                createKey(keyFile, generated);
                return generated;
            } catch (FileAlreadyExistsException raceLost) {
                return readWinner(keyFile);
            }
        } catch (ComponentsCursorException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
        }
    }

    private static Path userHome() {
        String value = System.getProperty("user.home");
        if (value == null || value.trim().isEmpty()) {
            throw unavailable();
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }

    private static void requireDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable();
        }
    }

    private static Path secureDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectory(directory);
            restrictPermissions(directory, DIRECTORY_PERMISSIONS);
            return directory;
        }
        try {
            createDirectory(directory, DIRECTORY_PERMISSIONS);
        } catch (FileAlreadyExistsException raceLost) {
            requireDirectory(directory);
        }
        restrictPermissions(directory, DIRECTORY_PERMISSIONS);
        return directory;
    }

    private static void createDirectory(Path directory, Set<PosixFilePermission> permissions)
            throws IOException {
        try {
            FileAttribute<Set<PosixFilePermission>> attribute =
                    PosixFilePermissions.asFileAttribute(permissions);
            Files.createDirectory(directory, attribute);
        } catch (UnsupportedOperationException exception) {
            Files.createDirectory(directory);
        }
    }

    private static void createKey(Path keyFile, byte[] key) throws IOException {
        OpenOption[] options = new OpenOption[] {
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        };
        try (FileChannel channel = FileChannel.open(keyFile, options)) {
            ByteBuffer buffer = ByteBuffer.wrap(key);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        restrictPermissions(keyFile, FILE_PERMISSIONS);
    }

    private static byte[] readWinner(Path keyFile) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 64; attempt++) {
            try {
                return readExactKey(keyFile);
            } catch (IOException exception) {
                lastFailure = exception;
                Thread.yield();
            }
        }
        throw lastFailure == null ? new IOException("signing key unavailable") : lastFailure;
    }

    private static byte[] readExactKey(Path keyFile) throws IOException {
        if (Files.isSymbolicLink(keyFile)) {
            throw new IOException("symbolic signing key rejected");
        }
        ByteBuffer buffer = ByteBuffer.allocate(KEY_BYTES + 1);
        try (FileChannel channel = FileChannel.open(
                keyFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
            }
        }
        if (buffer.position() != KEY_BYTES) {
            throw new IOException("invalid signing key length");
        }
        restrictPermissions(keyFile, FILE_PERMISSIONS);
        byte[] key = new byte[KEY_BYTES];
        buffer.flip();
        buffer.get(key);
        return key;
    }

    private static void restrictPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, permissions);
            }
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
        }
    }

    private static ComponentsCursorException unavailable() {
        return ComponentsCursorException.signingKeyUnavailable();
    }
}
