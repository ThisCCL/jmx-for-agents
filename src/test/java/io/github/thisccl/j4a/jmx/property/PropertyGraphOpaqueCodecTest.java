package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.thisccl.j4a.path.PropertyPath;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.Todo8ConstructorSideEffectProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PropertyGraphOpaqueCodecTest {
    @BeforeAll
    static void selectWorkerSaveService() {
        Todo8OpaqueFixtures.initializeSaveService();
    }

    @Test
    void pinSelectedSaveServiceFixtureFingerprintAndRecursiveClassSkeleton() throws Exception {
        byte[] targetBytes = Files.readAllBytes(Todo8OpaqueFixtures.fixture("response-assertion.jmx"));
        byte[] unseenFixture = Files.readAllBytes(
                Todo8OpaqueFixtures.fixture("synthetic-unseen-property.jmx"));
        ResponseAssertion target = Todo8OpaqueFixtures.assertion();
        JMeterProperty current = target.getPropertyOrNull(Todo8OpaqueFixtures.PROPERTY_NAME);

        byte[] payload = Todo8OpaqueFixtures.saveElement(current);
        JMeterProperty reloaded = (JMeterProperty) Todo8OpaqueFixtures.loadElement(payload);
        RuntimeContext context = Todo8OpaqueFixtures.runtimeContext();

        assertThat(Todo8OpaqueFixtures.sha256(targetBytes))
                .isEqualTo(Todo8OpaqueFixtures.FIXTURE_SHA256);
        assertThat(Todo8OpaqueFixtures.sha256(unseenFixture))
                .isEqualTo(Todo8OpaqueFixtures.UNSEEN_FIXTURE_SHA256);
        assertThat(Todo8OpaqueFixtures.saveElement(reloaded)).containsExactly(payload);
        assertThat(reloaded).isExactlyInstanceOf(CollectionProperty.class);
        assertThat(reloaded.getName()).isEqualTo(Todo8OpaqueFixtures.PROPERTY_NAME);
        assertThat(Todo8OpaqueFixtures.values(reloaded))
                .containsExactly("login failed", "access denied");
        assertThat(Todo8OpaqueFixtures.classSkeleton(reloaded)).containsExactly(
                CollectionProperty.class.getName(),
                StringProperty.class.getName(),
                StringProperty.class.getName());
        assertThat(context.fingerprint().jmeterHome())
                .isEqualTo(Todo8OpaqueFixtures.jmeterHome().toString());
        assertThat(context.fingerprint().jmeterVersion()).isEqualTo("5.6.3");
        assertThat(classFileMajorVersion(OpaquePropertyCodec.class))
                .as("OpaquePropertyCodec class-file major version")
                .isEqualTo(52);
        assertThat(context.fingerprint().librarySha256())
                .containsOnlyKeys("lib/ext/ApacheJMeter_core.jar");
        assertThat(Files.readAllBytes(Todo8OpaqueFixtures.fixture("response-assertion.jmx")))
                .containsExactly(targetBytes);

        System.out.println("PIN_SAVE_ELEMENT_SHA256=" + Todo8OpaqueFixtures.sha256(payload));
        System.out.println("PIN_CLASS_SKELETON=" + Todo8OpaqueFixtures.classSkeleton(reloaded));
        System.out.println("PIN_TARGET_SHA256=" + Todo8OpaqueFixtures.sha256(targetBytes));
        System.out.println("PIN_UNSEEN_FIXTURE_SHA256=" + Todo8OpaqueFixtures.sha256(unseenFixture));
        System.out.println("PIN_JAVA_VERSION=" + System.getProperty("java.version"));
    }

    static int classFileMajorVersion(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        InputStream resource = type.getResourceAsStream(resourceName);
        if (resource == null) {
            throw new IOException("Missing class-file resource: " + resourceName);
        }
        try (DataInputStream input = new DataInputStream(resource)) {
            if (input.readInt() != 0xCAFEBABE) {
                throw new IOException("Invalid class-file magic: " + resourceName);
            }
            input.readUnsignedShort();
            return input.readUnsignedShort();
        }
    }

    @Test
    void unseenScalarAppliesProjectsAndReloadsWithObservedClassAndStrictOpaqueWireDocument()
            throws Exception {
        ConfigTestElement element = new ConfigTestElement();
        element.setProperty(TestElement.TEST_CLASS, ConfigTestElement.class.getName());
        element.setProperty(TestElement.GUI_CLASS, "todo8.fixture.UnseenGui");
        element.setProperty(new Todo8UnseenStringProperty(
                Todo8OpaqueFixtures.UNSEEN_NAME, Todo8OpaqueFixtures.UNSEEN_VALUE));
        RuntimeContext context = Todo8OpaqueFixtures.runtimeContext();
        DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
        GraphSnapshot snapshot = graph.inspect(element, context);
        PropertyPath path = io.github.thisccl.j4a.path.TestPropertyPaths.properties("qa.unseen.property");

        GraphNode node = snapshot.resolve(path);
        assertThat(node.value().propertyClass()).isEqualTo(Todo8UnseenStringProperty.class.getName());
        assertThat(node.value().scalarValue()).isEqualTo(Todo8OpaqueFixtures.UNSEEN_VALUE);
        assertThat(node.capability().writableState()).isEqualTo(WritableState.WRITABLE);
        assertThat(node.capability().reason()).isEmpty();
        assertThat(node.capability().runtimeClassConstraint().propertyClass())
                .isEqualTo(Todo8UnseenStringProperty.class.getName());
        assertThat(node.capability().runtimeClassConstraint().valueClass())
                .contains(String.class.getName());
        assertThat(snapshot.resolveWritable(path)).isSameAs(node);

        String changedValue = "changed-unseen-value";
        RecursiveValue changed = RecursiveValue.scalar(
                GraphType.STRING, Todo8UnseenStringProperty.class.getName(), changedValue);
        PropertyWrite requested = new PropertyWrite(path, GraphType.STRING, changed);
        ConfigTestElement candidate = (ConfigTestElement) element.clone();

        MutationReceipt receipt = graph.apply(
                candidate, snapshot, Collections.singletonList(requested));

        assertThat(receipt.runtimeContext()).isSameAs(context);
        assertThat(receipt.writes()).containsExactly(requested);
        assertThat(graph.project(candidate, receipt).values()).containsExactly(requested);
        JMeterProperty applied = candidate.getPropertyOrNull(Todo8OpaqueFixtures.UNSEEN_NAME);
        assertThat(applied).isExactlyInstanceOf(Todo8UnseenStringProperty.class);
        assertThat(applied.getStringValue()).isEqualTo(changedValue);

        ConfigTestElement reloaded = (ConfigTestElement) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(candidate));
        JMeterProperty reloadedProperty = reloaded.getPropertyOrNull(
                Todo8OpaqueFixtures.UNSEEN_NAME);
        assertThat(reloadedProperty).isExactlyInstanceOf(Todo8UnseenStringProperty.class);
        assertThat(reloadedProperty.getStringValue()).isEqualTo(changedValue);
        assertThat(graph.project(reloaded, receipt).values()).containsExactly(requested);
        GraphNode reloadedNode = graph.inspect(reloaded, context).resolveWritable(path);
        assertThat(reloadedNode.value()).isEqualTo(changed);
        assertThat(reloadedNode.capability().runtimeClassConstraint())
                .isEqualTo(node.capability().runtimeClassConstraint());

        ResponseAssertion assertion = Todo8OpaqueFixtures.assertion();
        JMeterProperty property = assertion.getPropertyOrNull(Todo8OpaqueFixtures.PROPERTY_NAME);
        String payload = Todo8OpaqueFixtures.text(Todo8OpaqueFixtures.saveElement(property));
        String digest = Todo8OpaqueFixtures.sha256(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RecursiveValue.OpaqueValue opaque = new RecursiveValue.OpaqueValue(
                RecursiveValue.OpaqueValue.FORMAT,
                digest,
                property.getClass().getName(),
                "pinned-runtime",
                payload);
        PropertyWrite write = new PropertyWrite(
                io.github.thisccl.j4a.path.TestPropertyPaths.properties("Asserion.test_strings"),
                GraphType.OPAQUE,
                RecursiveValue.opaque(property.getClass().getName(), opaque));
        PropertyGraphDocumentMapper mapper = new PropertyGraphDocumentMapper();
        Map<String, Object> document = mapper.toDocument(write);

        assertThat(mapper.fromDocument(document).property())
                .isEqualTo(io.github.thisccl.j4a.path.PropertyAddress.fromPath(write.property()));
        assertThat(mapper.fromDocument(document).type()).isEqualTo(write.type());
        assertThat(mapper.fromDocument(document).value()).isEqualTo(write.value());
        assertThat(document).containsOnlyKeys("property", "type", "value");
        assertThat(document.get("type")).isEqualTo("opaque");
        assertThat(new ArrayList<Object>(((Map<?, ?>) document.get("value")).keySet()))
                .containsExactlyInAnyOrderElementsOf(
                Arrays.asList("presence", "property_class", "format", "base_digest",
                        "outer_property_class", "runtime_fingerprint", "payload"));
        assertThatThrownBy(() -> new RecursiveValue.OpaqueValue(
                "raw-xml", digest, property.getClass().getName(), "pinned-runtime", payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unrecognized opaque format");
        System.out.println("WRITABLE_UNSEEN_PROPERTY_CLASS=" + node.value().propertyClass());
        System.out.println("WRITABLE_UNSEEN_RELOAD_VALUE=" + reloadedProperty.getStringValue());
        System.out.println("PIN_OPAQUE_WIRE_KEYS=" + ((Map<?, ?>) document.get("value")).keySet());
    }

    @Test
    void readsSaveServicePayloadIntoRuntimeBoundEnvelope() throws Exception {
        ResponseAssertion target = Todo8OpaqueFixtures.assertion();
        JMeterProperty current = target.getPropertyOrNull(Todo8OpaqueFixtures.PROPERTY_NAME);
        OpaquePropertyCodec codec = new OpaquePropertyCodec(Todo8OpaqueFixtures.runtimeContext());

        RecursiveValue.OpaqueValue envelope = codec.read(current);

        assertThat(envelope.format()).isEqualTo(RecursiveValue.OpaqueValue.FORMAT);
        assertThat(envelope.outerPropertyClass()).isEqualTo(CollectionProperty.class.getName());
        assertThat(envelope.payload()).isEqualTo(Todo8OpaqueFixtures.text(
                Todo8OpaqueFixtures.saveElement(current)));
        assertThat(envelope.baseDigest()).isEqualTo(Todo8OpaqueFixtures.sha256(
                envelope.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(envelope.runtimeFingerprint()).isNotEmpty();
    }

    @Test
    void unchangedEnvelopeSurvivesFullCandidateRoundTrip() throws Exception {
        ResponseAssertion target = Todo8OpaqueFixtures.assertion();
        byte[] before = Todo8OpaqueFixtures.saveElement(target);
        OpaquePropertyCodec codec = new OpaquePropertyCodec(Todo8OpaqueFixtures.runtimeContext());
        RecursiveValue.OpaqueValue current = codec.read(
                target.getPropertyOrNull(Todo8OpaqueFixtures.PROPERTY_NAME));

        RecursiveValue.OpaqueValue result = codec.replace(
                target, Todo8OpaqueFixtures.PROPERTY_NAME, current);

        assertThat(result).isEqualTo(current);
        assertThat(Todo8OpaqueFixtures.saveElement(target)).containsExactly(before);
    }

    @Test
    void nonNoOpTextEditPersistsWithServerComputedDigest() throws Exception {
        ResponseAssertion target = Todo8OpaqueFixtures.assertion();
        OpaquePropertyCodec codec = new OpaquePropertyCodec(Todo8OpaqueFixtures.runtimeContext());
        RecursiveValue.OpaqueValue current = codec.read(
                target.getPropertyOrNull(Todo8OpaqueFixtures.PROPERTY_NAME));
        RecursiveValue.OpaqueValue submitted = Todo8OpaqueFixtures.withPayload(
                current, current.payload().replace(
                        Todo8OpaqueFixtures.BEFORE_TEXT, Todo8OpaqueFixtures.AFTER_TEXT));

        RecursiveValue.OpaqueValue result = codec.replace(
                target, Todo8OpaqueFixtures.PROPERTY_NAME, submitted);

        assertThat(result.payload()).contains(Todo8OpaqueFixtures.AFTER_TEXT)
                .doesNotContain(Todo8OpaqueFixtures.BEFORE_TEXT);
        assertThat(result.baseDigest()).isNotEqualTo(current.baseDigest());
        assertThat(result.baseDigest()).isEqualTo(Todo8OpaqueFixtures.sha256(
                result.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ResponseAssertion reloaded = (ResponseAssertion) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(target));
        assertThat(Todo8OpaqueFixtures.values(
                reloaded.getPropertyOrNull(Todo8OpaqueFixtures.PROPERTY_NAME)))
                .containsExactly(Todo8OpaqueFixtures.AFTER_TEXT, "access denied");
        System.out.println("SERVER_DIGEST_BEFORE=" + current.baseDigest());
        System.out.println("SERVER_DIGEST_AFTER=" + result.baseDigest());
    }

    @Test
    void nestedSameClassExpansionPersistsAndReloads() throws Exception {
        ResponseAssertion target = Todo8OpaqueFixtures.assertion();
        OpaquePropertyCodec codec = new OpaquePropertyCodec(Todo8OpaqueFixtures.runtimeContext());
        RecursiveValue.OpaqueValue current = codec.read(
                target.getPropertyOrNull(Todo8OpaqueFixtures.PROPERTY_NAME));
        String expanded = current.payload().replace(
                "</collectionProp>",
                "  <stringProp name=\"2\">same-class addition</stringProp>\n</collectionProp>");

        codec.replace(
                target,
                Todo8OpaqueFixtures.PROPERTY_NAME,
                Todo8OpaqueFixtures.withPayload(current, expanded));

        ResponseAssertion reloaded = (ResponseAssertion) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(target));
        assertThat(Todo8OpaqueFixtures.values(
                reloaded.getPropertyOrNull(Todo8OpaqueFixtures.PROPERTY_NAME)))
                .containsExactly("login failed", "access denied", "same-class addition");
    }

    @Test
    void staleDigestRejectsBeforeAttachment() throws Exception {
        assertRejected("stale-digest", value -> Todo8OpaqueFixtures.withDigest(
                value, repeat('0', 64)), "base digest");
    }

    @Test
    void runtimeFingerprintMismatchRejectsBeforeAttachment() throws Exception {
        assertRejected("runtime-fingerprint", value -> Todo8OpaqueFixtures.withFingerprint(
                value, "other-runtime"), "runtime fingerprint");
    }

    @Test
    void renamedRootRejectsBeforeAttachment() throws Exception {
        assertRejected("renamed-root", value -> Todo8OpaqueFixtures.withPayload(
                value, Todo8OpaqueFixtures.renamedRoot(value.payload())), "root property name");
    }

    @Test
    void changedOuterClassRejectsBeforeAttachment() throws Exception {
        assertRejected("outer-class", value -> Todo8OpaqueFixtures.withOuterClass(
                value, "org.apache.jmeter.testelement.property.MapProperty"), "outer property class");
    }

    @Test
    void newlyIntroducedNestedClassRejectsBeforeAttachment() throws Exception {
        assertRejected("nested-class", value -> Todo8OpaqueFixtures.withPayload(
                value, Todo8OpaqueFixtures.expandedNestedClass(value.payload())),
                "serialized classes");
    }

    @Test
    void callerSelectedClassRejectsBeforeItsConstructorRuns() throws Exception {
        ResponseAssertion target = Todo8OpaqueFixtures.assertion();
        byte[] before = Todo8OpaqueFixtures.saveElement(target);
        OpaquePropertyCodec codec = new OpaquePropertyCodec(Todo8OpaqueFixtures.runtimeContext());
        RecursiveValue.OpaqueValue current = codec.read(
                target.getPropertyOrNull(Todo8OpaqueFixtures.PROPERTY_NAME));
        String expandedStart = current.payload().replaceFirst(
                "<stringProp([^>]*)>",
                "<org.apache.jmeter.testelement.property.Todo8ConstructorSideEffectProperty$1>");
        String expanded = expandedStart.replaceFirst(
                "</stringProp>",
                "</org.apache.jmeter.testelement.property.Todo8ConstructorSideEffectProperty>");
        assertThat(expanded).isNotEqualTo(current.payload());
        Todo8ConstructorSideEffectProperty.resetConstructions();

        Throwable failure = catchThrowable(() -> codec.replace(
                target,
                Todo8OpaqueFixtures.PROPERTY_NAME,
                Todo8OpaqueFixtures.withPayload(current, expanded)));

        assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serialized classes");
        assertThat(Todo8ConstructorSideEffectProperty.constructions()).isZero();
        assertThat(Todo8OpaqueFixtures.saveElement(target)).containsExactly(before);
        System.out.println("PRELOAD_CONSTRUCTIONS="
                + Todo8ConstructorSideEffectProperty.constructions());
        System.out.println("PRELOAD_TARGET_SHA256=" + Todo8OpaqueFixtures.sha256(before));
    }

    @Test
    void callerSelectedClassAttributeRejectsBeforeDeserialization() throws Exception {
        assertRejected("class-attribute", value -> Todo8OpaqueFixtures.withPayload(
                value, value.payload().replaceFirst(
                        "<stringProp", "<stringProp class=\"java.lang.ProcessBuilder\"")),
                "serialized classes");
    }

    @Test
    void dtdAndExternalEntityRejectBeforeDeserialization() throws Exception {
        assertRejected("xxe", value -> Todo8OpaqueFixtures.withPayload(
                value, withDtd(value.payload())), "safe XML");
    }

    @Test
    void externalSchemaMetadataRejectsBeforeDeserialization() throws Exception {
        assertRejected("schema", value -> Todo8OpaqueFixtures.withPayload(
                value, value.payload().replaceFirst(
                        "<collectionProp",
                        "<collectionProp xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                                + "xsi:noNamespaceSchemaLocation=\"http://127.0.0.1:9/opaque.xsd\"")),
                "serialized classes");
    }

    @Test
    void excessiveXmlDepthRejectsBeforeDeserialization() throws Exception {
        assertRejected("depth", value -> Todo8OpaqueFixtures.withPayload(
                value, deeplyNested(value.payload(), 129)), "safe XML");
    }

    @Test
    void oversizedXmlRejectsBeforeDeserialization() throws Exception {
        assertRejected("bytes", value -> Todo8OpaqueFixtures.withPayload(
                value, value.payload() + "<!--" + repeat('x', 8 * 1024 * 1024) + "-->"),
                "byte limit");
    }

    @Test
    void objectValueClassExpansionRejectsBeforeAttachment() throws Exception {
        Todo8OpaqueFixtures.verifyObjectValueClassExpansionRejected(
                new OpaquePropertyCodec(Todo8OpaqueFixtures.runtimeContext()));
    }

    @Test
    void malformedXmlRejectsBeforeAttachment() throws Exception {
        assertRejected("malformed-xml", value -> Todo8OpaqueFixtures.withPayload(
                value, "<collectionProp"), "opaque payload");
    }

    private static void assertRejected(
            String probe, UnaryOperator<RecursiveValue.OpaqueValue> mutation, String message)
            throws Exception {
        ResponseAssertion target = Todo8OpaqueFixtures.assertion();
        byte[] before = Todo8OpaqueFixtures.saveElement(target);
        OpaquePropertyCodec codec = new OpaquePropertyCodec(Todo8OpaqueFixtures.runtimeContext());
        RecursiveValue.OpaqueValue current = codec.read(
                target.getPropertyOrNull(Todo8OpaqueFixtures.PROPERTY_NAME));

        Throwable failure = catchThrowable(() -> codec.replace(
                target, Todo8OpaqueFixtures.PROPERTY_NAME, mutation.apply(current)));

        assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
        byte[] after = Todo8OpaqueFixtures.saveElement(target);
        assertThat(after).containsExactly(before);
        System.out.println("REJECTION_SHA256 " + probe + " before="
                + Todo8OpaqueFixtures.sha256(before) + " after="
                + Todo8OpaqueFixtures.sha256(after));
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static String withDtd(String payload) {
        int declarationEnd = payload.indexOf("?>") + 2;
        return payload.substring(0, declarationEnd)
                + "<!DOCTYPE collectionProp [<!ENTITY xxe SYSTEM "
                + "\"file:///etc/passwd\">]>"
                + payload.substring(declarationEnd).replace(
                        Todo8OpaqueFixtures.BEFORE_TEXT, "&xxe;");
    }

    private static String deeplyNested(String payload, int depth) {
        StringBuilder nested = new StringBuilder();
        for (int index = 0; index < depth; index++) {
            nested.append("<stringProp name=\"nested\">");
        }
        nested.append("bounded");
        for (int index = 0; index < depth; index++) {
            nested.append("</stringProp>");
        }
        return payload.replaceFirst(
                "<stringProp([^>]*)>[^<]*</stringProp>",
                java.util.regex.Matcher.quoteReplacement(nested.toString()));
    }
}
