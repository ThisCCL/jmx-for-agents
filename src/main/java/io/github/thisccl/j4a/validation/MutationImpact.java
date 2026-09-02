package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.property.DefaultJMeterPropertyGraph;
import io.github.thisccl.j4a.jmx.property.MutationReceipt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;

final class MutationImpact {
    private final List<AffectedElement> affectedElements;
    private final DefaultJMeterPropertyGraph propertyGraph;
    private final RuntimeComponentIdentityResolver identityResolver;

    MutationImpact(List<AffectedElement> affectedElements) {
        this(affectedElements, null, RuntimeComponentIdentityResolver.actualClasses());
    }

    private MutationImpact(
            List<AffectedElement> affectedElements,
            DefaultJMeterPropertyGraph propertyGraph,
            RuntimeComponentIdentityResolver identityResolver) {
        this.affectedElements = Collections.unmodifiableList(new ArrayList<>(affectedElements));
        this.propertyGraph = propertyGraph;
        this.identityResolver = identityResolver;
    }

    static MutationImpact none() {
        return new MutationImpact(Collections.emptyList());
    }

    static MutationImpact from(JmxTestPlan plan, List<TestElement> identities) {
        return from(
                plan, identities, ignored -> null,
                RuntimeComponentIdentityResolver.actualClasses());
    }

    static MutationImpact fromSelectedRuntime(
            JmxTestPlan plan, List<TestElement> identities) {
        return from(
                plan, identities, ignored -> null,
                RuntimeComponentIdentityResolver.selected());
    }

    static MutationImpact from(
            JmxTestPlan plan, List<TestElement> identities,
            java.util.function.Function<TestElement, List<String>> projectedProperties) {
        return from(
                plan, identities, projectedProperties,
                RuntimeComponentIdentityResolver.actualClasses());
    }

    private static MutationImpact from(
            JmxTestPlan plan,
            List<TestElement> identities,
            java.util.function.Function<TestElement, List<String>> projectedProperties,
            RuntimeComponentIdentityResolver identityResolver) {
        Map<List<Integer>, AffectedElement> unique = new LinkedHashMap<>();
        for (TestElement identity : identities) {
            List<Integer> address = LocalWorkerTreeAddress.find(plan, identity);
            if (address != null) {
                List<String> properties = projectedProperties.apply(identity);
                unique.put(address, new AffectedElement(
                        address, properties,
                        ElementSignature.of(identity, properties, identityResolver)));
            }
        }
        return new MutationImpact(new ArrayList<>(unique.values()), null, identityResolver);
    }

    static MutationImpact fromGraph(
            JmxTestPlan plan,
            List<TestElement> identities,
            DefaultJMeterPropertyGraph propertyGraph,
            java.util.function.Function<TestElement, MutationReceipt> receipts) {
        RuntimeComponentIdentityResolver identityResolver =
                RuntimeComponentIdentityResolver.selected();
        Map<List<Integer>, AffectedElement> unique = new LinkedHashMap<>();
        for (TestElement identity : identities) {
            List<Integer> address = LocalWorkerTreeAddress.find(plan, identity);
            if (address != null) {
                MutationReceipt receipt = receipts.apply(identity);
                List<String> identityOnly = Collections.emptyList();
                unique.put(address, new AffectedElement(
                        address,
                        identityOnly,
                        ElementSignature.of(identity, identityOnly, identityResolver),
                        receipt));
            }
        }
        return new MutationImpact(
                new ArrayList<>(unique.values()), propertyGraph, identityResolver);
    }

    List<AffectedElement> affectedElements() {
        return affectedElements;
    }

    DefaultJMeterPropertyGraph propertyGraph() {
        return propertyGraph;
    }

    RuntimeComponentIdentityResolver identityResolver() {
        return identityResolver;
    }

    static final class AffectedElement {
        private final List<Integer> address;
        private final List<String> projectedProperties;
        private final ElementSignature signature;
        private final MutationReceipt receipt;

        AffectedElement(List<Integer> address, List<String> projectedProperties, ElementSignature signature) {
            this(address, projectedProperties, signature, null);
        }

        AffectedElement(
                List<Integer> address,
                List<String> projectedProperties,
                ElementSignature signature,
                MutationReceipt receipt) {
            this.address = Collections.unmodifiableList(new ArrayList<>(address));
            this.projectedProperties = projectedProperties == null ? null
                    : Collections.unmodifiableList(new ArrayList<>(projectedProperties));
            this.signature = signature;
            this.receipt = receipt;
        }

        AffectedElement(List<Integer> address, ElementSignature signature) {
            this(address, null, signature);
        }

        List<Integer> address() {
            return address;
        }

        ElementSignature signature() {
            return signature;
        }

        List<String> projectedProperties() {
            return projectedProperties;
        }

        MutationReceipt receipt() {
            return receipt;
        }
    }

    static final class ElementSignature {
        private final String runtimeClass;
        private final String componentIdentity;
        private final String testClass;
        private final String guiClass;
        private final List<String> properties;

        private ElementSignature(
                String runtimeClass,
                String componentIdentity,
                String testClass,
                String guiClass,
                List<String> properties) {
            this.runtimeClass = runtimeClass;
            this.componentIdentity = componentIdentity;
            this.testClass = testClass;
            this.guiClass = guiClass;
            this.properties = properties;
        }

        static ElementSignature of(TestElement element) {
            return of(element, null, RuntimeComponentIdentityResolver.actualClasses());
        }

        static ElementSignature of(TestElement element, List<String> projectedProperties) {
            return of(element, projectedProperties,
                    RuntimeComponentIdentityResolver.actualClasses());
        }

        static ElementSignature of(
                TestElement element,
                List<String> projectedProperties,
                RuntimeComponentIdentityResolver identityResolver) {
            List<String> values = new ArrayList<>();
            Iterator<JMeterProperty> properties = element.propertyIterator();
            while (properties.hasNext()) {
                JMeterProperty property = properties.next();
                String name = property.getName();
                if (TestElement.TEST_CLASS.equals(name) || TestElement.GUI_CLASS.equals(name)
                        || TestElement.ENABLED.equals(name)) {
                    continue;
                }
                if (projectedProperties == null
                        && name.toLowerCase(java.util.Locale.ROOT).contains("ref")) {
                    continue;
                }
                if (projectedProperties != null && !projectedProperties.contains(name)
                        && !TestElement.NAME.equals(name)) {
                    continue;
                }
                values.add(name + "\u0000" + property.getClass().getName() + "\u0000" + stableValue(property));
            }
            values.add(TestElement.ENABLED + "\u0000boolean\u0000" + element.isEnabled());
            Collections.sort(values);
            return new ElementSignature(
                    element.getClass().getName(), identityResolver.resolve(element),
                    element.getPropertyAsString(TestElement.TEST_CLASS),
                    element.getPropertyAsString(TestElement.GUI_CLASS),
                    Collections.unmodifiableList(values));
        }

        private static String stableValue(JMeterProperty property) {
            Object value = property.getObjectValue();
            if (!(value instanceof TestElement)) {
                return property.getStringValue();
            }
            List<String> nested = new ArrayList<>();
            Iterator<JMeterProperty> iterator = ((TestElement) value).propertyIterator();
            while (iterator.hasNext()) {
                JMeterProperty child = iterator.next();
                if (TestElement.ENABLED.equals(child.getName())) {
                    continue;
                }
                String childValue = stableValue(child);
                nested.add(child.getName() + "=" + child.getClass().getName() + "=" + childValue);
            }
            Collections.sort(nested);
            return value.getClass().getName() + nested;
        }

        boolean matches(ElementSignature other) {
            return runtimeClass.equals(other.runtimeClass)
                    && componentIdentity.equals(other.componentIdentity)
                    && metadataMatches(testClass, other.testClass)
                    && metadataMatches(guiClass, other.guiClass) && properties.equals(other.properties);
        }

        private static boolean metadataMatches(String left, String right) {
            if (left.equals(right)) {
                return true;
            }
            if (left.indexOf('.') >= 0 && right.indexOf('.') >= 0) {
                return false;
            }
            return simpleName(left).equals(simpleName(right));
        }

        private static String simpleName(String value) {
            int separator = value.lastIndexOf('.');
            return separator < 0 ? value : value.substring(separator + 1);
        }

        String componentIdentity() {
            return componentIdentity;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ElementSignature)) {
                return false;
            }
            ElementSignature that = (ElementSignature) other;
            return runtimeClass.equals(that.runtimeClass)
                    && componentIdentity.equals(that.componentIdentity)
                    && testClass.equals(that.testClass)
                    && guiClass.equals(that.guiClass) && properties.equals(that.properties);
        }

        @Override
        public int hashCode() {
            return Objects.hash(runtimeClass, componentIdentity, testClass, guiClass, properties);
        }

        @Override
        public String toString() {
            return "ElementSignature{" + runtimeClass + ", component=" + componentIdentity
                    + ", testClass=" + testClass + ", guiClass=" + guiClass
                    + ", properties=" + properties + "}";
        }
    }
}
