package io.github.thisccl.j4a.jmx.property;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class RuntimeFingerprint {
    private final String jmeterHome;
    private final String jmeterVersion;
    private final Map<String, String> librarySha256;

    public RuntimeFingerprint(
            String jmeterHome, String jmeterVersion, Map<String, String> librarySha256) {
        this.jmeterHome = requireText(jmeterHome, "JMeter home");
        this.jmeterVersion = requireText(jmeterVersion, "JMeter version");
        Objects.requireNonNull(librarySha256, "library hashes are required");
        LinkedHashMap<String, String> hashes = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : librarySha256.entrySet()) {
            hashes.put(
                    requireText(entry.getKey(), "library path"),
                    requireText(entry.getValue(), "library SHA-256"));
        }
        this.librarySha256 = Collections.unmodifiableMap(hashes);
    }

    public String jmeterHome() {
        return jmeterHome;
    }

    public String jmeterVersion() {
        return jmeterVersion;
    }

    public Map<String, String> librarySha256() {
        return librarySha256;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RuntimeFingerprint)) {
            return false;
        }
        RuntimeFingerprint that = (RuntimeFingerprint) other;
        return jmeterHome.equals(that.jmeterHome)
                && jmeterVersion.equals(that.jmeterVersion)
                && librarySha256.equals(that.librarySha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jmeterHome, jmeterVersion, librarySha256);
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
