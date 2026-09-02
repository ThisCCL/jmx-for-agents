package io.github.thisccl.j4a.jmx.property;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.TreeMap;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;

public final class OpaquePropertyCodec {
    private final String runtimeFingerprint;

    public OpaquePropertyCodec(RuntimeContext runtimeContext) {
        RuntimeContext required = Objects.requireNonNull(
                runtimeContext, "runtime context is required");
        this.runtimeFingerprint = fingerprint(required.fingerprint());
    }

    public RecursiveValue.OpaqueValue read(JMeterProperty current) throws IOException {
        return OpaqueEnvelope.observe(
                Objects.requireNonNull(current, "current property is required"),
                runtimeFingerprint).value();
    }

    public RecursiveValue.OpaqueValue replace(
            TestElement target,
            String rootPropertyName,
            RecursiveValue.OpaqueValue submitted) throws IOException {
        JMeterProperty replacement = materialize(target, rootPropertyName, submitted);
        target.setProperty(replacement);
        return OpaqueEnvelope.observe(replacement, runtimeFingerprint).value();
    }

    JMeterProperty materialize(
            TestElement target,
            String rootPropertyName,
            RecursiveValue.OpaqueValue submitted) throws IOException {
        Objects.requireNonNull(target, "target element is required");
        requireText(rootPropertyName, "root property name");
        Objects.requireNonNull(submitted, "submitted opaque value is required");

        JMeterProperty current = requireProperty(target, rootPropertyName);
        OpaqueEnvelope observed = OpaqueEnvelope.observe(current, runtimeFingerprint);
        requireBinding(observed.value(), submitted);
        OpaqueXmlPreflight.requireSafe(
                observed.value().payload(), submitted.payload(), rootPropertyName);

        JMeterProperty replacement = loadSubmitted(submitted.payload());
        requireRoot(current, rootPropertyName, replacement);
        OpaqueEnvelope replacementEnvelope = OpaqueEnvelope.observe(
                replacement, runtimeFingerprint);
        observed.skeleton().requireContains(replacementEnvelope.skeleton());

        TestElement candidate = roundTrip(target);
        candidate.setProperty(replacement);
        TestElement verifiedCandidate = roundTrip(candidate);
        JMeterProperty verifiedProperty = requireProperty(
                verifiedCandidate, rootPropertyName);
        requireRoot(current, rootPropertyName, verifiedProperty);
        OpaqueEnvelope verifiedEnvelope = OpaqueEnvelope.observe(
                verifiedProperty, runtimeFingerprint);
        observed.skeleton().requireContains(verifiedEnvelope.skeleton());
        if (!replacementEnvelope.value().baseDigest()
                .equals(verifiedEnvelope.value().baseDigest())) {
            throw new IllegalArgumentException(
                    "opaque replacement changed during full-candidate SaveService round-trip");
        }

        return verifiedProperty;
    }

    private void requireBinding(
            RecursiveValue.OpaqueValue current,
            RecursiveValue.OpaqueValue submitted) {
        if (!current.format().equals(submitted.format())) {
            throw new IllegalArgumentException("opaque format does not match current value");
        }
        if (!current.baseDigest().equals(submitted.baseDigest())) {
            throw new IllegalArgumentException("opaque base digest is stale");
        }
        if (!runtimeFingerprint.equals(submitted.runtimeFingerprint())) {
            throw new IllegalArgumentException("opaque runtime fingerprint does not match");
        }
        if (!current.outerPropertyClass().equals(submitted.outerPropertyClass())) {
            throw new IllegalArgumentException("opaque outer property class does not match");
        }
    }

    private static JMeterProperty loadSubmitted(String payload) throws IOException {
        Object loaded;
        try {
            loaded = OpaqueEnvelope.load(payload.getBytes(StandardCharsets.UTF_8));
        } catch (com.thoughtworks.xstream.XStreamException exception) {
            throw new IllegalArgumentException(
                    "opaque payload is not valid SaveService XML", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "opaque payload cannot be read by SaveService", exception);
        }
        if (!(loaded instanceof JMeterProperty)) {
            throw new IllegalArgumentException(
                    "opaque payload root must remain a JMeterProperty");
        }
        return (JMeterProperty) loaded;
    }

    private static TestElement roundTrip(TestElement source) throws IOException {
        Object loaded = OpaqueEnvelope.load(OpaqueEnvelope.save(source));
        if (!(loaded instanceof TestElement)
                || !source.getClass().equals(loaded.getClass())) {
            throw new IllegalArgumentException(
                    "full-candidate SaveService round-trip changed the target class");
        }
        return (TestElement) loaded;
    }

    private static JMeterProperty requireProperty(
            TestElement target, String rootPropertyName) {
        JMeterProperty property = target.getPropertyOrNull(rootPropertyName);
        if (property == null) {
            throw new IllegalArgumentException(
                    "opaque root property name is not present: " + rootPropertyName);
        }
        if (!rootPropertyName.equals(property.getName())) {
            throw new IllegalArgumentException(
                    "opaque root property name does not match current value");
        }
        return property;
    }

    private static void requireRoot(
            JMeterProperty current,
            String rootPropertyName,
            JMeterProperty replacement) {
        if (!rootPropertyName.equals(replacement.getName())) {
            throw new IllegalArgumentException(
                    "opaque root property name does not match current value");
        }
        if (!current.getClass().equals(replacement.getClass())) {
            throw new IllegalArgumentException(
                    "opaque outer property class does not match current value");
        }
    }

    private static String fingerprint(RuntimeFingerprint fingerprint) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, fingerprint.jmeterHome());
        append(canonical, fingerprint.jmeterVersion());
        for (java.util.Map.Entry<String, String> library
                : new TreeMap<String, String>(fingerprint.librarySha256()).entrySet()) {
            append(canonical, library.getKey());
            append(canonical, library.getValue());
        }
        return "sha256:" + OpaqueEnvelope.sha256(
                canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
    }
}
