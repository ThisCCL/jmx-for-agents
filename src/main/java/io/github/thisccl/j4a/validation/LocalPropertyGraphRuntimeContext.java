package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import io.github.thisccl.j4a.jmx.property.RuntimeFingerprint;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.util.JMeterUtils;

final class LocalPropertyGraphRuntimeContext {
    private static Path prewarmedHome;
    private static RuntimeContext prewarmedContext;

    private LocalPropertyGraphRuntimeContext() {
    }

    static RuntimeContext selected(Path jmeterHome) throws IOException {
        Path home = jmeterHome.toRealPath();
        synchronized (LocalPropertyGraphRuntimeContext.class) {
            if (home.equals(prewarmedHome)) {
                return prewarmedContext;
            }
        }
        return create(home);
    }

    static synchronized RuntimeContext prewarm(Path jmeterHome) throws IOException {
        Path home = jmeterHome.toRealPath();
        if (prewarmedContext == null) {
            RuntimeContext context;
            try {
                context = create(home);
            } catch (IOException exception) {
                throw new InitializationException(home, exception);
            }
            prewarmedHome = home;
            prewarmedContext = context;
        } else if (!home.equals(prewarmedHome)) {
            throw new IllegalStateException(
                    "Local JMeter worker cannot switch fingerprint homes after preflight: " + prewarmedHome);
        }
        return prewarmedContext;
    }

    private static RuntimeContext create(Path home) throws IOException {
        String workerId = System.getProperty("j4a.worker.id", "standalone-local-worker");
        RuntimeFingerprint fingerprint = new RuntimeFingerprint(
                home.toString(), JMeterUtils.getJMeterVersion(), libraryHashes(home));
        return new RuntimeContext(workerId, fingerprint);
    }

    static RuntimeContext inProcess() {
        return new RuntimeContext(
                System.getProperty("j4a.worker.id", "in-process-test-worker"),
                new RuntimeFingerprint(
                        "in-process",
                        JMeterUtils.getJMeterVersion(),
                        Collections.<String, String>emptyMap()));
    }

    private static Map<String, String> libraryHashes(Path home) throws IOException {
        List<Path> libraries = new ArrayList<Path>();
        addJars(home.resolve("lib"), libraries);
        addJars(home.resolve("lib").resolve("ext"), libraries);
        Collections.sort(libraries);
        Map<String, String> hashes = new LinkedHashMap<String, String>();
        for (Path library : libraries) {
            hashes.put(home.relativize(library).toString().replace('\\', '/'), sha256(library));
        }
        return hashes;
    }

    private static void addJars(Path directory, List<Path> libraries) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path entry : entries) {
                libraries.add(entry.toRealPath());
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder value = new StringBuilder();
            for (byte item : digest.digest()) {
                value.append(Character.forDigit((item >>> 4) & 0x0f, 16));
                value.append(Character.forDigit(item & 0x0f, 16));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static final class InitializationException extends IOException {
        private static final long serialVersionUID = 1L;

        private InitializationException(Path home, IOException cause) {
            super("Unable to fingerprint local JMeter runtime once for " + home, cause);
        }
    }
}
