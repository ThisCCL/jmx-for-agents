package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.ObjectProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.util.JMeterUtils;

final class Todo8OpaqueFixtures {
    static final String PROPERTY_NAME = "Asserion.test_strings";
    static final String BEFORE_TEXT = "login failed";
    static final String AFTER_TEXT = "login denied";
    static final String UNSEEN_NAME = "qa.unseen.property";
    static final String UNSEEN_VALUE = "populated-unseen-value";
    static final String OBJECT_NAME = "qa.opaque.object";
    static final String FIXTURE_SHA256 =
            "833bc17ee8818ada2bcec1366180ff781c9e6591212e70883961d43d05e3f6dd";
    static final String UNSEEN_FIXTURE_SHA256 =
            "74e999288309388ac58f75e34a7308a78659d2d30d484b0f1bf43e36fd66b28d";

    private Todo8OpaqueFixtures() {
    }

    static void initializeSaveService() {
        new SaveServiceJmxLoader(jmeterHome());
    }

    static Path jmeterHome() {
        return io.github.thisccl.j4a.TestJMeterRuntime.home();
    }

    static Path fixture(String name) {
        return Paths.get("src", "test", "resources", "property-graph-conformance", name)
                .toAbsolutePath().normalize();
    }

    static ResponseAssertion assertion() {
        JmxTestPlan plan = new SaveServiceJmxLoader(jmeterHome()).load(fixture("response-assertion.jmx"));
        return plan.depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Response Assertion"));
    }

    static RuntimeContext runtimeContext() throws IOException {
        Path core = jmeterHome().resolve("lib/ext/ApacheJMeter_core.jar");
        Map<String, String> libraries = new LinkedHashMap<String, String>();
        libraries.put("lib/ext/ApacheJMeter_core.jar", sha256(Files.readAllBytes(core)));
        return new RuntimeContext("todo8-selected-worker", new RuntimeFingerprint(
                jmeterHome().toString(), JMeterUtils.getJMeterVersion(), libraries));
    }

    static byte[] saveElement(Object value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveElement(value, output);
        return output.toByteArray();
    }

    static Object loadElement(byte[] payload) throws IOException {
        return SaveService.loadElement(new ByteArrayInputStream(payload));
    }

    static String text(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static List<String> values(JMeterProperty property) {
        List<String> values = new ArrayList<String>();
        PropertyIterator iterator = ((MultiProperty) property).iterator();
        while (iterator.hasNext()) {
            values.add(iterator.next().getStringValue());
        }
        return values;
    }

    static RecursiveValue.OpaqueValue withPayload(
            RecursiveValue.OpaqueValue source, String payload) {
        return new RecursiveValue.OpaqueValue(
                source.format(),
                source.baseDigest(),
                source.outerPropertyClass(),
                source.runtimeFingerprint(),
                payload);
    }

    static RecursiveValue.OpaqueValue withDigest(
            RecursiveValue.OpaqueValue source, String digest) {
        return new RecursiveValue.OpaqueValue(
                source.format(),
                digest,
                source.outerPropertyClass(),
                source.runtimeFingerprint(),
                source.payload());
    }

    static RecursiveValue.OpaqueValue withFingerprint(
            RecursiveValue.OpaqueValue source, String fingerprint) {
        return new RecursiveValue.OpaqueValue(
                source.format(),
                source.baseDigest(),
                source.outerPropertyClass(),
                fingerprint,
                source.payload());
    }

    static RecursiveValue.OpaqueValue withOuterClass(
            RecursiveValue.OpaqueValue source, String outerClass) {
        return new RecursiveValue.OpaqueValue(
                source.format(),
                source.baseDigest(),
                outerClass,
                source.runtimeFingerprint(),
                source.payload());
    }

    static String renamedRoot(String payload) {
        return payload.replace(
                "name=\"Asserion.test_strings\"",
                "name=\"Asserion.renamed\"");
    }

    static String expandedNestedClass(String payload) {
        return payload.replaceFirst(
                "<stringProp([^>]*)>[^<]*</stringProp>",
                "<intProp$1>7</intProp>");
    }

    static void verifyObjectValueClassExpansionRejected(OpaquePropertyCodec codec)
            throws Exception {
        ConfigTestElement target = new ConfigTestElement();
        target.setProperty(new ObjectProperty(OBJECT_NAME, "observed-object"));
        byte[] before = saveElement(target);
        RecursiveValue.OpaqueValue current = codec.read(target.getPropertyOrNull(OBJECT_NAME));
        String expanded = current.payload().replace(
                "<value class=\"java.lang.String\">observed-object</value>",
                "<value class=\"java.lang.Integer\">7</value>");
        if (expanded.equals(current.payload())) {
            throw new AssertionError("ObjectProperty value-class fixture did not mutate");
        }
        IllegalArgumentException failure = null;
        try {
            codec.replace(target, OBJECT_NAME, withPayload(current, expanded));
        } catch (IllegalArgumentException exception) {
            failure = exception;
        }
        if (failure == null || !failure.getMessage().contains("serialized classes")) {
            throw new AssertionError("ObjectProperty value-class expansion was not rejected");
        }
        if (!java.util.Arrays.equals(before, saveElement(target))) {
            throw new AssertionError("ObjectProperty rejection changed target bytes");
        }
    }

    static List<String> classSkeleton(JMeterProperty root) {
        List<String> classes = new ArrayList<String>();
        collectProperty(root, classes, new IdentityHashMap<Object, Boolean>());
        return classes;
    }

    private static void collectProperty(
            JMeterProperty property, List<String> classes, IdentityHashMap<Object, Boolean> seen) {
        if (seen.put(property, Boolean.TRUE) != null) {
            return;
        }
        classes.add(property.getClass().getName());
        if (property instanceof MultiProperty) {
            PropertyIterator children = ((MultiProperty) property).iterator();
            while (children.hasNext()) {
                collectProperty(children.next(), classes, seen);
            }
        }
        Object value = property.getObjectValue();
        if (property instanceof TestElementProperty && value instanceof TestElement) {
            collectElement((TestElement) value, classes, seen);
        }
    }

    private static void collectElement(
            TestElement element, List<String> classes, IdentityHashMap<Object, Boolean> seen) {
        if (seen.put(element, Boolean.TRUE) != null) {
            return;
        }
        classes.add(element.getClass().getName());
        PropertyIterator properties = element.propertyIterator();
        while (properties.hasNext()) {
            collectProperty(properties.next(), classes, seen);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(String.format("%02x", item & 0xff));
        }
        return value.toString();
    }
}
