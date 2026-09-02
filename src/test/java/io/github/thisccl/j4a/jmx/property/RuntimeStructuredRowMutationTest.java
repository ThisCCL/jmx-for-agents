package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.protocol.http.util.HTTPFileArgs;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestElementSchema;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RuntimeStructuredRowMutationTest {
    @BeforeAll
    static void initializeSelectedSaveService() {
        Todo8OpaqueFixtures.initializeSaveService();
    }

    @Test
    void reconstructsArgumentsDetachedAndPreservesOrderDuplicatesAndEmptyStrings() {
        Arguments original = new Arguments();
        original.addArgument(new Argument("original", "value", "=", "before"));
        JMeterProperty observed = new TestElementProperty("HTTPsampler.Arguments", original);
        StructuredRowShape shape = RuntimeStructuredRows.observe(observed, context()).get();
        Map<String, Object> empty = argument("", "", "", "");

        JMeterProperty replacement = shape.materialize(Arrays.asList(empty, empty));

        assertThat(original.getArgument(0).getName()).isEqualTo("original");
        StructuredRowShape rebuilt = RuntimeStructuredRows.observe(replacement, context()).get();
        assertThat(rebuilt.observedValue().rows()).containsExactly(empty, empty);
        assertThat(replacement.getClass()).isEqualTo(observed.getClass());
        assertThat(rebuilt.storagePropertyClass()).isEqualTo(shape.storagePropertyClass());
        assertThat(rebuilt.rowWrapperClass()).isEqualTo(shape.rowWrapperClass());
    }

    @Test
    void reconstructsNonArgumentsRuntimeFamilyWithItsObservedStorageClasses() {
        HTTPFileArgs original = new HTTPFileArgs();
        original.addHTTPFileArg(new HTTPFileArg("observed.txt", "observed", "text/plain"));
        JMeterProperty observed = new TestElementProperty("HTTPsampler.Files", original);
        StructuredRowShape shape = RuntimeStructuredRows.observe(observed, context()).get();
        Map<String, Object> first = file("first.txt", "upload", "text/plain");
        Map<String, Object> second = file("", "", "");

        JMeterProperty replacement = shape.materialize(Arrays.asList(first, second));

        StructuredRowShape rebuilt = RuntimeStructuredRows.observe(replacement, context()).get();
        assertThat(rebuilt.rowClass()).isEqualTo(HTTPFileArg.class.getName());
        assertThat(rebuilt.observedValue().rows()).containsExactly(first, second);
        assertThat(rebuilt.outerPropertyClass()).isEqualTo(shape.outerPropertyClass());
        assertThat(rebuilt.outerValueClass()).isEqualTo(shape.outerValueClass());
        assertThat(rebuilt.storagePropertyClass()).isEqualTo(shape.storagePropertyClass());
    }

    @Test
    void reconstructsDirectHeadersDetachedAndPreservesDuplicatesAndEmptyStrings() {
        HeaderManager manager = new HeaderManager();
        manager.add(new Header("Original", "before"));
        JMeterProperty observed = manager.getHeaders();
        StructuredRowShape shape = RuntimeStructuredRows.observe(observed, context()).get();
        Map<String, Object> empty = header("", "");

        JMeterProperty replacement = shape.materialize(Arrays.asList(empty, empty));

        assertThat(manager.getHeader(0).getName()).isEqualTo("Original");
        StructuredRowShape rebuilt = RuntimeStructuredRows.observe(replacement, context()).get();
        assertThat(rebuilt.observedValue().rows()).containsExactly(empty, empty);
        assertThat(replacement.getClass()).isEqualTo(observed.getClass());
        assertThat(rebuilt.storagePropertyClass()).isEqualTo(shape.storagePropertyClass());
        assertThat(rebuilt.rowWrapperClass()).isEqualTo(shape.rowWrapperClass());
    }

    @Test
    void reconstructsFirstDirectHeaderFromEmptyRuntimeProofAndReloads() throws Exception {
        HeaderManager manager = new HeaderManager();
        manager.setProperty(TestElement.GUI_CLASS,
                "org.apache.jmeter.protocol.http.gui.HeaderPanel");
        manager.setProperty(TestElement.TEST_CLASS, HeaderManager.class.getName());
        Map<String, Object> submitted = new LinkedHashMap<String, Object>();
        submitted.put("row_type", Header.class.getName());
        submitted.put("row_properties", Arrays.asList(
                descriptor("Header.name", "string", ""),
                descriptor("Header.value", "string", "")));
        submitted.put("rows", Collections.singletonList(header("X-First", "one")));

        java.util.Optional<PropertyWrite> write = new RuntimeStructuredRowWriter().prepare(
                manager,
                io.github.thisccl.j4a.path.TestPropertyPaths.properties("HeaderManager.headers"),
                submitted,
                context());

        assertThat(write).isPresent();
        JMeterProperty replacement = write.get().preparedProperty().get();
        manager.setProperty(replacement);
        HeaderManager reloaded = (HeaderManager) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(manager));
        assertThat(reloaded.size()).isEqualTo(1);
        assertThat(reloaded.getHeader(0).getName()).isEqualTo("X-First");
        assertThat(reloaded.getHeader(0).getValue()).isEqualTo("one");
    }

    @Test
    void fillsOmittedDirectHeaderDefaultsAndReloadsExactDescriptors() throws Exception {
        HeaderManager manager = new HeaderManager();
        manager.setProperty(TestElement.GUI_CLASS,
                "org.apache.jmeter.protocol.http.gui.HeaderPanel");
        manager.setProperty(TestElement.TEST_CLASS, HeaderManager.class.getName());
        Map<String, Object> submitted = new LinkedHashMap<String, Object>();
        submitted.put("row_type", Header.class.getName());
        submitted.put("row_properties", Arrays.asList(
                descriptor("Header.name", "string", ""),
                descriptor("Header.value", "string", "")));
        submitted.put("rows", Collections.singletonList(Collections.emptyMap()));

        PropertyWrite write = new RuntimeStructuredRowWriter().prepare(
                manager,
                io.github.thisccl.j4a.path.TestPropertyPaths.properties("HeaderManager.headers"),
                submitted,
                context()).get();
        manager.setProperty(write.preparedProperty().get());
        HeaderManager reloaded = (HeaderManager) Todo8OpaqueFixtures.loadElement(
                Todo8OpaqueFixtures.saveElement(manager));
        RuntimeStructuredRowDocument.Projection projection = RuntimeStructuredRowDocument.observe(
                reloaded, reloaded.getHeaders(), context()).get();

        assertThat(reloaded.size()).isEqualTo(1);
        assertThat(reloaded.getHeader(0).getName()).isEmpty();
        assertThat(reloaded.getHeader(0).getValue()).isEmpty();
        assertThat(projection.value().get("row_properties")).isEqualTo(Arrays.asList(
                descriptor("Header.name", "string", ""),
                descriptor("Header.value", "string", "")));
        assertThat(projection.value().get("rows"))
                .isEqualTo(Collections.singletonList(header("", "")));
    }

    @Test
    void fillsOmittedWrappedArgumentDescriptionAndReloadsExactDescriptors() throws Exception {
        JMeterProperty observed = new TestElementProperty(
                "HTTPsampler.Arguments", new Arguments());
        StructuredRowShape shape = RuntimeStructuredRows.observe(observed, context()).get();
        Map<String, Object> submitted = new LinkedHashMap<String, Object>();
        submitted.put(Argument.ARG_NAME, "query");
        submitted.put(Argument.VALUE, "value");
        submitted.put(Argument.METADATA, "=");

        JMeterProperty replacement = shape.materialize(
                Collections.singletonList(submitted));
        JMeterProperty reloaded = (JMeterProperty) OpaqueEnvelope.load(
                OpaqueEnvelope.save(replacement));
        StructuredRowShape rebuilt = RuntimeStructuredRows.observe(reloaded, context()).get();

        assertThat(rebuilt.observedValue().rows()).containsExactly(
                argument("query", "value", "=", ""));
        assertThat(rebuilt.fields()).extracting(StructuredRowField::descriptor)
                .containsExactly(
                        descriptor(Argument.ARG_NAME, "string", ""),
                        descriptor(Argument.VALUE, "string", ""),
                        descriptor(Argument.DESCRIPTION, "string", ""),
                        descriptor(Argument.METADATA, "string", ""));
    }

    @Test
    void provesAndMaterializesPersistedNullEmptyZeroAndFalseDefaults() throws Exception {
        PristineScalarRows owner = new PristineScalarRows();
        Map<String, Object> submitted = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> descriptors = Arrays.asList(
                descriptor("fixture.null", "null", null),
                descriptor("fixture.empty", "string", ""),
                descriptor("fixture.zero", "int", Integer.valueOf(0)),
                descriptor("fixture.enabled", "boolean", Boolean.FALSE));
        submitted.put("row_type", PristineScalarRow.class.getName());
        submitted.put("row_properties", descriptors);
        submitted.put("rows", Collections.singletonList(Collections.emptyMap()));

        PropertyWrite write = new RuntimeStructuredRowWriter().prepare(
                owner,
                io.github.thisccl.j4a.path.TestPropertyPaths.properties("fixture.rows"),
                submitted,
                context()).get();
        assertThat(owner.rows().size()).isZero();
        JMeterProperty reloaded = (JMeterProperty) OpaqueEnvelope.load(
                OpaqueEnvelope.save(write.preparedProperty().get()));
        StructuredRowShape rebuilt = RuntimeStructuredRows.observe(reloaded, context()).get();

        assertThat(rebuilt.fields()).extracting(StructuredRowField::descriptor)
                .containsExactlyElementsOf(descriptors);
        assertThat(rebuilt.observedValue().rows()).containsExactly(row(
                "fixture.null", null,
                "fixture.empty", "",
                "fixture.zero", Integer.valueOf(0),
                "fixture.enabled", Boolean.FALSE));
        assertThat(owner.rows().size()).isZero();
    }

    @Test
    void malformedOmittedRowFailsBeforeAnyDirectAttachment() {
        HeaderManager manager = new HeaderManager();
        JMeterProperty original = manager.getHeaders();
        Map<String, Object> submitted = new LinkedHashMap<String, Object>();
        submitted.put("row_type", Header.class.getName());
        submitted.put("row_properties", Arrays.asList(
                descriptor("Header.name", "string", "drift"),
                descriptor("Header.value", "string", "")));
        submitted.put("rows", Collections.singletonList(Collections.singletonMap(
                "Header.unknown", "nope")));

        assertThatThrownBy(() -> new RuntimeStructuredRowWriter().prepare(
                manager,
                io.github.thisccl.j4a.path.TestPropertyPaths.properties("HeaderManager.headers"),
                submitted,
                context()))
                .isInstanceOf(StructuredRowValueException.class)
                .hasMessageContaining("row_properties");
        assertThat((JMeterProperty) manager.getHeaders()).isSameAs(original);
        assertThat(manager.size()).isZero();
    }

    @Test
    void callerRowTypeAndContextClassLoaderCannotAuthorizeEmptyDirectRows() {
        HeaderManager manager = new HeaderManager();
        Map<String, Object> submitted = new LinkedHashMap<String, Object>();
        submitted.put("row_type", "sentinel.caller.NotARow");
        submitted.put("rows", Collections.singletonList(header("X-Sentinel", "one")));
        CountingClassLoader sentinel = new CountingClassLoader(
                Thread.currentThread().getContextClassLoader());
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(sentinel);
        try {
            assertThatThrownBy(() -> new RuntimeStructuredRowWriter().prepare(
                    manager,
                    io.github.thisccl.j4a.path.TestPropertyPaths.properties("HeaderManager.headers"),
                    submitted,
                    context()))
                    .isInstanceOf(StructuredRowValueException.class)
                    .hasMessageContaining("row_type")
                    .hasMessageContaining(Header.class.getName());
        } finally {
            thread.setContextClassLoader(original);
        }
        assertThat(sentinel.attempts).isZero();
        assertThat(manager.size()).isZero();
    }

    @Test
    void roundTripsProvenEmptyArgumentsAndHttpFileArgs() {
        JMeterProperty arguments = new TestElementProperty(
                "HTTPsampler.Arguments", new Arguments());
        JMeterProperty files = new TestElementProperty(
                "HTTPsampler.Files", new HTTPFileArgs());

        StructuredRowShape argumentShape = RuntimeStructuredRows.observe(arguments, context()).get();
        StructuredRowShape fileShape = RuntimeStructuredRows.observe(files, context()).get();

        assertThat(RuntimeStructuredRows.observe(
                argumentShape.materialize(Collections.emptyList()), context()).get()
                .observedValue().rows()).isEmpty();
        assertThat(RuntimeStructuredRows.observe(
                fileShape.materialize(Collections.emptyList()), context()).get()
                .observedValue().rows()).isEmpty();
    }

    @Test
    void invalidFinalRowLeavesObservedPropertyUntouched() {
        Arguments original = new Arguments();
        original.addArgument(new Argument("original", "value", "=", "before"));
        JMeterProperty observed = new TestElementProperty("HTTPsampler.Arguments", original);
        StructuredRowShape shape = RuntimeStructuredRows.observe(observed, context()).get();
        Map<String, Object> invalid = argument("bad", "value", "=", "description");
        invalid.put("unknown", "must fail");

        assertThatThrownBy(() -> shape.materialize(Arrays.asList(
                argument("valid", "one", "=", "first"), invalid)))
                .isInstanceOf(StructuredRowValueException.class)
                .hasMessageContaining("rows[1]").hasMessageContaining("unknown field");

        assertThat(original.getArgumentsAsMap()).containsExactlyEntriesOf(
                Collections.singletonMap("original", "value"));
        assertThat(RuntimeStructuredRows.observe(observed, context()).get().observedValue())
                .isEqualTo(shape.observedValue());
    }

    @Test
    void unsupportedDirectStorageFallsBackWithoutLoadingCallerRowTypeOrAttachingAnything() {
        AbstractTestElement target = new AbstractTestElement() {
        };
        CollectionProperty unsupported = new CollectionProperty(
                "plain", Collections.singletonList(new StringProperty("item", "original")));
        target.setProperty(unsupported);
        Map<String, Object> submitted = new LinkedHashMap<String, Object>();
        submitted.put("row_type", "not.loaded.CallerSelectedRow");
        submitted.put("rows", Collections.singletonList(header("X", "one")));

        assertThat(new RuntimeStructuredRowWriter().prepare(
                target,
                io.github.thisccl.j4a.path.TestPropertyPaths.properties("plain"),
                submitted,
                context())).isEmpty();
        assertThat(target.getPropertyOrNull("plain")).isSameAs(unsupported);
        assertThat(unsupported.iterator().next().getStringValue()).isEqualTo("original");
    }

    private static RuntimeContext context() {
        return new RuntimeContext("todo6", new RuntimeFingerprint(
                "/opt/jmeter-fixture/apache-jmeter-5.6.3", "5.6.3",
                Collections.singletonMap("core", "todo6")));
    }

    private static Map<String, Object> argument(
            String name, String value, String metadata, String description) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
        row.put(Argument.ARG_NAME, name);
        row.put(Argument.VALUE, value);
        row.put(Argument.METADATA, metadata);
        row.put(Argument.DESCRIPTION, description);
        return row;
    }

    private static Map<String, Object> file(String path, String parameter, String mimeType) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("File.path", path);
        row.put("File.paramname", parameter);
        row.put("File.mimetype", mimeType);
        return row;
    }

    private static Map<String, Object> header(String name, String value) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("Header.name", name);
        row.put("Header.value", value);
        return row;
    }

    private static Map<String, Object> descriptor(
            String name, String type, Object defaultValue) {
        LinkedHashMap<String, Object> descriptor = new LinkedHashMap<String, Object>();
        descriptor.put("name", name);
        descriptor.put("type", type);
        descriptor.put("required", Boolean.FALSE);
        descriptor.put("default", defaultValue);
        return descriptor;
    }

    private static Map<String, Object> row(Object... values) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }

    public static final class PristineScalarRow extends AbstractTestElement {
        private static final PristineScalarRowSchema SCHEMA = new PristineScalarRowSchema();

        public PristineScalarRow() {
            setProperty(new NullProperty("fixture.null"));
            setProperty(new StringProperty("fixture.empty", ""));
            setProperty(new IntegerProperty("fixture.zero", 0));
            setProperty(new BooleanProperty("fixture.enabled", false));
        }

        @Override
        public TestElementSchema getSchema() {
            return SCHEMA;
        }
    }

    public static final class PristineScalarRowSchema extends TestElementSchema {
        PristineScalarRowSchema() {
            stringDescriptor("nullable", "fixture.null");
            stringDescriptor("empty", "fixture.empty");
            intDescriptor("zero", "fixture.zero");
            booleanDescriptor("enabled", "fixture.enabled");
        }
    }

    public static final class PristineScalarRows extends AbstractTestElement {
        public PristineScalarRows() {
            setProperty(new CollectionProperty("fixture.rows", Collections.emptyList()));
        }

        CollectionProperty rows() {
            return (CollectionProperty) getProperty("fixture.rows");
        }

        public void add(PristineScalarRow row) {
            rows().addItem(row);
        }
    }

    private static final class CountingClassLoader extends ClassLoader {
        private int attempts;

        private CountingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if ("sentinel.caller.NotARow".equals(name)) {
                attempts++;
                throw new AssertionError("caller row_type must never be loaded");
            }
            return super.loadClass(name, resolve);
        }
    }
}
