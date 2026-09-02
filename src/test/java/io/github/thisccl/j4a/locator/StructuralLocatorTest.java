package io.github.thisccl.j4a.locator;


import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuralLocatorTest {
    @Test
    void propertyEditsDoNotChangeLocator() {
        MutableNode sampler = new MutableNode("HTTPSamplerProxy")
                .named("Login request")
                .enabled(true)
                .domain("example.com")
                .path("/login")
                .header("Accept", "application/json")
                .value("before");
        MutableNode root = new MutableNode("TestPlan").child(sampler);

        StructuralLocator locator = StructuralLocator.defaultLocator();

        String before = locator.locate(root).locatorFor(sampler);

        sampler.named("Renamed request")
                .enabled(false)
                .domain("api.example.com")
                .path("/changed")
                .header("Accept", "text/plain")
                .value("after");

        String after = locator.locate(root).locatorFor(sampler);

        assertThat(after).isEqualTo(before);
    }

    @Test
    void siblingReorderChangesLocator() {
        MutableNode first = new MutableNode("HTTPSamplerProxy").named("First");
        MutableNode second = new MutableNode("HTTPSamplerProxy").named("Second");
        MutableNode root = new MutableNode("TestPlan").child(first).child(second);

        StructuralLocator locator = StructuralLocator.defaultLocator();

        String firstLocator = locator.locate(root).locatorFor(first);

        root.children.clear();
        root.child(second).child(first);

        String reorderedFirstLocator = locator.locate(root).locatorFor(first);

        assertThat(reorderedFirstLocator).isNotEqualTo(firstLocator);
    }

    @Test
    void hierarchyChangeChangesLocator() {
        MutableNode request = new MutableNode("HTTPSamplerProxy").named("Request");
        MutableNode leftGroup = new MutableNode("ThreadGroup").child(request);
        MutableNode root = new MutableNode("TestPlan").child(leftGroup);

        StructuralLocator locator = StructuralLocator.defaultLocator();

        String original = locator.locate(root).locatorFor(request);

        leftGroup.children.clear();
        MutableNode rightGroup = new MutableNode("TransactionController").child(request);
        root.children.clear();
        root.child(rightGroup);

        String moved = locator.locate(root).locatorFor(request);

        assertThat(moved).isNotEqualTo(original);
    }

    @Test
    void duplicateDisplayNamesAreHandled() {
        MutableNode first = new MutableNode("HTTPSamplerProxy").named("Duplicate name");
        MutableNode second = new MutableNode("HTTPSamplerProxy").named("Duplicate name");
        MutableNode root = new MutableNode("TestPlan").child(first).child(second);

        LocatorIndex index = StructuralLocator.defaultLocator().locate(root);

        assertThat(index.locatorFor(first)).isNotEqualTo(index.locatorFor(second));
        assertThat(index.warnings()).isEmpty();
    }

    @Test
    void forcedHashCollisionAddsSuffixAndRejectsAmbiguousBaseLocator() {
        MutableNode first = new MutableNode("HTTPSamplerProxy").named("First");
        MutableNode second = new MutableNode("HTTPSamplerProxy").named("Second");
        MutableNode root = new MutableNode("TestPlan").child(first).child(second);

        StructuralLocator locator = StructuralLocator.withHasher(path -> path.contains("HTTPSamplerProxy") ? "abcdef123456" : "000000000001");
        LocatorIndex index = locator.locate(root);

        assertThat(index.locatorFor(first)).isEqualTo("jmx_abcdef123456");
        assertThat(index.locatorFor(second)).isEqualTo("jmx_abcdef123456_2");
        assertThat(index.warnings()).hasSize(1);
        assertThat(index.warnings().get(0)).contains("collision").contains("jmx_abcdef123456");

        assertThatThrownBy(() -> index.requireUnique("jmx_abcdef123456"))
                .isInstanceOf(AmbiguousLocatorException.class)
                .hasMessageContaining("jmx_abcdef123456");
        assertThat(index.requireUnique("jmx_abcdef123456_2")).isSameAs(second);
    }

    private static final class MutableNode implements LocatorNode {
        private final String componentClass;
        private final List<MutableNode> children = new ArrayList<>();
        private String name = "";
        private boolean enabled = true;
        private String domain = "";
        private String path = "";
        private String headerName = "";
        private String headerValue = "";
        private String value = "";

        private MutableNode(String componentClass) {
            this.componentClass = componentClass;
        }

        private MutableNode named(String name) {
            this.name = name;
            return this;
        }

        private MutableNode enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        private MutableNode domain(String domain) {
            this.domain = domain;
            return this;
        }

        private MutableNode path(String path) {
            this.path = path;
            return this;
        }

        private MutableNode header(String headerName, String headerValue) {
            this.headerName = headerName;
            this.headerValue = headerValue;
            return this;
        }

        private MutableNode value(String value) {
            this.value = value;
            return this;
        }

        private MutableNode child(MutableNode child) {
            children.add(child);
            return this;
        }

        @Override
        public String componentClass() {
            return componentClass;
        }

        @Override
        public List<? extends LocatorNode> children() {
            return children;
        }

        @Override
        public String displayName() {
            return name;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String samplerDomain() {
            return domain;
        }

        @Override
        public String samplerPath() {
            return path;
        }

        @Override
        public List<LocatorHeader> headers() {
            return Arrays.asList(new LocatorHeader(headerName, headerValue));
        }

        @Override
        public String value() {
            return value;
        }
    }
}
