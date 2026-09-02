package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LocalApplyRuntimeAuthorityTest {
    private final DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();

    @Test
    void resolvedAliasChainCommitsExactNestedObjectsInDeclaredOrder() throws Exception {
        fixtures.ensure();
        Path input = fixtures.root().resolve("basic.jmx");
        Path output = fixtures.root().resolve("out/resolved-alias-chain.jmx");
        String patch = "changes:\n"
                + "  - add:\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.control.gui.LoopControlPanel\n"
                + "      as: controller\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          value: Alias Controller\n"
                + "          type: string\n"
                + "  - add:\n"
                + "      parent: $controller\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      as: child\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          value: Alias Child\n"
                + "          type: string\n"
                + "  - set:\n"
                + "      ref: $child\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          value: Alias Child Updated\n"
                + "          type: string\n"
                + "  - move:\n"
                + "      ref: $controller\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: first\n"
                + "  - delete:\n"
                + "      ref: jmx_330976848c8e\n";

        LocalJMeterWorkerResponse response = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.applyPatchYaml(
                        input, fixtures.localHome(), patch, output, true)).response();

        assertThat(response.success()).as(response.toJsonLine()).isTrue();
        String xml = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertThat(xml).contains("testname=\"Alias Controller\"", "testname=\"Alias Child Updated\"");
        assertThat(xml).doesNotContain("testname=\"Synthetic HTTP Request\"");
        assertThat(xml.indexOf("testname=\"Alias Controller\""))
                .isLessThan(xml.indexOf("testname=\"Alias Child Updated\""));
    }

    @Test
    void lateAliasPropertyFailureRepeatedlyLeavesNoTargetOrDurableAlias() throws Exception {
        fixtures.ensure();
        Path input = fixtures.root().resolve("basic.jmx");
        Path output = fixtures.root().resolve("out/failed-resolved-alias.jmx");
        Files.deleteIfExists(output);
        byte[] inputBefore = Files.readAllBytes(input);
        String failingPatch = "changes:\n"
                + "  - add:\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.control.gui.LoopControlPanel\n"
                + "      as: controller\n"
                + "  - set:\n"
                + "      ref: $controller\n"
                + "      component: org.apache.jmeter.control.gui.LoopControlPanel\n"
                + "      properties:\n"
                + "        - property: LoopController\\.loops\n"
                + "          value: not-an-integer\n"
                + "          type: int\n";

        for (int attempt = 0; attempt < 2; attempt++) {
            LocalJMeterWorkerResponse failure = new LocalJMeterWorkerClient().execute(
                    LocalJMeterWorkerRequest.applyPatchYaml(
                            input, fixtures.localHome(), failingPatch, output, true)).response();

            assertThat(failure.success()).as(failure.toJsonLine()).isFalse();
            assertThat(Files.readAllBytes(input)).containsExactly(inputBefore);
            assertThat(output).doesNotExist();
        }

        String leakedAliasPatch = "changes:\n"
                + "  - set:\n"
                + "      ref: $controller\n"
                + "      properties:\n"
                + "        - property: TestElement\\.name\n"
                + "          value: must-not-apply\n"
                + "          type: string\n";
        LocalJMeterWorkerResponse unavailable = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.applyPatchYaml(
                        input, fixtures.localHome(), leakedAliasPatch, output, true)).response();

        assertThat(unavailable.success()).as(unavailable.toJsonLine()).isFalse();
        assertThat(Files.readAllBytes(input)).containsExactly(inputBefore);
        assertThat(output).doesNotExist();
    }

    @Test
    void rejectsCanAddToFalseWithoutMutatingTree() throws Exception {
        fixtures.ensure();
        Path patch = fixtures.root().resolve("placement-rejected.yml");
        Path output = fixtures.root().resolve("out/placement-rejected.jmx");
        Files.deleteIfExists(output);
        Files.write(patch, ("changes:\n  - add:\n      parent: jmx_19871e6efa95\n"
                + "      position: last\n      component: org.apache.jmeter.threads.gui.ThreadGroupGui\n")
                .getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult result = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.applyPatch(fixtures.root().resolve("basic.jmx"),
                        fixtures.localHome(), patch, output));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().errorCode()).isEqualTo("JMETER_PLACEMENT_REJECTED");
        assertThat(output).doesNotExist();
    }

    @Test
    void rejectsUnregisteredRawElementClassAsMissingIdentityWithoutMutatingFiles() throws Exception {
        fixtures.ensure();
        Path patch = fixtures.root().resolve("identity-not-found.yml");
        Path input = fixtures.root().resolve("basic.jmx");
        Path output = fixtures.root().resolve("out/identity-not-found.jmx");
        byte[] inputBefore = Files.readAllBytes(input);
        byte[] outputBefore = "unchanged-target".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(output.getParent());
        Files.write(output, outputBefore);
        Files.write(patch, ("changes:\n  - add:\n      parent: jmx_19871e6efa95\n"
                + "      position: last\n      component: org.apache.jmeter.threads.ThreadGroup\n")
                .getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult result = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.applyPatch(input, fixtures.localHome(), patch, output));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().errorCode()).isEqualTo("COMPONENT_IDENTITY_NOT_FOUND");
        assertThat(Files.readAllBytes(input)).isEqualTo(inputBefore);
        assertThat(Files.readAllBytes(output)).isEqualTo(outputBefore);
    }

    @Test
    void ineligibleFallbackPreservesOriginalCause() throws Exception {
        LocalJMeterMenuRegistry registry = LocalJMeterMenuRegistry.reflect(ExplodingMenuFactory.class);
        LocalJMeterMenuRegistry.Entry entry = registry.resolve(ExplodingGui.class.getName()).get();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LocalJMeterElementMaterializer.create(entry))
                .isInstanceOf(LocalJMeterElementMaterializer.MaterializationException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("primary explosion");
    }

    @Test
    void primaryGuiRepairsMarkerAndBlankNamesButPreservesReadableDefaults() throws Exception {
        LocalJMeterMenuRegistry registry = LocalJMeterMenuRegistry.reflect(PrimaryNamesMenuFactory.class);

        org.apache.jmeter.testelement.TestElement marker = LocalJMeterElementMaterializer.create(
                registry.resolve(MarkerNameGui.class.getName()).get());
        org.apache.jmeter.testelement.TestElement blank = LocalJMeterElementMaterializer.create(
                registry.resolve(BlankNameGui.class.getName()).get());
        org.apache.jmeter.testelement.TestElement readable = LocalJMeterElementMaterializer.create(
                registry.resolve(ReadableNameGui.class.getName()).get());

        assertThat(marker.getName()).isEqualTo("Resolved Marker");
        assertThat(blank.getName()).isEqualTo("Resolved Blank");
        assertThat(readable.getName()).isEqualTo("JMeter Default");
        assertThat(marker.getClass()).isEqualTo(MaterializedNameElement.class);
        assertThat(marker.getPropertyAsString(org.apache.jmeter.testelement.TestElement.TEST_CLASS))
                .isEqualTo(MaterializedNameElement.class.getName());
        assertThat(marker.getPropertyAsString(org.apache.jmeter.testelement.TestElement.GUI_CLASS))
                .isEqualTo(MarkerNameGui.class.getName());
    }

    @Test
    void testBeanDirectFallbackRepairsMarkerAndBlankNamesButPreservesReadableDefaults() throws Exception {
        LocalJMeterMenuRegistry registry = LocalJMeterMenuRegistry.reflect(FallbackNamesMenuFactory.class);

        org.apache.jmeter.testelement.TestElement marker = LocalJMeterElementMaterializer.create(
                registry.resolve(FallbackMarkerSampler.class.getName()).get());
        org.apache.jmeter.testelement.TestElement blank = LocalJMeterElementMaterializer.create(
                registry.resolve(FallbackBlankSampler.class.getName()).get());
        org.apache.jmeter.testelement.TestElement readable = LocalJMeterElementMaterializer.create(
                registry.resolve(FallbackReadableSampler.class.getName()).get());

        assertThat(marker.getName()).isEqualTo("Resolved Fallback Marker");
        assertThat(blank.getName()).isEqualTo("Resolved Fallback Blank");
        assertThat(readable.getName()).isEqualTo("Fallback Default");
        assertThat(marker.getClass()).isEqualTo(FallbackMarkerSampler.class);
        assertThat(marker.getPropertyAsString(org.apache.jmeter.testelement.TestElement.TEST_CLASS))
                .isEqualTo(FallbackMarkerSampler.class.getName());
        assertThat(marker.getPropertyAsString(org.apache.jmeter.testelement.TestElement.GUI_CLASS))
                .isEqualTo(org.apache.jmeter.testbeans.gui.TestBeanGUI.class.getName());
    }

    @Test
    void metadataTestBeanPrimaryPathPreservesJMeterDefaults(
            @org.junit.jupiter.api.io.TempDir Path tempDirectory) throws Exception {
        fixtures.ensure();
        Files.write(fixtures.localHome().resolve("bin/saveservice.properties"),
                "CSVDataSet=org.apache.jmeter.config.CSVDataSet\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        Path xml = tempDirectory.resolve("metadata-non-testbean.jmx");
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(LocalJMeterWorkerTestSupport.currentJavaExecutable());
        if (!System.getProperty("java.specification.version").startsWith("1.")) {
            command.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        }
        command.add("-Dsaveservice_properties=" + fixtures.localHome().resolve("bin/saveservice.properties"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(MetadataCanonicalSerializationProbe.class.getName());
        command.add(fixtures.localHome().toString());
        command.add(xml.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String facts;
        try (java.io.InputStream input = process.getInputStream()) {
            facts = new String(readAll(input), StandardCharsets.UTF_8);
        }
        assertThat(process.waitFor()).as(facts).isZero();
        assertThat(facts).contains(
                "testBean=true",
                "class=org.apache.jmeter.config.CSVDataSet",
                "testClass=org.apache.jmeter.config.CSVDataSet",
                "guiClass=org.apache.jmeter.testbeans.gui.TestBeanGUI",
                "name=CSV Data Set Config", "enabled=true", "filenameInitiallyAbsent=false",
                "delimiter=,", "recycle=true", "stopThread=false", "shareMode=shareMode.all");
        assertThat(Files.size(xml)).isGreaterThan(0L);
        String xmlText = new String(Files.readAllBytes(xml), StandardCharsets.UTF_8);
        assertThat(xmlText)
                .contains("<CSVDataSet guiclass=\"TestBeanGUI\""
                                + " testclass=\"CSVDataSet\" testname=\"CSV Data Set Config\">",
                        "<stringProp name=\"filename\">data.csv</stringProp>",
                        "<stringProp name=\"delimiter\">,</stringProp>",
                        "<boolProp name=\"recycle\">true</boolProp>",
                        "<boolProp name=\"stopThread\">false</boolProp>",
                        "<stringProp name=\"shareMode\">shareMode.all</stringProp>",
                        "</CSVDataSet>")
                .doesNotContain("<java.util.Map_-Entry>");
    }

    @Test
    void metadataBackedJsr223PrimaryPathPersistsOpenableDefaults(
            @org.junit.jupiter.api.io.TempDir Path tempDirectory) throws Exception {
        fixtures.ensure();
        Path patch = tempDirectory.resolve("add-jsr223-processors.yml");
        Path output = tempDirectory.resolve("jsr223-processors.jmx");
        Files.write(patch, ("changes:\n"
                + "  - add:\n      parent: jmx_19871e6efa95\n      position: last\n"
                + "      component: org.apache.jmeter.modifiers.JSR223PreProcessor\n"
                + "  - add:\n      parent: jmx_19871e6efa95\n      position: last\n"
                + "      component: org.apache.jmeter.extractor.JSR223PostProcessor\n")
                .getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult result = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.applyPatch(fixtures.root().resolve("basic.jmx"),
                        fixtures.localHome(), patch, output));

        assertThat(result.response().success()).isTrue();
        String xml = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertThat(xml)
                .contains("<JSR223PreProcessor guiclass=\"TestBeanGUI\"",
                        "<JSR223PostProcessor guiclass=\"TestBeanGUI\"")
                .doesNotContain("guiclass=\"org.apache.jmeter.gui.menu.StaticJMeterGUIComponent\"");
        for (String component : java.util.Arrays.asList("JSR223PreProcessor", "JSR223PostProcessor")) {
            int start = xml.indexOf("<" + component);
            int end = xml.indexOf("</" + component + ">", start);
            assertThat(xml.substring(start, end)).contains(
                    "<stringProp name=\"cacheKey\">true</stringProp>",
                    "<stringProp name=\"filename\"></stringProp>",
                    "<stringProp name=\"parameters\"></stringProp>",
                    "<stringProp name=\"script\"></stringProp>",
                    "<stringProp name=\"scriptLanguage\">groovy</stringProp>");
        }
    }

    private static byte[] readAll(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        for (int read; (read = input.read(buffer)) != -1; ) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    @Test
    void metadataNonTestBeanWithoutRuntimeGuiIdentityPreservesPrimaryFailure() throws Exception {
        LocalJMeterMenuRegistry.Entry entry = LocalJMeterMenuRegistry.reflect(MetadataOnlyMenuFactory.class)
                .resolve(MetadataOnlySampler.class.getName()).get();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LocalJMeterElementMaterializer.create(entry))
                .isInstanceOf(LocalJMeterElementMaterializer.MaterializationException.class)
                .hasMessageContaining(MetadataOnlySampler.class.getName())
                .hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    public static final class ExplodingMenuFactory {
        public static java.util.Map<String, java.util.List<Object>> getMenuMap() {
            java.util.Map<String, java.util.List<Object>> menus = new java.util.LinkedHashMap<>();
            menus.put("menu_sampler", java.util.Collections.<Object>singletonList(new FakeMenuInfo()));
            return menus;
        }
    }

    public static final class PrimaryNamesMenuFactory {
        public static java.util.Map<String, java.util.List<Object>> getMenuMap() {
            java.util.Map<String, java.util.List<Object>> menus = new java.util.LinkedHashMap<>();
            menus.put("menu_sampler", java.util.Arrays.<Object>asList(
                    new NamedMenuInfo("Resolved Marker", MarkerNameGui.class),
                    new NamedMenuInfo("Resolved Blank", BlankNameGui.class),
                    new NamedMenuInfo("Catalog Label", ReadableNameGui.class)));
            return menus;
        }
    }

    public static final class FallbackNamesMenuFactory {
        public static java.util.Map<String, java.util.List<Object>> getMenuMap() {
            java.util.Map<String, java.util.List<Object>> menus = new java.util.LinkedHashMap<>();
            menus.put("menu_sampler", java.util.Arrays.<Object>asList(
                    new NamedMenuInfo("Resolved Fallback Marker", FallbackMarkerSampler.class),
                    new NamedMenuInfo("Resolved Fallback Blank", FallbackBlankSampler.class),
                    new NamedMenuInfo("Catalog Fallback Label", FallbackReadableSampler.class)));
            return menus;
        }
    }

    public static final class NamedMenuInfo {
        private final String label;
        private final Class<?> menuClass;

        NamedMenuInfo(String label, Class<?> menuClass) {
            this.label = label;
            this.menuClass = menuClass;
        }

        public String getLabel() { return label; }
        public String getClassName() { return menuClass.getName(); }
        public boolean getEnabled(String action) { return true; }
    }

    public abstract static class NamedGui extends org.apache.jmeter.gui.AbstractJMeterGuiComponent {
        private static final long serialVersionUID = 1L;
        private final String defaultName;

        NamedGui(String defaultName) { this.defaultName = defaultName; }

        @Override public String getStaticLabel() { return "Synthetic Materializer"; }
        @Override public String getLabelResource() { return null; }
        @Override public javax.swing.JPopupMenu createPopupMenu() { return new javax.swing.JPopupMenu(); }
        @Override public java.util.Collection<String> getMenuCategories() {
            return java.util.Collections.singleton("menu_sampler");
        }
        @Override public org.apache.jmeter.testelement.TestElement createTestElement() {
            MaterializedNameElement element = new MaterializedNameElement();
            configureTestElement(element);
            element.setName(defaultName);
            return element;
        }
        @Override public void modifyTestElement(org.apache.jmeter.testelement.TestElement element) {
            configureTestElement(element);
        }
    }

    public static final class MarkerNameGui extends NamedGui {
        private static final long serialVersionUID = 1L;
        public MarkerNameGui() { super("[res_key=missing_primary_title]"); }
    }

    public static final class BlankNameGui extends NamedGui {
        private static final long serialVersionUID = 1L;
        public BlankNameGui() { super(" "); }
    }

    public static final class ReadableNameGui extends NamedGui {
        private static final long serialVersionUID = 1L;
        public ReadableNameGui() { super("JMeter Default"); }
    }

    public static final class MaterializedNameElement extends org.apache.jmeter.testelement.AbstractTestElement {
        private static final long serialVersionUID = 1L;
    }

    public abstract static class FallbackNameSampler extends org.apache.jmeter.samplers.AbstractSampler
            implements org.apache.jmeter.testbeans.TestBean {
        private static final long serialVersionUID = 1L;

        FallbackNameSampler(String defaultName) {
            setName(defaultName);
        }

        @Override public org.apache.jmeter.samplers.SampleResult sample(
                org.apache.jmeter.samplers.Entry entry) {
            return new org.apache.jmeter.samplers.SampleResult();
        }
    }

    @org.apache.jmeter.gui.TestElementMetadata(labelResource = "fallback_marker")
    public static final class FallbackMarkerSampler extends FallbackNameSampler {
        private static final long serialVersionUID = 1L;
        public FallbackMarkerSampler() { super("[res_key=missing_fallback_title]"); }
    }

    @org.apache.jmeter.gui.TestElementMetadata(labelResource = "fallback_blank")
    public static final class FallbackBlankSampler extends FallbackNameSampler {
        private static final long serialVersionUID = 1L;
        public FallbackBlankSampler() { super(" "); }
    }

    @org.apache.jmeter.gui.TestElementMetadata(labelResource = "fallback_readable")
    public static final class FallbackReadableSampler extends FallbackNameSampler {
        private static final long serialVersionUID = 1L;
        public FallbackReadableSampler() { super("Fallback Default"); }
    }

    public abstract static class ExplodingFallbackBeanInfo extends java.beans.SimpleBeanInfo {
        @Override
        public java.beans.PropertyDescriptor[] getPropertyDescriptors() {
            throw new IllegalStateException("force TestBeanGUI primary failure");
        }
    }

    public static final class FallbackMarkerSamplerBeanInfo extends ExplodingFallbackBeanInfo { }
    public static final class FallbackBlankSamplerBeanInfo extends ExplodingFallbackBeanInfo { }
    public static final class FallbackReadableSamplerBeanInfo extends ExplodingFallbackBeanInfo { }

    public static final class MetadataMenuFactory {
        public static java.util.Map<String, java.util.List<Object>> getMenuMap() {
            java.util.Map<String, java.util.List<Object>> menus = new java.util.LinkedHashMap<>();
            menus.put("menu_pre_processors", java.util.Collections.<Object>singletonList(
                    new MetadataMenuInfo()));
            return menus;
        }
    }

    public static final class MetadataMenuInfo {
        public String getLabel() { return "Metadata Element"; }
        public String getClassName() { return org.apache.jmeter.config.CSVDataSet.class.getName(); }
        public boolean getEnabled(String action) { return true; }
    }

    public static final class MetadataOnlyMenuFactory {
        public static java.util.Map<String, java.util.List<Object>> getMenuMap() {
            java.util.Map<String, java.util.List<Object>> menus = new java.util.LinkedHashMap<>();
            menus.put("menu_sampler", java.util.Collections.<Object>singletonList(
                    new MetadataOnlyMenuInfo()));
            return menus;
        }
    }

    public static final class MetadataOnlyMenuInfo {
        public String getLabel() { return "Metadata Only"; }
        public String getClassName() { return MetadataOnlySampler.class.getName(); }
        public boolean getEnabled(String action) { return true; }
    }

    @org.apache.jmeter.gui.TestElementMetadata(labelResource = "metadata_only")
    public static final class MetadataOnlySampler extends org.apache.jmeter.samplers.AbstractSampler {
        private static final long serialVersionUID = 1L;

        @Override
        public org.apache.jmeter.samplers.SampleResult sample(
                org.apache.jmeter.samplers.Entry entry) {
            return new org.apache.jmeter.samplers.SampleResult();
        }
    }


    public static final class FakeMenuInfo {
        public String getLabel() { return "Exploding"; }
        public String getClassName() { return ExplodingGui.class.getName(); }
        public boolean getEnabled(String action) { return true; }
    }

    public static final class ExplodingGui extends org.apache.jmeter.gui.AbstractJMeterGuiComponent {
        private static final long serialVersionUID = 1L;
        public ExplodingGui() { throw new IllegalStateException("primary explosion"); }
        @Override public String getStaticLabel() { return "Exploding"; }
        @Override public String getLabelResource() { return null; }
        @Override public javax.swing.JPopupMenu createPopupMenu() { return new javax.swing.JPopupMenu(); }
        @Override public java.util.Collection<String> getMenuCategories() {
            return java.util.Collections.singleton("menu_sampler");
        }
        @Override public org.apache.jmeter.testelement.TestElement createTestElement() { return null; }
        @Override public void modifyTestElement(org.apache.jmeter.testelement.TestElement element) { }
    }
}
