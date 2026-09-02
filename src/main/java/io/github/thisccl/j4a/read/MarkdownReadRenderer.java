package io.github.thisccl.j4a.read;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.locator.LocatorIndex;
import io.github.thisccl.j4a.locator.StructuralLocator;
import io.github.thisccl.j4a.reference.BoundReferences;
import io.github.thisccl.j4a.validation.ReferenceBindings;
import io.github.thisccl.j4a.validation.RuntimeComponentIdentityResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;

public final class MarkdownReadRenderer {
    private final RuntimeComponentIdentityResolver identityResolver;

    public MarkdownReadRenderer() {
        this(RuntimeComponentIdentityResolver.actualClasses());
    }

    public MarkdownReadRenderer(RuntimeComponentIdentityResolver identityResolver) {
        this.identityResolver = Objects.requireNonNull(
                identityResolver, "identityResolver");
    }

    public String render(JmxTestPlan testPlan, ReadOptions options) {
        return render(testPlan, options, ReferenceBindings.snapshot(testPlan));
    }

    public String render(JmxTestPlan testPlan, ReadOptions options, BoundReferences references) {
        List<JmxComponentNode> roots = JmxComponentNode.fromTree(
                testPlan.tree(), io.github.thisccl.j4a.jmx.JmxSourceLineIndex.empty(),
                identityResolver);
        if (roots.isEmpty()) {
            return "";
        }

        StringBuilder markdown = new StringBuilder();
        for (JmxComponentNode root : roots) {
            LocatorIndex addresses = StructuralLocator.defaultLocator().locate(root);
            appendNodes(markdown, Collections.singletonList(root), null, references, addresses, options, 1);
        }
        return markdown.toString();
    }

    private void appendNodes(
            StringBuilder markdown,
            List<JmxComponentNode> nodes,
            JmxComponentNode parent,
            BoundReferences references,
            LocatorIndex addresses,
            ReadOptions options,
            int depth) {
        for (int index = 0; index < nodes.size(); index++) {
            JmxComponentNode node = nodes.get(index);
            appendNode(markdown, node, parent, nodes, index, references, addresses, options, depth);
            appendNodes(markdown, node.children(), node, references, addresses, options, depth + 1);
        }
    }

    private void appendNode(
            StringBuilder markdown,
            JmxComponentNode node,
            JmxComponentNode parent,
            List<JmxComponentNode> siblings,
            int siblingIndex,
            BoundReferences references,
            LocatorIndex addresses,
            ReadOptions options,
            int depth) {
        if (markdown.length() > 0) {
            markdown.append("\n");
        }

        String ref = expose(references, addresses, node);
        String component = node.runtimeComponentIdentity();
        Placement placement = placement(parent, siblings, siblingIndex, references, addresses);

        markdown.append(repeat("#", Math.max(1, depth))).append(" ")
                .append(node.componentClass()).append(" - ").append(node.displayName()).append("\n");

        List<PropertyEntry> properties = properties(node.element());
        if (!node.enabled() && !options.includeDisabledDetails()) {
            markdown.append("locator: ").append(ref).append("\n")
                    .append("enabled: ").append(node.enabled()).append("\n")
                    .append("\nSummary:\n")
                    .append("- child components: ").append(countDescendants(node)).append("\n")
                    .append("- editable properties hidden: ").append(selectProperties(node, properties, options).size()).append("\n")
                    .append("- rerun with `--include-disabled-details` to show details\n");
            return;
        }

        markdown.append("ref: ").append(ref).append("\n")
                .append("component: ").append(component).append("\n")
                .append("locator: ").append(ref).append("\n")
                .append("enabled: ").append(node.enabled()).append("\n");

        List<PropertyEntry> selectedProperties = selectProperties(node, properties, options);
        appendKeyFields(markdown, node, properties);
        appendEditableProperties(markdown, ref, selectedProperties);
        appendSetCard(markdown, ref, component, selectedProperties);
        appendPlacement(markdown, placement);
        appendMoveCard(markdown, ref, component, placement);
        appendDeleteCard(markdown, ref, component, placement);
    }

    private void appendKeyFields(StringBuilder markdown, JmxComponentNode node, List<PropertyEntry> properties) {
        markdown.append("\nKey fields:\n")
                .append("- name: `").append(escapeBackticks(node.displayName())).append("`\n");
        for (String propertyName : defaultPropertyNames(node.componentClass())) {
            find(properties, propertyName).ifPresent(entry -> markdown.append("- ")
                    .append(keyLabel(propertyName)).append(": `")
                    .append(escapeBackticks(entry.value())).append("`\n"));
        }
    }

    private void appendEditableProperties(StringBuilder markdown, String locator, List<PropertyEntry> properties) {
        markdown.append("\nEditable properties:\n");
        if (properties.isEmpty()) {
            markdown.append("- none\n");
            return;
        }

        for (PropertyEntry property : properties) {
            markdown.append("- property: ").append(property.path()).append("\n")
                    .append("  value: ").append(yamlValue(property)).append("\n")
                    .append("  type: ").append(property.type()).append("\n")
                    .append("  example: `set <file> --locator ").append(locator)
                    .append(" --property ").append(property.path())
                    .append(" --value \"...\" --out <file>`\n");
        }
    }

    private void appendSetCard(StringBuilder markdown, String ref, String component, List<PropertyEntry> properties) {
        if (properties.isEmpty()) {
            return;
        }
        markdown.append("\nSet card:\n")
                .append("```yaml\n")
                .append("- set:\n")
                .append("    ref: ").append(ref).append("\n")
                .append("    component: ").append(component).append("\n")
                .append("    properties:\n");
        for (PropertyEntry property : properties) {
            markdown.append("      - property: ").append(property.path()).append("\n")
                    .append("        value: ").append(yamlValue(property)).append("\n")
                    .append("        type: ").append(property.type()).append("\n");
        }
        markdown.append("```\n");
    }

    private void appendPlacement(StringBuilder markdown, Placement placement) {
        if (!placement.parent().isPresent()) {
            return;
        }
        markdown.append("\nPlacement:\n")
                .append("- parent: ").append(placement.parent().get()).append("\n");
        placement.before().ifPresent(before -> markdown.append("- before: ").append(before).append("\n"));
        placement.after().ifPresent(after -> markdown.append("- after: ").append(after).append("\n"));
        placement.position().ifPresent(position -> markdown.append("- position: ").append(position).append("\n"));
    }

    private void appendMoveCard(StringBuilder markdown, String ref, String component, Placement placement) {
        if (!placement.parent().isPresent()) {
            return;
        }
        markdown.append("\nMove card:\n")
                .append("```yaml\n")
                .append("- move:\n")
                .append("    ref: ").append(ref).append("\n")
                .append("    component: ").append(component).append("\n")
                .append("    parent: ").append(placement.parent().get()).append("\n");
        placement.before().ifPresent(before -> markdown.append("    before: ").append(before).append("\n"));
        placement.after().ifPresent(after -> markdown.append("    after: ").append(after).append("\n"));
        placement.position().ifPresent(position -> markdown.append("    position: ").append(position).append("\n"));
        markdown.append("```\n");
    }

    private void appendDeleteCard(StringBuilder markdown, String ref, String component, Placement placement) {
        if (!placement.parent().isPresent()) {
            return;
        }
        markdown.append("\nDelete card:\n")
                .append("```yaml\n")
                .append("- delete:\n")
                .append("    ref: ").append(ref).append("\n")
                .append("    component: ").append(component).append("\n")
                .append("```\n");
    }

    private List<PropertyEntry> selectProperties(JmxComponentNode node, List<PropertyEntry> properties, ReadOptions options) {
        if (options.verbose()) {
            return properties;
        }

        Set<String> defaultNames = defaultPropertyNames(node.componentClass());
        return properties.stream()
                .filter(property -> defaultNames.contains(property.rawName()))
                .collect(Collectors.toList());
    }

    private static List<PropertyEntry> properties(TestElement element) {
        List<PropertyEntry> properties = new ArrayList<>();
        PropertyIterator iterator = element.propertyIterator();
        while (iterator.hasNext()) {
            collectProperty(iterator.next(), "", Collections.<Object>emptyList(), properties);
        }
        return Collections.unmodifiableList(properties);
    }

    private static void collectProperty(
            JMeterProperty property,
            String rawPrefix,
            List<Object> pathPrefix,
            List<PropertyEntry> properties) {
        String propertyName = property.getName();
        String rawName = rawPrefix.length() == 0 ? propertyName : rawPrefix + "." + propertyName;
        List<Object> path = append(pathPrefix, propertyName);
        if (property instanceof TestElementProperty) {
            TestElementProperty testElementProperty = (TestElementProperty) property;
            PropertyIterator iterator = testElementProperty.iterator();
            while (iterator.hasNext()) {
                collectProperty(iterator.next(), rawName, path, properties);
            }
            return;
        }
        if (property instanceof MultiProperty) {
            MultiProperty multiProperty = (MultiProperty) property;
            int index = 0;
            for (JMeterProperty child : multiProperty) {
                collectProperty(child, rawName + "[" + index + "]", append(path, Integer.valueOf(index)), properties);
                index++;
            }
            return;
        }
        properties.add(new PropertyEntry(rawName, jsonArray(path), property.getStringValue(), valueType(property)));
    }

    private static List<Object> append(List<Object> prefix, Object segment) {
        ArrayList<Object> result = new ArrayList<Object>(prefix.size() + 1);
        result.addAll(prefix);
        result.add(segment);
        return result;
    }

    private static String jsonArray(List<Object> segments) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < segments.size(); index++) {
            if (index > 0) {
                result.append(',');
            }
            Object segment = segments.get(index);
            if (segment instanceof Integer) {
                result.append(segment);
            } else {
                appendJsonString(result, (String) segment);
            }
        }
        return result.append(']').toString();
    }

    private static void appendJsonString(StringBuilder result, String value) {
        result.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                case '\\':
                    result.append('\\').append(character);
                    break;
                case '\b':
                    result.append("\\b");
                    break;
                case '\f':
                    result.append("\\f");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", Integer.valueOf(character)));
                    } else {
                        result.append(character);
                    }
            }
        }
        result.append('"');
    }

    private static String valueType(JMeterProperty property) {
        Object value = property.getObjectValue();
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Integer) {
            return "int";
        }
        if (value instanceof Long) {
            return "long";
        }
        if (value instanceof Float || value instanceof Double) {
            return "double";
        }
        return "string";
    }

    private static Placement placement(
            JmxComponentNode parent,
            List<JmxComponentNode> siblings,
            int siblingIndex,
            BoundReferences references,
            LocatorIndex addresses) {
        if (parent == null) {
            return new Placement(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
        Optional<String> before = siblingIndex + 1 < siblings.size()
                ? Optional.of(expose(references, addresses, siblings.get(siblingIndex + 1)))
                : Optional.empty();
        Optional<String> after = siblingIndex > 0
                ? Optional.of(expose(references, addresses, siblings.get(siblingIndex - 1)))
                : Optional.empty();
        return new Placement(
                Optional.of(expose(references, addresses, parent)),
                before,
                after,
                Optional.of(position(siblings.size(), siblingIndex)));
    }

    private static String expose(
            BoundReferences references, LocatorIndex addresses, JmxComponentNode node) {
        return references.expose(
                addresses.locatorFor(node), node.element().getClass().getName());
    }

    private static String position(int siblingCount, int siblingIndex) {
        if (siblingIndex == 0 && siblingCount > 1) {
            return "first";
        }
        if (siblingIndex == siblingCount - 1) {
            return "last";
        }
        return Integer.toString(siblingIndex);
    }

    private static Set<String> defaultPropertyNames(String componentClass) {
        Set<String> names = new LinkedHashSet<>();
        switch (componentClass) {
            case "TestPlan":
                names.add("TestPlan.comments");
                break;
            case "ThreadGroup":
                names.add("ThreadGroup.on_sample_error");
                names.add("ThreadGroup.num_threads");
                names.add("ThreadGroup.ramp_time");
                break;
            case "HTTPSamplerProxy":
                names.add("HTTPSampler.domain");
                names.add("HTTPSampler.path");
                names.add("HTTPSampler.method");
                break;
            default:
                break;
        }
        return names;
    }

    private static Optional<PropertyEntry> find(List<PropertyEntry> properties, String rawName) {
        return properties.stream().filter(property -> rawName.equals(property.rawName())).findFirst();
    }

    private static String keyLabel(String propertyName) {
        if ("ThreadGroup.num_threads".equals(propertyName)) {
            return "threads";
        }
        int dot = propertyName.lastIndexOf('.');
        String label = dot >= 0 ? propertyName.substring(dot + 1) : propertyName;
        return label.replace('_', ' ');
    }

    private static int countDescendants(JmxComponentNode node) {
        int count = node.children().size();
        for (JmxComponentNode child : node.children()) {
            count += countDescendants(child);
        }
        return count;
    }

    private static String escapeBackticks(String value) {
        return value.replace("`", "\\`");
    }

    private static String yamlValue(PropertyEntry property) {
        if (!"string".equals(property.type())) {
            return property.value();
        }
        if (property.value().isEmpty()) {
            return "\"\"";
        }
        if (property.value().matches("(?i:true|false|null|~)") || property.value().matches("[-+]?[0-9]+(\\.[0-9]+)?")) {
            return quoteYamlString(property.value());
        }
        if (property.value().matches("[A-Za-z0-9_./:-]+")) {
            return property.value();
        }
        return quoteYamlString(property.value());
    }

    private static String quoteYamlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static final class PropertyEntry {
        private final String rawName;
        private final String path;
        private final String value;
        private final String type;

        private PropertyEntry(String rawName, String path, String value, String type) {
            this.rawName = rawName;
            this.path = path;
            this.value = value;
            this.type = type;
        }

        private String rawName() {
            return rawName;
        }

        private String path() {
            return path;
        }

        private String value() {
            return value;
        }

        private String type() {
            return type;
        }
    }

    private static final class Placement {
        private final Optional<String> parent;
        private final Optional<String> before;
        private final Optional<String> after;
        private final Optional<String> position;

        private Placement(Optional<String> parent, Optional<String> before, Optional<String> after, Optional<String> position) {
            this.parent = parent;
            this.before = before;
            this.after = after;
            this.position = position;
        }

        private Optional<String> parent() {
            return parent;
        }

        private Optional<String> before() {
            return before;
        }

        private Optional<String> after() {
            return after;
        }

        private Optional<String> position() {
            return position;
        }
    }
}
