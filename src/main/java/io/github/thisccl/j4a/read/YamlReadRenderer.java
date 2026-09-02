package io.github.thisccl.j4a.read;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.JmxSourceRange;
import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import io.github.thisccl.j4a.jmx.property.RuntimeFingerprint;
import io.github.thisccl.j4a.jmx.property.RuntimeStructuredRowResolver;
import io.github.thisccl.j4a.locator.LocatorNotFoundException;
import io.github.thisccl.j4a.locator.LocatorIndex;
import io.github.thisccl.j4a.locator.StructuralLocator;
import io.github.thisccl.j4a.reference.BoundReferences;
import io.github.thisccl.j4a.reference.ReferenceResolution;
import io.github.thisccl.j4a.validation.ReferenceBindings;
import io.github.thisccl.j4a.validation.RuntimeComponentIdentityResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

public final class YamlReadRenderer {
    private final ReadPropertyExtractor properties = new ReadPropertyExtractor();
    private final RuntimeComponentIdentityResolver identityResolver;

    public YamlReadRenderer() {
        this.identityResolver = null;
    }

    public YamlReadRenderer(RuntimeComponentIdentityResolver identityResolver) {
        this.identityResolver = Objects.requireNonNull(
                identityResolver, "identityResolver");
    }

    public String render(JmxTestPlan testPlan, ReadOptions options) {
        return render(testPlan, options, ReferenceBindings.snapshot(testPlan), inProcessRuntime());
    }

    public String render(JmxTestPlan testPlan, ReadOptions options, RuntimeContext runtimeContext) {
        return render(testPlan, options, ReferenceBindings.snapshot(testPlan), runtimeContext);
    }

    public String render(JmxTestPlan testPlan, ReadOptions options, BoundReferences references) {
        return render(testPlan, options, references, inProcessRuntime());
    }

    public String render(
            JmxTestPlan testPlan,
            ReadOptions options,
            BoundReferences references,
            RuntimeContext runtimeContext) {
        return render(testPlan, options, references, runtimeContext,
                RuntimeStructuredRowResolver.none());
    }

    public String render(
            JmxTestPlan testPlan,
            ReadOptions options,
            BoundReferences references,
            RuntimeContext runtimeContext,
            RuntimeStructuredRowResolver rowResolver) {
        List<JmxComponentNode> roots = JmxComponentNode.fromTree(
                testPlan.tree(), testPlan.sourceLineIndex(), identityResolver(runtimeContext));
        if (roots.isEmpty()) {
            return "";
        }

        JmxComponentNode root = roots.get(0);
        LocatorIndex addresses = StructuralLocator.defaultLocator().locate(root);
        Map<String, Object> document = options.ref() == null
                ? renderRoot(root, references, addresses, options, runtimeContext, rowResolver)
                : renderFocus(root, references, addresses, options, runtimeContext, rowResolver);
        return yaml().dump(document);
    }

    private Map<String, Object> renderRoot(
            JmxComponentNode root,
            BoundReferences references,
            LocatorIndex addresses,
            ReadOptions options,
            RuntimeContext runtimeContext,
            RuntimeStructuredRowResolver rowResolver) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("root", renderNode(
                root, references, addresses, options.depth(), options,
                propertiesMode(options, false), true, runtimeContext, rowResolver));
        return document;
    }

    private Map<String, Object> renderFocus(
            JmxComponentNode root,
            BoundReferences references,
            LocatorIndex addresses,
            ReadOptions options,
            RuntimeContext runtimeContext,
            RuntimeStructuredRowResolver rowResolver) {
        JmxComponentNode focus = focusNode(root, references, addresses, options.ref());
        List<JmxComponentNode> path = pathTo(root, focus);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("path", path.stream()
                .map(node -> summary(node, references, addresses))
                .collect(Collectors.toList()));
        document.put("focus", renderNode(
                focus, references, addresses, focusDepth(options), options,
                propertiesMode(options, true), false, runtimeContext, rowResolver));
        return document;
    }

    private Map<String, Object> renderNode(
            JmxComponentNode node,
            BoundReferences references,
            LocatorIndex addresses,
            Integer remainingDepth,
            ReadOptions options,
            ReadOptions.PropertyMode propertyMode,
            boolean cascadeProperties,
            RuntimeContext runtimeContext,
            RuntimeStructuredRowResolver rowResolver) {
        Map<String, Object> output = summary(node, references, addresses);
        boolean detailsAllowed = node.enabled() || options.includeDisabledDetails();
        if (detailsAllowed) {
            List<Map<String, Object>> selectedProperties = selectedProperties(
                    node, propertyMode, options, runtimeContext, rowResolver);
            if (!selectedProperties.isEmpty()) {
                output.put("properties", selectedProperties);
            }
        }

        if (node.children().isEmpty()) {
            if (!detailsAllowed) {
                output.put("child_count", 0);
            }
            return output;
        }

        if (remainingDepth != null && remainingDepth <= 0) {
            output.put("child_count", node.children().size());
            output.put("children_omitted", true);
            return output;
        }

        Integer nextDepth = remainingDepth == null ? null : remainingDepth - 1;
        ReadOptions.PropertyMode childPropertyMode = cascadeProperties ? propertyMode : ReadOptions.PropertyMode.NONE;
        output.put("children", node.children().stream()
                .map(child -> renderNode(
                        child, references, addresses, nextDepth, options,
                        childPropertyMode, cascadeProperties, runtimeContext, rowResolver))
                .collect(Collectors.toList()));
        return output;
    }

    private Map<String, Object> summary(
            JmxComponentNode node, BoundReferences references, LocatorIndex addresses) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ref", references.expose(
                addresses.locatorFor(node), node.element().getClass().getName()));
        output.put("component", node.runtimeComponentIdentity());
        output.put("name", node.displayName());
        output.put("enabled", node.enabled());
        node.sourceRange().ifPresent(sourceRange -> output.put("source", sourceRange(sourceRange)));
        return output;
    }

    private static JmxComponentNode focusNode(
            JmxComponentNode root,
            BoundReferences references,
            LocatorIndex addresses,
            String publicReference) {
        ReferenceResolution resolution = references.resolve(publicReference);
        if (resolution.status() != ReferenceResolution.Status.RESOLVED || !resolution.handle().isPresent()) {
            throw new LocatorNotFoundException(publicReference);
        }
        JmxComponentNode focus = findNode(root, references, addresses, resolution.handle().get());
        if (focus == null) {
            throw new LocatorNotFoundException(publicReference);
        }
        return focus;
    }

    private static JmxComponentNode findNode(
            JmxComponentNode node,
            BoundReferences references,
            LocatorIndex addresses,
            io.github.thisccl.j4a.reference.ResolvedNodeHandle handle) {
        if (references.matches(
                handle, addresses.locatorFor(node), node.element().getClass().getName())) {
            return node;
        }
        for (JmxComponentNode child : node.children()) {
            JmxComponentNode match = findNode(child, references, addresses, handle);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static Map<String, Object> sourceRange(JmxSourceRange sourceRange) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("start_line", sourceRange.startLine());
        output.put("end_line", sourceRange.endLine());
        return output;
    }

    private List<Map<String, Object>> selectedProperties(
            JmxComponentNode node,
            ReadOptions.PropertyMode mode,
            ReadOptions options,
            RuntimeContext runtimeContext,
            RuntimeStructuredRowResolver rowResolver) {
        if (mode == ReadOptions.PropertyMode.NONE) {
            return Collections.emptyList();
        }
        String componentKind = node.runtimeComponentIdentity();
        Set<String> keyNames = ReadComponentKinds.keyPropertyNames(componentKind);
        return properties.properties(node, runtimeContext, rowResolver).stream()
                .filter(property -> includes(property, mode, keyNames))
                .map(YamlReadRenderer::propertyMap)
                .collect(Collectors.toList());
    }

    private static boolean includes(
            ReadProperty property, ReadOptions.PropertyMode mode, Set<String> keyNames) {
        if (mode == ReadOptions.PropertyMode.ALL) {
            return true;
        }
        if (mode == ReadOptions.PropertyMode.WRITABLE) {
            return property.synthesizedName() || property.graphNode().capability().writable();
        }
        return keyNames.contains(property.rawName());
    }

    private static Map<String, Object> propertyMap(ReadProperty property) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("property", property.address());
        output.put("value", property.value());
        output.put("type", property.type());
        return output;
    }

    private static Integer focusDepth(ReadOptions options) {
        return options.depth() == null ? null : options.depth();
    }

    private static ReadOptions.PropertyMode propertiesMode(ReadOptions options, boolean focused) {
        if (focused && options.propertyMode() == ReadOptions.PropertyMode.NONE) {
            return ReadOptions.PropertyMode.KEY;
        }
        return options.propertyMode();
    }

    private static List<JmxComponentNode> pathTo(JmxComponentNode root, JmxComponentNode focus) {
        List<JmxComponentNode> path = new ArrayList<>();
        if (!findPath(root, focus, path)) {
            throw new IllegalArgumentException("Focused node is not under root");
        }
        return Collections.unmodifiableList(new ArrayList<>(path));
    }

    private static boolean findPath(JmxComponentNode node, JmxComponentNode focus, List<JmxComponentNode> path) {
        path.add(node);
        if (node == focus) {
            return true;
        }
        for (JmxComponentNode child : node.children()) {
            if (findPath(child, focus, path)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        return false;
    }

    private static Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options);
    }

    private static RuntimeContext inProcessRuntime() {
        return new RuntimeContext(
                "in-process",
                new RuntimeFingerprint(
                        "in-process", "unknown", Collections.<String, String>emptyMap()));
    }

    private RuntimeComponentIdentityResolver identityResolver(
            RuntimeContext runtimeContext) {
        if (identityResolver != null) {
            return identityResolver;
        }
        return "in-process".equals(runtimeContext.fingerprint().jmeterHome())
                ? RuntimeComponentIdentityResolver.actualClasses()
                : RuntimeComponentIdentityResolver.selected();
    }
}
