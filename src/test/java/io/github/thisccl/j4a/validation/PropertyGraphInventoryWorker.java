package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.property.DefaultJMeterPropertyGraph;
import io.github.thisccl.j4a.jmx.property.GraphNode;
import io.github.thisccl.j4a.jmx.property.GraphOwnership;
import io.github.thisccl.j4a.jmx.property.GraphPresence;
import io.github.thisccl.j4a.jmx.property.GraphSnapshot;
import io.github.thisccl.j4a.jmx.property.GraphType;
import io.github.thisccl.j4a.jmx.property.PropertyGraphDocumentMapper;
import io.github.thisccl.j4a.jmx.property.PropertyWrite;
import io.github.thisccl.j4a.jmx.property.RepresentationSource;
import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import io.github.thisccl.j4a.path.PropertyAddress;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.util.JMeterUtils;

public final class PropertyGraphInventoryWorker {
    private static final String SYSTEM_REASON =
            "JMeter identity metadata is managed by the selected runtime";
    private static final Set<String> SYSTEM_PROPERTIES = new HashSet<String>(Arrays.asList(
            TestElement.GUI_CLASS, TestElement.TEST_CLASS));

    private final Path home;
    private final Path repository;
    private final Path pluginJar;
    private final RuntimeContext runtimeContext;
    private final DefaultJMeterPropertyGraph graph = new DefaultJMeterPropertyGraph();
    private final PropertyGraphDocumentMapper documents = new PropertyGraphDocumentMapper();
    private final List<Map<String, Object>> observedRows = new ArrayList<Map<String, Object>>();
    private int materializedEntries;
    private int materializationFailed;
    private boolean unsupportedGuidance;

    private PropertyGraphInventoryWorker(Path home, Path repository, Path pluginJar) throws Exception {
        this.home = home.toRealPath();
        this.repository = repository.toRealPath();
        this.pluginJar = pluginJar.toRealPath();
        LocalJMeterWorkerRuntime.initialize(this.home);
        this.runtimeContext = LocalPropertyGraphRuntimeContext.selected(this.home);
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("usage: <jmeter-home> <repository> <plugin-jar> <output>");
            System.exit(2);
        }
        try {
            PropertyGraphInventoryWorker worker = new PropertyGraphInventoryWorker(
                    Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]));
            Map<String, Object> inventory = worker.collect();
            Path output = Paths.get(args[3]).toAbsolutePath().normalize();
            Files.write(output, PropertyGraphInventorySupport.dump(inventory)
                    .getBytes(StandardCharsets.UTF_8));
            System.out.println("INVENTORY_ROWS " + worker.observedRows.size());
            System.exit(0);
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private Map<String, Object> collect() throws Exception {
        LinkedHashMap<String, Object> inventory = new LinkedHashMap<String, Object>();
        inventory.put("schema_version", 1);
        inventory.put("runtime", runtime());
        inventory.put("fixtures", fixtures());
        inventory.put("plugin_fixtures", pluginFixtures());
        inventory.put("property_subclasses", propertySubclasses());
        inventory.put("menu_entries", menuEntries());
        inspectFixtures();
        Collections.sort(observedRows, Comparator.comparing(row -> String.valueOf(row.get("row_id"))));
        inventory.put("observed_rows", observedRows);
        inventory.put("classification_policy", classificationPolicy());
        inventory.put("summary", summary());
        return inventory;
    }

    private Map<String, Object> runtime() throws IOException {
        LinkedHashMap<String, Object> runtime = new LinkedHashMap<String, Object>();
        runtime.put("canonical_home", home.toString());
        runtime.put("jmeter_version", JMeterUtils.getJMeterVersion());
        runtime.put("lib_sha256", libraryHashes(home.resolve("lib")));
        runtime.put("lib_ext_sha256", libraryHashes(home.resolve("lib").resolve("ext")));
        return runtime;
    }

    private List<Map<String, Object>> libraryHashes(Path directory) throws IOException {
        List<Path> jars = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path path : stream) jars.add(path.toRealPath());
        }
        Collections.sort(jars);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Path jar : jars) {
            rows.add(row("path", home.relativize(jar).toString().replace('\\', '/'),
                    "sha256", PropertyGraphInventorySupport.sha256(jar)));
        }
        return rows;
    }

    private List<Map<String, Object>> fixtures() throws IOException {
        Path root = repository.resolve("src").resolve("test").resolve("resources")
                .resolve("property-graph-conformance");
        List<Path> fixtures;
        try (Stream<Path> stream = Files.walk(root)) {
            fixtures = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jmx"))
                    .sorted().collect(Collectors.toList());
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Path fixture : fixtures) {
            rows.add(row("path", relative(fixture),
                    "sha256", PropertyGraphInventorySupport.sha256(fixture)));
        }
        return rows;
    }

    private List<Map<String, Object>> pluginFixtures() throws IOException {
        return Collections.singletonList(row(
                "path", "synthetic-unseen-property-plugin.jar",
                "content_sha256", PropertyGraphInventorySupport.canonicalJarHash(pluginJar),
                "component_class", DefaultLocalProfileHomeFixtures.UNSEEN_PROPERTY_TEST_ELEMENT,
                "property_class", DefaultLocalProfileHomeFixtures.UNSEEN_PROPERTY_CLASS));
    }

    private List<Map<String, Object>> propertySubclasses() throws IOException {
        List<Path> jars = new ArrayList<Path>(LocalJMeterClasspath.localJars(home));
        jars.add(pluginJar);
        Collections.sort(jars);
        LinkedHashMap<String, Map<String, Object>> found = new LinkedHashMap<String, Map<String, Object>>();
        for (Path jar : jars) {
            try (JarFile file = new JarFile(jar.toFile())) {
                List<String> names = new ArrayList<String>();
                Enumeration<JarEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(".class") && !name.equals("module-info.class")) {
                        names.add(name.substring(0, name.length() - 6).replace('/', '.'));
                    }
                }
                Collections.sort(names);
                for (String name : names) {
                    try {
                        Class<?> type = Class.forName(name, false, getClass().getClassLoader());
                        if (type != JMeterProperty.class && JMeterProperty.class.isAssignableFrom(type)
                                && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
                            found.put(name, row(
                                    "class", name,
                                    "classification", classClassification(type),
                                    "classpath_entry", classpathEntry(jar)));
                        }
                    } catch (ClassNotFoundException | LinkageError ignored) {
                    }
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(found.values());
        Collections.sort(rows, Comparator.comparing(item -> String.valueOf(item.get("class"))));
        return rows;
    }

    private String classpathEntry(Path jar) throws IOException {
        Path real = jar.toRealPath();
        return real.equals(pluginJar) ? "synthetic-unseen-property-plugin.jar"
                : home.relativize(real).toString().replace('\\', '/');
    }

    private static String classClassification(Class<?> type) {
        if (MapProperty.class.isAssignableFrom(type)) return "structured_map";
        if (TestElementProperty.class.isAssignableFrom(type)) return "structured_element";
        if (CollectionProperty.class.isAssignableFrom(type)
                || org.apache.jmeter.testelement.property.MultiProperty.class.isAssignableFrom(type)) {
            return "structured_collection";
        }
        if (org.apache.jmeter.testelement.property.StringProperty.class.isAssignableFrom(type)
                || org.apache.jmeter.testelement.property.BooleanProperty.class.isAssignableFrom(type)
                || org.apache.jmeter.testelement.property.IntegerProperty.class.isAssignableFrom(type)
                || org.apache.jmeter.testelement.property.LongProperty.class.isAssignableFrom(type)
                || org.apache.jmeter.testelement.property.FloatProperty.class.isAssignableFrom(type)
                || org.apache.jmeter.testelement.property.DoubleProperty.class.isAssignableFrom(type)
                || org.apache.jmeter.testelement.property.NullProperty.class.isAssignableFrom(type)) {
            return "structured_scalar";
        }
        return "opaque";
    }

    private List<Map<String, Object>> menuEntries() {
        List<LocalJMeterMenuRegistry.Entry> entries = new ArrayList<LocalJMeterMenuRegistry.Entry>(
                LocalJMeterMenuRegistry.current().entries());
        Collections.sort(entries, Comparator
                .comparing(LocalJMeterMenuRegistry.Entry::group)
                .thenComparing(LocalJMeterMenuRegistry.Entry::label)
                .thenComparing(LocalJMeterMenuRegistry.Entry::menuClassName)
                .thenComparing(entry -> entry.kind().name()));
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (LocalJMeterMenuRegistry.Entry entry : entries) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
            String entryId = entry.group() + "|" + entry.label() + "|"
                    + entry.menuClassName() + "|" + entry.kind().name();
            row.put("entry_id", entryId);
            row.put("menu_group", entry.group());
            row.put("label", entry.label());
            row.put("component_class", entry.component());
            row.put("menu_class", entry.menuClassName());
            row.put("registry_kind", entry.kind().name());
            try {
                TestElement element = materialize(entry);
                if (element == null) {
                    materializationFailed++;
                    row.put("materialization", row(
                            "status", "materialization_failed",
                            "failure_class", "NoPublicMaterializer",
                            "failure_message", "registry entry has no public isolated materializer"));
                } else {
                    reload(element);
                    materializedEntries++;
                    row.put("materialization", row(
                            "status", "materialized",
                            "test_element_class", element.getClass().getName()));
                    inspectElement("menu:" + entryId, entry.component(), element);
                }
            } catch (Throwable throwable) {
                materializationFailed++;
                Throwable root = root(throwable);
                row.put("materialization", row(
                        "status", "materialization_failed",
                        "failure_class", root.getClass().getName(),
                        "failure_message", sanitize(root.getMessage())));
            }
            rows.add(row);
        }
        return rows;
    }

    private TestElement materialize(LocalJMeterMenuRegistry.Entry entry) throws Exception {
        Class<?> fallback = entry.fallbackTestElementClass();
        if (fallback != null) {
            Constructor<?> constructor = fallback.getDeclaredConstructor();
            if (Modifier.isPublic(constructor.getModifiers()) && Modifier.isPublic(fallback.getModifiers())) {
                return (TestElement) constructor.newInstance();
            }
            return null;
        }
        if (entry.kind() != LocalJMeterMenuRegistry.RegistrationKind.GUI_COMPONENT) return null;
        Class<?> menuClass = Class.forName(entry.menuClassName(), true, getClass().getClassLoader());
        JMeterGUIComponent gui = (JMeterGUIComponent) menuClass.getDeclaredConstructor().newInstance();
        try {
            TestElement defaults = gui.createTestElement();
            defaults.setProperty(TestElement.GUI_CLASS, entry.menuClassName());
            return defaults;
        } finally {
            if (gui instanceof TestBeanGUI) {
                JMeterUtils.removeLocaleChangeListener((TestBeanGUI) gui);
            }
        }
    }

    private void inspectFixtures() throws Exception {
        Path root = repository.resolve("src").resolve("test").resolve("resources")
                .resolve("property-graph-conformance");
        List<Path> fixtures;
        try (Stream<Path> stream = Files.walk(root)) {
            fixtures = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jmx"))
                    .sorted().collect(Collectors.toList());
        }
        for (Path fixture : fixtures) {
            JmxTestPlan plan = LocalJMeterWorkerJmx.load(fixture, home);
            int index = 0;
            for (TestElement element : plan.depthFirstTestElements()) {
                inspectElement("fixture:" + relative(fixture) + "#" + index,
                        componentIdentity(element), element);
                index++;
            }
        }
    }

    private void inspectElement(String origin, String component, TestElement element) {
        GraphSnapshot snapshot = graph.inspect(element, runtimeContext);
        Map<String, Object> publicMetadata = new LinkedHashMap<String, Object>();
        try {
            for (io.github.thisccl.j4a.components.ComponentCatalog.ComponentProperty property
                    : LocalComponentProperties.properties(element, element.getClass(), runtimeContext)) {
                publicMetadata.put(property.address().toString(), property);
                if ("unsupported".equals(property.type())
                        || (property.reason() != null && property.reason().contains("type: unsupported"))) {
                    unsupportedGuidance = true;
                }
            }
        } catch (RuntimeException exception) {
            throw exception;
        }
        for (GraphNode node : snapshot.nodes()) {
            observedRows.add(observedRow(origin, component, element, snapshot, node, publicMetadata));
        }
    }

    private Map<String, Object> observedRow(
            String origin, String component, TestElement element, GraphSnapshot snapshot,
            GraphNode node, Map<String, Object> publicMetadata) {
        List<Object> path = PropertyAddress.fromPath(node.path()).segments();
        String propertyClass = node.value().propertyClass();
        LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("row_id", origin + "|" + component + "|" + path + "|" + propertyClass);
        row.put("origin", origin);
        row.put("component_class", component);
        row.put("property_path", path);
        row.put("property_class", propertyClass);
        row.put("value_class", node.capability().runtimeClassConstraint().valueClass().orElse(null));
        row.put("presence", node.value().presence().wireName());
        row.put("shape", node.type().wireName());
        row.put("representation_source", node.capability().representationSource().wireName());

        boolean system = node.capability().ownership() == GraphOwnership.SYSTEM;
        if (system) {
            row.put("classification", "system_owned");
            row.put("ownership", "system");
            row.put("persisted", node.value().presence() == GraphPresence.PRESENT);
            row.put("writable", false);
            row.put("denominator", false);
            row.put("reason", SYSTEM_REASON);
            row.put("round_trip_proof", systemProof(element, snapshot, node));
            return row;
        }

        if (node.value().presence() == GraphPresence.ABSENT) {
            row.put("classification", "transient_runtime_only");
            row.put("ownership", "user");
            row.put("persisted", false);
            row.put("writable", false);
            row.put("denominator", false);
            row.put("reason", "declared runtime shape is absent and not persisted; omit it unless materialized");
            row.put("round_trip_proof", row(
                    "kind", "non_persisted_absence",
                    "result", "excluded",
                    "save_service_reload_present", false));
            return row;
        }

        Map<String, Object> persistence = persistenceProbe(element, node);
        if (!Boolean.TRUE.equals(persistence.get("same_runtime_shape"))) {
            row.put("classification", "transient_runtime_only");
            row.put("ownership", "user");
            row.put("persisted", false);
            row.put("writable", false);
            row.put("denominator", false);
            row.put("reason", "observed runtime default does not retain the same property shape through SaveService reload");
            row.put("round_trip_proof", persistence);
            return row;
        }

        String classification = classification(element, snapshot, node);
        row.put("classification", classification);
        row.put("ownership", "user");
        row.put("persisted", true);
        row.put("writable", true);
        row.put("denominator", true);
        Map<String, Object> document = reusableDocument(snapshot, node);
        row.put("reusable_document", document);
        row.put("round_trip_proof", userProof(element, snapshot, node, document, classification));
        return row;
    }

    private Map<String, Object> persistenceProbe(TestElement element, GraphNode node) {
        LinkedHashMap<String, Object> proof = new LinkedHashMap<String, Object>();
        proof.put("kind", "save_service_persistence_probe");
        proof.put("expected_presence", node.value().presence().wireName());
        proof.put("expected_shape", node.type().wireName());
        proof.put("expected_property_class", node.value().propertyClass());
        try {
            GraphNode actual = graph.inspect(reload(element), runtimeContext).resolve(node.path());
            boolean same = node.value().presence() == actual.value().presence()
                    && node.type() == actual.type()
                    && node.value().propertyClass().equals(actual.value().propertyClass());
            proof.put("actual_presence", actual.value().presence().wireName());
            proof.put("actual_shape", actual.type().wireName());
            proof.put("actual_property_class", actual.value().propertyClass());
            proof.put("same_runtime_shape", same);
            proof.put("result", same ? "passed" : "excluded");
        } catch (Throwable throwable) {
            Throwable root = root(throwable);
            proof.put("same_runtime_shape", false);
            proof.put("result", "excluded");
            proof.put("failure_class", root.getClass().getName());
            proof.put("failure_message", sanitize(root.getMessage()));
        }
        return proof;
    }

    private Map<String, Object> reusableDocument(GraphSnapshot snapshot, GraphNode node) {
        List<Object> path = PropertyAddress.fromPath(node.path()).segments();
        for (Map<String, Object> document : documents.topLevelDocuments(snapshot, false)) {
            if (path.equals(document.get("property"))) return document;
        }
        return documents.toDocument(node);
    }

    static String classification(TestElement element, GraphSnapshot snapshot, GraphNode node) {
        List<PropertyPathSegment> segments = node.path().segments();
        PropertyPathSegment terminal = segments.get(segments.size() - 1);
        String name = terminal.kind() == PropertyPathSegment.Kind.PROPERTY
                ? terminal.name() : null;
        if (name != null && segments.size() > 1) {
            List<PropertyPathSegment> parent = segments.subList(0, segments.size() - 1);
            for (GraphNode candidate : snapshot.nodes()) {
                if (candidate.path().segments().equals(parent)
                        && candidate.type() == GraphType.MAP) {
                    name = null;
                    break;
                }
            }
        }
        GraphType type = node.type();
        if (type == GraphType.COLLECTION) return "structured_collection";
        if (type == GraphType.MAP) return "structured_map";
        if (type == GraphType.ELEMENT) return "structured_element";
        if (type == GraphType.OPAQUE) return "opaque";
        if (type.isScalar()) return "structured_scalar";
        return "unclassified";
    }

    private Map<String, Object> userProof(
            TestElement element, GraphSnapshot originalSnapshot, GraphNode node,
            Map<String, Object> document, String classification) {
        LinkedHashMap<String, Object> proof = new LinkedHashMap<String, Object>();
        proof.put("kind", classification);
        proof.put("read_document_sha256", PropertyGraphInventorySupport.sha256(
                PropertyGraphInventorySupport.dump(new LinkedHashMap<String, Object>(document))
                        .getBytes(StandardCharsets.UTF_8)));
        try {
            TestElement candidate = reload(element);
            GraphSnapshot candidateSnapshot = graph.inspect(candidate, runtimeContext);
            PropertyWrite write = documents.resolve(document, candidateSnapshot);
            graph.apply(candidate, candidateSnapshot, Collections.singletonList(write));
            TestElement reloaded = reload(candidate);
            GraphSnapshot reloadedSnapshot = graph.inspect(reloaded, runtimeContext);
            GraphNode actual = reloadedSnapshot.resolve(node.path());
            Map<String, Object> actualDocument = reusableDocument(reloadedSnapshot, actual);
            boolean equal = document.equals(actualDocument);
            proof.put("write_applied", true);
            proof.put("save_service_reloaded", true);
            proof.put("projection_equal", equal);
            proof.put("actual_document_sha256", PropertyGraphInventorySupport.sha256(
                    PropertyGraphInventorySupport.dump(new LinkedHashMap<String, Object>(actualDocument))
                            .getBytes(StandardCharsets.UTF_8)));
            proof.put("result", equal ? "passed" : "failed");
        } catch (Throwable throwable) {
            Throwable root = root(throwable);
            proof.put("write_applied", false);
            proof.put("save_service_reloaded", false);
            proof.put("projection_equal", false);
            proof.put("result", "failed");
            proof.put("failure_class", root.getClass().getName());
            proof.put("failure_message", sanitize(root.getMessage()));
        }
        return proof;
    }

    private Map<String, Object> systemProof(TestElement element, GraphSnapshot original, GraphNode node) {
        LinkedHashMap<String, Object> proof = new LinkedHashMap<String, Object>();
        proof.put("kind", "system_identity_exclusion");
        try {
            boolean omitted = documents.topLevelDocuments(original, false).stream()
                    .noneMatch(document -> PropertyAddress.fromPath(node.path()).segments()
                            .equals(document.get("property")));
            boolean diagnostic;
            try {
                original.resolveWritable(node.path());
                diagnostic = false;
            } catch (IllegalArgumentException exception) {
                diagnostic = exception.getMessage().contains("read-only")
                        && exception.getMessage().contains("identity metadata");
            }
            if (node.value().presence() == GraphPresence.ABSENT) {
                proof.put("internal_value_retained", true);
                proof.put("ordinary_read_omitted", omitted);
                proof.put("non_writable_diagnostic", diagnostic);
                proof.put("save_service_reload", "not_applicable_absent_schema_declaration");
                proof.put("result", omitted && diagnostic ? "excluded" : "failed");
                return proof;
            }
            TestElement reloaded = reload(element);
            GraphSnapshot snapshot = graph.inspect(reloaded, runtimeContext);
            GraphNode retained = snapshot.resolve(node.path());
            boolean same = node.value().equals(retained.value());
            boolean reloadOmitted = documents.topLevelDocuments(snapshot, false).stream()
                    .noneMatch(document -> PropertyAddress.fromPath(node.path()).segments()
                            .equals(document.get("property")));
            proof.put("internal_value_retained", same);
            proof.put("ordinary_read_omitted", omitted && reloadOmitted);
            proof.put("non_writable_diagnostic", diagnostic);
            proof.put("result", same && omitted && reloadOmitted && diagnostic ? "passed" : "failed");
        } catch (Throwable throwable) {
            Throwable root = root(throwable);
            proof.put("result", "failed");
            proof.put("failure_class", root.getClass().getName());
            proof.put("failure_message", sanitize(root.getMessage()));
        }
        return proof;
    }

    private TestElement reload(TestElement element) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveElement(element, output);
        Object loaded = SaveService.loadElement(new ByteArrayInputStream(output.toByteArray()));
        if (!(loaded instanceof TestElement)) {
            throw new IllegalStateException("SaveService reload did not return TestElement: " + loaded);
        }
        return (TestElement) loaded;
    }

    private List<Map<String, Object>> classificationPolicy() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row("classification", "structured_scalar", "persisted_user", true, "requires_round_trip", true));
        rows.add(row("classification", "structured_collection", "persisted_user", true, "requires_round_trip", true));
        rows.add(row("classification", "structured_map", "persisted_user", true, "requires_round_trip", true));
        rows.add(row("classification", "structured_element", "persisted_user", true, "requires_round_trip", true));
        rows.add(row("classification", "semantic", "persisted_user", true, "requires_round_trip", true));
        rows.add(row("classification", "opaque", "persisted_user", true, "requires_round_trip", true));
        rows.add(row("classification", "system_owned", "persisted_user", false, "requires_round_trip", false));
        rows.add(row("classification", "transient_runtime_only", "persisted_user", false, "requires_round_trip", false));
        rows.add(row("classification", "materialization_failed", "persisted_user", false, "requires_round_trip", false));
        return rows;
    }

    private Map<String, Object> summary() {
        int persistedUser = 0;
        int persistedProof = 0;
        int system = 0;
        int transientRows = 0;
        int unclassified = 0;
        boolean retained = true;
        boolean nonWritable = true;
        boolean omitted = true;
        for (Map<String, Object> row : observedRows) {
            String classification = String.valueOf(row.get("classification"));
            if ("unclassified".equals(classification)) unclassified++;
            if (Boolean.TRUE.equals(row.get("persisted")) && "user".equals(row.get("ownership"))) {
                persistedUser++;
                if ("passed".equals(mapping(row.get("round_trip_proof")).get("result"))) persistedProof++;
            }
            if ("system_owned".equals(classification)) {
                system++;
                Map<String, Object> proof = mapping(row.get("round_trip_proof"));
                if (Boolean.TRUE.equals(row.get("persisted"))) {
                    retained &= Boolean.TRUE.equals(proof.get("internal_value_retained"));
                    nonWritable &= Boolean.TRUE.equals(proof.get("non_writable_diagnostic"));
                    omitted &= Boolean.TRUE.equals(proof.get("ordinary_read_omitted"));
                }
            }
            if ("transient_runtime_only".equals(classification)) transientRows++;
        }
        return row(
                "registry_entries", LocalJMeterMenuRegistry.current().entries().size(),
                "materialized_entries", materializedEntries,
                "materialization_failed", materializationFailed,
                "observed_rows", observedRows.size(),
                "persisted_user_total", persistedUser,
                "persisted_user_with_proof", persistedProof,
                "system_owned", system,
                "transient_runtime_only", transientRows,
                "unclassified", unclassified,
                "system_identity_retained", retained && system > 0,
                "system_identity_non_writable", nonWritable && system > 0,
                "ordinary_read_omits_system_identity", omitted && system > 0,
                "unsupported_submission_guidance", unsupportedGuidance);
    }

    private static String componentIdentity(TestElement element) {
        String gui = element.getPropertyAsString(TestElement.GUI_CLASS);
        return gui == null || gui.trim().isEmpty() ? element.getClass().getName() : gui;
    }

    private String relative(Path path) {
        return repository.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private String sanitize(String message) {
        if (message == null || message.trim().isEmpty()) return "no message";
        return message.replace(home.toString(), "<JMETER_HOME>")
                .replace(repository.toString(), "<REPOSITORY>")
                .replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static Throwable root(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }

    private static LinkedHashMap<String, Object> row(Object... values) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            row.put(String.valueOf(values[index]), values[index + 1]);
        }
        return row;
    }
}
