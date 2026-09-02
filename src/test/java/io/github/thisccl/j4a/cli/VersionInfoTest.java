package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.VersionInfo;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class VersionInfoTest {
    @TempDir
    Path tempDir;

    @Test
    void readsGeneratedVersionResource() throws Exception {
        assertThat(VersionInfo.version()).isEqualTo(packageVersion());
    }

    @Test
    void failsWhenGeneratedVersionResourceIsMissing() throws Exception {
        String classResource = "/io/github/thisccl/j4a/VersionInfo.class";
        Path classFile = tempDir.resolve("io/github/thisccl/j4a/VersionInfo.class");
        Files.createDirectories(classFile.getParent());
        InputStream input = VersionInfoTest.class.getResourceAsStream(classResource);
        assertThat(input).isNotNull();
        try {
            Files.copy(input, classFile);
        } finally {
            input.close();
        }

        URL[] classPath = {tempDir.toUri().toURL()};
        URLClassLoader loader = new URLClassLoader(classPath, null);
        try {
            Class<?> isolated = Class.forName("io.github.thisccl.j4a.VersionInfo", false, loader);
            assertThatThrownBy(() -> isolated.getMethod("version").invoke(null))
                    .isInstanceOf(ExceptionInInitializerError.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Missing generated version resource: META-INF/j4a/version.properties");
        } finally {
            loader.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static String packageVersion() throws Exception {
        Path packageJson = Paths.get(System.getProperty("user.dir"), "package.json");
        InputStream input = Files.newInputStream(packageJson);
        try {
            Map<String, Object> metadata = (Map<String, Object>) new Yaml().load(
                    new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
            return String.valueOf(metadata.get("version"));
        } finally {
            input.close();
        }
    }
}
