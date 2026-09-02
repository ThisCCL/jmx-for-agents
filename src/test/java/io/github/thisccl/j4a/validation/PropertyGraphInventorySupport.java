package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

final class PropertyGraphInventorySupport {
    static final String UNSEEN_PROPERTY_CLASS =
            "io.github.thisccl.j4a.synthetic.property.UnseenStringProperty";
    static final List<String> UNSEEN_PROPERTY_ADDRESS =
            Collections.singletonList("qa.unseen.property");
    private static final String JMETER_HOME_TOKEN = "<JMETER_HOME>";
    private static final String RUNTIME_FINGERPRINT_TOKEN = "<RUNTIME_FINGERPRINT>";
    private static final Duration WORKER_TIMEOUT = Duration.ofMinutes(10);

    private PropertyGraphInventorySupport() {
    }

    static Path selectedHome() throws IOException {
        return io.github.thisccl.j4a.TestJMeterRuntime.home().toRealPath();
    }

    static Path pluginJar() throws IOException {
        DefaultLocalProfileHomeFixtures.ensureCachedHomes();
        Path jar = DefaultLocalProfileHomeFixtures.cacheSourceHomeForTesting("local-home")
                .resolve("lib").resolve("ext")
                .resolve(DefaultLocalProfileHomeFixtures.UNSEEN_PROPERTY_PLUGIN_JAR);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("synthetic property plugin fixture missing: " + jar);
        }
        return jar.toRealPath();
    }

    static void generate(Path target) throws Exception {
        Path home = selectedHome();
        Path plugin = pluginJar();
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        List<String> command = new ArrayList<String>();
        command.add(LocalJMeterWorkerProcess.javaExecutable());
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(LocalJMeterClasspath.localFirstClasspath(home)
                + System.getProperty("path.separator") + plugin);
        command.add(PropertyGraphInventoryWorker.class.getName());
        command.add(home.toString());
        command.add(Paths.get("").toAbsolutePath().normalize().toString());
        command.add(plugin.toString());
        command.add(target.toAbsolutePath().normalize().toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(home.resolve("bin").toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread collector = new Thread(() -> copy(process.getInputStream(), output),
                "property-graph-inventory-output");
        collector.setDaemon(true);
        collector.start();
        boolean finished = process.waitFor(WORKER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(30, TimeUnit.SECONDS);
            throw new AssertionError("inventory worker timed out after " + WORKER_TIMEOUT);
        }
        collector.join(TimeUnit.SECONDS.toMillis(10));
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new AssertionError("inventory worker exit=" + process.exitValue() + " output=" + text);
        }
        if (!Files.isRegularFile(target) || Files.size(target) == 0L) {
            throw new AssertionError("inventory worker produced no artifact: " + target + " output=" + text);
        }
        Map<String, Object> inventory = read(target);
        Map<String, Object> runtime = mapping(inventory.get("runtime"));
        String expectedHome = home.toRealPath().toString();
        assertThat(runtime.get("canonical_home"))
                .as("INVENTORY_RUNTIME_HOME expected=" + expectedHome)
                .isEqualTo(expectedHome);
        runtime.put("canonical_home", JMETER_HOME_TOKEN);
        List<Map<String, Object>> fingerprintHolders = new ArrayList<Map<String, Object>>();
        Set<String> fingerprints = new HashSet<String>();
        collectRuntimeFingerprints(inventory, fingerprintHolders, fingerprints);
        assertThat(fingerprintHolders).as("INVENTORY_RUNTIME_FINGERPRINTS").isNotEmpty();
        assertThat(fingerprints).as("INVENTORY_RUNTIME_FINGERPRINT_VARIANTS").hasSize(1);
        int reusableDocuments = 0;
        int normalizedDocuments = 0;
        int normalizedFingerprints = 0;
        for (Object value : list(inventory.get("observed_rows"))) {
            Map<String, Object> row = mapping(value);
            if (!row.containsKey("reusable_document")) {
                continue;
            }
            reusableDocuments++;
            Map<String, Object> document = mapping(row.get("reusable_document"));
            Map<String, Object> proof = mapping(row.get("round_trip_proof"));
            String readDigest = sha256Value(proof.get("read_document_sha256"),
                    "INVENTORY_READ_DOCUMENT_SHA256");
            String actualDigest = sha256Value(proof.get("actual_document_sha256"),
                    "INVENTORY_ACTUAL_DOCUMENT_SHA256");
            boolean passed = Boolean.TRUE.equals(proof.get("projection_equal"))
                    && "passed".equals(proof.get("result"));
            if (passed) {
                assertThat(actualDigest).as("INVENTORY_PROJECTION_DIGEST").isEqualTo(readDigest);
            }
            String computedRaw = sha256(dump(new LinkedHashMap<String, Object>(document))
                    .getBytes(StandardCharsets.UTF_8));
            assertThat(readDigest).as("INVENTORY_READ_DOCUMENT_DIGEST").isEqualTo(computedRaw);
            if (passed) {
                assertThat(actualDigest).as("INVENTORY_ACTUAL_DOCUMENT_DIGEST").isEqualTo(computedRaw);
            }
            int changed = normalizeRuntimeFingerprints(document);
            normalizedFingerprints += changed;
            if (changed > 0) {
                normalizedDocuments++;
                assertThat(proof.get("write_applied")).isEqualTo(Boolean.TRUE);
                assertThat(proof.get("save_service_reloaded")).isEqualTo(Boolean.TRUE);
                assertThat(proof.get("projection_equal")).isEqualTo(Boolean.TRUE);
                assertThat(proof.get("result")).isEqualTo("passed");
                String normalizedDigest = sha256(dump(new LinkedHashMap<String, Object>(document))
                        .getBytes(StandardCharsets.UTF_8));
                proof.put("read_document_sha256", normalizedDigest);
                proof.put("actual_document_sha256", normalizedDigest);
            }
        }
        assertThat(normalizedFingerprints).as("INVENTORY_NORMALIZED_RUNTIME_FINGERPRINTS")
                .isEqualTo(fingerprintHolders.size());
        Files.write(target, dump(inventory).getBytes(StandardCharsets.UTF_8));
        System.out.println("INVENTORY_WORKER_EXIT 0");
        System.out.println("INVENTORY_RUNTIME_HOME " + expectedHome);
        System.out.println("INVENTORY_RUNTIME_FINGERPRINTS " + fingerprintHolders.size());
        System.out.println("INVENTORY_REUSABLE_DOCUMENTS " + reusableDocuments);
        System.out.println("INVENTORY_NORMALIZED_DOCUMENTS " + normalizedDocuments);
        System.out.println(text.trim());
    }

    static Comparison generateAndValidate() throws Exception {
        Path candidate = Files.createTempFile("jmeter-5.6.3-inventory-validate-", ".yml");
        try {
            generate(candidate);
            Map<String, Object> actual = read(candidate);
            validate(actual);
            Map<String, Object> summary = mapping(actual.get("summary"));
            return new Comparison(
                    integer(summary.get("registry_entries")),
                    integer(summary.get("materialized_entries")),
                    integer(summary.get("materialization_failed")),
                    integer(summary.get("observed_rows")),
                    integer(summary.get("persisted_user_total")),
                    integer(summary.get("persisted_user_with_proof")),
                    integer(summary.get("system_owned")),
                    integer(summary.get("transient_runtime_only")),
                    integer(summary.get("unclassified")));
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    static Map<String, Object> read(Path path) throws IOException {
        Object loaded = new Yaml().load(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        return mapping(loaded);
    }

    private static void collectRuntimeFingerprints(
            Object value, List<Map<String, Object>> holders, Set<String> fingerprints) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> mapping = mapping(value);
            if (mapping.containsKey("runtime_fingerprint")) {
                Object fingerprint = mapping.get("runtime_fingerprint");
                assertThat(fingerprint).as("INVENTORY_RUNTIME_FINGERPRINT_FORMAT")
                        .isInstanceOf(String.class);
                assertThat((String) fingerprint).as("INVENTORY_RUNTIME_FINGERPRINT_FORMAT")
                        .matches("sha256:[a-f0-9]{64}");
                holders.add(mapping);
                fingerprints.add((String) fingerprint);
            }
            for (Map.Entry<String, Object> entry : mapping.entrySet()) {
                if (!"runtime_fingerprint".equals(entry.getKey())) {
                    collectRuntimeFingerprints(entry.getValue(), holders, fingerprints);
                }
            }
        } else if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                collectRuntimeFingerprints(item, holders, fingerprints);
            }
        }
    }

    private static int normalizeRuntimeFingerprints(Object value) {
        int count = 0;
        if (value instanceof Map<?, ?>) {
            Map<String, Object> mapping = mapping(value);
            for (Map.Entry<String, Object> entry : mapping.entrySet()) {
                if ("runtime_fingerprint".equals(entry.getKey())) {
                    entry.setValue(RUNTIME_FINGERPRINT_TOKEN);
                    count++;
                } else {
                    count += normalizeRuntimeFingerprints(entry.getValue());
                }
            }
        } else if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                count += normalizeRuntimeFingerprints(item);
            }
        }
        return count;
    }

    private static String sha256Value(Object value, String label) {
        assertThat(value).as(label).isInstanceOf(String.class);
        assertThat((String) value).as(label).matches("[a-f0-9]{64}");
        return (String) value;
    }

    static void validate(Map<String, Object> inventory) {
        assertThat(inventory.get("schema_version")).as("BASELINE_SCHEMA_MISSING").isEqualTo(1);
        Map<String, Object> summary = mapping(inventory.get("summary"));
        assertThat(integer(summary.get("unclassified")))
                .as("UNCLASSIFIED_ROW expected=0 actual=" + summary.get("unclassified"))
                .isZero();
        assertThat(integer(summary.get("persisted_user_with_proof")))
                .as("PERSISTED_CAPABILITY_GAP expected=" + summary.get("persisted_user_total")
                        + " actual=" + summary.get("persisted_user_with_proof"))
                .isEqualTo(integer(summary.get("persisted_user_total")));
        assertThat(integer(summary.get("system_owned"))).as("SYSTEM_IDENTITY_MISSING").isGreaterThan(0);
        assertThat(summary.get("system_identity_retained")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("system_identity_non_writable")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("ordinary_read_omits_system_identity")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("unsupported_submission_guidance")).isEqualTo(Boolean.FALSE);

        boolean unseenSubclass = false;
        for (Object value : list(inventory.get("property_subclasses"))) {
            Map<String, Object> row = mapping(value);
            if (UNSEEN_PROPERTY_CLASS.equals(row.get("class"))) {
                unseenSubclass = true;
                assertThat(row.get("classification")).isEqualTo("structured_scalar");
            }
        }
        assertThat(unseenSubclass).as("UNSEEN_PLUGIN_CLASS_MISSING").isTrue();

        boolean unseenRow = false;
        for (Object value : list(inventory.get("observed_rows"))) {
            Map<String, Object> row = mapping(value);
            if (UNSEEN_PROPERTY_ADDRESS.equals(row.get("property_path"))) {
                unseenRow = true;
                assertThat(row.get("property_class")).isEqualTo(UNSEEN_PROPERTY_CLASS);
                assertThat(row.get("classification")).isIn(
                        "structured_scalar", "semantic", "opaque");
                assertThat(mapping(row.get("round_trip_proof")).get("result")).isEqualTo("passed");
            }
            if (Boolean.TRUE.equals(row.get("persisted")) && "user".equals(row.get("ownership"))) {
                assertThat(row.get("writable")).as("NON_USER_WRITABLE " + row.get("row_id")).isEqualTo(Boolean.TRUE);
                assertThat(row).containsKey("reusable_document");
                assertThat(mapping(row.get("round_trip_proof")).get("result"))
                        .as("CAPABILITY_GAP " + row.get("row_id")).isEqualTo("passed");
            }
            if ("system_owned".equals(row.get("classification"))) {
                assertThat(row.get("writable")).isEqualTo(Boolean.FALSE);
                assertThat(row.get("denominator")).isEqualTo(Boolean.FALSE);
                assertThat(row.get("reason")).isNotNull();
            }
            if ("transient_runtime_only".equals(row.get("classification"))) {
                assertThat(row.get("writable")).isEqualTo(Boolean.FALSE);
                assertThat(row.get("denominator")).isEqualTo(Boolean.FALSE);
                assertThat(row.get("reason")).isNotNull();
            }
        }
        assertThat(unseenRow).as("UNSEEN_PLUGIN_UNCLASSIFIED").isTrue();
    }

    static String dump(Map<String, Object> inventory) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(false);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setWidth(160);
        return new Yaml(options).dump(inventory);
    }

    static String sha256(Path path) throws IOException {
        return sha256(Files.readAllBytes(path));
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) {
                value.append(String.format("%02x", item & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static String canonicalJarHash(Path jar) throws IOException {
        List<String> names = new ArrayList<String>();
        try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (!entry.isDirectory()) names.add(entry.getName());
            }
            Collections.sort(names);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (String name : names) {
                bytes.write(name.getBytes(StandardCharsets.UTF_8));
                bytes.write(0);
                try (InputStream input = file.getInputStream(file.getJarEntry(name))) {
                    copy(input, bytes);
                }
                bytes.write(0);
            }
            return sha256(bytes.toByteArray());
        }
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) {
        byte[] buffer = new byte[8192];
        int count;
        try {
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to capture inventory worker output", exception);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapping(Object value) {
        if (!(value instanceof Map)) throw new AssertionError("expected mapping, actual=" + value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        if (!(value instanceof List)) throw new AssertionError("expected list, actual=" + value);
        return (List<Object>) value;
    }

    static int integer(Object value) {
        if (!(value instanceof Number)) throw new AssertionError("expected number, actual=" + value);
        return ((Number) value).intValue();
    }

    static final class Comparison {
        final int registryEntries;
        final int materializedEntries;
        final int materializationFailed;
        final int observedRows;
        final int persistedUserTotal;
        final int persistedUserWithProof;
        final int systemOwned;
        final int transientRuntimeOnly;
        final int unclassified;

        Comparison(int registryEntries, int materializedEntries,
                int materializationFailed, int observedRows, int persistedUserTotal,
                int persistedUserWithProof, int systemOwned, int transientRuntimeOnly,
                int unclassified) {
            this.registryEntries = registryEntries;
            this.materializedEntries = materializedEntries;
            this.materializationFailed = materializationFailed;
            this.observedRows = observedRows;
            this.persistedUserTotal = persistedUserTotal;
            this.persistedUserWithProof = persistedUserWithProof;
            this.systemOwned = systemOwned;
            this.transientRuntimeOnly = transientRuntimeOnly;
            this.unclassified = unclassified;
        }
    }

}
