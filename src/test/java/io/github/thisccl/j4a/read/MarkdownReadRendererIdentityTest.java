package io.github.thisccl.j4a.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.reference.BoundReferences;
import io.github.thisccl.j4a.reference.ReferenceResolution;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

class MarkdownReadRendererIdentityTest {
    @Test
    void renderUsesActualClassWhenNoSelectedRuntimeRegistryIsSupplied() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("request");
        sampler.setProperty(TestElement.GUI_CLASS,
                "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        ListedHashTree tree = new ListedHashTree();
        tree.add(sampler);

        String markdown = new MarkdownReadRenderer().render(
                new JmxTestPlan(tree), new ReadOptions(false, false));

        assertThat(markdown)
                .contains("component: org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy")
                .doesNotContain("component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui")
                .doesNotContain("component: http.request");
    }

    @Test
    void renderFallsBackToExactRuntimeElementClassWhenGuiClassIsAbsent() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("request");
        ListedHashTree tree = new ListedHashTree();
        tree.add(sampler);

        String markdown = new MarkdownReadRenderer().render(
                new JmxTestPlan(tree), new ReadOptions(false, false));

        assertThat(markdown)
                .contains("component: org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy")
                .doesNotContain("component: http.request");
    }

    @Test
    void renderUsesTheBoundReferenceSpaceForEveryPublishedReference() {
        HTTPSamplerProxy parent = new HTTPSamplerProxy();
        parent.setName("parent");
        HTTPSamplerProxy child = new HTTPSamplerProxy();
        child.setName("child");
        ListedHashTree tree = new ListedHashTree();
        HashTree children = tree.add(parent);
        children.add(child);

        BoundReferences bound = assignedReferences("snapshot-parent", "snapshot-child");

        String markdown = new MarkdownReadRenderer().render(
                new JmxTestPlan(tree), new ReadOptions(false, false), bound);

        assertThat(markdown)
                .contains("ref: snapshot-parent")
                .contains("ref: snapshot-child")
                .contains("locator: snapshot-child")
                .contains("- parent: snapshot-parent")
                .contains("    ref: snapshot-child")
                .contains("    parent: snapshot-parent")
                .doesNotContain("jmx_");
    }

    @Test
    void renderUsesScalarArrayAddressesForPunctuationBearingPropertyNames() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("request");
        sampler.setProperty("literal.dot", "dot");
        sampler.setProperty("literal[bracket]", "bracket");
        sampler.setProperty("literal\\backslash", "backslash");
        ListedHashTree tree = new ListedHashTree();
        tree.add(sampler);

        String markdown = new MarkdownReadRenderer().render(
                new JmxTestPlan(tree), new ReadOptions(true, false));

        assertScalarArrayProperty(markdown, "literal.dot", "[\"literal.dot\"]");
        assertScalarArrayProperty(markdown, "literal[bracket]", "[\"literal[bracket]\"]");
        assertScalarArrayProperty(markdown, "literal\\backslash", "[\"literal\\\\backslash\"]");
    }

    @Test
    void renderKeepsSingleQuoteLiteralInsideScalarArrayMember() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("request");
        sampler.setProperty("can't", "ambiguous");
        ListedHashTree tree = new ListedHashTree();
        tree.add(sampler);

        String markdown = new MarkdownReadRenderer().render(
                new JmxTestPlan(tree), new ReadOptions(true, false));

        assertScalarArrayProperty(markdown, "can't", "[\"can't\"]");
    }

    private static void assertScalarArrayProperty(String markdown, String propertyName, String expectedAddress) {
        assertThat(markdown)
                .contains("- property: " + expectedAddress + "\n")
                .contains(" --property " + expectedAddress + " --value \"...\"")
                .contains("      - property: " + expectedAddress + "\n");
    }

    private static BoundReferences assignedReferences(String parentReference, String childReference) {
        return new BoundReferences() {
            private final Map<String, String> referencesByAddress = new LinkedHashMap<String, String>();

            @Override
            public String expose(String structuralAddress, String componentClass) {
                String reference = referencesByAddress.get(structuralAddress);
                if (reference != null) {
                    return reference;
                }
                reference = referencesByAddress.isEmpty() ? parentReference : childReference;
                referencesByAddress.put(structuralAddress, reference);
                return reference;
            }

            @Override
            public boolean matches(
                    io.github.thisccl.j4a.reference.ResolvedNodeHandle handle,
                    String structuralAddress,
                    String componentClass) {
                return false;
            }

            @Override
            public ReferenceResolution resolve(String publicReference) {
                throw new AssertionError("Markdown rendering must not resolve references");
            }
        };
    }
}
