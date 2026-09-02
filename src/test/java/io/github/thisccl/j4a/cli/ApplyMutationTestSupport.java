package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class ApplyMutationTestSupport {
    static final String TEST_PLAN_REF = "jmx_d62893619b1f";
    static final String THREAD_GROUP_REF = "jmx_19871e6efa95";
    static final String HTTP_REQUEST_REF = "jmx_330976848c8e";

    private ApplyMutationTestSupport() {
    }

    static String setPatch(String domain) {
        return String.format("changes:\n  - set:\n      ref: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      properties:\n        - property: [HTTPSampler.domain]\n          value: %s\n          type: string\n", HTTP_REQUEST_REF, domain);
    }

    static String addPatch(String name) {
        return String.format("changes:\n  - add:\n      parent: %s\n      after: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      properties:\n        - property: [TestElement.name]\n          value: %s\n          type: string\n        - property: [HTTPSampler.domain]\n          value: api.example.test\n          type: string\n        - property: [HTTPSampler.path]\n          value: /health\n          type: string\n        - property: [HTTPSampler.method]\n          value: GET\n          type: string\n", THREAD_GROUP_REF, HTTP_REQUEST_REF, name);
    }

    static String defaultsOnlyHttpAddPatch() {
        return String.format("changes:\n  - add:\n      parent: %s\n      position: last\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n", THREAD_GROUP_REF);
    }

    static String defaultsOnlyHttpAddPatchWithEmptyProperties() {
        return String.format("changes:\n  - add:\n      parent: %s\n      position: last\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      properties: []\n", THREAD_GROUP_REF);
    }

    static String defaultsOnlyHttpAddPatchWithoutPlacement() {
        return String.format("changes:\n  - add:\n      parent: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n", THREAD_GROUP_REF);
    }

    static String httpAddPatchAtPosition(String position) {
        return String.format("changes:\n  - add:\n      parent: %s\n      position: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n", THREAD_GROUP_REF, position);
    }

    static String threadGroupUnderThreadGroupPatch() {
        return String.format("changes:\n  - add:\n      parent: %s\n      position: last\n      component: org.apache.jmeter.threads.gui.ThreadGroupGui\n", THREAD_GROUP_REF);
    }

    static String overlayHttpAddPatch(String name, String path) {
        return String.format("changes:\n  - add:\n      parent: %s\n      position: last\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      properties:\n        - property: [TestElement.name]\n          value: %s\n          type: string\n        - property: [HTTPSampler.path]\n          value: %s\n          type: string\n", THREAD_GROUP_REF, name, path);
    }

    static String addLoopControllerPatch() {
        return String.format("changes:\n  - add:\n      parent: %s\n      after: %s\n      component: org.apache.jmeter.control.gui.LoopControlPanel\n      properties:\n        - property: [LoopController.loops]\n          value: '2'\n          type: string\n        - property: [LoopController.continue_forever]\n          value: false\n          type: boolean\n", THREAD_GROUP_REF, HTTP_REQUEST_REF);
    }

    static String argumentRows(String name, String value) {
        return "row_type: org.apache.jmeter.protocol.http.util.HTTPArgument\n"
                + "rows:\n"
                + "  - Argument.name: " + name + "\n"
                + "    Argument.value: " + value + "\n"
                + "    HTTPArgument.always_encode: false\n"
                + "    HTTPArgument.content_type: ''\n"
                + "    Argument.metadata: '='\n"
                + "    HTTPArgument.use_equals: true\n";
    }

    static String addNestedHttpPatch(String parentRef) {
        return String.format("changes:\n  - add:\n      parent: %s\n      position: first\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      properties:\n        - property: [TestElement.name]\n          value: GET /looped\n          type: string\n        - property: [HTTPSampler.domain]\n          value: api.example.test\n          type: string\n        - property: [HTTPSampler.path]\n          value: /looped\n          type: string\n        - property: [HTTPSampler.method]\n          value: GET\n          type: string\n", parentRef);
    }

    static String moveBeforePatch(String ref) {
        return String.format("changes:\n  - move:\n      ref: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      parent: %s\n      before: %s\n", ref, THREAD_GROUP_REF, HTTP_REQUEST_REF);
    }

    static String deletePatch() {
        return String.format("changes:\n  - delete:\n      ref: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n", HTTP_REQUEST_REF);
    }

    static String moveBeforeThenDeleteOriginalPatch(String ref) {
        return String.format("changes:\n  - move:\n      ref: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      parent: %s\n      before: %s\n  - delete:\n      ref: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n", ref, THREAD_GROUP_REF, HTTP_REQUEST_REF, HTTP_REQUEST_REF);
    }

    static String moveBeforeItselfPatch(String ref) {
        return String.format("changes:\n  - move:\n      ref: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      parent: %s\n      before: %s\n", ref, THREAD_GROUP_REF, ref);
    }

    static String moveAfterItselfPatch(String ref) {
        return String.format("changes:\n  - move:\n      ref: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      parent: %s\n      after: %s\n", ref, THREAD_GROUP_REF, ref);
    }

    static String moveUnderParentPatch(String ref, String component, String parentRef) {
        return String.format("changes:\n  - move:\n      ref: %s\n      component: %s\n      parent: %s\n      position: last\n", ref, component, parentRef);
    }

    static String moveBeforeRefPatch(String ref, String component, String beforeRef) {
        return String.format("changes:\n  - move:\n      ref: %s\n      component: %s\n      parent: %s\n      before: %s\n", ref, component, THREAD_GROUP_REF, beforeRef);
    }

    static String moveAfterRefPatch(String ref, String component, String afterRef) {
        return String.format("changes:\n  - move:\n      ref: %s\n      component: %s\n      parent: %s\n      after: %s\n", ref, component, THREAD_GROUP_REF, afterRef);
    }

    static String invalidMixedPatch() {
        return String.format("changes:\n  - set:\n      ref: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      properties:\n        - property: [HTTPSampler.domain]\n          value: should-not-write.example\n          type: string\n  - delete:\n      ref: jmx_missing\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n", HTTP_REQUEST_REF);
    }

    static String refFor(Path jmxPath, String elementName) {
        CliTestResult result = MainCliTestSupport.runMainWithLocalRuntime("", "read", jmxPath.toString(), "--depth", "10");
        assertThat(result.exitCode()).isZero();
        Map<String, Object> root = mapping(CliYamlAssertions.parseMapping(result.stdout()).get("root"));
        return findRef(root, elementName)
                .orElseThrow(() -> new AssertionError("No ref found for " + elementName + " in read output:\n" + result.stdout()));
    }

    static NestedLoopInput nestedLoopInput(Path tempDir, String fileName) throws IOException {
        Path input = tempDir.resolve(fileName);
        Files.copy(MainCliTestSupport.fixture("simple-http.jmx"), input);
        CliTestResult loopResult = MainCliTestSupport.runMainWithLocalRuntime(
                addLoopControllerPatch(), "apply", input.toString(), "--patch", "-", "--override");
        assertThat(loopResult.exitCode()).isZero();
        String loopRef = refFor(input, "Loop Controller");
        CliTestResult nestedResult = MainCliTestSupport.runMainWithLocalRuntime(
                addNestedHttpPatch(loopRef), "apply", input.toString(), "--patch", "-", "--override");
        assertThat(nestedResult.exitCode()).isZero();
        return new NestedLoopInput(input, loopRef, refFor(input, "GET /looped"));
    }

    static List<String> elementNames(Path jmxPath) {
        Document document = parseXml(jmxPath);
        List<String> names = new java.util.ArrayList<>();
        NodeList elements = document.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (element.hasAttribute("testname")) {
                names.add(element.getAttribute("testname"));
            }
        }
        return names;
    }

    static String propertyAsString(Path jmxPath, String elementName, String propertyName) {
        NodeList elements = parseXml(jmxPath).getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (!elementName.equals(element.getAttribute("testname"))) {
                continue;
            }
            NodeList properties = element.getElementsByTagName("stringProp");
            for (int propertyIndex = 0; propertyIndex < properties.getLength(); propertyIndex++) {
                Element property = (Element) properties.item(propertyIndex);
                if (propertyName.equals(property.getAttribute("name"))) {
                    return property.getTextContent();
                }
            }
            return null;
        }
        throw new AssertionError("No element named " + elementName + " in " + jmxPath);
    }

    private static Document parseXml(Path jmxPath) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(jmxPath.toFile());
        } catch (Exception exception) {
            throw new AssertionError("Unable to parse JMX test fixture: " + jmxPath, exception);
        }
    }

    static void assertInvalidAddDoesNotOverwrite(Path tempDir, String patchText, String... expectedMessages)
            throws IOException {
        Path output = tempDir.resolve("invalid-add-output.jmx");
        Files.write(output, "existing".getBytes(StandardCharsets.UTF_8));
        CliTestResult result = MainCliTestSupport.runMainWithLocalRuntime(
                patchText, "apply", MainCliTestSupport.fixture("simple-http.jmx").toString(),
                "--patch", "-", "--out", output.toString(), "--force-out");
        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(3);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(expectedMessages);
        assertThat(new String(Files.readAllBytes(output), StandardCharsets.UTF_8)).isEqualTo("existing");
    }

    private static java.util.Optional<String> findRef(Map<String, Object> node, String elementName) {
        if (elementName.equals(node.get("name"))) {
            return java.util.Optional.of((String) node.get("ref"));
        }
        Object children = node.get("children");
        if (!(children instanceof List<?>)) {
            return java.util.Optional.empty();
        }
        for (Object child : (List<?>) children) {
            java.util.Optional<String> ref = findRef(mapping(child), elementName);
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

    static final class NestedLoopInput {
        private final Path path;
        private final String loopRef;
        private final String nestedRef;

        NestedLoopInput(Path path, String loopRef, String nestedRef) {
            this.path = path;
            this.loopRef = loopRef;
            this.nestedRef = nestedRef;
        }

        Path path() {
            return path;
        }

        String loopRef() {
            return loopRef;
        }

        String nestedRef() {
            return nestedRef;
        }
    }
}
