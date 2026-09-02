package io.github.thisccl.j4a;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class VersionInfo {
    private static final String RESOURCE_NAME = "META-INF/j4a/version.properties";
    private static final String VERSION = loadVersion();

    private VersionInfo() {
    }

    public static String version() {
        return VERSION;
    }

    private static String loadVersion() {
        ClassLoader loader = VersionInfo.class.getClassLoader();
        InputStream input = loader.getResourceAsStream(RESOURCE_NAME);
        if (input == null) {
            throw new IllegalStateException("Missing generated version resource: " + RESOURCE_NAME);
        }
        Properties properties = new Properties();
        try (InputStream resource = input) {
            properties.load(resource);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read generated version resource: " + RESOURCE_NAME, exception);
        }
        String version = properties.getProperty("version");
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalStateException("Missing version in generated resource: " + RESOURCE_NAME);
        }
        return version;
    }
}
