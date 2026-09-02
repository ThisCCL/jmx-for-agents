package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.BeforeAll;
import org.yaml.snakeyaml.Yaml;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalJMeterWorkerPolymorphicArgumentTest {
    private final DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();
    private final LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
            Duration.ofSeconds(20), Duration.ofSeconds(90), Duration.ofSeconds(90));

    @BeforeAll
    void installCanonicalStructuredMenuFixture() throws Exception {
        fixtures.ensure();
        CanonicalStructuredMenuFixture.install(fixtures.localHome());
    }

    @Test
    void httpArgumentAndPrimitivePropertiesPersistThroughSaveServiceRoundTrip() throws IOException {
        Path patch = writePatch("http-polymorphic.yml", "changes:\n"
                + "  - set:\n"
                + "      ref: jmx_330976848c8e\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      properties:\n"
                + "        - property: [HTTPsampler.Arguments]\n"
                + "          type: rows\n"
                + "          value:\n"
                + "            row_type: org.apache.jmeter.protocol.http.util.HTTPArgument\n"
                + "            rows:\n"
                + "              - Argument.name: q\n"
                + "                Argument.value: abc\n"
                + "                Argument.metadata: '='\n"
                + "                HTTPArgument.always_encode: true\n"
                + "                HTTPArgument.use_equals: false\n"
                + "                HTTPArgument.content_type: text/plain\n");
        Path output = fixtures.root().resolve("http-polymorphic-output.jmx");

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                fixtures.root().resolve("basic.jmx"), fixtures.localHome(), patch, output));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(client.execute(LocalJMeterWorkerRequest.validate(output, fixtures.localHome()))
                .response().success()).isTrue();
        LocalJMeterWorkerResult reloaded = client.execute(LocalJMeterWorkerRequest.renderReadData(
                output, fixtures.localHome(), "5", null, "ALL", "false"));
        assertThat(reloaded.response().success()).as(reloaded.response().toJsonLine()).isTrue();
        assertThat(reloaded.response().payload()).contains(
                "row_type: org.apache.jmeter.protocol.http.util.HTTPArgument",
                "type: rows",
                "Argument.name: q",
                "Argument.value: abc",
                "Argument.metadata: =",
                "HTTPArgument.always_encode: true",
                "HTTPArgument.use_equals: false",
                "HTTPArgument.content_type: text/plain")
                .doesNotContain("element_type:");
        assertThat(text(output)).contains(
                "elementType=\"HTTPArgument\"",
                "name=\"HTTPArgument.always_encode\">true",
                "name=\"HTTPArgument.use_equals\">false",
                "name=\"HTTPArgument.content_type\">text/plain",
                "name=\"Argument.name\">q",
                "name=\"Argument.value\">abc",
                "name=\"Argument.metadata\">=")
                .doesNotContain("elementType=\"org.apache.jmeter.protocol.http.util.HTTPArgument\"");
    }

    @Test
    void kpArgumentAndEncryptedPropertyPersistThroughLocalRuntimeRoundTrip() throws Exception {
        Path input = fixtures.kpArgumentBackedJmx();
        String ref = refFor(input, fixtures.localHome(), "KpArgument Sampler");
        Path patch = writePatch("kp-polymorphic.yml", "changes:\n"
                + "  - set:\n"
                + "      ref: " + ref + "\n"
                + "      properties:\n"
                + "        - property: [kingdomSampler.arguments]\n"
                + "          type: rows\n"
                + "          value:\n"
                + "            row_type: " + DefaultLocalProfileQaFixtures.KP_ARGUMENT_CLASS + "\n"
                + "            rows:\n"
                + "              - Argument.name: branch_no\n"
                + "                Argument.value: '1001'\n"
                + "                Argument.metadata: '='\n"
                + "                kingdomArgument.encrypted: true\n"
                );
        Path output = fixtures.root().resolve("kp-polymorphic-output.jmx");

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                input, fixtures.localHome(), patch, output));
        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(client.execute(LocalJMeterWorkerRequest.validate(output, fixtures.localHome())).response().success()).isTrue();
        String saved = text(output);

        assertThat(saved).contains(
                "elementType=\"" + DefaultLocalProfileQaFixtures.KP_ARGUMENT_CLASS + "\"",
                "name=\"kingdomArgument.encrypted\">true",
                "name=\"Argument.name\">branch_no", "name=\"Argument.value\">1001",
                "name=\"Argument.metadata\">=");
    }

    @Test
    void applySetInfersRegisteredDescriptorFromLocatedPluginTarget() throws Exception {
        fixtures.ensure();
        String packageName = DefaultLocalProfilePluginFixtures.DESCRIPTOR_PACKAGE;
        Path input = fixtures.root().resolve("custom-descriptor-backed.jmx");
        String samplerClass = packageName + ".CustomBooleanDescriptorSampler";
        writeDescriptorBackedJmx(input, samplerClass, "Custom Descriptor Sampler", "custom.arguments",
                packageName + ".CustomBooleanArgument", "qa.enabled");
        String ref = refFor(input, fixtures.localHome(), "Custom Descriptor Sampler");
        Path patch = writePatch("custom-descriptor-apply.yml", "changes:\n"
                + "  - set:\n"
                + "      ref: " + ref + "\n"
                + "      properties:\n"
                + "        - property: [custom.arguments]\n"
                + "          type: rows\n"
                + "          value:\n"
                + "            row_type: " + packageName + ".CustomBooleanArgument\n"
                + "            rows:\n"
                + "              - Argument.name: branch_no\n"
                + "                Argument.value: '2002'\n"
                + "                qa.enabled: true\n"
                );
        Path output = fixtures.root().resolve("custom-descriptor-apply-output.jmx");

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                input, fixtures.localHome(), patch, output));
        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(client.execute(LocalJMeterWorkerRequest.validate(output, fixtures.localHome())).response().success()).isTrue();
        String saved = text(output);

        assertThat(saved).contains(
                "elementType=\"" + packageName + ".CustomBooleanArgument\"",
                "name=\"qa.enabled\">true",
                "name=\"Argument.name\">branch_no", "name=\"Argument.value\">2002")
                .doesNotContain("name=\"qa.equals\"");
    }

    @Test
    void unloadableAndIncompatibleTypesNameTypeAndRuntimeWithoutOverwritingOutput() throws IOException {
        assertTypeFailure("missing.example.Argument", "does not match observed row type");
        assertTypeFailure("java.lang.String", "does not match observed row type");
    }

    @Test
    void omittedRowTypeForUnboundPluginArgumentsFailsWithoutChangingRequestedOutput() throws IOException {
        Path patch = writePatch("unbound-omitted-row-type.yml", "changes:\n"
                + "  - add:\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: last\n"
                + "      component: " + DefaultLocalProfilePluginFixtures.DESCRIPTOR_PACKAGE
                + ".UnboundDescriptorSamplerGui\n"
                + "      properties:\n"
                + "        - property: [unbound.arguments]\n"
                + "          type: rows\n"
                + "          value:\n"
                + "            rows:\n"
                + "              - Argument.name: branch_no\n"
                + "                Argument.value: '1001'\n"
                + "                qa.enabled: true\n");
        Path output = fixtures.root().resolve("unbound-omitted-row-type-output.jmx");
        byte[] sentinel = "byte-identical-sentinel\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(output, sentinel);

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                fixtures.root().resolve("basic.jmx"), fixtures.localHome(), patch, output));

        assertThat(result.response().success()).isFalse();
        assertUnsupportedRows(result, "qa.enabled");
        assertThat(Files.readAllBytes(output)).containsExactly(sentinel);
    }

    @Test
    void runtimeDescriptorExecutesOrderedMultiKeySetterFootprint() throws Exception {
        String packageName = DefaultLocalProfilePluginFixtures.DESCRIPTOR_PACKAGE;
        LocalJMeterWorkerResult details = client.execute(LocalJMeterWorkerRequest.componentDetails(
                fixtures.localHome(), packageName + ".CoupledDescriptorSamplerGui", true));
        assertThat(details.response().success()).as(details.response().toJsonLine()).isTrue();
        assertThat(details.response().payload()).contains(
                "row_type: " + packageName + ".SetterCoupledArgument",
                "  - qa.encoded",
                "  - qa.equals")
                .doesNotContain("  - qa.enabled");
        Path input = fixtures.root().resolve("coupled-descriptor-backed.jmx");
        writeDescriptorBackedJmx(input, packageName + ".CoupledDescriptorSampler", "Coupled Descriptor Sampler",
                "coupled.arguments", packageName + ".SetterCoupledArgument", "qa.encoded");
        String ref = refFor(input, fixtures.localHome(), "Coupled Descriptor Sampler");
        Path patch = writePatch("coupled-runtime-descriptor.yml", coupledPatch(ref, false));
        Path output = fixtures.root().resolve("coupled-runtime-descriptor-output.jmx");

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                input, fixtures.localHome(), patch, output));
        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(client.execute(LocalJMeterWorkerRequest.validate(output, fixtures.localHome())).response().success()).isTrue();
        String saved = text(output);

        assertThat(saved).contains(
                "elementType=\"" + packageName + ".SetterCoupledArgument\"",
                "name=\"qa.encoded\">true",
                "name=\"qa.equals\">true",
                "name=\"Argument.name\">q",
                "name=\"Argument.value\">v");
        assertThat(saved.indexOf("name=\"qa.encoded\">true"))
                .isLessThan(saved.indexOf("name=\"qa.equals\">true"));
    }

    @Test
    void rawPropertyConflictWithCanonicalSetterFootprintFailsByteIdentically() throws Exception {
        String packageName = DefaultLocalProfilePluginFixtures.DESCRIPTOR_PACKAGE;
        Path input = fixtures.root().resolve("coupled-conflict-descriptor-backed.jmx");
        writeDescriptorBackedJmx(input, packageName + ".CoupledDescriptorSampler", "Coupled Conflict Sampler",
                "coupled.arguments", packageName + ".SetterCoupledArgument", "qa.encoded");
        String ref = refFor(input, fixtures.localHome(), "Coupled Conflict Sampler");
        Path patch = writePatch("coupled-runtime-conflict.yml", coupledPatch(ref, true));
        Path output = fixtures.root().resolve("coupled-runtime-conflict-output.jmx");
        byte[] sentinel = "coupled-conflict-sentinel\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(output, sentinel);
        byte[] beforeHash = sha256(output);

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                input, fixtures.localHome(), patch, output));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().message()).contains(
                "qa.equals", "canonical setter footprint", "qa.encoded");
        assertThat(Files.readAllBytes(output)).containsExactly(sentinel);
        assertThat(sha256(output)).containsExactly(beforeHash);
    }

    private static String coupledPatch(String ref, boolean conflict) {
        return "changes:\n"
                + "  - set:\n"
                + "      ref: " + ref + "\n"
                + "      properties:\n"
                + "        - property: [coupled.arguments]\n"
                + "          type: rows\n"
                + "          value:\n"
                + "            row_type: " + DefaultLocalProfilePluginFixtures.DESCRIPTOR_PACKAGE
                + ".SetterCoupledArgument\n"
                + "            rows:\n"
                + "              - Argument.name: q\n"
                + "                Argument.value: v\n"
                + "                qa.encoded: true\n"
                + (conflict ? "                qa.equals: false\n" : "");
    }

    private static byte[] sha256(Path path) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }

    private void assertTypeFailure(String rowType, String reason) throws IOException {
        Path input = fixtures.httpArgumentBackedJmx();
        String ref = refFor(input, fixtures.localHome(), "HTTPArgument Request");
        Path patch = writePatch("bad-" + rowType.replace('.', '-') + ".yml", "changes:\n"
                + "  - set:\n"
                + "      ref: " + ref + "\n"
                + "      properties:\n"
                + "        - property: [HTTPsampler.Arguments]\n"
                + "          type: rows\n"
                + "          value:\n"
                + "            row_type: " + rowType + "\n"
                + "            rows:\n"
                + "              - Argument.name: q\n"
                + "                Argument.value: v\n"
                + "                Argument.metadata: '='\n"
                + "                HTTPArgument.always_encode: true\n"
                + "                HTTPArgument.use_equals: true\n"
                + "                HTTPArgument.content_type: text/plain\n");
        Path output = fixtures.root().resolve("preserved-" + rowType.replace('.', '-') + ".jmx");
        Files.write(output, "preserve-me".getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                input, fixtures.localHome(), patch, output));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().message()).contains("rows field 'row_type'", reason);
        assertThat(text(output)).isEqualTo("preserve-me");
    }

    private static void assertUnsupportedRows(LocalJMeterWorkerResult result, String unknownField) {
        assertThat(result.response().success()).isFalse();
        assertThat(result.response().toJsonLine()).contains(
                "\"errorCode\":\"SEMANTIC_LOAD_ERROR\"",
                "\"category\":\"runtime\"");
        assertThat(result.response().message()).contains(unknownField, "row");
    }

    private Path writePatch(String name, String yaml) throws IOException {
        fixtures.ensure();
        Path path = fixtures.root().resolve(name);
        Files.write(path, yaml.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static void writeDescriptorBackedJmx(
            Path target, String samplerClass, String name, String property, String rowClass, String flag)
            throws IOException {
        Files.write(target, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jmeterTestPlan version=\"1.2\" properties=\"5.0\" jmeter=\"5.6.3\">\n"
                + "  <hashTree>\n"
                + "    <TestPlan guiclass=\"TestPlanGui\" testclass=\"TestPlan\" testname=\"Descriptor Plan\" enabled=\"true\"/>\n"
                + "    <hashTree>\n"
                + "      <" + samplerClass + " guiclass=\"" + samplerClass + "Gui\" testclass=\""
                + samplerClass + "\" testname=\"" + name + "\" enabled=\"true\">\n"
                + "        <elementProp name=\"" + property + "\" elementType=\"Arguments\">\n"
                + "          <collectionProp name=\"Arguments.arguments\">\n"
                + "            <elementProp name=\"branch_no\" elementType=\"" + rowClass + "\">\n"
                + "              <boolProp name=\"" + flag + "\">true</boolProp>\n"
                + "              <stringProp name=\"Argument.name\">branch_no</stringProp>\n"
                + "              <stringProp name=\"Argument.value\">1001</stringProp>\n"
                + "            </elementProp>\n"
                + "          </collectionProp>\n"
                + "        </elementProp>\n"
                + "      </" + samplerClass + ">\n"
                + "      <hashTree/>\n"
                + "    </hashTree>\n"
                + "  </hashTree>\n"
                + "</jmeterTestPlan>\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String refFor(Path jmx, Path localHome, String name) throws IOException {
        LocalJMeterWorkerResult result = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(20), Duration.ofSeconds(90), Duration.ofSeconds(90))
                .execute(LocalJMeterWorkerRequest.renderReadData(
                        jmx, localHome, "5", null, "NONE", "false"));
        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        Map<String, Object> document = mapping(new Yaml().load(result.response().payload()));
        Map<String, Object> root = document.containsKey("root") ? mapping(document.get("root")) : document;
        return findRef(root, name).orElseThrow(() -> new AssertionError("Missing ref for " + name));
    }

    private static java.util.Optional<String> findRef(Map<String, Object> node, String name) {
        if (name.equals(node.get("name"))) {
            return java.util.Optional.of((String) node.get("ref"));
        }
        Object children = node.get("children");
        if (!(children instanceof List<?>)) {
            return java.util.Optional.empty();
        }
        for (Object child : (List<?>) children) {
            java.util.Optional<String> ref = findRef(mapping(child), name);
            if (ref.isPresent()) {
                return ref;
            }
        }
        return java.util.Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static String text(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
