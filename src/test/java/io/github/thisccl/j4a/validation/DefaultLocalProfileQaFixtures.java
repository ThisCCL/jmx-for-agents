package io.github.thisccl.j4a.validation;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public final class DefaultLocalProfileQaFixtures {
    private static final Path BASE_ROOT = Paths.get("build", "qa", "default-local-profile");
    private static final AtomicLong NEXT_ROOT_ID = new AtomicLong();
    public static final String EXT_PLUGIN_CLASS = "io.github.thisccl.j4a.synthetic.ExtOnlySampler";
    public static final String UNREPRESENTABLE_ADDRESS_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.property.UnrepresentableAddressSampler";
    public static final String SEARCH_PLUGIN_CLASS = "io.github.thisccl.j4a.synthetic.SearchPathOnlySampler";
    public static final String THREAD_GROUP_PLUGIN_CLASS = "io.github.thisccl.j4a.synthetic.SyntheticThreadGroup";
    public static final String GENERIC_CONTROLLER_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.SyntheticGenericController";
    public static final String TEST_FRAGMENT_PLUGIN_CLASS = "io.github.thisccl.j4a.synthetic.SyntheticTestFragment";
    public static final String SLOW_PLUGIN_CLASS = "io.github.thisccl.j4a.synthetic.SlowSampler";
    public static final String DISCOVERED_ONLY_PLUGIN_CLASS = "io.github.thisccl.j4a.synthetic.DiscoveredOnly";
    public static final String REQUEST_DEFAULTS_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.KpRequestDefaults";
    public static final String ABSTRACT_SAMPLER_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.AbstractKpSampler";
    public static final String DUPLICATE_PLUGIN_ALPHA_CLASS =
            "io.github.thisccl.j4a.synthetic.alpha.DuplicateSampler";
    public static final String DUPLICATE_PLUGIN_BETA_CLASS =
            "io.github.thisccl.j4a.synthetic.beta.DuplicateSampler";
    public static final String MALFORMED_SCHEMA_PLUGIN_CLASS =
            "io.github.thisccl.j4a.synthetic.MalformedSchemaSampler";
    public static final String KP_ARGUMENT_CLASS =
            "io.github.thisccl.j4a.synthetic.jmeter.protocol.kingdom.config.KpArgument";

    private final boolean cacheHomes;
    private final Path root;
    private boolean ensured;

    public DefaultLocalProfileQaFixtures() {
        this(true);
    }

    private DefaultLocalProfileQaFixtures(boolean cacheHomes) {
        this.cacheHomes = cacheHomes;
        this.root = BASE_ROOT.resolve(cacheHomes ? "work" : "fresh")
                .resolve(processId() + "-" + NEXT_ROOT_ID.incrementAndGet());
    }

    public static DefaultLocalProfileQaFixtures cached() {
        return new DefaultLocalProfileQaFixtures(true);
    }

    public static DefaultLocalProfileQaFixtures fresh() {
        return new DefaultLocalProfileQaFixtures(false);
    }

    public Path root() {
        return root;
    }

    public Path localHome() throws IOException {
        ensure();
        return homePath("local-home");
    }

    public Path localHomeWithoutPlugin() throws IOException {
        ensure();
        return homePath("local-home-without-plugin");
    }

    public Path pluginBackedJmx() throws IOException {
        ensure();
        return root.resolve("plugin-backed.jmx").toAbsolutePath().normalize();
    }

    public Path unrepresentableAddressJmx() throws IOException {
        ensure();
        return root.resolve("unrepresentable-address.jmx").toAbsolutePath().normalize();
    }

    public Path searchPathBackedJmx() throws IOException {
        ensure();
        return root.resolve("search-path-backed.jmx").toAbsolutePath().normalize();
    }

    public Path timeoutBackedJmx() throws IOException {
        ensure();
        return root.resolve("timeout-backed.jmx").toAbsolutePath().normalize();
    }

    public Path httpArgumentBackedJmx() throws IOException {
        ensure();
        return root.resolve("http-argument-backed.jmx").toAbsolutePath().normalize();
    }

    public Path kpArgumentBackedJmx() throws IOException {
        ensure();
        return root.resolve("kp-argument-backed.jmx").toAbsolutePath().normalize();
    }

    public Path markerOnlyCoreHome() throws IOException {
        ensure();
        Path home = root.resolve("marker-only-core-home");
        DefaultLocalProfileHomeFixtures.createMarkerOnlyCoreHome(home);
        return home.toAbsolutePath().normalize();
    }

    public String slowStartingJavaExecutable(Path childPidFile) throws IOException {
        ensure();
        Path script = root.resolve(isWindows() ? "slow-java.cmd" : "slow-java.sh");
        Files.deleteIfExists(childPidFile);
        if (isWindows()) {
            String pidPath = childPidFile.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
            Files.write(script, ("@echo off\r\n"
                    + "powershell -NoProfile -ExecutionPolicy Bypass -Command "
                    + "\"Start-Process powershell -ArgumentList '-NoProfile','-Command','$PID | Set-Content -LiteralPath ''"
                    + pidPath + "''; Start-Sleep -Seconds 30' -WindowStyle Hidden\"\r\n"
                    + "ping -n 6 127.0.0.1 >NUL\r\n").getBytes(StandardCharsets.UTF_8));
        } else {
            String pidPath = childPidFile.toAbsolutePath().normalize().toString().replace("'", "'\\''");
            Files.write(script, ("#!/bin/sh\n"
                    + "sh -c 'echo $$ > '" + pidPath + "'; sleep 30' &\n"
                    + "sleep 5\n").getBytes(StandardCharsets.UTF_8));
            script.toFile().setExecutable(true);
        }
        return script.toAbsolutePath().normalize().toString();
    }


    public void ensure() throws IOException {
        if (ensured) {
            return;
        }
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("out"));
        if (cacheHomes) {
            DefaultLocalProfileHomeFixtures.ensureCachedHomes();
            DefaultLocalProfileHomeFixtures.copyCachedHomesTo(root);
        } else {
            DefaultLocalProfileHomeFixtures.createHome(root.resolve("local-home"), true);
            DefaultLocalProfileHomeFixtures.createHome(root.resolve("local-home-without-plugin"), false);
        }
        copyFixture("simple-http.jmx", root.resolve("basic.jmx"));
        copyFixture("simple-http.jmx", root.resolve("unicode").resolve("压测脚本").resolve("basic.jmx"));
        Path unicodeSource = cacheHomes ? root.resolve("local-home") : null;
        DefaultLocalProfileHomeFixtures.createUnicodeHome(
                root.resolve("unicode").resolve("压测工具").resolve("apache-jmeter"), unicodeSource);
        writePatch(root.resolve("add-local-component.yml"),
                "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        writePatch(root.resolve("add-ext-local-component.yml"), "ext.only.sampler");
        writePatch(root.resolve("add-nonstandard-gui.yml"), "nonstandard.gui.sampler");
        writePatch(root.resolve("add-no-menu-testbean.yml"), "no.menu.test.bean");
        writePatch(root.resolve("add-discovered-only.yml"), "discovered.only");
        writePatch(root.resolve("add-unsupported-local.yml"), "slow.sampler");
        writeSetMoveDeletePatch(root.resolve("set-move-delete.yml"));
        writePluginJmx(root.resolve("plugin-backed.jmx"), EXT_PLUGIN_CLASS, "Ext Plugin Sampler");
        writePluginJmx(root.resolve("unrepresentable-address.jmx"),
                UNREPRESENTABLE_ADDRESS_PLUGIN_CLASS, "Unrepresentable Address Sampler");
        writePluginJmx(root.resolve("search-path-backed.jmx"), SEARCH_PLUGIN_CLASS, "Search Path Plugin Sampler");
        writePluginJmx(root.resolve("timeout-backed.jmx"), SLOW_PLUGIN_CLASS, "Slow Plugin Sampler");
        writeHttpArgumentJmx(root.resolve("http-argument-backed.jmx"));
        writeKpArgumentJmx(root.resolve("kp-argument-backed.jmx"));
        ensured = true;
    }

    public void delete() throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            java.util.Iterator<Path> iterator = paths.sorted(java.util.Comparator.reverseOrder()).iterator();
            while (iterator.hasNext()) {
                Files.deleteIfExists(iterator.next());
            }
        }
        ensured = false;
    }

    private Path homePath(String name) {
        return root.resolve(name).toAbsolutePath().normalize();
    }

    public Path nonstandardGuiPatch() throws IOException {
        ensure();
        return root.resolve("add-nonstandard-gui.yml").toAbsolutePath().normalize();
    }

    public Path noMenuTestBeanPatch() throws IOException {
        ensure();
        return root.resolve("add-no-menu-testbean.yml").toAbsolutePath().normalize();
    }

    private static void copyFixture(String fixture, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            URI uri = DefaultLocalProfileQaFixtures.class.getResource("/fixtures/" + fixture).toURI();
            Files.copy(Paths.get(uri), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to copy fixture: " + fixture, exception);
        }
    }

    private static void writePatch(Path target, String component) throws IOException {
        Files.write(target, ("changes:\n  - add:\n      parent: jmx_19871e6efa95\n"
                + "      position: last\n      component: " + component + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeSetMoveDeletePatch(Path target) throws IOException {
        Files.write(target, ("changes:\n  - set:\n      ref: jmx_330976848c8e\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      properties:\n"
                + "        - property: HTTPSampler\\.domain\n          value: changed.example\n"
                + "          type: string\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writePluginJmx(Path target, String className, String name) throws IOException {
        Files.write(target, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jmeterTestPlan version=\"1.2\" properties=\"5.0\" jmeter=\"5.6.3\">\n"
                + "  <hashTree>\n"
                + "    <TestPlan guiclass=\"TestPlanGui\" testclass=\"TestPlan\" testname=\"Synthetic Plugin Plan\" enabled=\"true\"/>\n"
                + "    <hashTree>\n"
                + "      <" + className + " guiclass=\"" + className + "Gui\" testclass=\"" + className
                + "\" testname=\"" + name + "\" enabled=\"true\"/>\n"
                + "      <hashTree/>\n"
                + "    </hashTree>\n"
                + "  </hashTree>\n"
                + "</jmeterTestPlan>\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeHttpArgumentJmx(Path target) throws IOException {
        Files.write(target, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jmeterTestPlan version=\"1.2\" properties=\"5.0\" jmeter=\"5.6.3\">\n"
                + "  <hashTree>\n"
                + "    <TestPlan guiclass=\"TestPlanGui\" testclass=\"TestPlan\" testname=\"HTTPArgument Plan\" enabled=\"true\"/>\n"
                + "    <hashTree>\n"
                + "      <ThreadGroup guiclass=\"ThreadGroupGui\" testclass=\"ThreadGroup\" testname=\"Synthetic Thread Group\" enabled=\"true\"/>\n"
                + "      <hashTree>\n"
                + "        <HTTPSamplerProxy guiclass=\"HttpTestSampleGui\" testclass=\"HTTPSamplerProxy\" testname=\"HTTPArgument Request\" enabled=\"true\">\n"
                + "          <elementProp name=\"HTTPsampler.Arguments\" elementType=\"Arguments\" guiclass=\"HTTPArgumentsPanel\" testclass=\"Arguments\" testname=\"User Defined Variables\" enabled=\"true\">\n"
                + "            <collectionProp name=\"Arguments.arguments\">\n"
                + "              <elementProp name=\"q\" elementType=\"org.apache.jmeter.protocol.http.util.HTTPArgument\">\n"
                + "                <boolProp name=\"HTTPArgument.always_encode\">true</boolProp>\n"
                + "                <boolProp name=\"HTTPArgument.use_equals\">true</boolProp>\n"
                + "                <stringProp name=\"HTTPArgument.content_type\">text/plain</stringProp>\n"
                + "                <stringProp name=\"Argument.name\">q</stringProp>\n"
                + "                <stringProp name=\"Argument.value\">abc</stringProp>\n"
                + "                <stringProp name=\"Argument.metadata\">=</stringProp>\n"
                + "              </elementProp>\n"
                + "            </collectionProp>\n"
                + "          </elementProp>\n"
                + "        </HTTPSamplerProxy>\n"
                + "        <hashTree/>\n"
                + "      </hashTree>\n"
                + "    </hashTree>\n"
                + "  </hashTree>\n"
                + "</jmeterTestPlan>\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeKpArgumentJmx(Path target) throws IOException {
        Files.write(target, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jmeterTestPlan version=\"1.2\" properties=\"5.0\" jmeter=\"5.6.3\">\n"
                + "  <hashTree>\n"
                + "    <TestPlan guiclass=\"TestPlanGui\" testclass=\"TestPlan\" testname=\"KpArgument Plan\" enabled=\"true\"/>\n"
                + "    <hashTree>\n"
                + "      <" + EXT_PLUGIN_CLASS + " guiclass=\"" + EXT_PLUGIN_CLASS + "Gui\" testclass=\""
                + EXT_PLUGIN_CLASS + "\" testname=\"KpArgument Sampler\" enabled=\"true\">\n"
                + "        <elementProp name=\"kingdomSampler.arguments\" elementType=\"Arguments\">\n"
                + "          <collectionProp name=\"Arguments.arguments\">\n"
                + "            <elementProp name=\"branch_no\" elementType=\"" + KP_ARGUMENT_CLASS + "\">\n"
                + "              <boolProp name=\"kingdomArgument.encrypted\">true</boolProp>\n"
                + "              <stringProp name=\"Argument.name\">branch_no</stringProp>\n"
                + "              <stringProp name=\"Argument.value\">1001</stringProp>\n"
                + "              <stringProp name=\"Argument.metadata\">=</stringProp>\n"
                + "            </elementProp>\n"
                + "          </collectionProp>\n"
                + "        </elementProp>\n"
                + "      </" + EXT_PLUGIN_CLASS + ">\n"
                + "      <hashTree/>\n"
                + "    </hashTree>\n"
                + "  </hashTree>\n"
                + "</jmeterTestPlan>\n").getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String processId() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int separator = runtimeName.indexOf('@');
        String id = separator >= 0 ? runtimeName.substring(0, separator) : runtimeName;
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
